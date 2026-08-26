package vtmachine.view;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import vtmachine.model.Sim;
import vtmachine.model.ReplayFrame;
import vtmachine.model.ReplayTimeline;

/** All two-dimensional status, narration, metrics, and controls for the machine. */
public final class Hud {
    private static final Pattern VT_ID = Pattern.compile("VT-(\\d+)");
    private static final String[] CHAPTER_TITLES = {
        "BOOT", "MOUNT", "PARK", "RESUME", "JDK 21 vs 25", "SCALE",
        "PLATFORM vs VT", "POOL LIMIT", "CPU BOUND", "STRUCTURED"
    };
    private static final String[] CHAPTER_COLORS = {
        "amber", "blue", "purple", "green", "red", "white",
        "amber", "purple", "blue", "green"
    };
    private static final String[] CHAPTER_TEXT = {
        "Power on. A small set of OS threads lights up on the CPU cores; the JVM starts carrier (platform) threads, and the ForkJoinPool scheduler spins up above them. This small machine is all the OS ever sees.",
        "Application tasks arrive with varied runtimes: some finish quickly, some compute longer, and dotted-satellite tasks will perform I/O. The scheduler mounts each runnable VT onto a free carrier; only while mounted does a VT consume an OS thread.",
        "An I/O-bound VT reaches its randomized wait. Its stack-chunk marker lifts from the carrier into the heap while a pulse travels to the external network, disk, timer, or database endpoint. The carrier is instantly free for another VT.",
        "The external I/O completes. The stored continuation moves back through the run queue and remounts on ANY free carrier — not necessarily the one it left. Watch the stack marker merge into a different slot.",
        "Run the same blocking operation inside synchronized. On JDK 21 it pins the VT to its carrier. Since JDK 24 (JEP 491), JDK 25 can unmount it and release the carrier. Native or foreign-function frames may still pin.",
        "The payoff. 500 mixed-duration tasks flood in while the green run-queue pressure bar expands. A fixed set of illustrative carriers drains the queue; I/O parking releases lanes, pinning blocks them, and completed VTs dissolve in place after releasing their carrier.",
        "Run the same blocking I/O workload two ways. A platform-thread-per-task design ties up one costly OS thread per wait; virtual threads park cheaply while a small, fixed carrier pool keeps executing other work.",
        "Virtual threads remove the thread bottleneck, not downstream limits. Only three tasks may hold a database connection; every other VT parks in the heap without occupying a carrier until a permit becomes available.",
        "Virtual threads improve blocking concurrency, not CPU parallelism. Compute-only tasks saturate every carrier and the run queue grows, but throughput plateaus at the available carrier/core count.",
        "Related child VTs live inside parent scopes. Each scope forks four children and joins only after they finish; when one CHECKOUT child fails, its active siblings are cancelled and the failure is contained within that scope."
    };

    public record Actions(Runnable playPause, IntConsumer chapter, Consumer<Boolean> freeRun,
            Consumer<Boolean> liveMode, Runnable burst, Runnable park,
            Runnable jdk21Block, Runnable jdk25Block,
            Runnable settings, Runnable about, LongConsumer highlight, IntConsumer replayFrame,
            Runnable returnLive, Runnable refocus) {}

