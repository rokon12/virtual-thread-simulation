# The Virtual Thread Machine — JavaFX Port

Implementation spec · Java 25 · JavaFX SubScene 3D · FXyz3D permitted.
Everything needed to rebuild the HTML simulation (`Virtual Thread Machine 3D.dc.html`) as a native JavaFX app. Full scope: 3D scene + HUD/controls.

## 1 · Architecture

Keep the simulation model pure (no JavaFX imports) so it can be unit-tested; the scene and HUD observe it. One `AnimationTimer` drives everything — do not use per-node `Timeline`s for thread movement, they don't scale to 500 nodes.

| Class | Responsibility |
|---|---|
| `vtmachine.App` | Stage, root BorderPane: SubScene center, HUD right (290px), controls bottom, narration overlay via StackPane |
| `vtmachine.model.Sim` | Pure model: tick(dt), spawn/mount/park/resume/pin/complete, chapter engine, event log ring buffer. No JavaFX types |
| `vtmachine.model.Vt` | id, state, lifecycle phase/timers, external I/O endpoint, pos (double x,y,z), work/work0, io, carrier ref, hero flag, active tween |
| `vtmachine.model.Carrier` | mounted Vt, pinT countdown, heat 0..1 |
| `vtmachine.model.ReplayFrame` | Immutable copy of display-relevant model, carrier, lifecycle, resource-pool, scope, log, and HUD state |
| `vtmachine.model.ReplayTimeline` | JavaFX-free rolling 360-frame history sampled every 0.15 simulated seconds; derives aggregated event markers |
| `vtmachine.view.MachineScene` | SubScene + world Group: slabs, cores, slots, ring, heap, external I/O endpoints, queue pressure, VT/stack node pools, dissolve canvas, follow/comparison overlays, hero trail, labels |
| `vtmachine.view.CameraRig` | Orbit spherical (theta, phi, dist, targetY), presets, mouse drag/scroll, lerp-to-goal |
| `vtmachine.view.Hud` | Counters, key-behavior flash cards, event log, narration card, replay scrubber/event markers, speed slider, buttons |

Meta-note for the talk: the sim models virtual threads but should also *run on* them — feed the model from a real `Thread.ofVirtual()` executor if you want live data instead of the synthetic spawner (see §6.5).

## 2 · Scene & Geometry

World units are abstract; keep the numbers below verbatim. **IMPORTANT:** the HTML sim is Y-up; JavaFX 3D is Y-down. Wrap the world Group in `new Scale(1, -1, 1)` (or negate all Y values) once, then use these coordinates unchanged. Camera: `PerspectiveCamera(true)`, fieldOfView 45, near 1, far 1000.

| Element | Geometry (w×h×d, center) | Notes |
|---|---|---|
| Layer 0 · CPU cores slab | Box 150×3.5×64 @ y=0 | fill #1c1508, edge #f5b84c |
| Layer 1 · Carriers slab | Box 150×3.5×56 @ y=26 | fill #0a1626, edge #60a5fa |
| Layer 2 · Scheduler slab | Box 150×3.5×50 @ y=52 | fill #150f2b, edge #a78bfa; rotating torus r=14 tube=0.9 @ y=56, spin 1.5 rad/s |
| Layer 3 · Runnable deck | Box 170×3.5×66 @ y=78 | fill #0a2018, edge #34d399 |
| Heap basket | Box 40×3.5×40 @ (118,30,0); open 36×44×36 wire rails above | edge #a78bfa; full-size parked VTs remain unobstructed inside |
| Core gear i (i=0..C−1) | Cylinder r=4.7 h=6.4 plus 12 radial teeth and dark axle @ (laneX(i), 6, 0) | laneX(i) = (i−(C−1)/2)·min(26,130/(C−1)); C=4 default, clamp 2–10; rotates only while working |
| Carrier slot i | Torus r=6 tube=0.7 @ (laneX(i), 28.4, 0), flat | idle #24425f · occupied #60a5fa · pinned #f87171 + pulse scale ±8% @ 8 rad/s |
| Lane pillar i | Cylinder r=0.5 h=26 @ (laneX(i), 15, 0) | dim blue at idle; bright amber work pulse while mounted; red stutter while pinned |
| VT particle | Sphere r=1.9 (pool of maxThreads+40) | plus glow shell Sphere r=3.6, opacity .22 (see §5) |
| Mount position | (laneX(i), 30, 0) | running VT sits here; pulse scale 1.5±0.12 @ 7 rad/s |
| Queue slot i | spiral: a=0.55i, r=7+2.6√i → (cos a·1.7r, 80.5, sin a·0.62r) | elliptical spiral fills the deck to 500 |
| Heap slot i | x=118+((i%25)%5−2)·6.8, y=34+⌊i/25⌋·6.8, z=(⌊(i%25)/5⌋−2)·6.8 | 5×5 per level, stacks upward |
| Task inlet | spawn (−70±8, 90+rnd·8, 24±8) | label "APPLICATION TASKS · SUBMIT ↓" |
| Task ingress | open 34×23 submission tray at (−70,0,24) with three descending code cards | cards loop toward the runnable layer while work is active |
| Stack-chunk marker | Box 5.4×1.15×2.5 accompanying a parking or resuming VT | appears as the continuation leaves a carrier, rests in the heap, and returns to a carrier |
| Core activity i | Toothed core gear plus front piston and rising sparks @ (laneX(i),6,0) | amber rotation while working, dim at idle, slow red stutter while pinned |
| External I/O | NETWORK / DISK / TIMER / DATABASE icons at x=160, y=78, z=−52..53 | a pulsing link and travelling signal connect each parked VT to its deterministic endpoint |
| Queue-pressure bar | Box 130×2.4×5 along the runnable deck | left-anchored scale follows runnable count; pulses and says BACKPRESSURE when waiting work exceeds carrier count |

