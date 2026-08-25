package vtmachine.view;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.IntConsumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import vtmachine.model.Sim;

/** All two-dimensional status, narration, and controls for the machine. */
public final class Hud {
    private static final String[] CHAPTER_TITLES = {
        "BOOT", "MOUNT", "PARK", "RESUME", "PINNED", "SCALE"
    };
    private static final String[] CHAPTER_COLORS = {
        "amber", "blue", "purple", "green", "red", "white"
    };
    private static final String[] CHAPTER_TEXT = {
        "Power on. Four OS threads light up on the CPU cores; the JVM starts a matching pool of carrier (platform) threads, and the ForkJoinPool scheduler spins up above them. This small machine is all the OS ever sees.",
        "Application tasks arrive and become virtual threads — cheap, JVM-managed objects on the runnable deck. The scheduler mounts each runnable VT onto a free carrier; only while mounted does a VT consume an OS thread.",
        "A VT hits blocking I/O. Instead of blocking its OS thread, it unmounts: its stack and continuation are copied to the heap tower, and the carrier is instantly free to run another VT.",
        "The I/O completes. The stored continuation makes the VT runnable again and it remounts on ANY free carrier — not necessarily the one it left. Watch it land on a different slot.",
        "The failure mode: blocking inside a synchronized block or native call pins the VT to its carrier. The slot locks red — that carrier cannot be released until the pin ends. This is the one case that still wastes an OS thread.",
        "The payoff. 500 virtual threads flood in and the machine does not grow: 4 carriers multiplex all of them, parked threads costing only heap memory. Thousands of concurrent tasks, a handful of OS threads."
    };

    private final Sim sim;
    private final IntConsumer chapterAction;
    private final HBox header;
    private final VBox sidebar;
    private final HBox bottomBar;
    private final VBox narration;
    private final Label led = new Label();
    private final Label status = new Label("BOOTING");
    private final Label playLabel = new Label();
    private final Button playButton = new Button("Pause");
    private final ToggleButton guided = new ToggleButton("GUIDED");
    private final ToggleButton freeRun = new ToggleButton("FREE RUN");
    private final Label[] counters = new Label[4];
    private final EnumMap<Sim.Flash, Region> flashes = new EnumMap<>(Sim.Flash.class);
    private final List<Label> logLines = new ArrayList<>();
    private final Label chapterNumber = new Label();
    private final Label chapterTitle = new Label();
    private final Label chapterBody = new Label();
    private final Label speedReadout = new Label("0.75×");

    public Hud(Sim sim, IntConsumer chapterAction, Runnable refocusScene) {
        this.sim = sim;
        this.chapterAction = chapterAction;
        header = buildHeader(refocusScene);
        sidebar = buildSidebar();
        bottomBar = buildBottomBar(refocusScene);
        narration = buildNarration(refocusScene);
        sync();
    }

