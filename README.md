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

## Controls

- `Space`: pause or resume
- `←` / `→`: previous or next chapter
- `1`–`4`: overview, carriers, heap, and top camera presets
- Mouse drag: orbit
- Mouse wheel or trackpad scroll: zoom
- HUD controls: add 25 tasks, force a park, force a pin, or change speed

## Reproducible settings

Command-line settings use `--name=value` syntax:

```bash
mvn javafx:run -Djavafx.args="--carriers=4 --max-threads=500 --task-rate=1.4 --seed=42"
```

Supported settings:

- `--carriers=2..6`
- `--max-threads=50..800`
- `--task-rate=0.3..6.0`
- `--seed=<long>`

For visual smoke tests, `--snapshot=/path/image.ppm` writes a full-window PPM
after 4.5 seconds. Override the delay with `--snapshot-at=<seconds>`.

## Test

```bash
mvn test
```

The model is JavaFX-free and uses a seeded `RandomGenerator`. The application
feeds it fixed 60 Hz simulation steps so a seed produces repeatable behavior
independently of render-frame timing.
