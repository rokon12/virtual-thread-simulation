package vtmachine;

import java.io.InputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import vtmachine.model.Sim;
import vtmachine.view.CameraRig;
import vtmachine.view.Hud;
import vtmachine.view.MachineScene;

/** JavaFX entry point for The Virtual Thread Machine. */
public final class App extends Application {
    private static final double FIXED_STEP = 1.0 / 60.0;

    private Sim sim;
    private MachineScene machine;
    private Hud hud;
    private AnimationTimer timer;
    private int observedChapter;
    private Scene appScene;
    private Settings settings;

    @Override
    public void start(Stage stage) {
        loadFonts();
        settings = Settings.from(getParameters().getRaw());
        sim = new Sim(settings.carriers, settings.maxThreads, settings.taskRate, settings.seed);
        machine = new MachineScene(sim);
        hud = new Hud(sim, this::gotoChapter, machine::requestFocus);

        StackPane machineAndNarration = new StackPane(machine, hud.narration());
        StackPane.setAlignment(hud.narration(), Pos.BOTTOM_LEFT);
        StackPane.setMargin(hud.narration(), new Insets(0, 0, 16, 16));

        BorderPane shell = new BorderPane();
        shell.getStyleClass().add("app-shell");
        shell.setTop(hud.header());
        shell.setCenter(machineAndNarration);
        shell.setRight(hud.sidebar());
        shell.setBottom(hud.bottomBar());

        appScene = new Scene(shell, 1440, 900, Color.web("#070b12"));
        appScene.getStylesheets().add(resource("/hud.css"));
        installKeyboard(appScene);

        stage.setTitle("The Virtual Thread Machine");
        stage.setScene(appScene);
        stage.setMinWidth(1440);
        stage.setMinHeight(900);
        stage.setWidth(1440);
        stage.setHeight(900);
        stage.show();
        machine.requestFocus();

        observedChapter = sim.chapter();
        machine.cameraForChapter(observedChapter);
        startLoop();

        Platform.runLater(() -> {
            String pipeline = System.getProperty("prism.order", "auto");
            System.out.println("JavaFX Prism pipeline: " + pipeline);
            if ("sw".equalsIgnoreCase(pipeline)) {
                System.err.println("Warning: software rendering is active; chapter 6 may not sustain 60 fps.");
            }
        });
    }

    private void startLoop() {
        timer = new AnimationTimer() {
            private long last = -1;
            private double accumulator;
            private double wallTime;
            private int frame;
            private boolean snapshotWritten;

            @Override
            public void handle(long now) {
                double frameDt = last < 0 ? 0 : Math.min(0.05, (now - last) / 1_000_000_000.0);
                last = now;
                wallTime += frameDt;
                if (sim.running()) {
                    accumulator += frameDt * sim.speed();
                    while (accumulator >= FIXED_STEP) {
                        sim.tick(FIXED_STEP);
                        accumulator -= FIXED_STEP;
                    }
                }

                if (observedChapter != sim.chapter()) {
                    observedChapter = sim.chapter();
                    machine.cameraForChapter(observedChapter);
                    hud.sync();
                }
                machine.sync(frameDt);
                hud.syncFrame(now / 1_000_000_000.0);
                if (++frame % 10 == 0) hud.sync();
                if (!snapshotWritten && settings.snapshotPath != null && wallTime >= settings.snapshotAt) {
                    snapshotWritten = true;
                    writeSnapshot(Path.of(settings.snapshotPath));
                    Platform.exit();
                }
            }
        };
        timer.start();
    }

    private void installKeyboard(Scene scene) {
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (scene.getFocusOwner() instanceof TextInputControl) return;
            KeyCode code = event.getCode();
            switch (code) {
                case SPACE -> {
                    sim.setRunning(!sim.running());
                    hud.sync();
                    event.consume();
                }
                case LEFT -> {
                    gotoChapter(sim.chapter() - 1);
                    event.consume();
                }
                case RIGHT -> {
                    gotoChapter(sim.chapter() + 1);
                    event.consume();
                }
                case DIGIT1, NUMPAD1 -> machine.cameraPreset(CameraRig.Preset.OVERVIEW);
                case DIGIT2, NUMPAD2 -> machine.cameraPreset(CameraRig.Preset.CARRIERS);
                case DIGIT3, NUMPAD3 -> machine.cameraPreset(CameraRig.Preset.HEAP);
                case DIGIT4, NUMPAD4 -> machine.cameraPreset(CameraRig.Preset.TOP);
                default -> { }
            }
        });
    }

    private void gotoChapter(int chapter) {
        sim.gotoChapter(chapter);
        observedChapter = sim.chapter();
        machine.cameraForChapter(observedChapter);
        hud.sync();
    }

    @Override
    public void stop() {
        if (timer != null) timer.stop();
    }

    private static void loadFonts() {
        for (String file : List.of(
                "SpaceGrotesk-Regular.ttf", "SpaceGrotesk-Medium.ttf", "SpaceGrotesk-Bold.ttf",
                "IBMPlexMono-Regular.ttf", "IBMPlexMono-Medium.ttf", "IBMPlexMono-SemiBold.ttf")) {
            try (InputStream stream = App.class.getResourceAsStream("/fonts/" + file)) {
                if (stream != null) Font.loadFont(stream, 12);
            } catch (Exception exception) {
                System.err.println("Could not load bundled font " + file + ": " + exception.getMessage());
            }
        }
    }

    private static String resource(String name) {
        return java.util.Objects.requireNonNull(App.class.getResource(name), "Missing resource " + name)
                .toExternalForm();
    }

    /** Writes a binary PPM without introducing AWT/Swing into the runtime image. */
    private void writeSnapshot(Path path) {
        WritableImage image = appScene.snapshot(null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(path))) {
                output.write(("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
                byte[] row = new byte[width * 3];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = image.getPixelReader().getArgb(x, y);
                        int offset = x * 3;
                        row[offset] = (byte) (argb >>> 16);
                        row[offset + 1] = (byte) (argb >>> 8);
                        row[offset + 2] = (byte) argb;
                    }
                    output.write(row);
                }
            }
            System.out.println("Snapshot written to " + path.toAbsolutePath());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write snapshot " + path, exception);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    private record Settings(int carriers, int maxThreads, double taskRate, long seed,
            String snapshotPath, double snapshotAt) {
        static Settings from(List<String> args) {
            Map<String, String> values = new java.util.HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) continue;
                int equals = arg.indexOf('=');
                values.put(arg.substring(2, equals), arg.substring(equals + 1));
            }
            return new Settings(
                    boundedInt(values.get("carriers"), Sim.DEFAULT_CARRIERS, 2, 6),
                    boundedInt(values.get("max-threads"), Sim.DEFAULT_MAX_THREADS, 50, 800),
                    boundedDouble(values.get("task-rate"), Sim.DEFAULT_TASK_RATE, 0.3, 6.0),
                    longValue(values.get("seed"), System.nanoTime()),
                    values.get("snapshot"),
                    boundedDouble(values.get("snapshot-at"), 4.5, 0.5, 30.0));
        }

        private static int boundedInt(String value, int fallback, int min, int max) {
            try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
            catch (RuntimeException ignored) { return fallback; }
        }

        private static double boundedDouble(String value, double fallback, double min, double max) {
            try { return Math.max(min, Math.min(max, Double.parseDouble(value))); }
            catch (RuntimeException ignored) { return fallback; }
        }

        private static long longValue(String value, long fallback) {
            try { return Long.parseLong(value); }
            catch (RuntimeException ignored) { return fallback; }
        }
    }
}
