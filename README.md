# The Virtual Thread Machine

A native Java 25 and JavaFX 3D visualization of how virtual threads mount,
park, resume, and pin across a small carrier pool. The implementation follows
[`design/JavaFX-Spec.md`](design/JavaFX-Spec.md) and uses the accompanying HTML
simulation as its behavioral reference.

[![Native installers](https://github.com/rokon12/virtual-thread-simulation/actions/workflows/native-installers.yml/badge.svg)](https://github.com/rokon12/virtual-thread-simulation/actions/workflows/native-installers.yml)

![The Virtual Thread Machine demonstrating parking, downstream limits, structured scopes, and historical timeline replay](docs/media/virtual-thread-machine-demo.gif)

## About

Built by **Bazlur Rahman** (`bazlur@bazlur.dev`), The Virtual Thread Machine is
an educational tool for making normally invisible virtual-thread behavior easy
to see and explain. It visualizes mounting, parking, resuming, pinning, carrier
scheduling, external I/O, downstream limits, and structured concurrency. It is
a teaching simulation rather than a production JVM profiler.

## Install

Packaged releases include a Java runtime, so installing the application does
not require a separate JDK or Maven installation. Download the package for your
platform from [GitHub Releases](https://github.com/rokon12/virtual-thread-simulation/releases):

| Platform | Package | Installation |
| --- | --- | --- |
| macOS Apple silicon | `Virtual-Thread-Machine-<version>-macos-arm64.dmg` | Open the DMG and drag **Virtual Thread Machine** into Applications. |
| Windows x64 | `Virtual-Thread-Machine-<version>-windows-x64.msi` | Run the MSI and use the Start Menu or desktop shortcut. |
| Debian/Ubuntu x64 | `Virtual-Thread-Machine-<version>-linux-x64.deb` | Run `sudo apt install ./Virtual-Thread-Machine-<version>-linux-x64.deb`. |

Tagged builds such as `v1.0.0` are published to Releases automatically. The
latest untagged packages can also be downloaded from a successful
[Native installers workflow run](https://github.com/rokon12/virtual-thread-simulation/actions/workflows/native-installers.yml).

Packages are unsigned by default, so macOS or Windows can display a publisher
warning for development builds. The macOS packaging scripts support Developer
ID signing and Apple notarization as described below.

## Run from source

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

- `Space`: pause/resume the live simulation or play/pause recorded history
- `←` / `→`: previous or next chapter
- `1`–`4`: overview, carriers, heap, and top camera presets
- `0`: emergency overview camera
- `P` or the `Present` button: presenter mode (full screen with a focused,
  projector-scaled HUD); `Esc` returns to the operator view
- `A`: auto-advance chapters every 11 seconds
- `R`: replay the current chapter
- `Q`: cycle automatic, high, and low render quality
- `H`: toggle high-contrast mode
- `C`: toggle cinematic event spotlight and slow motion
- `M`: mute or enable optional presentation sound cues
- `N`: toggle the second-screen speaker-notes window
- `J` / `K`: step backward or forward through recorded history
- `L`: leave replay and return to the untouched live simulation
- `F11`: toggle full screen
- Mouse drag: orbit
- Mouse wheel/trackpad scroll or two-finger pinch: zoom in and out
- Drag the bottom timeline or focus it and use `←` / `→`: inspect an earlier
  immutable frame; colored marks identify chapters, mounts, parks, pins,
  completions, failures, and cancellations
- Click a VT or one of its event-log entries: follow its lifecycle and time in
  each state
- `Esc` or the lifecycle card's × button: stop following a VT
- HUD controls are chapter-aware: lifecycle actions appear only for mount/park/
  resume, JDK actions only for the version comparison, and structured-scope
  actions only for Chapter 10. Settings, About, and Present remain available.

Presenter mode replaces paragraph narration with one audience-facing takeaway,
enlarges fixed teaching diagrams and machine labels for a projector, adds safer
camera breathing room, and removes operator diagnostics. The consequence meter
also scales for wide displays and gets out of the way on narrow screens. Keep
the full narration and event log in the operator view or the second-screen
speaker notes.

`Showdown` runs both JDK behaviors simultaneously with the same eight-task
queue. The JDK 21 lane retains a red pinned carrier while the JDK 25 lane parks
the blocker and continues completing work, making the throughput difference
visible without switching back and forth.

Cinematic mode is enabled by default in guided demonstrations. Important mount,
park, resume, and pin events briefly slow the model, dim the surrounding scene,
and spotlight the responsible VT with a large causal callout. Use `C` or the
`CINEMA ON/OFF` camera-bar control to disable it for uninterrupted workloads.

During a guided park, an X-ray overlay names representative Java frames such as
`handleRequest()`, `loadApplicationData()`, and the active blocking call. The
frames compress into a single heap-backed continuation, follow the VT through
the heap and run queue, and expand again while it remounts.

A live consequence meter anchors the lower-right presentation view. It reports
free carriers, run-queue pressure, completed tasks, and cumulative pinned versus
parked VT-seconds. The latter are aggregate thread-seconds, so ten VTs parked
for one second contribute ten parked VT-seconds.

Presentation sound starts muted. Enable `SOUND ON` or press `M` for short,
locally synthesized mount, park, resume, pin-warning, and completion cues. The
cues require no media files or network access and automatically remain disabled
if the operating system has no available audio output line.

The `SCALE` chapter now closes as a staged finale. While the configured VT
workload floods the model, the camera gradually pulls back and a colored
constellation spreads around the machine. The motion resolves into a held title
card showing the configured virtual-thread count, the small carrier/OS-thread
count, and the central takeaway: parked VTs use zero carriers.

The sidebar includes live total, carrier utilization, average I/O, event-log
highlighting, and a rolling completions/second chart. `AUTO` render quality
hides nonessential glows when frame rate drops or the task pool becomes dense.
The layout compacts below 1280 px and all controls expose keyboard focus and
accessible descriptions.

At the top of the machine, animated code cards pass through the application
task inlet before becoming runnable virtual threads. At the bottom, each OS/CPU
core is a toothed processor gear: it rotates and brightens while its carrier
executes, eases up to speed and coasts smoothly to rest, and becomes a red
warning stutter while pinned.

The rolling timeline retains the latest 360 display snapshots. Entering history
automatically pauses the live model, updates the 3D particles, counters, event
log, narration, lifecycle card, and speaker notes to the selected instant, and
never restores historical data into the model. `LIVE`/`L` returns to the exact
live state and running/auto-play mode that existed before scrubbing.

Tasks deliberately have different profiles and seeded random durations: short
CPU work, longer compute work, and I/O-bound work with randomized 1–8 second
waits. I/O-bound VTs carry a small purple satellite before blocking. When they
park, a stack-chunk marker moves from the released carrier into the heap while
an animated connection continues to an external network, disk, timer, or
database endpoint. The parked VT keeps the same sphere identity and size inside
an open heap basket, with its retained stack shown as a small plate beneath it.
The continuation returns through the run queue before mounting on any available
carrier.

Clicking a VT opens a live lifecycle strip for `RUNNABLE`, `MOUNTED`, `PARKED`,
and `TERMINATED` durations. The `JDK 21` and `JDK 25` buttons run the same modeled
blocking operation inside `synchronized`: JDK 21 retains the carrier in red,
while JDK 25 moves the VT to the heap and releases the carrier. A pressure bar
expands and contracts with the scheduler run queue. Completed VTs release their
carrier, turn white, and dissolve in place.

Four advanced chapters turn the machine into a performance lab:

- `PLATFORM vs VT` runs the same blocking workload as one-platform-thread-per-task
  and as virtual threads on a fixed carrier pool.
- `POOL LIMIT` places a three-permit database connection pool downstream; excess
  VTs visibly park without consuming carriers. In `LIVE JDK` mode the permits
  are enforced by a fair `Semaphore`, so the wait is performed by real VTs.
- `CPU BOUND` saturates the carriers with compute-only tasks and plots the
  throughput plateau at the carrier/core count.
- `STRUCTURED` turns the final chapter into an interactive presentation lab. A
  structured scope tree is contrasted with orphan-prone unstructured tasks; an
  animated `FORK → RUN → FAIL → CANCEL → JOIN → CLOSE` timeline follows the
  lifetime, inherited user/trace context is released on close, and the final
  card holds the invariant that no child outlives its parent. Chapter-only
  controls cycle `Shutdown on failure`, `Shutdown on success`, and `Await all`
  policies, inject a CHECKOUT failure, cancel the parent, or replay the story.
  Every policy and choreography state is captured in replay history.

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
Use `--snapshot-chapter=1..10` to capture a specific chapter. The helper script
`scripts/capture-chapters.sh` captures all ten with seed 42. Add
`--snapshot-follow` to include the selected hero VT's lifecycle card in a
visual smoke test. Add `--snapshot-replay` to capture the historical replay UI
roughly two seconds behind the live edge.

## Test

```bash
mvn test
```

The model is JavaFX-free and uses a seeded `RandomGenerator`. The application
feeds it fixed 60 Hz simulation steps so a seed produces repeatable behavior
independently of render-frame timing.

The suite also exercises randomized state invariants, immutable bounded replay
history and markers, deterministic event vocabulary, the real virtual-thread
bridge, settings parsing, and a scale performance budget.

## Build native installers

JDK 25's `jpackage` creates a self-contained installer with its own Java
runtime. Build on the target operating system:

| Platform | Command | Installer |
| --- | --- | --- |
| macOS | `scripts/package.sh` | `.dmg` |
| Windows | `pwsh -File scripts/package.ps1` | `.msi` |
| Debian/Ubuntu Linux | `scripts/package.sh` | `.deb` |

Results are written under `target/dist` with predictable names such as
`Virtual-Thread-Machine-1.0.0-macos-arm64.dmg`. Override the version with
`VT_MACHINE_VERSION=1.1.0` on macOS/Linux or
`$env:VT_MACHINE_VERSION="1.1.0"` in PowerShell. Use
`mvn clean package -Pnative-image` when you only need the unpackaged
application image.

The display name remains **Virtual Thread Machine**, while each platform uses
an installer-safe identity: `ca.bazlur.virtualthreadmachine` on macOS,
`virtual-thread-machine` on Linux, and a stable upgrade UUID on Windows.

The [Native installers workflow](https://github.com/rokon12/virtual-thread-simulation/actions/workflows/native-installers.yml)
builds all three packages on their native runners. Run it manually to download
workflow artifacts, or push a tag such as `v1.0.0` to create a GitHub release
with the installers attached.

On macOS, set `VT_MACHINE_SIGN_IDENTITY` before packaging to codesign with the
hardened runtime. After configuring an `xcrun notarytool` keychain profile, set
`VT_MACHINE_NOTARY_PROFILE` and run `scripts/notarize-macos.sh` to submit,
staple, and validate the `.dmg`.

## Maven package

Each tagged release also publishes the modular JAR to GitHub Packages:

```xml
<dependency>
    <groupId>ca.bazlur</groupId>
    <artifactId>virtual-thread-simulation</artifactId>
    <version>1.0.0</version>
</dependency>
```

GitHub Packages follows the private repository's access permissions and
requires an authenticated Maven client with `read:packages`. Desktop users
should use the self-contained installers from GitHub Releases instead.

## JDK 21/25 accuracy note

JDK 21 pins a virtual thread when it blocks inside `synchronized`. Since JDK 24,
JEP 491 allows that same operation to unmount, which is the JDK 25 behavior shown
by the comparison controls. Native or foreign-function frames can still prevent
unmounting on JDK 25. The version comparison is deterministic and therefore
available in `SYNTHETIC` mode; `LIVE JDK` always reflects the host JDK. Virtual-
thread stack chunks live on the heap, and the 3D carrier lanes are a teaching
abstraction, particularly in the live feed.