### Color palette

| Color | Hex | Role |
|---|---|---|
| green | #34d399 | runnable/queued VT, resume flash, GUIDED accent |
| blue | #60a5fa | mounted/running VT, carrier layer, mount flash |
| purple | #a78bfa | parked VT, scheduler, open heap basket/continuations |
| amber | #f5b84c | OS/CPU layer, core emissive (0.2+heat·0.9) |
| red | #f87171 | pinned carrier + its VT, PINNED label |
| white | #e6edf3 | terminating VT dissolve, headline text |
| bg/chrome | #070b12 · #0d1520 · #1a2735 | scene background/fog, HUD cards, borders |

## 3 · State Machine & Timing

```
toQueue ──→ queued ──→ mounting ──→ running ──┬──→ done ──→ dead (recycled)
   ↑ (resume, resumed=true)                    ├──→ parking ──→ parked ──→ toQueue
   └───────────────────────────────────────────┘    (pin: flag on Carrier, not a VT state)
```

| Event / parameter | Value | Notes |
|---|---|---|
| Tick | dt = min(0.05, frameΔ)·speed | speed default 0.75, slider 0.25–3.0 step 0.25 |
| Boot sequence | 3.0 s, layers rise staggered 0.5 s, over 0.5 s each | rise offset 18 units; then auto-enter Chapter 2 (MOUNT) |
| Spawn rate (free run) | 1.4 task/s, ≤6 spawns/frame, cap maxThreads=500 | burst button adds +25 |
| Work per VT | 1.6 + rnd·2.8 s | progress = 1 − work/work0 |
| Park probability | 0.30·dt while running, once per VT | resumed VTs never re-park (keeps flow legible) |
| Parked I/O wait | 1.8 + rnd·3.0 s | then unshift to queue head, resumed=true |
| Pin probability / duration | 0.03·dt / 2.6 + rnd·1.2 s | work frozen while pinned; carrier heat stays hot |
| Tween durations | spawn→queue 0.7 · mount 0.55 · park 0.85 · resume 0.85 s | smoothstep e=t²(3−2t), arc: +sin(πe)·(8+rnd·10) on Y; completion dissolves at the carrier over 1.35 s |
| Idle drift to slot | pos += (target−pos)·min(1, 4·dt) | queued + parked reflow when indices shift |
| Flash decay / log | opacity = max(0, 1 − age/1.6) · log keeps 9 lines | HUD sync at ~6 Hz, not every frame |

### Chapters

| Ch | Title | On enter | Camera preset (θ, φ, dist, targetY) |
|---|---|---|---|
| 1 | BOOT | reset sim, boot=0 | overview (0.65, 1.12, 260, 45) |
| 2 | MOUNT | burst +6, chaos off | carriers (0.35, 1.25, 150, 30) |
| 3 | PARK | pendingPark=true (next running VT parks) | heap (−0.55, 1.15, 170, 45) |
| 4 | RESUME | clamp a parked VT's io ≤ 0.8 s | carriers |
| 5 | PINNED | pendingPin=true | carriers |
| 6 | SCALE | burst to 500, chaos on | overview · TOP preset = (0.65, 0.35, 300, 40) |
| 7 | PLATFORM vs VT | clear workload; submit the same I/O-bound task count to the virtual-thread model; show the platform-thread baseline | overview |
| 8 | POOL LIMIT | clear workload; submit database I/O tasks behind three connection permits | heap |
| 9 | CPU BOUND | clear workload; submit compute-only tasks at 6× carrier count | overview |
| 10 | STRUCTURED | clear workload; fork three four-child scopes; fail one CHECKOUT child and cancel its active siblings | overview |

## 4 · HUD & Controls

| Region | Spec |
|---|---|
| Header | Pulsing green LED (1.6 s), title, GUIDED/FREE RUN toggle (ToggleGroup), status text BOOTING/RUNNING/PAUSED |
| Right sidebar 290px | Counters include RUNNABLE, MOUNTED, PARKED, COMPLETED, live total, and utilization · task-profile mix · throughput graph · 4 behavior cards · event log, 7 clickable mono lines |
| Narration card | Bottom-left 400px overlay: CHAPTER n/10, colored title, body text, ←/Next buttons. Chapter copy: §10 verbatim |
| Bottom bar | Pause/Run · +25 tasks · Force park · Force pin · replay scrubber with marker rail and LIVE return · speed Slider + readout "0.75×" |
| Keyboard | SPACE live/replay play-pause · J/K history step · L return live · ←/→ chapters (or slider step while focused) · 1–4 camera presets · mouse drag orbit (Δθ=−0.005/px, φ clamp 0.15–1.45) · scroll zoom (dist 80–480) |
| Hover / follow | PickResult on the VT pool → tooltip; click a VT or log line to pin a lifecycle card showing RUNNABLE / MOUNTED / PARKED / TERMINATED durations |

