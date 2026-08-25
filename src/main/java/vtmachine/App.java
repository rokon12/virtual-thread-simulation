package vtmachine;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import vtmachine.live.LiveWorkload;
import vtmachine.model.Sim;
import vtmachine.view.CameraRig;
import vtmachine.view.Hud;
import vtmachine.view.MachineScene;
import vtmachine.view.SpeakerNotesWindow;

/** JavaFX entry point for The Virtual Thread Machine. */
public final class App extends Application {
    private static final double FIXED_STEP = 1.0 / 60.0;
    private static final double AUTO_CHAPTER_SECONDS = 11.0;

    private Sim sim;
    private MachineScene machine;
    private Hud hud;
    private LiveWorkload liveWorkload;
    private SpeakerNotesWindow speakerNotes;
    private AnimationTimer timer;
    private int observedChapter;
    private Scene appScene;
    private BorderPane shell;
    private Stage stage;
    private Settings settings;
    private boolean presenterMode;
    private boolean autoplay;
    private boolean highContrast;
    private double chapterWallTime;
    private double fps = 60;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        loadFonts();
        settings = Settings.from(getParameters().getRaw());
        shell = new BorderPane();
        shell.getStyleClass().add("app-shell");
        appScene = new Scene(shell, 1440, 900, Color.web("#070b12"));
        appScene.getStylesheets().add(resource("/hud.css"));
        installKeyboard(appScene);

        stage.setTitle("The Virtual Thread Machine");
        stage.setScene(appScene);
        stage.setMinWidth(1024);
        stage.setMinHeight(700);
        stage.setWidth(1440);
        stage.setHeight(900);
        rebuild(settings);
        appScene.widthProperty().addListener((observable, oldValue, newValue) ->
                hud.setCompact(newValue.doubleValue() < 1280));
        stage.show();
        machine.requestFocus();

        speakerNotes = new SpeakerNotesWindow(stage, sim, resource("/hud.css"));
        observedChapter = sim.chapter();
        machine.cameraForChapter(observedChapter);
        startLoop();
        if (settings.presenter) setPresenterMode(true);