    private final Sim sim;
    private final ReplayTimeline timeline;
    private final Actions actions;
    private final HBox header;
    private final VBox sidebar;
    private final HBox bottomBar;
    private final VBox narration;
    private final Label led = new Label();
    private final Label status = new Label("BOOTING");
    private final Label subtitle = new Label("Mount · Park · Resume · Pin — virtual threads on a tiny carrier pool, live in 3D");
    private final Label feedNotice = new Label();
    private final Button playButton = new Button("Pause");
    private Button jdk21Button;
    private Button jdk25Button;
    private final ToggleButton guided = new ToggleButton("GUIDED");
    private final ToggleButton freeRun = new ToggleButton("FREE RUN");
    private final ToggleButton synthetic = new ToggleButton("SYNTHETIC");
    private final ToggleButton live = new ToggleButton("LIVE JDK");
    private final Label[] counters = new Label[6];
    private final EnumMap<Sim.Flash, Region> flashes = new EnumMap<>(Sim.Flash.class);
    private final List<Label> logLines = new ArrayList<>();
    private final Label chapterNumber = new Label();
    private final Label chapterTitle = new Label();
    private final Label chapterBody = new Label();
    private final Label speedReadout = new Label("0.75×");
    private final Label performance = new Label("-- FPS · AUTO");
    private final Label timelineStatus = new Label("LIVE · recording");
    private final Label timelineLegend = new Label("SPAWN · MOUNT · PARK · PIN · DONE");
    private final Slider timelineSlider = new Slider(0, 0, 0);
    private final Canvas timelineMarkers = new Canvas(300, 9);
    private final Button returnLive = new Button("LIVE");
    private final Slider speedSlider = new Slider(0.25, 3.0, 0.75);
    private final Canvas throughput = new Canvas(254, 54);
    private final double[] throughputSamples = new double[72];
    private int throughputCursor;
    private double lastSampleTime = Double.NaN;
    private int lastCompleted;
    private ReplayFrame replayFrame;
    private boolean replayPlaying;
    private boolean syncingTimeline;
    private int lastTimelineSize = -1;
    private int lastTimelineIndex = -1;
    private VBox timelineControl;

    public Hud(Sim sim, ReplayTimeline timeline, Actions actions) {
        this.sim = sim;
        this.timeline = timeline;
        this.actions = actions;
        header = buildHeader();
        sidebar = buildSidebar();
        bottomBar = buildBottomBar();
        narration = buildNarration();
        sync();
    }