Type: Space Grotesk (UI) + IBM Plex Mono (data). Ship both as bundled TTFs via `Font.loadFont` — don't depend on system fonts on the conference machine.

## 5 · Web → JavaFX Mapping

| Web sim (three.js) | JavaFX equivalent |
|---|---|
| requestAnimationFrame loop | `AnimationTimer.handle(nowNanos)` — compute dt yourself, same 0.05 s clamp |
| WebGLRenderer + Scene | `SubScene(world, w, h, true, SceneAntialiasing.BALANCED)`, fill #070b12. No fog — approximate with far-plane 1000 and the dark bg |
| InstancedMesh (500 spheres) | No instancing. Pre-allocate a pool of 540 `Sphere(1.9)`, toggle `visible`. Share exactly 6 `PhongMaterial`s (one per color) — never one material per node |
| Additive glow shells | No additive blending. Second pool of `Sphere(3.6)` with 22%-opacity self-illuminated material; accept the softer look, or skip glow — the dark bg carries it |
| emissiveIntensity pulse on cores | Swap between 4–5 pre-baked `selfIlluminationMap` brightness levels, or lerp `diffuseColor` toward white by heat |
| CanvasTexture text sprites | 2D `Label`s in an overlay Pane, repositioned each frame via `node.localToScene(0,0,0)` projection (§6.4). FXyz3D `Text3DMesh` only for the big layer titles if you want true 3D type |
| Hero trail (vertex-colored Line) | FXyz3D `PolyLine3D` rebuilt each frame from the 36-point ring buffer, or 36 tiny pooled spheres with decaying opacity |
| Torus / edges | FXyz3D `TorusMesh`; slab edges = 12 thin Box strips per slab (JavaFX has no wireframe overlay) |
| Orbit math | Identical spherical formula on a `Translate(target) → Rotate(Y,θ) → Rotate(X,φ) → Translate(0,0,−dist)` camera rig |
| Raycast hover | Free: `setOnMouseMoved` + `event.getPickResult().getIntersectedNode()`; map node→Vt in an IdentityHashMap |

## 6 · Key Snippets (Java 25)

### 6.1 · Main loop

```java
new AnimationTimer() {
    long last = -1;
    @Override public void handle(long now) {
        double dt = last < 0 ? 0 : Math.min(0.05, (now - last) / 1e9);
        last = now;
        if (sim.running()) sim.tick(dt * sim.speed());
        scene.sync(sim);           // move pooled nodes, colors, trail
        if (++frame % 10 == 0) hud.sync(sim);   // ~6 Hz is plenty
    }
}.start();
```

### 6.2 · Camera rig (matches web presets exactly)

```java
// world is wrapped in new Scale(1, -1, 1), so Y-up math carries over
void apply(double th, double ph, double d, double ty) {
    double x = Math.sin(th) * Math.sin(ph) * d;
    double y = Math.cos(ph) * d + ty;
    double z = Math.cos(th) * Math.sin(ph) * d;
    camera.getTransforms().setAll(
        new Translate(x, -y, z),                       // note negated Y
        lookAt(new Point3D(x, -y, z), new Point3D(0, -ty, 0)));
}
```

### 6.3 · Shared materials + node pool

```java
static final PhongMaterial GREEN = mat("#34d399"), BLUE = mat("#60a5fa"),
    PURPLE = mat("#a78bfa"), RED = mat("#f87171"), WHITE = mat("#e6edf3");

static PhongMaterial mat(String hex) {
    var m = new PhongMaterial(Color.web(hex));
    m.setSpecularColor(Color.web(hex).brighter());
    return m;
}
// pool: List<Sphere> of size cap; sync() walks sim.vts(),
// assigns sphere[i].setTranslate…, setMaterial(byState), setVisible(true),
// then hides the rest. Never allocate nodes mid-frame.
```

### 6.4 · Projecting 3D anchors for 2D labels

```java
Point3D p = anchorNode.localToScene(0, 0, 0, true);  // scene coords
label.relocate(p.getX() - label.getWidth() / 2, p.getY() - 28);
// call for each visible label inside the AnimationTimer, after sync()
```

### 6.5 · Driving the model with real virtual threads (the demo flex)

```java
try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 500; i++) {
        exec.submit(() -> {
            long id = Thread.currentThread().threadId();
            sim.post(new Spawned(id));
            work();                                  // CPU slice → running
            sim.post(new Parked(id));
            Thread.sleep(Duration.ofMillis(1800 + rnd(3000)));  // real park!
            sim.post(new Resumed(id));
            work();
            sim.post(new Completed(id));
        });
    }
}
// sim.post() enqueues onto a ConcurrentLinkedQueue drained by tick();
// carrier lanes then show REAL mounts: read carrier id via
// jdk.internal tracking or JFR jdk.VirtualThreadPinned events for §PINNED
```

Virtual threads are final since Java 21 — no preview flags on 25. For the PINNED chapter with real threads, trigger it honestly: `synchronized (lock) { Thread.sleep(...); }` pins only on Java ≤23. Since Java 24 (JEP 491), `synchronized`, monitor entry, and `Object.wait()` can all unmount normally. A Java 25 pin requires a native or foreign-function frame on the stack (for example, native code calling back into blocking Java code). The synthetic PINNED chapter represents that remaining case and must say so explicitly.