        Platform.runLater(() -> {
            String pipeline = System.getProperty("prism.order", "auto");
            System.out.println("JavaFX Prism pipeline: " + pipeline);
            if ("sw".equalsIgnoreCase(pipeline)) {
                System.err.println("Warning: software rendering is active; AUTO quality will reduce effects.");
            }
        });
    }

    private void rebuild(Settings replacement) {
        if (liveWorkload != null) {
            liveWorkload.close();
            liveWorkload = null;
        }
        settings = replacement;
        sim = new Sim(settings.carriers, settings.maxThreads, settings.taskRate, settings.seed);
        if (settings.live) sim.setLiveMode(true);
        if (settings.live) liveWorkload = new LiveWorkload(sim, settings.maxThreads, settings.taskRate, settings.seed);
        machine = new MachineScene(sim);
        Hud.Actions actions = new Hud.Actions(this::gotoChapter, this::setFreeRun,
                this::switchLiveMode, () -> submitTasks(25), sim::forcePark, sim::forcePin,
                this::showSettings, machine::highlightVt, machine::requestFocus);
        hud = new Hud(sim, actions);
        StackPane machineAndNarration = new StackPane(machine, hud.narration());
        StackPane.setAlignment(hud.narration(), Pos.BOTTOM_LEFT);
        StackPane.setMargin(hud.narration(), new Insets(0, 0, 16, 16));
        shell.setTop(hud.header());
        shell.setCenter(machineAndNarration);
        shell.setRight(hud.sidebar());
        shell.setBottom(hud.bottomBar());
        hud.setCompact(appScene.getWidth() < 1280);
        hud.setPresenterMode(presenterMode);
        machine.setPresenterMode(presenterMode);
        machine.setHighContrast(highContrast);
        observedChapter = sim.chapter();
        chapterWallTime = 0;
        if (speakerNotes != null) speakerNotes.setSim(sim);
    }

    private void startLoop() {
        timer = new AnimationTimer() {
            private long last = -1;
            private double accumulator;
            private double wallTime;
            private int frame;
            private boolean snapshotWritten;
            private boolean snapshotChapterApplied;

            @Override
            public void handle(long now) {
                double frameDt = last < 0 ? 0 : Math.min(0.05, (now - last) / 1_000_000_000.0);
                last = now;
                wallTime += frameDt;
                chapterWallTime += frameDt;
                if (frameDt > 0) fps += (1 / frameDt - fps) * 0.075;
                if (liveWorkload != null) liveWorkload.tick(frameDt, sim.running() && sim.freeRun());
                if (sim.running()) {
                    accumulator += frameDt * sim.speed();
                    while (accumulator >= FIXED_STEP) {
                        sim.tick(FIXED_STEP);
                        accumulator -= FIXED_STEP;
                    }
                }

                if (observedChapter != sim.chapter()) {
                    observedChapter = sim.chapter();
                    chapterWallTime = 0;
                    machine.cameraForChapter(observedChapter);
                    if (liveWorkload != null) liveWorkload.onChapter(observedChapter);
                    hud.sync();
                }
                if (autoplay && sim.bootT() >= 3 && chapterWallTime >= AUTO_CHAPTER_SECONDS) {
                    gotoChapter(sim.chapter() + 1);
                }
                if (!snapshotChapterApplied && settings.snapshotChapter > 0 && sim.bootT() >= 3) {
                    snapshotChapterApplied = true;
                    int target = settings.snapshotChapter - 1;
                    if (sim.chapter() != target) gotoChapter(target);
                }
                machine.sync(frameDt);
                if (++frame % 15 == 0) machine.setPerformance(fps, frameDt * 1_000);
                hud.syncFrame(wallTime, fps, machine.qualityLabel());
                if (frame % 10 == 0) hud.sync();
                speakerNotes.sync(wallTime, fps, autoplay);
                if (!snapshotWritten && settings.snapshotPath != null && wallTime >= settings.snapshotAt) {
                    snapshotWritten = true;
                    if (settings.snapshotFollow && sim.hero() != null) {
                        machine.highlightVt(sim.hero().id());
                        machine.sync(0);
                        appScene.getRoot().applyCss();
                        appScene.getRoot().layout();
                    }
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
                case LEFT -> { gotoChapter(sim.chapter() - 1); event.consume(); }
                case RIGHT -> { gotoChapter(sim.chapter() + 1); event.consume(); }
                case DIGIT0, NUMPAD0 -> machine.cameraPreset(CameraRig.Preset.OVERVIEW);
                case DIGIT1, NUMPAD1 -> machine.cameraPreset(CameraRig.Preset.OVERVIEW);
                case DIGIT2, NUMPAD2 -> machine.cameraPreset(CameraRig.Preset.CARRIERS);
                case DIGIT3, NUMPAD3 -> machine.cameraPreset(CameraRig.Preset.HEAP);
                case DIGIT4, NUMPAD4 -> machine.cameraPreset(CameraRig.Preset.TOP);
                case F11 -> stage.setFullScreen(!stage.isFullScreen());
                case P -> setPresenterMode(!presenterMode);
                case A -> {
                    autoplay = !autoplay;
                    chapterWallTime = 0;
                    sim.recordMessage("auto-play " + (autoplay ? "enabled" : "disabled"));
                }
                case R -> gotoChapter(sim.chapter());
                case Q -> sim.recordMessage("render quality: " + machine.cycleQuality());
                case H -> setHighContrast(!highContrast);
                case N -> speakerNotes.toggle();
                case ESCAPE -> {
                    if (presenterMode) setPresenterMode(false);
                    else machine.clearFollow();
                }
                default -> { }
            }
        });
    }

    private void submitTasks(int count) {
        if (liveWorkload != null) liveWorkload.submit(count);
        else sim.burst(count);
    }

    private void setFreeRun(boolean enabled) {
        sim.setFreeRun(enabled);
    }

    private void switchLiveMode(boolean enabled) {
        if (sim.liveMode() == enabled) return;
        rebuild(settings.withLive(enabled));
        machine.requestFocus();
    }

    private void gotoChapter(int chapter) {
        sim.gotoChapter(chapter);
        observedChapter = sim.chapter();
        chapterWallTime = 0;
        machine.cameraForChapter(observedChapter);
        if (liveWorkload != null) liveWorkload.onChapter(observedChapter);
        hud.sync();
    }

    private void setPresenterMode(boolean enabled) {
        presenterMode = enabled;
        hud.setPresenterMode(enabled);
        machine.setPresenterMode(enabled);
        stage.setFullScreen(enabled);
        machine.requestFocus();
    }

    private void setHighContrast(boolean enabled) {
        highContrast = enabled;
        shell.getStyleClass().remove("high-contrast");
        if (enabled) shell.getStyleClass().add("high-contrast");
        machine.setHighContrast(enabled);
        sim.recordMessage("high contrast " + (enabled ? "enabled" : "disabled"));
    }

    private void showSettings() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Demo settings");
        dialog.setHeaderText("Restart the machine with reproducible settings");
        ButtonType apply = new ButtonType("Restart", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        Spinner<Integer> carriers = new Spinner<>(2, 10, settings.carriers);
        Spinner<Integer> maxThreads = new Spinner<>(50, 800, settings.maxThreads, 25);
        Spinner<Double> taskRate = new Spinner<>(0.3, 6.0, settings.taskRate, 0.1);
        carriers.setEditable(true);
        maxThreads.setEditable(true);
        taskRate.setEditable(true);
        TextField seed = new TextField(Long.toString(settings.seed));
        CheckBox live = new CheckBox("Use real JDK virtual threads");
        live.setSelected(sim.liveMode());
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.addRow(0, new Label("Carrier lanes"), carriers);
        grid.addRow(1, new Label("Maximum tasks"), maxThreads);
        grid.addRow(2, new Label("Tasks / second"), taskRate);
        grid.addRow(3, new Label("Random seed"), seed);
        grid.add(live, 0, 4, 2, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.showAndWait().filter(apply::equals).ifPresent(ignored -> {
            long selectedSeed;
            try { selectedSeed = Long.parseLong(seed.getText().trim()); }
            catch (NumberFormatException invalid) { selectedSeed = settings.seed; }
            rebuild(new Settings(carriers.getValue(), maxThreads.getValue(), taskRate.getValue(),
                    selectedSeed, live.isSelected(), settings.presenter, null, 4.5, 0, false));
            machine.requestFocus();
        });
    }

    @Override
    public void stop() {
        if (timer != null) timer.stop();
        if (liveWorkload != null) liveWorkload.close();
        if (speakerNotes != null) speakerNotes.close();
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

    public static void main(String[] args) { launch(args); }

    record Settings(int carriers, int maxThreads, double taskRate, long seed,
            boolean live, boolean presenter, String snapshotPath, double snapshotAt,
            int snapshotChapter, boolean snapshotFollow) {
        Settings withLive(boolean enabled) {
            return new Settings(carriers, maxThreads, taskRate, seed, enabled, presenter,
                    snapshotPath, snapshotAt, snapshotChapter, snapshotFollow);
        }

        static Settings from(List<String> args) {
            Map<String, String> values = new HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--")) continue;
                int equals = arg.indexOf('=');
                if (equals < 0) values.put(arg.substring(2), "true");
                else values.put(arg.substring(2, equals), arg.substring(equals + 1));
            }
            return new Settings(
                    boundedInt(values.get("carriers"), Sim.DEFAULT_CARRIERS, 2, 10),
                    boundedInt(values.get("max-threads"), Sim.DEFAULT_MAX_THREADS, 50, 800),
                    boundedDouble(values.get("task-rate"), Sim.DEFAULT_TASK_RATE, 0.3, 6.0),
                    longValue(values.get("seed"), System.nanoTime()),
                    Boolean.parseBoolean(values.getOrDefault("live", "false")),
                    Boolean.parseBoolean(values.getOrDefault("presenter", "false")),
                    values.get("snapshot"),
                    boundedDouble(values.get("snapshot-at"), 4.5, 0.5, 30.0),
                    boundedInt(values.get("snapshot-chapter"), 0, 0, 6),
                    Boolean.parseBoolean(values.getOrDefault("snapshot-follow", "false")));
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
