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

/** All two-dimensional status, narration, metrics, and controls for the machine. */
public final class Hud {
    private static final Pattern VT_ID = Pattern.compile("VT-(\\d+)");
    private static final String[] CHAPTER_TITLES = {
        "BOOT", "MOUNT", "PARK", "RESUME", "PINNED", "SCALE",
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
        "Compare the two paths above the machine: parking moves stack chunks to the heap and releases the carrier; native or foreign-function pinning locks the carrier red and makes runnable work wait. Ordinary synchronized code no longer pins on Java 25.",
        "The payoff. 500 mixed-duration tasks flood in while the green run-queue pressure bar expands. A fixed set of illustrative carriers drains the queue; I/O parking releases lanes, pinning blocks them, and completed VTs dissolve in place after releasing their carrier.",
        "Run the same blocking I/O workload two ways. A platform-thread-per-task design ties up one costly OS thread per wait; virtual threads park cheaply while a small, fixed carrier pool keeps executing other work.",
        "Virtual threads remove the thread bottleneck, not downstream limits. Only three tasks may hold a database connection; every other VT parks in the heap without occupying a carrier until a permit becomes available.",
        "Virtual threads improve blocking concurrency, not CPU parallelism. Compute-only tasks saturate every carrier and the run queue grows, but throughput plateaus at the available carrier/core count.",
        "Related child VTs live inside parent scopes. Each scope forks four children and joins only after they finish; when one CHECKOUT child fails, its active siblings are cancelled and the failure is contained within that scope."
    };

    public record Actions(IntConsumer chapter, Consumer<Boolean> freeRun,
            Consumer<Boolean> liveMode, Runnable burst, Runnable park, Runnable pin,
            Runnable settings, LongConsumer highlight, Runnable refocus) {}

    private final Sim sim;
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
    private final Canvas throughput = new Canvas(254, 54);
    private final double[] throughputSamples = new double[72];
    private int throughputCursor;
    private double lastSampleTime = Double.NaN;
    private int lastCompleted;

    public Hud(Sim sim, Actions actions) {
        this.sim = sim;
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
                behaviorCard(Sim.Flash.PIN, "④ Pinned", "Carrier stays red while queued work waits.", "red"));

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
            sim.setRunning(!sim.running());
            sync();
            actions.refocus.run();
        });
        Button burst = controlButton("+25 tasks", "burst-button", actions.burst,
                "Submit 25 tasks to the selected workload");
        Button park = controlButton("Force park", "park-button", actions.park,
                "Park one running virtual thread");
        Button pin = controlButton("Force pin", "pin-button", actions.pin,
                "Demonstrate one native or foreign-function pin");
        Button settings = controlButton("Settings", "settings-button", actions.settings,
                "Change carrier count, task limit, task rate, and random seed");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        performance.getStyleClass().add("performance-label");
        Label speedLabel = new Label("SPEED");
        speedLabel.getStyleClass().add("speed-label");
        Slider speed = new Slider(0.25, 3.0, sim.speed());
        speed.setBlockIncrement(0.25);
        speed.setMajorTickUnit(0.25);
        speed.setSnapToTicks(true);
        speed.setPrefWidth(120);
        speed.setAccessibleText("Simulation speed");
        speed.valueProperty().addListener((observable, oldValue, newValue) -> {
            double snapped = Math.round(newValue.doubleValue() * 4) / 4.0;
            sim.setSpeed(snapped);
            speedReadout.setText("%.2f×".formatted(sim.speed()));
        });
        speedReadout.getStyleClass().add("speed-readout");
        speedReadout.setMinWidth(44);
        bar.getChildren().addAll(playButton, burst, park, pin, settings, spacer,
                performance, speedLabel, speed, speedReadout);
        return bar;
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
            actions.chapter.accept(sim.chapter() - 1);
            actions.refocus.run();
        });
        Button next = new Button("Next →");
        next.getStyleClass().add("next-button");
        next.setAccessibleText("Next chapter");
        next.setOnAction(event -> {
            actions.chapter.accept(sim.chapter() + 1);
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
        Sim.Stats stats = sim.stats();
        counters[0].setText(Integer.toString(stats.runnable()));
        counters[1].setText(Integer.toString(stats.mounted()));
        counters[2].setText(Integer.toString(stats.parked()));
        counters[3].setText(Integer.toString(stats.completed()));
        counters[4].setText(Integer.toString(sim.vts().size()));
        counters[5].setText("%.0f%%".formatted(sim.carrierUtilization() * 100));
        for (Sim.Flash flash : Sim.Flash.values()) {
            flashes.get(flash).setOpacity(Math.max(0, 1 - sim.flashAge(flash) / 1.6));
        }
        int line = 0;
        for (String entry : sim.log()) {
            if (line >= logLines.size()) break;
            Label label = logLines.get(line++);
            label.setText(entry);
            label.setAccessibleText("Event: " + entry);
        }
        while (line < logLines.size()) logLines.get(line++).setText("");
        status.setText(!sim.running() ? "PAUSED" : sim.bootT() < 3 ? "BOOTING" : "RUNNING");
        playButton.setText(sim.running() ? "Pause" : "Run");
        guided.setSelected(!sim.freeRun());
        freeRun.setSelected(sim.freeRun());
        synthetic.setSelected(!sim.liveMode());
        live.setSelected(sim.liveMode());
        Sim.ProfileStats mix = sim.profileStats();
        feedNotice.setText((sim.liveMode()
                ? "● LIVE · illustrative lanes · I/O avg %.1fs"
                : "◇ MODEL · I/O avg %.1fs").formatted(sim.averageIoSeconds())
                + "\nMIX · FAST " + mix.fast() + " · CPU " + mix.compute()
                + " · I/O " + mix.ioBound());
        int chapter = sim.chapter();
        chapterNumber.setText("CHAPTER " + (chapter + 1) + "/" + Sim.CHAPTER_COUNT);
        chapterTitle.setText(chapterTitle(chapter));
        chapterTitle.getStyleClass().removeIf(style -> style.startsWith("text-"));
        chapterTitle.getStyleClass().add("text-" + CHAPTER_COLORS[chapter]);
        chapterBody.setText(chapterText(chapter));
        narration.setAccessibleText(chapterTitle(chapter) + ". " + chapterText(chapter));
        speedReadout.setText("%.2f×".formatted(sim.speed()));
    }

    public void setCompact(boolean compact) {
        setSidebarWidth(sidebar, compact ? 238 : 290);
        narration.setPrefWidth(compact ? 340 : 400);
        narration.setMaxWidth(compact ? 340 : 400);
        chapterBody.setMaxWidth(compact ? 308 : 368);
        subtitle.setManaged(!compact);
        subtitle.setVisible(!compact);
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