## 7 · Project Setup

```xml
<!-- Maven — pom.xml essentials -->
<properties><maven.compiler.release>25</maven.compiler.release></properties>
<dependencies>
  <dependency><groupId>org.openjfx</groupId><artifactId>javafx-controls</artifactId><version>25</version></dependency>
  <dependency><groupId>org.openjfx</groupId><artifactId>javafx-graphics</artifactId><version>25</version></dependency>
  <dependency><groupId>org.fxyz3d</groupId><artifactId>fxyz3d</artifactId><version>0.6.0</version></dependency>
</dependencies>
<!-- run: mvn javafx:run  (javafx-maven-plugin 0.0.8, mainClass vtmachine.App) -->
```

| File | Content |
|---|---|
| `src/main/java/module-info.java` | `requires javafx.controls; requires org.fxyz3d.core;` · `exports vtmachine;` |
| `src/main/resources/fonts/` | SpaceGrotesk-[Regular\|Medium\|Bold].ttf, IBMPlexMono-[Regular\|Medium\|SemiBold].ttf (OFL licensed — bundle freely) |
| `src/main/resources/hud.css` | All HUD colors/typography from §4 as CSS classes; the 3D scene takes no CSS |
| Launch flags | None required. Verify `System.getProperty("prism.order")` reports a HW pipeline (es2/d3d/metal) at startup; warn on `sw` — 540 spheres in the software rasterizer will not hold 60 fps |

Stage: 1440×900 minimum. The SubScene must resize with the window: bind `subScene.widthProperty()` to the center region, and recompute the label-projection overlay on every pulse.

## 8 · Model API + Tick Algorithm

```java
public final class Sim {
    public record Stats(int runnable, int mounted, int parked, int completed) {}
    public enum VtState { TO_QUEUE, QUEUED, MOUNTING, RUNNING, PARKING, PARKED, DONE, DEAD }
    public enum Flash   { MOUNT, PARK, RESUME, PIN }

    // -- control surface (called from FX thread only) --
    void tick(double dt);            void setRunning(boolean b);   void setSpeed(double s);
    void burst(int n);               boolean forcePark();          boolean forcePin();
    void gotoChapter(int i);         void setFreeRun(boolean b);   void reset(int carriers);

    // -- read surface (view + hud) --
    List<Vt> vts();  List<Carrier> carriers();  Stats stats();
    Deque<String> log();             // ≤9 entries, newest first
    double flashAge(Flash f);        // seconds since event, for 1.6 s fade
    int chapter();  double bootT();  Vt hero();    // nullable
}
```

### tick(dt) — exact phase order (matters: mount must see this frame's freed carriers)

```
1  BOOT      if bootT < 3: bootT += dt; if crossed 3 → log("scheduler online"), gotoChapter(2); return
2  SPAWN     spawnAcc += rate*dt; while (spawnAcc≥1 || burst>0) && vts < cap && n<6: spawn()
             spawn(): choose FAST (36%), COMPUTE (32%), or IO_BOUND (32%);
                      work = 0.45–1.5 / 2.2–7.0 / 0.8–2.6 s respectively;
                      IO_BOUND gets a 1–8 s planned wait and a randomized work trigger;
                      if hero==null → hero=this; tween to queueSlot(queue.size), 0.7s; queue.add
3  TWEENS    for each vt with active tween: t += dt/dur; e = t²(3−2t);
             pos = lerp(from,to,e); pos.y += sin(πe)*arc; if t≥1 → fire onArrive
4  DRIFT     queued/parked without tween: pos += (slot(index) − pos) * min(1, 4dt)
5  CARRIERS  for each carrier: heat = max(0, heat − 0.8dt)
             if pinT>0: pinT −= dt; on reaching 0 → log("VT-n unpinned · resumes")
             if empty: take first QUEUED w/o tween → MOUNTING, tween to (laneX,30,0) 0.55s
                       → RUNNING on arrive; flash(MOUNT); log(mounted|resumed on Cn)
6  RUN       for each RUNNING vt: carrier.heat = min(1, heat+2dt)
             pendingPark? → park(vt) and clear flag        // guided chapter 3
             pendingPin?  → pin(vt) and clear flag          // guided chapter 5
             carrier pinned? → skip (work frozen)
             work −= dt
             IO_BOUND && !resumed && work≤ioTrigger → park(vt)
             chaos && work>0.4:  r=rnd();  !resumed && r<0.30dt → park(vt)
                                            r>1−0.03dt → pin(vt)
             work≤0 → free carrier; DONE; hold position and dissolve for 1.35s → DEAD; completed++
7  PARKED    io −= dt; io≤0 → resumed=true; queue.addFirst; tween to queueSlot(0) 0.85s; flash(RESUME)
8  REAP      remove DEAD (return spheres to pool); hero dead → hero=null

park(vt): free carrier; PARKING; io=planned 1–8 s (or a random demo wait); tween to heapSlot 0.85s
          → PARKED; flash(PARK); log("VT-n I/O wait · carrier released")
pin(vt):  carrier.pinT = 2.6+rnd*1.2; flash(PIN); log("VT-n PINNED on Cn")
```

Determinism: give Sim an injectable `RandomGenerator` seed so a rehearsed run can be replayed exactly on stage. All randomness above flows through it.

## 9 · View Sync Algorithm (per frame)

