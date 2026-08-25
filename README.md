# The Virtual Thread Machine

A native Java 25 and JavaFX 3D visualization of how virtual threads mount,
park, resume, and pin across a small carrier pool. The implementation follows
[`design/JavaFX-Spec.md`](design/JavaFX-Spec.md) and uses the accompanying HTML
simulation as its behavioral reference.

## Run

Requirements: JDK 25 and Maven 3.9+.

```bash
mvn javafx:run
```

The application opens at 1440×900. It automatically advances from BOOT to
MOUNT after three simulated seconds.

Use the `SYNTHETIC` feed for a completely deterministic teaching run, or select
`LIVE JDK` to drive the visualization with tasks executing on a real
`Executors.newVirtualThreadPerTaskExecutor()`. In live mode, mount/park/resume
events come from the workload and JFR observes `jdk.VirtualThreadPinned` events.
The four carrier lanes remain illustrative because Java intentionally does not
expose ordinary virtual-thread carrier identity.

## Controls

- `Space`: pause or resume
- `←` / `→`: previous or next chapter
- `1`–`4`: overview, carriers, heap, and top camera presets
- `0`: emergency overview camera
- `P`: presenter mode (full screen with distraction-free HUD)
- `A`: auto-advance chapters every 11 seconds
- `R`: replay the current chapter
- `Q`: cycle automatic, high, and low render quality
- `H`: toggle high-contrast mode
- `N`: toggle the second-screen speaker-notes window
- `F11`: toggle full screen
- Mouse drag: orbit
- Mouse wheel/trackpad scroll or two-finger pinch: zoom in and out
- HUD controls: switch synthetic/live feeds, add 25 tasks, force a park or
  demonstration pin, change speed, or restart with new demo settings

The sidebar includes live total, carrier utilization, average I/O, event-log
highlighting, and a rolling completions/second chart. `AUTO` render quality
hides nonessential glows when frame rate drops or the task pool becomes dense.
The layout compacts below 1280 px and all controls expose keyboard focus and
accessible descriptions.

Tasks deliberately have different profiles and seeded random durations: short
CPU work, longer compute work, and I/O-bound work with randomized 1–8 second
waits. I/O-bound VTs carry a small purple satellite before blocking, then turn
purple and move into the heap-backed I/O area while unmounted. Parked particles
retain their glow, receive an on-screen VT badge, and are included in the heap's
live parked-count label.

Completed VTs turn white and fly into a small `TERMINATED · GC ELIGIBLE`
reclamation bin before disappearing. The wording is intentional: termination
makes an unreferenced VT eligible for collection, but does not imply that the
garbage collector ran immediately.

## Reproducible settings

Command-line settings use `--name=value` syntax:

```bash
mvn javafx:run -Djavafx.args="--carriers=4 --max-threads=500 --task-rate=1.4 --seed=42"
```

Supported settings:

- `--carriers=2..10`
- `--max-threads=50..800`
- `--task-rate=0.3..6.0`
- `--seed=<long>`
- `--live` (use real JDK virtual-thread tasks)
- `--presenter` (start in presenter/full-screen mode)

For visual smoke tests, `--snapshot=/path/image.ppm` writes a full-window PPM
after 4.5 seconds. Override the delay with `--snapshot-at=<seconds>`.
Use `--snapshot-chapter=1..6` to capture a specific chapter. The helper script
`scripts/capture-chapters.sh` captures all six with seed 42.

## Test

```bash
mvn test
```

The model is JavaFX-free and uses a seeded `RandomGenerator`. The application
feeds it fixed 60 Hz simulation steps so a seed produces repeatable behavior
independently of render-frame timing.

The suite also exercises randomized state invariants, deterministic event
vocabulary, the real virtual-thread bridge, settings parsing, and a scale
performance budget.

## Native package

Build a self-contained platform app image with JDK 25's `jpackage`:

```bash
scripts/package.sh
# or: mvn clean package -Pnative-image
```

The result is under `target/dist`. On macOS, set
`VT_MACHINE_SIGN_IDENTITY` before packaging to codesign with the hardened
runtime. After configuring an `xcrun notarytool` keychain profile, set
`VT_MACHINE_NOTARY_PROFILE` and run `scripts/notarize-macos.sh` to submit,
staple, and validate the bundle.

## Java 25 accuracy note

Since JDK 24, blocking in ordinary `synchronized` code no longer pins a virtual
thread. The remaining pinning case demonstrated here is blocking while a native
or foreign-function frame prevents unmounting. Virtual-thread stack chunks live
on the heap; the 3D carrier lanes are a teaching abstraction, particularly in
the live feed.