    private HBox buildHeader() {
        HBox bar = new HBox(12);
        bar.getStyleClass().add("header-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        led.getStyleClass().add("status-led");
        led.setMinSize(10, 10);
        led.setMaxSize(10, 10);
        led.setAccessibleText("Simulation status indicator");

        Label title = new Label("THE VIRTUAL THREAD MACHINE");
        title.getStyleClass().add("app-title");
        subtitle.getStyleClass().add("app-subtitle");
        VBox titles = new VBox(1, title, subtitle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox runModes = togglePair(guided, freeRun);
        guided.setAccessibleText("Guided chapter mode");
        freeRun.setAccessibleText("Continuous workload mode");
        guided.setSelected(true);
        guided.setOnAction(event -> selectExclusive(guided, () -> actions.freeRun.accept(false)));
        freeRun.setOnAction(event -> selectExclusive(freeRun, () -> actions.freeRun.accept(true)));

        HBox feeds = togglePair(synthetic, live);
        synthetic.setAccessibleText("Deterministic synthetic workload");
        live.setAccessibleText("Real Java virtual-thread workload");
        synthetic.setSelected(true);
        synthetic.setOnAction(event -> selectExclusive(synthetic, () -> actions.liveMode.accept(false)));
        live.setOnAction(event -> selectExclusive(live, () -> actions.liveMode.accept(true)));

        status.getStyleClass().add("status-text");
        status.setAlignment(Pos.CENTER_RIGHT);
        status.setMinWidth(86);
        status.setPrefWidth(86);
        bar.getChildren().addAll(led, titles, spacer, runModes, feeds, status);
        return bar;
    }

    private HBox togglePair(ToggleButton first, ToggleButton second) {
        ToggleGroup group = new ToggleGroup();
        first.setToggleGroup(group);
        second.setToggleGroup(group);
        first.getStyleClass().add("mode-toggle");
        second.getStyleClass().add("mode-toggle");
        HBox buttons = new HBox(first, second);
        buttons.getStyleClass().add("mode-group");
        return buttons;
    }

    private void selectExclusive(ToggleButton selected, Runnable action) {
        if (!selected.isSelected()) {
            selected.setSelected(true);
            return;
        }
        action.run();
        sync();
        actions.refocus.run();
    }

    private VBox buildSidebar() {
        VBox panel = new VBox(11);
        panel.getStyleClass().add("sidebar");
        setSidebarWidth(panel, 290);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        ColumnConstraints first = new ColumnConstraints();
        ColumnConstraints second = new ColumnConstraints();
        first.setPercentWidth(50);
        second.setPercentWidth(50);
        grid.getColumnConstraints().addAll(first, second);
        grid.add(counterCard(0, "RUNNABLE", "green"), 0, 0);
        grid.add(counterCard(1, "MOUNTED", "blue"), 1, 0);
        grid.add(counterCard(2, "PARKED", "purple"), 0, 1);
        grid.add(counterCard(3, "COMPLETED", "white"), 1, 1);
        grid.add(counterCard(4, "LIVE TOTAL", "amber"), 0, 2);
        grid.add(counterCard(5, "CARRIER UTIL", "blue"), 1, 2);

        feedNotice.getStyleClass().add("feed-notice");
        feedNotice.setWrapText(true);
        feedNotice.setMinHeight(38);
        VBox behaviors = new VBox(6,
                behaviorCard(Sim.Flash.MOUNT, "① Mount", "Runnable VT occupies one lane.", "blue"),
                behaviorCard(Sim.Flash.PARK, "② I/O wait", "Stack chunks move to heap; external I/O pulse continues.", "purple"),
                behaviorCard(Sim.Flash.RESUME, "③ Resume", "Continuation returns through queue to any free lane.", "green"),
                behaviorCard(Sim.Flash.PIN, "④ JDK 21 vs 25",
                        "synchronized wait: pin on 21, unmount on 25.", "red"));

        Label throughputHeader = new Label("THROUGHPUT · COMPLETIONS/S");
        throughputHeader.getStyleClass().add("section-header");
        throughput.getStyleClass().add("throughput-chart");
        throughput.setAccessibleText("Rolling graph of virtual-thread completions per second");
        Label logHeader = new Label("EVENT LOG · CLICK A VT TO FOLLOW");
        logHeader.getStyleClass().add("section-header");
        VBox logBox = new VBox(0);
        logBox.getStyleClass().add("event-log");
        for (int i = 0; i < 7; i++) {
            Label line = new Label();
            line.getStyleClass().add("event-line");
            line.setMaxWidth(Double.MAX_VALUE);
            line.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            line.setOnMouseClicked(event -> highlightFromLog(line.getText()));
            logLines.add(line);
            logBox.getChildren().add(line);
        }
        VBox.setVgrow(logBox, Priority.ALWAYS);
        panel.getChildren().addAll(grid, feedNotice, behaviors, throughputHeader, throughput, logHeader, logBox);
        return panel;
    }

    private Node counterCard(int index, String name, String color) {
        Label value = new Label("0");
        value.getStyleClass().addAll("counter-value", "text-" + color);
        Label label = new Label(name);
        label.getStyleClass().add("counter-label");
        VBox card = new VBox(-1, value, label);
        card.getStyleClass().add("counter-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setAccessibleText(name + " counter");
        counters[index] = value;
        return card;
    }

    private Node behaviorCard(Sim.Flash flash, String titleText, String bodyText, String color) {
        Label title = new Label(titleText);
        title.getStyleClass().addAll("behavior-title", "text-" + color);
        Label body = new Label(bodyText);
        body.getStyleClass().add("behavior-body");
        body.setWrapText(true);
        VBox content = new VBox(2, title, body);
        Region overlay = new Region();
        overlay.getStyleClass().addAll("behavior-flash", "flash-" + color);
        overlay.setMouseTransparent(true);
        flashes.put(flash, overlay);
        StackPane card = new StackPane(content, overlay);
        card.getStyleClass().add("behavior-card");
        StackPane.setAlignment(content, Pos.CENTER_LEFT);
        card.setAccessibleText(titleText + ". " + bodyText);
        return card;
    }

    private HBox buildBottomBar() {
        HBox bar = new HBox(10);
        bar.getStyleClass().add("bottom-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        playButton.getStyleClass().addAll("control-button", "play-button");
        playButton.setMinWidth(90);
        playButton.setAccessibleText("Pause or resume the simulation");
        playButton.setOnAction(event -> {
            actions.playPause.run();
            sync();
            actions.refocus.run();
        });
        Button burst = controlButton("+25 tasks", "burst-button", actions.burst,
                "Submit 25 tasks to the selected workload");
        Button park = controlButton("Force park", "park-button", actions.park,
                "Park one running virtual thread");
        jdk21Button = controlButton("JDK 21", "jdk21-button", actions.jdk21Block,
                "JDK 21: blocking inside synchronized pins the virtual thread to its carrier");
        jdk25Button = controlButton("JDK 25", "jdk25-button", actions.jdk25Block,
                "JDK 25: the same synchronized blocking operation unmounts and releases the carrier");
        Button settings = controlButton("Settings", "settings-button", actions.settings,
                "Change carrier count, task limit, task rate, and random seed");
        Button about = controlButton("About", "about-button", actions.about,
                "Learn who built the simulator and why it exists");
        VBox timelineControl = buildTimelineControl();
        HBox.setHgrow(timelineControl, Priority.ALWAYS);
        performance.getStyleClass().add("performance-label");
        Label speedLabel = new Label("SPEED");
        speedLabel.getStyleClass().add("speed-label");
        speedSlider.setValue(sim.speed());
        speedSlider.setBlockIncrement(0.25);
        speedSlider.setMajorTickUnit(0.25);
        speedSlider.setSnapToTicks(true);
        speedSlider.setPrefWidth(120);
        speedSlider.setAccessibleText("Simulation speed");
        speedSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            double snapped = Math.round(newValue.doubleValue() * 4) / 4.0;
            sim.setSpeed(snapped);
            speedReadout.setText("%.2f×".formatted(sim.speed()));
        });
        speedReadout.getStyleClass().add("speed-readout");
        speedReadout.setMinWidth(44);
        bar.getChildren().addAll(playButton, burst, park, jdk21Button, jdk25Button,
                settings, about, timelineControl,
                performance, speedLabel, speedSlider, speedReadout);
        return bar;
    }

    private VBox buildTimelineControl() {
        timelineStatus.getStyleClass().add("timeline-status");
        timelineStatus.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        timelineStatus.setMaxWidth(230);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        timelineLegend.getStyleClass().add("timeline-legend");
        returnLive.getStyleClass().add("timeline-live-button");
        returnLive.setAccessibleText("Return from replay history to the live simulation");
        returnLive.setDisable(true);
        returnLive.setOnAction(event -> {
            actions.returnLive.run();
            actions.refocus.run();
        });
        HBox header = new HBox(6, timelineStatus, spacer, timelineLegend, returnLive);
        header.setAlignment(Pos.CENTER_LEFT);

        timelineSlider.getStyleClass().add("timeline-slider");
        timelineSlider.setMinWidth(120);
        timelineSlider.setMaxWidth(Double.MAX_VALUE);
        timelineSlider.setBlockIncrement(1);
        timelineSlider.setAccessibleText("Recorded simulation history");
        timelineSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (syncingTimeline || timeline.size() == 0) return;
            actions.replayFrame.accept((int) Math.round(newValue.doubleValue()));
        });
        timelineMarkers.setMouseTransparent(true);
        timelineMarkers.widthProperty().bind(timelineSlider.widthProperty());
        timelineControl = new VBox(-2, header, timelineSlider, timelineMarkers);
        timelineControl.getStyleClass().add("timeline-control");
        timelineControl.setMinWidth(150);
        timelineControl.setPrefWidth(340);
        timelineControl.setMaxWidth(Double.MAX_VALUE);
        timelineControl.widthProperty().addListener((observable, oldValue, newValue) -> drawTimelineMarkers());
        return timelineControl;
    }