```
MachineScene.sync(sim):
1 BOOT RISE   a(i) = clamp((bootT − 0.5i)/0.5);  slab[i].translateY = restY(i) − (1−a)*18
              (remember: world group carries Scale(1,−1,1))
2 RING        schedulerRing.rotate += 1.5 * dt * 57.3°   (only if a(2) > 0.6, else dim material)
3 CORES/SLOTS core i: material lerp amber→white by heat; pinned → RED variants;
              slot i: color idle/occupied/pinned; pinned → scale 1 ± 0.08·sin(8t), show PINNED label
4 VT POOL     i=0; for vt in sim.vts():
                s = sphere[i], g = glow[i]
                s.translate = vt.pos (Y negated by world scale — set raw values)
                scale: RUNNING 1.5+0.12·sin(7t+id) · PARKED 1.5+0.08·sin(5t+id) · else 1.0 · hero ×1.45
                material: QUEUED/TO_QUEUE green · RUNNING/MOUNTING blue (red if carrier pinned)
                          PARKING/PARKED purple · DONE white
                g mirrors s at scale ×1.15; both visible; i++
              hide sphere[i..], glow[i..]
5 HERO        ring buffer (36) push hero.pos → rebuild PolyLine3D; move "VT-n" overlay label
              hero==null → hide trail + label
6 CAMERA      if goal: o += (goal−o)*0.06 each of θ,φ,dist,targetY; clear goal when |Δd|<1 ∧ |Δθ|<0.01
7 LABELS      reproject all overlay labels per §6.4

Hud.sync(sim) — every 10th frame:
  counters from stats(); flash cards opacity = max(0, 1 − flashAge/1.6)
  log ListView items; status text; narration card if chapter changed
```

Threading rule: everything above runs on the FX Application Thread. The only cross-thread traffic is `sim.post(event)` from real virtual threads (§6.5) into a `ConcurrentLinkedQueue`, drained at the top of tick().

## 10 · Chapter Copy (verbatim) + Legend

| Chapter | Narration text (use verbatim) |
|---|---|
| 1 BOOT | Power on. Four OS threads light up on the CPU cores; the JVM starts a matching pool of carrier (platform) threads, and the ForkJoinPool scheduler spins up above them. This small machine is all the OS ever sees. |
| 2 MOUNT | Application tasks arrive with varied runtimes: some finish quickly, some compute longer, and dotted-satellite tasks will perform I/O. The scheduler mounts each runnable VT onto a free carrier; only while mounted does a VT consume an OS thread. |
| 3 PARK | An I/O-bound VT reaches its randomized wait. It turns purple and flies to the heap area while its stack chunks remain with the virtual thread. Its carrier is instantly free to run another VT. |
| 4 RESUME | The I/O completes. The stored continuation makes the VT runnable again and it remounts on ANY free carrier — not necessarily the one it left. Watch it land on a different slot. |
| 5 PINNED | A remaining failure mode on Java 25: blocking while a native or foreign-function frame prevents unmounting pins the VT to its carrier. The slot locks red until the pin ends. Ordinary synchronized code no longer pins. |
| 6 SCALE | The payoff. 500 mixed-duration tasks flood in and the machine does not grow: a fixed set of illustrative carrier lanes multiplexes fast, compute-heavy, and I/O-bound virtual threads, while parked waits retain lightweight heap-backed state. |
| 7 PLATFORM vs VT | Run the same blocking I/O workload two ways. A platform-thread-per-task design ties up one costly OS thread per wait; virtual threads park cheaply while a small, fixed carrier pool keeps executing other work. |
| 8 POOL LIMIT | Virtual threads remove the thread bottleneck, not downstream limits. Only three tasks may hold a database connection; every other VT parks in the heap without occupying a carrier until a permit becomes available. |
| 9 CPU BOUND | Virtual threads improve blocking concurrency, not CPU parallelism. Compute-only tasks saturate every carrier and the run queue grows, but throughput plateaus at the available carrier/core count. |
| 10 STRUCTURED | Related child VTs live inside parent scopes. Each scope forks four children and joins only after they finish; when one CHECKOUT child fails, its active siblings are cancelled and the failure is contained within that scope. |

| Legend card | Body text |
|---|---|
| 1 · Mount | Runnable VT mounts on a free carrier thread. |
| 2 · Park | Blocking I/O unmounts the VT; continuation stored on the heap, carrier released. |
| 3 · Resume | I/O done; VT remounts on any free carrier. |
| 4 · Pinned | Blocking with a native/foreign frame can pin the carrier; synchronized alone does not on Java 25. |

## 11 · Performance Budget + Acceptance Checklist

| Budget | Target |
|---|---|
| Frame rate, chapter 6 (540 visible nodes) | 60 fps on integrated GPU; tick+sync ≤ 4 ms/frame |
| Allocation per frame | ~zero: pooled spheres, reused Point3D scratch, ring-buffer trail. Only the trail PolyLine3D rebuild allocates — acceptable at 1/frame |
| HUD updates | 6 Hz; never bind counters directly to per-frame properties |
| Startup to first frame | < 2 s including font loading |

Acceptance — matches the HTML reference sim when:

- [ ] Boot: 5 layers rise bottom-up over 3 s, then MOUNT chapter auto-enters and 6 tasks spawn
- [ ] Park: VT arcs to heap tower, carrier ring dims to idle within the same second, PARK card flashes purple
- [ ] Resume: parked VT re-enters queue HEAD and mounts on a different lane than it left (verify with forced park on C1 while C2 free)
- [ ] Pinned: slot + core turn red, PINNED label shows, work % frozen for 2.6–3.8 s, other carriers keep flowing
- [ ] Scale: counter reaches 500 spawned; RUNNABLE+MOUNTED+PARKED+in-flight ≈ live total; fps holds
- [ ] Platform comparison: identical I/O task counts show N platform OS threads versus the configured fixed carrier count
- [ ] Pool limit: at most three DB permits are active; extra VTs remain parked and carriers continue draining work
- [ ] CPU bound: all carriers stay busy, no tasks park, and the queue grows beyond the carrier count
- [ ] Structured: three four-child scope trees render; CHECKOUT records one failure, cancels active siblings, and joins exceptionally
- [ ] Replay: scrubbing pauses the live model and updates 3D/HUD/log/notes from immutable frames; playing advances recorded frames; LIVE restores the untouched live state and previous running/auto-play flags
- [ ] Hero: exactly one trail at a time; new hero auto-picked after previous completes
- [ ] Input: SPACE, ←/→, 1–4, drag orbit (φ clamped 0.15–1.45), scroll zoom (80–480), hover tooltip on any sphere
- [ ] Determinism: same seed → identical event log across two runs

Out of scope, deliberately: bloom/post-processing (no JavaFX support), fog, shadows, and sound. If the talk needs more spectacle, the cheapest wins are a brighter glow pool and a slow ambient camera drift while idle.

---

# AGENT-GRADE ADDENDUM (§12–17)

The sections below close every ambiguity. With §1–17 an agent should need zero design decisions. Where this document and the HTML reference (`Virtual Thread Machine 3D.dc.html`) disagree, the HTML source is ground truth.

## 12 · lookAt Implementation (completes §6.2)

JavaFX has no camera lookAt. Use this affine (JavaFX screen-up is −Y; camera looks down +Z):

```java
static Affine lookAt(Point3D eye, Point3D target) {
    Point3D up = new Point3D(0, -1, 0);
    Point3D f  = target.subtract(eye).normalize();     // forward (+Z of camera)
    Point3D r  = up.crossProduct(f).normalize();       // right
    Point3D u  = f.crossProduct(r);                    // true up
    return new Affine(
        r.getX(), u.getX(), f.getX(), eye.getX(),
        r.getY(), u.getY(), f.getY(), eye.getY(),
        r.getZ(), u.getZ(), f.getZ(), eye.getZ());
}
// CameraRig.apply(): camera.getTransforms().setAll(lookAt(eyeScene, targetScene));
// eyeScene = (x, −y, z) and targetScene = (0, −ty, 0) from §6.2 (Y negated because
// the world group carries Scale(1,−1,1) but the camera sits OUTSIDE that group).
```

Camera goal lerp (every frame while a goal exists): `o.θ += (g.θ−o.θ)·0.06`, same factor for φ, dist, targetY; clear the goal when `|g.dist−o.dist| < 1 && |g.θ−o.θ| < 0.01`. Any mouse-drag or scroll immediately clears the goal.

### Input handlers, exact

| Input | Effect |
|---|---|
| Mouse press on SubScene | remember the picked VT and begin a potential drag; cursor CLOSED_HAND; clear camera goal |
| Drag | θ −= Δx·0.005 ; φ = clamp(φ − Δy·0.004, 0.15, 1.45) |
| Release / exit | end drag; cursor OPEN_HAND; a release within 4px follows the remembered VT instead of orbiting |
| Scroll | dist = clamp(dist + Δy·0.4, 80, 480); clear goal |
| Pinch gesture | `ZoomEvent.ZOOM`: dist += −ln(zoomFactor)·420; clamp 80–480; clear goal |
| Mouse move (not dragging) | pick → tooltip (§14); recheck every 3rd frame is fine |
| SPACE | toggle live running, or replay playback while history is selected. Ignore when focus is in a text input |
| J / K / L | previous replay frame / next replay frame / return to live edge |
| ← / → | gotoChapter(current ∓/± 1) — wraps modulo 10 |
| 1 / 2 / 3 / 4 | camera presets overview / carriers / heap / top |

## 13 · HUD — Exact Styling (completes §4)

Global: window bg `#070b12`; UI font Space Grotesk; data font IBM Plex Mono; all panel borders 1px `#141f2c`; card borders 1px `#1a2735`; card bg `#0d1520`; card radius 8px.