    private HBox buildHeader(Runnable refocusScene) {
        HBox bar = new HBox(12);
        bar.getStyleClass().add("header-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        led.getStyleClass().add("status-led");
        led.setMinSize(10, 10);
        led.setMaxSize(10, 10);

        Label title = new Label("THE VIRTUAL THREAD MACHINE");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("Mount · Park · Resume · Pin — 500 virtual threads on 4 carriers, live in 3D");
        subtitle.getStyleClass().add("app-subtitle");
        VBox titles = new VBox(1, title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToggleGroup modes = new ToggleGroup();
        guided.setToggleGroup(modes);
        freeRun.setToggleGroup(modes);
        guided.getStyleClass().add("mode-toggle");
        freeRun.getStyleClass().add("mode-toggle");
        guided.setSelected(true);
        guided.setOnAction(event -> {
            if (!guided.isSelected()) {
                guided.setSelected(true);
                return;
            }
            sim.setFreeRun(false);
            sync();
            refocusScene.run();
        });
        freeRun.setOnAction(event -> {
            if (!freeRun.isSelected()) {
                freeRun.setSelected(true);
                return;
            }
            sim.setFreeRun(true);
            sync();
            refocusScene.run();
        });
        HBox modeButtons = new HBox(guided, freeRun);
        modeButtons.getStyleClass().add("mode-group");

        status.getStyleClass().add("status-text");
        status.setAlignment(Pos.CENTER_RIGHT);
        status.setMinWidth(86);
        status.setPrefWidth(86);

        bar.getChildren().addAll(led, titles, spacer, modeButtons, status);
        return bar;
    }

    private VBox buildSidebar() {
        VBox panel = new VBox(14);
        panel.getStyleClass().add("sidebar");
        panel.setPrefWidth(290);
        panel.setMinWidth(290);
        panel.setMaxWidth(290);

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

        VBox behaviors = new VBox(7,
                behaviorCard(Sim.Flash.MOUNT, "1 · Mount", "Runnable VT mounts on a free carrier thread.", "blue"),
                behaviorCard(Sim.Flash.PARK, "2 · Park", "Blocking I/O unmounts the VT; continuation stored on the heap, carrier released.", "purple"),
                behaviorCard(Sim.Flash.RESUME, "3 · Resume", "I/O done; VT remounts on any free carrier.", "green"),
                behaviorCard(Sim.Flash.PIN, "4 · Pinned", "Blocking in synchronized/native code pins the carrier — it can't be released.", "red"));

        Label logHeader = new Label("EVENT LOG");
        logHeader.getStyleClass().add("section-header");
        VBox logBox = new VBox(1);
        logBox.getStyleClass().add("event-log");
        for (int i = 0; i < 9; i++) {
            Label line = new Label();
            line.getStyleClass().add("event-line");
            line.setMaxWidth(Double.MAX_VALUE);
            line.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            logLines.add(line);
            logBox.getChildren().add(line);
        }
        VBox.setVgrow(logBox, Priority.ALWAYS);

        panel.getChildren().addAll(grid, behaviors, logHeader, logBox);
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
        return card;
    }

    private HBox buildBottomBar(Runnable refocusScene) {
        HBox bar = new HBox(10);
        bar.getStyleClass().add("bottom-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        playButton.getStyleClass().addAll("control-button", "play-button");
        playButton.setMinWidth(90);
        playButton.setOnAction(event -> {
            sim.setRunning(!sim.running());
            sync();
            refocusScene.run();
        });

        Button burst = controlButton("+25 tasks", "burst-button", () -> sim.burst(25), refocusScene);
        Button park = controlButton("Force park", "park-button", sim::forcePark, refocusScene);
        Button pin = controlButton("Force pin", "pin-button", sim::forcePin, refocusScene);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label speedLabel = new Label("SPEED");
        speedLabel.getStyleClass().add("speed-label");
        Slider speed = new Slider(0.25, 3.0, sim.speed());
        speed.setBlockIncrement(0.25);
        speed.setMajorTickUnit(0.25);
        speed.setSnapToTicks(true);
        speed.setPrefWidth(130);
        speed.valueProperty().addListener((observable, oldValue, newValue) -> {
            double snapped = Math.round(newValue.doubleValue() * 4) / 4.0;
            sim.setSpeed(snapped);
            speedReadout.setText("%.2f×".formatted(sim.speed()));
        });
        speedReadout.getStyleClass().add("speed-readout");
        speedReadout.setMinWidth(44);

        bar.getChildren().addAll(playButton, burst, park, pin, spacer, speedLabel, speed, speedReadout);
        return bar;
    }

    private Button controlButton(String text, String styleClass, Runnable action, Runnable refocusScene) {
        Button button = new Button(text);
        button.getStyleClass().addAll("control-button", styleClass);
        button.setOnAction(event -> {
            action.run();
            sync();
            refocusScene.run();
        });
        return button;
    }

    private VBox buildNarration(Runnable refocusScene) {
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
        previous.setOnAction(event -> {
            chapterAction.accept(sim.chapter() - 1);
            refocusScene.run();
        });
        Button next = new Button("Next →");
        next.getStyleClass().add("next-button");
        next.setOnAction(event -> {
            chapterAction.accept(sim.chapter() + 1);
            refocusScene.run();
        });
        HBox row = new HBox(9, chapterNumber, chapterTitle, spacer, previous, next);
        row.setAlignment(Pos.CENTER_LEFT);

        chapterBody.getStyleClass().add("chapter-body");
        chapterBody.setWrapText(true);
        chapterBody.setMaxWidth(368);
        card.getChildren().addAll(row, chapterBody);
        return card;
    }

    public void syncFrame(double wallTimeSeconds) {
        led.setOpacity(0.35 + 0.65 * (0.5 + 0.5 * Math.cos(wallTimeSeconds * Math.PI * 2 / 1.6)));
    }

    public void sync() {
        Sim.Stats stats = sim.stats();
        counters[0].setText(Integer.toString(stats.runnable()));
        counters[1].setText(Integer.toString(stats.mounted()));
        counters[2].setText(Integer.toString(stats.parked()));
        counters[3].setText(Integer.toString(stats.completed()));

        for (Sim.Flash flash : Sim.Flash.values()) {
            flashes.get(flash).setOpacity(Math.max(0, 1 - sim.flashAge(flash) / 1.6));
        }

        int line = 0;
        for (String entry : sim.log()) {
            if (line >= logLines.size()) break;
            logLines.get(line++).setText(entry);
        }
        while (line < logLines.size()) logLines.get(line++).setText("");

        status.setText(!sim.running() ? "PAUSED" : sim.bootT() < 3 ? "BOOTING" : "RUNNING");
        playButton.setText(sim.running() ? "Pause" : "Run");
        guided.setSelected(!sim.freeRun());
        freeRun.setSelected(sim.freeRun());

        int chapter = sim.chapter();
        chapterNumber.setText("CHAPTER " + (chapter + 1) + "/6");
        chapterTitle.setText(CHAPTER_TITLES[chapter]);
        chapterTitle.getStyleClass().removeIf(style -> style.startsWith("text-"));
        chapterTitle.getStyleClass().add("text-" + CHAPTER_COLORS[chapter]);
        chapterBody.setText(CHAPTER_TEXT[chapter]);
        speedReadout.setText("%.2f×".formatted(sim.speed()));
    }

    public Node header() { return header; }
    public Node sidebar() { return sidebar; }
    public Node bottomBar() { return bottomBar; }
    public Node narration() { return narration; }
}