    private Button controlButton(String text, String styleClass, Runnable action, String help) {
        Button button = new Button(text);
        button.getStyleClass().addAll("control-button", styleClass);
        button.setAccessibleText(help);
        button.setTooltip(new Tooltip(help));
        button.setOnAction(event -> {
            action.run();
            sync();
            actions.refocus.run();
        });
        return button;
    }

    private VBox buildNarration() {
        VBox card = new VBox(7);
        card.getStyleClass().add("narration-card");
        card.setPrefWidth(400);
        card.setMaxWidth(400);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        chapterNumber.getStyleClass().add("chapter-number");
        chapterTitle.getStyleClass().add("chapter-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button previous = new Button("←");
        previous.getStyleClass().add("previous-button");
        previous.setAccessibleText("Previous chapter");
        previous.setOnAction(event -> {
            actions.chapter.accept((replayFrame == null ? sim.chapter() : replayFrame.chapter()) - 1);
            actions.refocus.run();
        });
        Button next = new Button("Next →");
        next.getStyleClass().add("next-button");
        next.setAccessibleText("Next chapter");
        next.setOnAction(event -> {
            actions.chapter.accept((replayFrame == null ? sim.chapter() : replayFrame.chapter()) + 1);
            actions.refocus.run();
        });
        HBox row = new HBox(9, chapterNumber, chapterTitle, spacer, previous, next);
        row.setAlignment(Pos.CENTER_LEFT);
        chapterBody.getStyleClass().add("chapter-body");
        chapterBody.setWrapText(true);
        chapterBody.setMaxWidth(368);
        card.getChildren().addAll(row, chapterBody);
        return card;
    }

    private void highlightFromLog(String text) {
        Matcher matcher = VT_ID.matcher(text == null ? "" : text);
        if (matcher.find()) actions.highlight.accept(Long.parseLong(matcher.group(1)));
        actions.refocus.run();
    }

    public void syncFrame(double wallTimeSeconds, double fps, String quality) {
        led.setOpacity(0.35 + 0.65 * (0.5 + 0.5 * Math.cos(wallTimeSeconds * Math.PI * 2 / 1.6)));
        performance.setText("%.0f FPS · %s".formatted(fps, quality));
        if (replayFrame != null) return;
        if (Double.isNaN(lastSampleTime)) {
            lastSampleTime = wallTimeSeconds;
            lastCompleted = sim.stats().completed();
        } else if (wallTimeSeconds - lastSampleTime >= 0.5) {
            double elapsed = wallTimeSeconds - lastSampleTime;
            int completed = sim.stats().completed();
            throughputSamples[throughputCursor++ % throughputSamples.length]
                    = Math.max(0, completed - lastCompleted) / elapsed;
            lastCompleted = completed;
            lastSampleTime = wallTimeSeconds;
            drawThroughput();
        }
    }

    private void drawThroughput() {
        GraphicsContext graphics = throughput.getGraphicsContext2D();
        double width = throughput.getWidth();
        double height = throughput.getHeight();
        graphics.clearRect(0, 0, width, height);
        graphics.setFill(Color.web("#0b131e"));
        graphics.fillRoundRect(0, 0, width, height, 8, 8);
        double max = 0;
        for (double sample : throughputSamples) max = Math.max(max, sample);
        double scaleMax = Math.max(1, max);
        graphics.setStroke(Color.web("#34d399", 0.25));
        graphics.strokeLine(0, height * 0.5, width, height * 0.5);
        graphics.setStroke(Color.web("#6ee7b7"));
        graphics.setLineWidth(1.6);
        graphics.beginPath();
        for (int i = 0; i < throughputSamples.length; i++) {
            int source = (throughputCursor + i) % throughputSamples.length;
            double x = i * width / (throughputSamples.length - 1);
            double y = height - 5 - throughputSamples[source] / scaleMax * (height - 10);
            if (i == 0) graphics.moveTo(x, y); else graphics.lineTo(x, y);
        }
        graphics.stroke();
        graphics.setFill(Color.web("#9db2c8"));
        graphics.setFont(javafx.scene.text.Font.font("IBM Plex Mono", 9));
        graphics.fillText("peak %.1f/s".formatted(max), 7, 12);
    }

    public void sync() {
        Sim.Stats stats = replayFrame == null ? sim.stats() : replayFrame.stats();
        int liveCount = replayFrame == null ? sim.vts().size() : replayFrame.vts().size();
        double utilization = replayFrame == null ? sim.carrierUtilization() : replayFrame.carrierUtilization();
        counters[0].setText(Integer.toString(stats.runnable()));
        counters[1].setText(Integer.toString(stats.mounted()));
        counters[2].setText(Integer.toString(stats.parked()));
        counters[3].setText(Integer.toString(stats.completed()));
        counters[4].setText(Integer.toString(liveCount));
        counters[5].setText("%.0f%%".formatted(utilization * 100));
        for (Sim.Flash flash : Sim.Flash.values()) {
            double age = replayFrame == null ? sim.flashAge(flash)
                    : replayFrame.flashAges().getOrDefault(flash, 9.0);
            flashes.get(flash).setOpacity(Math.max(0, 1 - age / 1.6));
        }
        int line = 0;
        Iterable<String> entries = replayFrame == null ? sim.log() : replayFrame.log();
        for (String entry : entries) {
            if (line >= logLines.size()) break;
            Label label = logLines.get(line++);
            label.setText(entry);
            label.setAccessibleText("Event: " + entry);
        }
        while (line < logLines.size()) logLines.get(line++).setText("");
        boolean displayedFreeRun = replayFrame == null ? sim.freeRun() : replayFrame.freeRun();
        boolean displayedLive = replayFrame == null ? sim.liveMode() : replayFrame.liveMode();
        status.setText(replayFrame != null ? "REPLAY"
                : !sim.running() ? "PAUSED" : sim.bootT() < 3 ? "BOOTING" : "RUNNING");
        playButton.setText(replayFrame != null ? replayPlaying ? "Pause replay" : "Play replay"
                : sim.running() ? "Pause" : "Run");
        playButton.setAccessibleText(replayFrame != null
                ? "Play or pause recorded simulation history" : "Pause or resume the simulation");
        guided.setSelected(!displayedFreeRun);
        freeRun.setSelected(displayedFreeRun);
        synthetic.setSelected(!displayedLive);
        live.setSelected(displayedLive);
        jdk21Button.setDisable(displayedLive);
        jdk25Button.setDisable(displayedLive);
        Sim.ProfileStats mix = replayFrame == null ? sim.profileStats() : replayFrame.profileStats();
        double averageIo = replayFrame == null ? sim.averageIoSeconds() : replayFrame.averageIoSeconds();
        feedNotice.setText((displayedLive
                ? "● LIVE · illustrative lanes · I/O avg %.1fs"
                : "◇ MODEL · I/O avg %.1fs").formatted(averageIo)
                + "\nMIX · FAST " + mix.fast() + " · CPU " + mix.compute()
                + " · I/O " + mix.ioBound());
        int chapter = replayFrame == null ? sim.chapter() : replayFrame.chapter();
        chapterNumber.setText("CHAPTER " + (chapter + 1) + "/" + Sim.CHAPTER_COUNT);
        chapterTitle.setText(chapterTitle(chapter));
        chapterTitle.getStyleClass().removeIf(style -> style.startsWith("text-"));
        chapterTitle.getStyleClass().add("text-" + CHAPTER_COLORS[chapter]);
        chapterBody.setText(chapterText(chapter));
        narration.setAccessibleText(chapterTitle(chapter) + ". " + chapterText(chapter));
        speedReadout.setText("%.2f×".formatted(sim.speed()));
    }

    public void setReplayFrame(ReplayFrame frame, boolean playing) {
        replayFrame = frame;
        replayPlaying = playing;
        sync();
    }

    public void clearReplayFrame() {
        replayFrame = null;
        replayPlaying = false;
        sync();
    }

    public void syncTimeline(int selectedIndex, boolean playing) {
        replayPlaying = playing;
        int size = timeline.size();
        int latest = Math.max(0, size - 1);
        int selected = replayFrame == null ? latest : Math.max(0, Math.min(selectedIndex, latest));
        syncingTimeline = true;
        timelineSlider.setMax(latest);
        timelineSlider.setMajorTickUnit(Math.max(1, latest / 8.0));
        timelineSlider.setValue(selected);
        timelineSlider.setDisable(size < 2);
        syncingTimeline = false;
        returnLive.setDisable(replayFrame == null);

        ReplayFrame selectedFrame = size == 0 ? null : timeline.frame(selected);
        if (selectedFrame == null) {
            timelineStatus.setText("LIVE · recording");
        } else if (replayFrame == null) {
            timelineStatus.setText("LIVE · " + formatTime(selectedFrame.time()) + " · recording");
        } else {
            double behind = Math.max(0, timeline.latest().time() - selectedFrame.time());
            String marker = timeline.markersAt(selected).stream().findFirst()
                    .map(ReplayTimeline.Marker::label).map(label -> " · " + label).orElse("");
            timelineStatus.setText((playing ? "REPLAY ▶ · " : "REPLAY · ")
                    + formatTime(selectedFrame.time()) + " · −" + String.format("%.1fs", behind) + marker);
        }
        timelineSlider.setAccessibleText(timelineStatus.getText());
        if (lastTimelineSize != size || lastTimelineIndex != selected) {
            lastTimelineSize = size;
            lastTimelineIndex = selected;
            drawTimelineMarkers();
        }
    }

    private void drawTimelineMarkers() {
        GraphicsContext graphics = timelineMarkers.getGraphicsContext2D();
        double width = timelineMarkers.getWidth();
        double height = timelineMarkers.getHeight();
        graphics.clearRect(0, 0, width, height);
        graphics.setStroke(Color.web("#26364a"));
        graphics.setLineWidth(1);
        graphics.strokeLine(5, 2, Math.max(5, width - 5), 2);
        int denominator = Math.max(1, timeline.size() - 1);
        for (ReplayTimeline.Marker marker : timeline.markers()) {
            double x = 5 + marker.frameIndex() / (double) denominator * Math.max(0, width - 10);
            graphics.setStroke(markerColor(marker.type()));
            graphics.setLineWidth(marker.type() == ReplayTimeline.EventType.CHAPTER ? 2.2 : 1.3);
            graphics.strokeLine(x, 0, x, height);
        }
    }

    private static Color markerColor(ReplayTimeline.EventType type) {
        return switch (type) {
            case CHAPTER -> Color.web("#f5b84c");
            case SPAWN, RESUME -> Color.web("#34d399");
            case MOUNT -> Color.web("#60a5fa");
            case PARK -> Color.web("#a78bfa");
            case PIN, FAIL -> Color.web("#f87171");
            case COMPLETE -> Color.web("#e6edf3");
            case CANCEL -> Color.web("#d6a94e");
        };
    }

    private static String formatTime(double seconds) {
        int minutes = (int) seconds / 60;
        double remainder = seconds - minutes * 60;
        return "%02d:%04.1f".formatted(minutes, remainder);
    }

    public void setCompact(boolean compact) {
        setSidebarWidth(sidebar, compact ? 238 : 290);
        narration.setPrefWidth(compact ? 340 : 400);
        narration.setMaxWidth(compact ? 340 : 400);
        chapterBody.setMaxWidth(compact ? 308 : 368);
        subtitle.setManaged(!compact);
        subtitle.setVisible(!compact);
        performance.setManaged(!compact);
        performance.setVisible(!compact);
        timelineLegend.setManaged(!compact);
        timelineLegend.setVisible(!compact);
        timelineControl.setMinWidth(compact ? 110 : 150);
        timelineControl.setPrefWidth(compact ? 220 : 340);
        timelineStatus.setMaxWidth(compact ? 150 : 230);
        speedSlider.setPrefWidth(compact ? 90 : 120);
        throughput.setWidth(compact ? 202 : 254);
        drawThroughput();
    }

    private static void setSidebarWidth(VBox panel, double width) {
        panel.setMinWidth(width);
        panel.setPrefWidth(width);
        panel.setMaxWidth(width);
    }

    public void setPresenterMode(boolean presenter) {
        setShown(header, !presenter);
        setShown(sidebar, !presenter);
        setShown(bottomBar, !presenter);
        narration.getStyleClass().remove("presenter-narration");
        if (presenter) narration.getStyleClass().add("presenter-narration");
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    public static String chapterTitle(int chapter) {
        return CHAPTER_TITLES[Math.floorMod(chapter, CHAPTER_TITLES.length)];
    }

    public static String chapterText(int chapter) {
        return CHAPTER_TEXT[Math.floorMod(chapter, CHAPTER_TEXT.length)];
    }

    public Node header() { return header; }
    public Node sidebar() { return sidebar; }
    public Node bottomBar() { return bottomBar; }
    public Node narration() { return narration; }
}