| Element | Exact spec |
|---|---|
| Header bar | padding 12 22; bottom border. LED: 10px circle #34d399, dropshadow blur 12 #34d399, opacity pulses 1→0.35→1 over 1.6 s. Title 19px/700 letter-spacing .04em #e6edf3: "THE VIRTUAL THREAD MACHINE". Subtitle 12px #7d8fa3: "Mount · Park · Resume · Pin — 500 virtual threads on 4 carriers, live in 3D" |
| GUIDED / FREE RUN toggle | two buttons, mono 11px, padding 6 12, radius 7. Active: bg #0f2b21, fg #6ee7b7, border #2a5c48. Inactive: bg #0e1826, fg #7d8fa3, border #26364a |
| Status text | mono 12px letter-spacing .12em #34d399, right-aligned, width 86: BOOTING / RUNNING / PAUSED |
| Counter cards (2×2, gap 8) | padding 8 10; value mono 22px (colors: runnable #34d399, mounted #60a5fa, parked #a78bfa, completed #e6edf3); label mono 10px #7d8fa3 uppercase |
| Key-behavior cards (×4, gap 7) | padding 8 11; title 14px/500 in the behavior color; body 12px #8ea2b8. Flash: full-card overlay, colors rgba(96,165,250,.16) / rgba(167,139,250,.16) / rgba(52,211,153,.16) / rgba(248,113,113,.18); opacity = max(0, 1−age/1.6), 0.35 s ease |
| Event log | header 11px letter-spacing .16em #7d8fa3 "EVENT LOG"; lines mono 10.5px lh 1.75 #9db2c8, ellipsis-truncated, newest first, max 9 |
| Narration card | 400px wide, bottom-left inset 16; bg rgba(10,16,25,.88), border #1d2b3c, radius 12, padding 14 16, blur backdrop if cheap. Row: "CHAPTER n/10" mono 12px #7d8fa3 · title 17px/700 in chapter color · spacer · ← button 30×26 (bg #0e1826, border #26364a, fg #9db2c8) · "Next →" button h26 padding 0 12 (bg #0f2b21, border #2a5c48, fg #6ee7b7). Body 14px lh 1.55 #b6c6d8 |
| Camera preset buttons | top-right inset 16/14, gap 6; mono 10px, padding 5 10, radius 6, bg rgba(14,24,38,.8), border #26364a, fg #9db2c8; labels OVERVIEW CARRIERS HEAP TOP |
| Shortcut hint | bottom-right inset 16; mono 10px #5c7089; include presenter, quality, contrast, notes, and camera shortcuts. Drag orbits; scrolling and pinch gestures zoom. |
| Bottom bar | padding 10 22, top border. Pause/Run: 13px/500, padding 8 18, radius 8, width 90, green set. Task/park/pin/settings controls retain their chapter colors. Center: replay status, event legend, history slider + marker rail, LIVE button. Right: FPS, SPEED slider w130 range 0.25–3, and readout "0.75×". |
| Hover tooltip | bg rgba(10,16,25,.92), border #2a3b52, radius 6, padding 5 9, mono 12px #cfe0f2, no wrap, offset cursor +14/+10 |

## 14 · Event Catalog + Tooltip Grammar (exhaustive)

Log line format: `%03ds %s` where the integer is floor(sim.t). Every message the sim ever emits:

| Trigger | Message |
|---|---|
| initial feed entry | `— boot sequence initiated —` (unprefixed) / `boot: power on` |
| boot completes (t≥3) | `scheduler online` |
| mount (fresh) | `VT-{id} mounted on C{n}` |
| mount (after park) | `VT-{id} resumed on C{n}` |
| park | `VT-{id} I/O wait · carrier released` |
| I/O completes | `VT-{id} I/O done · runnable` |
| pin | `VT-{id} PINNED on C{n}` |
| pin expires | `VT-{id} unpinned · resumes` |
| complete | `VT-{id} completed · C{n} free · terminated` |
| burst button | `burst: 25 tasks submitted` |
| force park, none eligible | `no unpinned running VT` |
| force pin, none eligible | `no running VT` |
| mode → free | `free run: continuous load` |
| mode → guided | `guided mode` |
| chapter enter (2–10) | lifecycle logs plus `platform vs virtual`, `connection pool`, `CPU bound`, and `structured scopes` scenario logs |

Tooltip text: `VT-{id} · {state}` + (if carrier ≥0) ` on C{n}` + (if RUNNING) ` · {pct}% done` where pct = clamp(round((1−work/work0)·100), 0, 100) + (if PARKED) ` · I/O {io:%.1f}s`. State strings are the lowercase enum names used in the web sim: toQueue, queued, mounting, running, parking, parked, done.

## 15 · Chapter Engine — Exact Semantics (completes §3)

`gotoChapter(i)` (i is 0-based internally; HUD shows i+1 of 10):

```
i = ((i mod 10) + 10) mod 10               // ← and → wrap around
mode = GUIDED; chaos = false; spawnRate = 0
pendingPark = pendingPin = false           // stale flags never leak across chapters
switch i:
  0 BOOT:   reset(carriers); bootT = 0; camera OVERVIEW
            // reset keeps: speed. resets: t, vts, queue, carriers, hero, log, counters
  1 MOUNT:  burst += 6; camera CARRIERS; log "chapter: mount"
  2 PARK:   if no RUNNING vt: burst += 4   // self-heals an empty machine
            pendingPark = true; camera HEAP; log "chapter: park"
  3 RESUME: p = first PARKED vt; if p: p.io = min(p.io, 0.8)
            else: burst += 2; pendingPark = true    // will park then quickly resume
            camera CARRIERS; log "chapter: resume"
  4 PINNED: if no RUNNING vt: burst += 4; burst += carriers*3
            pendingPin = true; camera CARRIERS; log "chapter: pinned" // keeps work queued behind the pin
  5 SCALE:  burst += maxThreads − vts.size; chaos = true; camera OVERVIEW
            log "chapter: scale — flooding tasks"
  6 COMPARE: clear workload; burst += max(24, carriers*8) I/O tasks; camera OVERVIEW
  7 POOL:    clear workload; burst += max(18, carriers*5) DB tasks; permits = min(3, carriers); camera HEAP
  8 CPU:     clear workload; burst += max(24, carriers*6) compute tasks; camera OVERVIEW
  9 SCOPE:   clear workload; create SEARCH/CHECKOUT/REPORT scopes with four children each;
             one CHECKOUT child fails and cancels its active siblings; camera OVERVIEW
```

Edge cases an agent must honor:
- During boot (bootT<3) only bootT advances; spawn/mount/etc are skipped, but chapter buttons still work (chapter 0 restarts boot; others take effect once boot ends).
- Boot end auto-calls `gotoChapter(1)` exactly once.
- `pendingPark`/`pendingPin` fire on the FIRST vt encountered in RUNNING iteration order, then clear.
- Free-run toggle: chaos=true, spawnRate=taskRate (default 1.4/s), chapter card stays on last chapter. Guided toggle: chaos=false, spawnRate=0; in-flight VTs finish naturally.
- Force park picks the first RUNNING vt whose carrier is not pinned; force pin likewise. Both log the failure message if none.
- Mount selection: first QUEUED vt with no active tween (skips ones still flying in). Resume re-entry: addFirst (queue head) so resumed VTs mount before waiting fresh ones.
- A resumed vt never parks again (`resumed` flag) — deliberate, keeps the story legible.
- Hero: assigned at spawn when hero==null; survives park/resume; cleared when DEAD; the next spawn inherits the role.

## 16 · 3D Text Label Inventory (completes §2)

Rendering: each label is a canvas-drawn texture `600 {px}px IBM Plex Mono` on transparent, shown as a screen-facing quad (web: Sprite, depthTest off; JavaFX: 2D overlay Label projected per §6.4 — always on top matches depthTest:off). Default px = 28. World size = canvasPixelSize × 0.42.

| Text | Color | Position | px | Anchor |
|---|---|---|---|---|
| OS THREADS / CPU CORES | #f5b84c | (−81, 7, 0) — left edge of slab 0, y = slabY+7 | 28 | right-middle |
| CARRIER THREADS | #60a5fa | (−81, 33, 0) | 28 | right-middle |
| SCHEDULER · ForkJoinPool | #a78bfa | (−81, 59, 0) | 28 | right-middle |
| VIRTUAL THREADS · runnable | #34d399 | (−91, 85, 0) | 28 | right-middle |
| HEAP · {n} PARKED STACK CHUNKS · I/O EXTERNAL | #a78bfa | (118, 74, 0) | 28 | center; open wire basket, full-size VT spheres, live count, selected VT plus up to three projected VT-id badges |
| RUN QUEUE · {state} | #34d399 | (0, 91, 28) | 28 | expands with runnable pressure and announces BACKPRESSURE above carrier capacity |
| EXTERNAL I/O + endpoint labels | #a78bfa | x=160, y=78, z=−52..53 | 22–28 | network, disk, timer, and database wait destinations |
| C1…Cn | #8ea2b8 | (laneX(i), 22, 16) | 24 | center |
| PINNED (per lane, hidden unless pinned) | #f87171 | (laneX(i), 40, 0) | 28 | center |
| APPLICATION TASKS · SUBMIT ↓ | #7d8fa3 | (−70, 102, 24) | 28 | center; animated code-card tray beneath |
| VT-{heroId} (follows hero) | #6ee7b7 | hero.pos + (0, 8, 0) | 24 | center |

Hero trail: 36-point ring buffer, seeded at (0,−999,0) (off-scene) so it doesn't streak on first frames; per-vertex color ramp from black to rgb(0.43, 0.91, 0.72) (= #6ee7b7) tail→head; line opacity 0.9.

Lighting (web values; approximate in JavaFX): AmbientLight #8899bb @ 0.55 → `new AmbientLight(Color.web("#8899bb").deriveColor(0,1,0.55,1))`; key directional white 0.75 from (80,180,120) → `PointLight` far away at that direction ×5; rim PointLight #60a5fa 0.5 at (−120,80,−80) range ~400. Grid floor: 600×600, 40 divisions, line colors #14202e / #0e1620, at y=−16.

## 17 · Tweakable Parameters + Build Order

Exposed settings (a Preferences dialog or CLI flags):

| Param | Range | Default | Applied |
|---|---|---|---|
| carriers | 2–10 | 4 | on reset only (lane spacing contracts above 6) |
| maxThreads | 50–800 | 500 | live (spawn cap; pool sized at max+40) |
| taskRate | 0.3–6.0 /s | 1.4 | live (free-run spawn rate) |
| seed | any long | random | on reset |

Recommended reconstruction order (each step runs):
1. `Sim` + unit tests: seeded run of 60 simulated seconds asserting the §14 log sequence.
2. `App` shell + HUD with dummy stats (all §13 styling).
3. `MachineScene` statics: slabs, cores, slots, pillars, ring, heap, grid, labels (§2, §16).
4. `CameraRig` + input (§12) — verify all four presets frame the machine.
5. VT pool + sync (§9) driven by Sim — chapters 1–10 acceptance boxes (§11).
6. Boot rise, hero trail, tooltip, flash cards, keyboard — remaining acceptance boxes.
7. Optional: real-virtual-thread feed (§6.5) behind a `--live` flag.
