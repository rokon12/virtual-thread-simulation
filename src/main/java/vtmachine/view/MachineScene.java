package vtmachine.view;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.DepthTest;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.Shape3D;
import javafx.scene.shape.Sphere;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import vtmachine.model.ReplayFrame;
import vtmachine.model.Sim;
import vtmachine.model.ThreadView;
import vtmachine.model.Vec3;

/** The complete 3D machine, its projected labels, tooltip, and camera controls. */
public final class MachineScene extends StackPane {
    private static final int PARKED_BADGE_LIMIT = 4;
    private static final Color GREEN = Color.web("#34d399");
    private static final Color BLUE = Color.web("#60a5fa");
    private static final Color PURPLE = Color.web("#a78bfa");
    private static final Color AMBER = Color.web("#f5b84c");
    private static final Color RED = Color.web("#f87171");
    private static final Color WHITE = Color.web("#e6edf3");

    private enum VtColor { GREEN, BLUE, PURPLE, AMBER, RED, WHITE }
    public enum Quality { AUTO, HIGH, LOW }
    private enum AnchorAlignment { CENTER, RIGHT }
    private record ProjectedLabel(Group anchor, Label label, AnchorAlignment alignment) {}
    private record BootLayer(Group group, double restY, int order) {}
    private record CpuCore(Group gear, List<Shape3D> heatSurfaces) {}
    private record CoreActivity(Group indicator, Cylinder piston, List<Sphere> sparks) {}
    private static final class GearMotion {
        double angle;
        double velocity;
        double stutter;

        GearMotion(double angle) {
            this.angle = angle;
        }
    }

    private final Sim sim;
    private final Group root3d = new Group();
    private final Group world = new Group();
    private final SubScene subScene;
    private final Pane labelOverlay = new Pane();
    private final Canvas terminationCanvas = new Canvas();
    private final Canvas lessonCanvas = new Canvas(680, 168);
    private final CameraRig camera = new CameraRig();
    private final Label tooltip = new Label();
    private final HBox cameraButtons;
    private final Label shortcut;
    private final Label diagnostics = new Label("-- FPS · AUTO/HIGH");
    private final Label followTitle = new Label();
    private final Label followStatus = new Label();
    private final Label[] followDurations = new Label[Sim.LifecyclePhase.values().length];
    private final VBox followOverlay = buildFollowOverlay();
    private final Label comparisonParkValue = new Label();
    private final Label comparisonPinValue = new Label();
    private final VBox comparisonParkCard = comparisonCard("PARK", "VT → HEAP", comparisonParkValue, "purple");
    private final VBox comparisonPinCard = comparisonCard("PIN", "VT ⊗ CARRIER", comparisonPinValue, "red");
    private final HBox comparisonOverlay = buildComparisonOverlay();

    private final List<BootLayer> bootLayers = new ArrayList<>();
    private final List<ProjectedLabel> projectedLabels = new ArrayList<>();
    private final List<CpuCore> cores = new ArrayList<>();
    private final List<GearMotion> coreGearMotions = new ArrayList<>();
    private final List<CoreActivity> coreActivities = new ArrayList<>();
    private final List<Cylinder> carrierPillars = new ArrayList<>();
    private final List<TorusMesh> slots = new ArrayList<>();
    private final List<Label> pinnedLabels = new ArrayList<>();
    private final List<Sphere> particles = new ArrayList<>();
    private final List<Sphere> glows = new ArrayList<>();
    private final List<Sphere> ioMarkers = new ArrayList<>();
    private final List<Box> stackChunks = new ArrayList<>();
    private final List<Cylinder> ioLinks = new ArrayList<>();
    private final List<Sphere> ioSignals = new ArrayList<>();
    private final List<Label> parkedBadges = new ArrayList<>();
    private final List<Node> externalIoNodes = new ArrayList<>();
    private final List<Label> externalIoLabels = new ArrayList<>();
    private final List<Box> databasePermitSlots = new ArrayList<>();
    private final List<Group> applicationTaskCards = new ArrayList<>();
    private final List<Sphere> trail = new ArrayList<>();
    private final Map<Node, ThreadView> pickedVts = new IdentityHashMap<>();
    private final EnumMap<VtColor, PhongMaterial> materials = new EnumMap<>(VtColor.class);
    private final EnumMap<VtColor, PhongMaterial> glowMaterials = new EnumMap<>(VtColor.class);
    private final EnumMap<Sim.IoDevice, Vec3> ioEndpoints = new EnumMap<>(Sim.IoDevice.class);
    private final List<PhongMaterial> coreHeatMaterials = new ArrayList<>();

    private final PhongMaterial idleSlot = material(Color.web("#24425f"));
    private final PhongMaterial activeSlot = material(BLUE);
    private final PhongMaterial pinnedSlot = material(RED);
    private final PhongMaterial pinnedCore = material(Color.web("#8f2f31"));
    private final PhongMaterial coreHub = material(Color.web("#231b10"));
    private final PhongMaterial coreActivityIdle = material(Color.web("#5d461f"));
    private final PhongMaterial coreActivityActive = material(Color.web("#ffd275"));
    private final PhongMaterial coreActivityPinned = material(RED);
    private final PhongMaterial schedulerBright = material(PURPLE);
    private final PhongMaterial schedulerDim = material(Color.web("#2a2145"));
    private final PhongMaterial stackChunkMaterial = material(Color.web("#c4b5fd"));
    private final PhongMaterial ioLinkMaterial = transparentMaterial(PURPLE, 0.38);
    private final PhongMaterial resourceLinkMaterial = transparentMaterial(GREEN, 0.55);
    private final PhongMaterial resourceIdleMaterial = material(Color.web("#26364a"));
    private final PhongMaterial resourceActiveMaterial = material(GREEN);

    private TorusMesh schedulerRing;
    private Box queuePressureBar;
    private Group applicationTaskIngress;
    private Group heapGhost;
    private Label heapLabel;
    private Label queuePressureLabel;
    private Label heroLabel;
    private Label databasePoolLabel;
    private long heroId = -1;
    private ThreadView followedVt;
    private long followedId = -1;
    private ThreadView pressedVt;
    private ReplayFrame replayFrame;
    private double pressX;
    private double pressY;
    private double displayedQueuePressure;
    private boolean externalIoVisible;
    private boolean dragging;
    private Quality requestedQuality = Quality.AUTO;
    private boolean autoLow;
    private boolean highContrast;
    private double lastGearDisplayTime = Double.NaN;

    public MachineScene(Sim sim) {
        this.sim = sim;
        setMinSize(0, 0);
        setStyle("-fx-background-color: #070b12;");
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.PARENT);
        setAccessibleText("Interactive three-dimensional virtual-thread scheduler. Drag to orbit; scroll or pinch to zoom.");
        root3d.setDepthTest(DepthTest.ENABLE);
        world.getTransforms().add(new Scale(1, -1, 1));
        root3d.getChildren().add(world);

        subScene = new SubScene(root3d, 960, 760, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.web("#070b12"));
        subScene.setCamera(camera.camera());
        subScene.widthProperty().bind(widthProperty());
        subScene.heightProperty().bind(heightProperty());

        labelOverlay.setMouseTransparent(true);
        terminationCanvas.setMouseTransparent(true);
        terminationCanvas.widthProperty().bind(widthProperty());
        terminationCanvas.heightProperty().bind(heightProperty());
        lessonCanvas.setMouseTransparent(true);
        lessonCanvas.setAccessibleRole(AccessibleRole.TEXT);
        lessonCanvas.setVisible(false);
        tooltip.getStyleClass().add("vt-tooltip");
        tooltip.setManaged(false);
        tooltip.setVisible(false);
        labelOverlay.getChildren().add(tooltip);
        for (int i = 0; i < PARKED_BADGE_LIMIT; i++) {
            Label badge = new Label();
            badge.getStyleClass().add("parked-badge");
            badge.setManaged(false);
            badge.setMouseTransparent(true);
            badge.setVisible(false);
            parkedBadges.add(badge);
            labelOverlay.getChildren().add(badge);
        }

        initialiseMaterials();
        buildLights();
        buildStatics();
        buildPool();
        buildTrail();

        cameraButtons = buildCameraButtons();
        shortcut = new Label("SPACE play/pause · J/K replay · L live · ← → chapters · P present · A auto · N notes");
        shortcut.getStyleClass().add("shortcut-hint");
        shortcut.setMouseTransparent(true);
        diagnostics.getStyleClass().add("diagnostics-label");
        diagnostics.setMouseTransparent(true);

        getChildren().addAll(subScene, terminationCanvas, labelOverlay, cameraButtons, shortcut,
                diagnostics, comparisonOverlay, lessonCanvas, followOverlay);
        StackPane.setAlignment(cameraButtons, Pos.TOP_RIGHT);
        StackPane.setMargin(cameraButtons, new Insets(14, 16, 0, 0));
        StackPane.setAlignment(shortcut, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(shortcut, new Insets(0, 16, 16, 0));
        StackPane.setAlignment(diagnostics, Pos.TOP_LEFT);
        StackPane.setMargin(diagnostics, new Insets(16, 0, 0, 16));
        StackPane.setAlignment(comparisonOverlay, Pos.TOP_CENTER);
        StackPane.setMargin(comparisonOverlay, new Insets(14, 0, 0, 0));
        StackPane.setAlignment(lessonCanvas, Pos.TOP_CENTER);
        StackPane.setMargin(lessonCanvas, new Insets(56, 0, 0, 0));
        StackPane.setAlignment(followOverlay, Pos.TOP_LEFT);
        StackPane.setMargin(followOverlay, new Insets(54, 0, 0, 16));

        installPointerControls();
    }

    private VBox buildFollowOverlay() {
        followTitle.getStyleClass().add("follow-title");
        followStatus.getStyleClass().add("follow-status");
        followStatus.setWrapText(true);
        followStatus.setMaxWidth(356);
        Button close = new Button("×");
        close.getStyleClass().add("follow-close");
        close.setAccessibleText("Stop following this virtual thread");
        close.setOnAction(event -> {
            followedVt = null;
            followedId = -1;
            followOverlay.setVisible(false);
            requestFocus();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox header = new HBox(8, followTitle, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox phases = new HBox(5);
        int index = 0;
        for (Sim.LifecyclePhase phase : Sim.LifecyclePhase.values()) {
            Label value = new Label(phase.display() + "\n0.0s");
            value.getStyleClass().addAll("follow-phase", "follow-phase-" + phase.name().toLowerCase(Locale.ROOT));
            followDurations[index++] = value;
            phases.getChildren().add(value);
        }
        Label hint = new Label("Click another VT to follow its lifecycle");
        hint.getStyleClass().add("follow-hint");
        VBox box = new VBox(7, header, phases, followStatus, hint);
        box.getStyleClass().add("follow-overlay");
        box.setPrefWidth(380);
        box.setMaxSize(380, Region.USE_PREF_SIZE);
        box.setVisible(false);
        return box;
    }

    private VBox comparisonCard(String titleText, String diagram, Label value, String color) {
        Label title = new Label(titleText);
        title.getStyleClass().addAll("comparison-title", "text-" + color);
        Label visual = new Label(diagram);
        visual.getStyleClass().add("comparison-visual");
        value.getStyleClass().add("comparison-value");
        VBox card = new VBox(2, title, visual, value);
        card.getStyleClass().addAll("comparison-card", "comparison-" + color);
        card.setAlignment(Pos.CENTER);
        return card;
    }

    private HBox buildComparisonOverlay() {
        HBox box = new HBox(8, comparisonParkCard, comparisonPinCard);
        box.getStyleClass().add("comparison-overlay");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setMouseTransparent(true);
        box.setVisible(false);
        box.setAccessibleText("Parking releases a carrier; pinning retains a carrier and increases queue pressure.");
        return box;
    }

    private void initialiseMaterials() {
        materials.put(VtColor.GREEN, material(GREEN));
        materials.put(VtColor.BLUE, material(BLUE));
        materials.put(VtColor.PURPLE, material(PURPLE));
        materials.put(VtColor.AMBER, material(AMBER));
        materials.put(VtColor.RED, material(RED));
        materials.put(VtColor.WHITE, material(WHITE));
        glowMaterials.put(VtColor.GREEN, glowMaterial(GREEN));
        glowMaterials.put(VtColor.BLUE, glowMaterial(BLUE));
        glowMaterials.put(VtColor.PURPLE, glowMaterial(PURPLE));
        glowMaterials.put(VtColor.AMBER, glowMaterial(AMBER));
        glowMaterials.put(VtColor.RED, glowMaterial(RED));
        glowMaterials.put(VtColor.WHITE, glowMaterial(WHITE));

        for (int i = 0; i < 5; i++) {
            double t = i / 4.0;
            coreHeatMaterials.add(material(AMBER.interpolate(WHITE, t * 0.72)));
        }
    }

    private void buildLights() {
        AmbientLight ambient = new AmbientLight(Color.web("#4b566c"));
        PointLight key = new PointLight(Color.web("#bfc7d8"));
        key.setTranslateX(400);
        key.setTranslateY(-900);
        key.setTranslateZ(600);
        PointLight rim = new PointLight(Color.web("#305784"));
        rim.setTranslateX(-120);
        rim.setTranslateY(-80);
        rim.setTranslateZ(-80);
        root3d.getChildren().addAll(ambient, key, rim, camera.camera());
    }

    private void buildStatics() {
        buildGrid();
        Group cpu = layer(150, 3.5, 64, 0, Color.web("#1c1508"), AMBER, 0);
        Group carriers = layer(150, 3.5, 56, 26, Color.web("#0a1626"), BLUE, 1);
        Group scheduler = layer(150, 3.5, 50, 52, Color.web("#150f2b"), PURPLE, 2);
        Group runnable = layer(170, 3.5, 66, 78, Color.web("#0a2018"), GREEN, 3);
        // Keep strong references; these layers are animated as groups during boot.
        if (cpu == null || carriers == null || scheduler == null || runnable == null) throw new AssertionError();

        Group heap = new Group();
        heap.setTranslateX(118);
        heap.setTranslateY(30);
        addBoxWithEdges(heap, 40, 3.5, 40, 0, 0, 0, Color.web("#171129"), PURPLE);
        heapGhost = buildHeapBasket();
        heap.getChildren().add(heapGhost);
        world.getChildren().add(heap);
        bootLayers.add(new BootLayer(heap, 30, 4));

        for (int i = 0; i < sim.carriers().size(); i++) buildLane(i);

        schedulerRing = new TorusMesh(14, 0.9f, 48, 10);
        schedulerRing.setMaterial(schedulerDim);
        schedulerRing.setTranslateY(56);
        schedulerRing.setRotationAxis(Rotate.Y_AXIS);
        world.getChildren().add(schedulerRing);

        queuePressureBar = new Box(130, 2.4, 5);
        queuePressureBar.setTranslateX(-63);
        queuePressureBar.setTranslateY(83);
        queuePressureBar.setTranslateZ(29);
        queuePressureBar.setMaterial(transparentMaterial(GREEN, 0.72));
        queuePressureBar.setCullFace(CullFace.NONE);
        queuePressureBar.setScaleX(0.03);
        world.getChildren().add(queuePressureBar);

        buildExternalIo();
        buildApplicationTaskIngress();

        addProjectedLabel("OS THREADS / CPU CORES", "amber", -81, 7, 0, AnchorAlignment.RIGHT);
        addProjectedLabel("CARRIER THREADS", "blue", -81, 33, 0, AnchorAlignment.RIGHT);
        addProjectedLabel("SCHEDULER · ForkJoinPool", "purple", -81, 59, 0, AnchorAlignment.RIGHT);
        addProjectedLabel("VIRTUAL THREADS · runnable", "green", -91, 85, 0, AnchorAlignment.RIGHT);
        heapLabel = addProjectedLabel("I/O WAIT · 0 VTs · PARKED STACK CHUNKS", "purple",
                118, 74, 0, AnchorAlignment.CENTER);
        queuePressureLabel = addProjectedLabel("RUN QUEUE · EMPTY", "green",
                0, 91, 28, AnchorAlignment.CENTER);
        addProjectedLabel("APPLICATION TASKS · SUBMIT ↓", "muted",
                Sim.TASK_INLET_X, 102, Sim.TASK_INLET_Z,
                AnchorAlignment.CENTER);
    }

    private void buildApplicationTaskIngress() {
        applicationTaskIngress = new Group();
        applicationTaskIngress.setTranslateX(Sim.TASK_INLET_X);
        applicationTaskIngress.setTranslateZ(Sim.TASK_INLET_Z);
        applicationTaskIngress.setMouseTransparent(true);

        addBoxWithEdges(applicationTaskIngress, 34, 1.8, 23, 0, 83, 0,
                Color.web("#101a27"), Color.web("#6ee7b7"));
        PhongMaterial chuteMaterial = transparentMaterial(GREEN, 0.7);
        for (int side : new int[] {-1, 1}) {
            Box chuteRail = new Box(0.65, 4, 0.65);
            chuteRail.setTranslateX(side * 8.5);
            chuteRail.setTranslateY(81.5);
            chuteRail.setMaterial(chuteMaterial);
            applicationTaskIngress.getChildren().add(chuteRail);
        }

        Color[] cardColors = {GREEN, BLUE, PURPLE};
        for (int i = 0; i < cardColors.length; i++) {
            Group card = new Group();
            Box body = new Box(10, 5.8, 4.0);
            body.setMaterial(material(Color.web("#dbeafe")));
            body.setCullFace(CullFace.NONE);
            Box codeLine = new Box(6.2, 0.45, 4.15);
            codeLine.setTranslateY(0.8);
            codeLine.setMaterial(material(cardColors[i]));
            Box codeLineShort = new Box(4.2, 0.38, 4.16);
            codeLineShort.setTranslateX(-1);
            codeLineShort.setTranslateY(-0.8);
            codeLineShort.setMaterial(material(cardColors[i].interpolate(WHITE, 0.35)));
            card.getChildren().addAll(body, codeLine, codeLineShort);
            card.setTranslateX((i - 1) * 6.4);
            card.setRotationAxis(Rotate.Y_AXIS);
            applicationTaskCards.add(card);
            applicationTaskIngress.getChildren().add(card);
        }
        world.getChildren().add(applicationTaskIngress);
    }

    /** An open wire basket keeps parked VTs visible instead of tinting them through a solid cube. */
    private Group buildHeapBasket() {
        Group basket = new Group();
        PhongMaterial railMaterial = material(PURPLE);
        double halfWidth = 18;
        double height = 44;
        double thickness = 0.75;

        for (int sx : new int[] {-1, 1}) {
            for (int sz : new int[] {-1, 1}) {
                Box post = new Box(thickness, height, thickness);
                post.setTranslateX(sx * halfWidth);
                post.setTranslateY(24);
                post.setTranslateZ(sz * halfWidth);
                post.setMaterial(railMaterial);
                basket.getChildren().add(post);
            }
        }
        for (double y : new double[] {3, 13, 24, 35, 46}) {
            for (int side : new int[] {-1, 1}) {
                Box xRail = new Box(halfWidth * 2, thickness, thickness);
                xRail.setTranslateY(y);
                xRail.setTranslateZ(side * halfWidth);
                xRail.setMaterial(railMaterial);
                Box zRail = new Box(thickness, thickness, halfWidth * 2);
                zRail.setTranslateX(side * halfWidth);
                zRail.setTranslateY(y);
                zRail.setMaterial(railMaterial);
                basket.getChildren().addAll(xRail, zRail);
            }
        }
        basket.setMouseTransparent(true);
        return basket;
    }

    private void buildExternalIo() {
        PhongMaterial device = material(Color.web("#7c6de8"));
        PhongMaterial pulse = transparentMaterial(PURPLE, 0.32);
        Sim.IoDevice[] devices = Sim.IoDevice.values();
        for (int i = 0; i < devices.length; i++) {
            Sim.IoDevice type = devices[i];
            Vec3 position = new Vec3(160, 78, -52 + i * 35);
            ioEndpoints.put(type, position);
            Group icon = new Group();
            icon.setTranslateX(position.x);
            icon.setTranslateY(position.y);
            icon.setTranslateZ(position.z);
            switch (type) {
                case NETWORK -> {
                    Sphere center = new Sphere(3.4, 10);
                    center.setMaterial(device);
                    for (int branch = -1; branch <= 1; branch += 2) {
                        Sphere node = new Sphere(1.5, 8);
                        node.setTranslateX(branch * 6);
                        node.setTranslateY(branch * 2.5);
                        node.setMaterial(device);
                        Box wire = new Box(8, 0.55, 0.55);
                        wire.setTranslateX(branch * 3.2);
                        wire.setTranslateY(branch * 1.2);
                        wire.setRotate(branch * 20);
                        wire.setMaterial(pulse);
                        icon.getChildren().addAll(wire, node);
                    }
                    icon.getChildren().add(center);
                }
                case DISK -> {
                    Cylinder platter = new Cylinder(5.5, 2.4, 18);
                    platter.setMaterial(device);
                    TorusMesh rim = new TorusMesh(4.2f, 0.45f, 32, 7);
                    rim.setTranslateY(1.3);
                    rim.setMaterial(pulse);
                    icon.getChildren().addAll(platter, rim);
                }
                case TIMER -> {
                    TorusMesh clock = new TorusMesh(4.8f, 0.65f, 36, 8);
                    clock.setRotationAxis(Rotate.X_AXIS);
                    clock.setRotate(90);
                    clock.setMaterial(device);
                    Box hand = new Box(0.65, 5, 0.65);
                    hand.setTranslateY(2.1);
                    hand.setRotate(-32);
                    hand.setMaterial(pulse);
                    icon.getChildren().addAll(clock, hand);
                }
                case DATABASE -> {
                    for (int level = 0; level < 3; level++) {
                        Cylinder disk = new Cylinder(5.2, 2.0, 18);
                        disk.setTranslateY(level * 3.0 - 3.0);
                        disk.setMaterial(level == 1 ? pulse : device);
                        icon.getChildren().add(disk);
                    }
                }
            }
            world.getChildren().add(icon);
            externalIoNodes.add(icon);
            Label label = addProjectedLabel(type.display(), "purple", position.x,
                    position.y + 8, position.z, AnchorAlignment.CENTER);
            label.setStyle("-fx-font-size: 9px;");
            externalIoLabels.add(label);
        }
        externalIoLabels.add(addProjectedLabel("EXTERNAL I/O", "purple",
                160, 103, 0, AnchorAlignment.CENTER));

        for (int permit = 0; permit < Sim.DATABASE_PERMITS; permit++) {
            Box slot = new Box(6.5, 3.0, 6.5);
            slot.setTranslateX(132 + permit * 9);
            slot.setTranslateY(69);
            slot.setTranslateZ(53);
            slot.setMaterial(resourceIdleMaterial);
            slot.setCullFace(CullFace.NONE);
            slot.setVisible(false);
            databasePermitSlots.add(slot);
            world.getChildren().add(slot);
        }
        databasePoolLabel = addProjectedLabel("DB CONNECTION POOL · 0/3", "green",
                141, 61, 53, AnchorAlignment.CENTER);
        databasePoolLabel.setVisible(false);
    }

    private void buildGrid() {
        PhongMaterial major = material(Color.web("#14202e"));
        PhongMaterial minor = material(Color.web("#0e1620"));
        for (int i = -20; i <= 20; i++) {
            PhongMaterial gridMaterial = i % 5 == 0 ? major : minor;
            double offset = i * 15;
            Box xLine = new Box(600, 0.12, i % 5 == 0 ? 0.28 : 0.16);
            xLine.setTranslateY(-16);
            xLine.setTranslateZ(offset);
            xLine.setMaterial(gridMaterial);
            Box zLine = new Box(i % 5 == 0 ? 0.28 : 0.16, 0.12, 600);
            zLine.setTranslateX(offset);
            zLine.setTranslateY(-16);
            zLine.setMaterial(gridMaterial);
            world.getChildren().addAll(xLine, zLine);
        }
    }

    private Group layer(double width, double height, double depth, double y, Color fill, Color edge, int order) {
        Group group = new Group();
        group.setTranslateY(y);
        addBoxWithEdges(group, width, height, depth, 0, 0, 0, fill, edge);
        world.getChildren().add(group);
        bootLayers.add(new BootLayer(group, y, order));
        return group;
    }

    private void buildLane(int index) {
        double x = sim.laneX(index);
        CpuCore core = buildCpuCore(x);
        cores.add(core);
        coreGearMotions.add(new GearMotion(index * 31));

        Group indicator = new Group();
        indicator.setTranslateX(x);
        indicator.setTranslateY(6);
        Cylinder piston = new Cylinder(1.15, 9, 10);
        piston.setTranslateY(8.5);
        piston.setTranslateZ(30);
        piston.setMaterial(coreActivityIdle);
        indicator.getChildren().add(piston);
        List<Sphere> sparks = new ArrayList<>();
        for (int sparkIndex = 0; sparkIndex < 3; sparkIndex++) {
            Sphere spark = new Sphere(0.62, 7);
            spark.setMaterial(coreActivityIdle);
            spark.setVisible(false);
            sparks.add(spark);
            indicator.getChildren().add(spark);
        }
        coreActivities.add(new CoreActivity(indicator, piston, sparks));

        TorusMesh slot = new TorusMesh(6, 0.7f, 32, 8);
        slot.setTranslateX(x);
        slot.setTranslateY(28.4);
        slot.setMaterial(idleSlot);
        slots.add(slot);

        Cylinder pillar = new Cylinder(0.5, 26, 6);
        pillar.setTranslateX(x);
        pillar.setTranslateY(15);
        pillar.setMaterial(transparentMaterial(BLUE, 0.18));
        pillar.setCullFace(CullFace.NONE);
        carrierPillars.add(pillar);
        world.getChildren().addAll(core.gear(), indicator, pillar, slot);

        addProjectedLabel("C" + (index + 1), "lane", x, 22, 16, AnchorAlignment.CENTER)
                .setStyle("-fx-font-size: 10px;");
        Label pinned = addProjectedLabel("PINNED", "red", x, 40, 0, AnchorAlignment.CENTER);
        pinned.setVisible(false);
        pinnedLabels.add(pinned);
    }

    private CpuCore buildCpuCore(double x) {
        Group gear = new Group();
        gear.setTranslateX(x);
        gear.setTranslateY(6);
        gear.setRotationAxis(Rotate.Y_AXIS);
        List<Shape3D> heatSurfaces = new ArrayList<>();

        Cylinder body = new Cylinder(4.7, 6.4, 24);
        body.setMaterial(coreHeatMaterials.getFirst());
        body.setCullFace(CullFace.NONE);
        heatSurfaces.add(body);
        gear.getChildren().add(body);

        int toothCount = 12;
        double toothRadius = 5.35;
        for (int toothIndex = 0; toothIndex < toothCount; toothIndex++) {
            double angle = Math.PI * 2 * toothIndex / toothCount;
            Box tooth = new Box(2.7, 6.6, 2.05);
            tooth.setTranslateX(Math.cos(angle) * toothRadius);
            tooth.setTranslateZ(Math.sin(angle) * toothRadius);
            tooth.setRotationAxis(Rotate.Y_AXIS);
            tooth.setRotate(-Math.toDegrees(angle));
            tooth.setMaterial(coreHeatMaterials.getFirst());
            tooth.setCullFace(CullFace.NONE);
            heatSurfaces.add(tooth);
            gear.getChildren().add(tooth);
        }

        Cylinder axle = new Cylinder(1.45, 7.0, 20);
        axle.setMaterial(coreHub);
        axle.setCullFace(CullFace.NONE);
        gear.getChildren().add(axle);
        return new CpuCore(gear, heatSurfaces);
    }

    private void addBoxWithEdges(Group parent, double width, double height, double depth,
            double x, double y, double z, Color fill, Color edge) {
        Box body = new Box(width, height, depth);
        body.setTranslateX(x);
        body.setTranslateY(y);
        body.setTranslateZ(z);
        body.setMaterial(material(fill));
        body.setCullFace(CullFace.NONE);
        parent.getChildren().add(body);

        double thickness = 0.35;
        PhongMaterial edgeMaterial = material(edge);
        for (int sy : new int[] {-1, 1}) {
            for (int sz : new int[] {-1, 1}) {
                Box line = new Box(width, thickness, thickness);
                line.setTranslateX(x);
                line.setTranslateY(y + sy * height / 2);
                line.setTranslateZ(z + sz * depth / 2);
                line.setMaterial(edgeMaterial);
                parent.getChildren().add(line);
            }
        }
        for (int sx : new int[] {-1, 1}) {
            for (int sz : new int[] {-1, 1}) {
                Box line = new Box(thickness, height, thickness);
                line.setTranslateX(x + sx * width / 2);
                line.setTranslateY(y);
                line.setTranslateZ(z + sz * depth / 2);
                line.setMaterial(edgeMaterial);
                parent.getChildren().add(line);
            }
        }
        for (int sx : new int[] {-1, 1}) {
            for (int sy : new int[] {-1, 1}) {
                Box line = new Box(thickness, thickness, depth);
                line.setTranslateX(x + sx * width / 2);
                line.setTranslateY(y + sy * height / 2);
                line.setTranslateZ(z);
                line.setMaterial(edgeMaterial);
                parent.getChildren().add(line);
            }
        }
    }

    private void buildPool() {
        int capacity = sim.maxThreads() + 40;
        for (int i = 0; i < capacity; i++) {
            Sphere glow = new Sphere(3.6, 8);
            glow.setVisible(false);
            glow.setMouseTransparent(true);
            glow.setCullFace(CullFace.NONE);
            Sphere particle = new Sphere(1.9, 10);
            particle.setVisible(false);
            particle.setCullFace(CullFace.NONE);
            Sphere ioMarker = new Sphere(0.62, 7);
            ioMarker.setVisible(false);
            ioMarker.setMouseTransparent(true);
            ioMarker.setMaterial(materials.get(VtColor.PURPLE));
            Box stackChunk = new Box(5.4, 1.15, 2.5);
            stackChunk.setVisible(false);
            stackChunk.setMouseTransparent(true);
            stackChunk.setCullFace(CullFace.NONE);
            stackChunk.setMaterial(stackChunkMaterial);
            Cylinder ioLink = new Cylinder(0.18, 1, 5);
            ioLink.setVisible(false);
            ioLink.setMouseTransparent(true);
            ioLink.setCullFace(CullFace.NONE);
            ioLink.setMaterial(ioLinkMaterial);
            Sphere ioSignal = new Sphere(0.72, 7);
            ioSignal.setVisible(false);
            ioSignal.setMouseTransparent(true);
            ioSignal.setMaterial(materials.get(VtColor.PURPLE));
            particles.add(particle);
            glows.add(glow);
            ioMarkers.add(ioMarker);
            stackChunks.add(stackChunk);
            ioLinks.add(ioLink);
            ioSignals.add(ioSignal);
            world.getChildren().addAll(ioLink, stackChunk, glow, particle, ioMarker, ioSignal);
        }
    }

    private void buildTrail() {
        for (int i = 0; i < 36; i++) {
            double alpha = 0.05 + 0.55 * i / 35.0;
            Sphere dot = new Sphere(0.28 + i / 35.0 * 0.22, 6);
            dot.setMaterial(transparentMaterial(Color.web("#6ee7b7"), alpha));
            dot.setTranslateY(-999);
            dot.setMouseTransparent(true);
            trail.add(dot);
            world.getChildren().add(dot);
        }
        heroLabel = addProjectedLabel("", "hero", 0, -999, 0, AnchorAlignment.CENTER);
        heroLabel.setVisible(false);
    }

    private HBox buildCameraButtons() {
        HBox bar = new HBox(6);
        bar.getStyleClass().add("camera-buttons");
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        for (CameraRig.Preset preset : CameraRig.Preset.values()) {
            Button button = new Button(preset.name());
            button.getStyleClass().add("camera-button");
            button.setAccessibleText(preset.name().toLowerCase(Locale.ROOT) + " camera preset");
            button.setOnAction(event -> camera.toPreset(preset));
            bar.getChildren().add(button);
        }
        return bar;
    }

    private Label addProjectedLabel(String text, String colorClass, double x, double y, double z,
            AnchorAlignment alignment) {
        Group anchor = new Group();
        anchor.setTranslateX(x);
        anchor.setTranslateY(y);
        anchor.setTranslateZ(z);
        world.getChildren().add(anchor);
        Label label = new Label(text);
        label.getStyleClass().addAll("world-label", "world-label-" + colorClass);
        label.setManaged(false);
        label.setMouseTransparent(true);
        labelOverlay.getChildren().add(label);
        projectedLabels.add(new ProjectedLabel(anchor, label, alignment));
        return label;
    }

    private void installPointerControls() {
        subScene.setCursor(Cursor.OPEN_HAND);
        subScene.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            dragging = false;
            pressX = event.getSceneX();
            pressY = event.getSceneY();
            pressedVt = pickedVts.get(event.getPickResult().getIntersectedNode());
            tooltip.setVisible(false);
            camera.beginDrag(pressX, pressY);
            subScene.setCursor(Cursor.CLOSED_HAND);
            subScene.requestFocus();
        });
        subScene.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (Math.hypot(event.getSceneX() - pressX, event.getSceneY() - pressY) > 4) dragging = true;
            if (dragging) camera.drag(event.getSceneX(), event.getSceneY());
        });
        subScene.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            ThreadView clicked = !dragging ? pressedVt : null;
            endDrag();
            pressedVt = null;
            if (clicked != null) follow(clicked);
        });
        subScene.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            endDrag();
            pressedVt = null;
            tooltip.setVisible(false);
        });
        subScene.setOnScroll(event -> {
            camera.zoom(event.getDeltaY());
            event.consume();
        });
        subScene.addEventHandler(ZoomEvent.ZOOM, event -> {
            camera.zoom(-Math.log(Math.max(0.01, event.getZoomFactor())) * 420);
            event.consume();
        });
        subScene.setOnMouseMoved(this::showTooltip);
    }

    private void endDrag() {
        dragging = false;
        subScene.setCursor(Cursor.OPEN_HAND);
    }

    private void showTooltip(MouseEvent event) {
        if (dragging) return;
        ThreadView vt = pickedVts.get(event.getPickResult().getIntersectedNode());
        if (vt == null) {
            tooltip.setVisible(false);
            return;
        }
        int carrierIndex = vt.carrierIndex();
        int progress = vt.work0() == 0 ? 0 : (int) Math.round((1 - vt.work() / vt.work0()) * 100);
        progress = Math.max(0, Math.min(100, progress));
        String text = "VT-" + vt.id() + " · " + vt.profile().display() + " · " + vt.state().display()
                + (carrierIndex >= 0 ? " on C" + (carrierIndex + 1) : "")
                + (vt.state() == Sim.VtState.RUNNING ? " · " + progress + "% done" : "")
                + (vt.state() == Sim.VtState.PARKED
                        ? vt.live() ? " · real I/O wait (planned "
                                + String.format(Locale.ROOT, "%.1f", vt.plannedIoSeconds()) + "s)"
                                : " · I/O " + String.format(Locale.ROOT, "%.1f", vt.io()) + "s left"
                        : vt.profile() == Sim.TaskProfile.IO_BOUND
                                ? " · I/O " + String.format(Locale.ROOT, "%.1f", vt.plannedIoSeconds()) + "s planned"
                                : "")
                + (vt.ioDevice() == null ? "" : " · " + vt.ioDevice().display().toLowerCase(Locale.ROOT));
        tooltip.setText(text);
        tooltip.autosize();
        tooltip.relocate(Math.min(getWidth() - tooltip.getWidth() - 4, event.getX() + 14),
                Math.min(getHeight() - tooltip.getHeight() - 4, event.getY() + 10));
        tooltip.setVisible(true);
        tooltip.toFront();
    }

    public void sync(double dt) {
        syncBoot();
        schedulerRing.setRotate(displayTime() * 1.5 * 180 / Math.PI);
        double schedulerRise = clamp((displayBootT() - 1.0) / 0.5, 0, 1);
        schedulerRing.setMaterial(schedulerRise > 0.6 ? schedulerBright : schedulerDim);
        syncCarriers(gearAnimationDelta());
        syncApplicationTaskIngress();
        syncQueuePressure(dt);
        camera.sync();
        syncParticles();
        syncHero();
        syncParkedBadges();
        syncTerminationEffects();
        syncFollowOverlay();
        syncComparisonOverlay();
        syncResourcePool();
        drawLessonOverlay();
        projectLabels();
    }

    private void syncBoot() {
        for (BootLayer layer : bootLayers) {
            double amount = clamp((displayBootT() - layer.order * 0.5) / 0.5, 0, 1);
            layer.group.setTranslateY(layer.restY - (1 - amount) * 18);
            if (layer.order == 4) heapGhost.setOpacity(amount * 0.48);
        }
    }

    private double gearAnimationDelta() {
        double now = displayTime();
        if (!Double.isFinite(lastGearDisplayTime)) {
            lastGearDisplayTime = now;
            return 0;
        }
        double delta = now - lastGearDisplayTime;
        lastGearDisplayTime = now;
        return delta > 0 && delta <= 0.25 ? delta : 0;
    }

    private void syncCarriers(double motionDt) {
        for (int i = 0; i < displayCarrierCount(); i++) {
            boolean pinned = displayCarrierPinned(i);
            boolean working = displayCarrierMounted(i);
            int heatLevel = (int) Math.round(clamp(displayCarrierHeat(i), 0, 1)
                    * (coreHeatMaterials.size() - 1));
            CpuCore core = cores.get(i);
            PhongMaterial coreMaterial = pinned ? pinnedCore : coreHeatMaterials.get(heatLevel);
            for (Shape3D surface : core.heatSurfaces()) surface.setMaterial(coreMaterial);
            slots.get(i).setMaterial(pinned ? pinnedSlot
                    : working ? activeSlot : idleSlot);
            double scale = pinned ? 1 + Math.sin(displayTime() * 8) * 0.08 : 1;
            slots.get(i).setScaleX(scale);
            slots.get(i).setScaleY(scale);
            slots.get(i).setScaleZ(scale);
            pinnedLabels.get(i).setVisible(pinned);

            CoreActivity activity = coreActivities.get(i);
            PhongMaterial activityMaterial = pinned ? coreActivityPinned
                    : working ? coreActivityActive : coreActivityIdle;
            for (Node node : activity.indicator().getChildren()) {
                if (node instanceof Shape3D shape) shape.setMaterial(activityMaterial);
            }
            activity.indicator().setOpacity(pinned || working ? 0.95 : 0.2);
            activity.piston().setScaleY(pinned ? 0.72 + 0.16 * Math.sin(displayTime() * 8 + i)
                    : working ? 0.72 + 0.28 * (0.5 + 0.5 * Math.sin(displayTime() * 11 + i))
                    : 0.18);
            core.gear().setScaleY(pinned
                    ? 1 + 0.08 * Math.sin(displayTime() * 8 + i)
                    : working ? 1 + 0.045 * Math.sin(displayTime() * 11 + i) : 1);
            core.gear().setTranslateY(6 + (working && !pinned
                    ? 0.45 * (0.5 + 0.5 * Math.sin(displayTime() * 11 + i)) : 0));
            GearMotion gearMotion = coreGearMotions.get(i);
            double targetVelocity = working && !pinned ? 82 + heatLevel * 13 : 0;
            double response = working && !pinned ? 4.5 : pinned ? 8 : 2.6;
            double velocityBlend = 1 - Math.exp(-motionDt * response);
            gearMotion.velocity += (targetVelocity - gearMotion.velocity) * velocityBlend;
            gearMotion.angle = (gearMotion.angle + gearMotion.velocity * motionDt) % 360;
            double stutterBlend = 1 - Math.exp(-motionDt * 7);
            gearMotion.stutter += ((pinned ? 1 : 0) - gearMotion.stutter) * stutterBlend;
            double pinnedOffset = gearMotion.stutter * Math.sin(displayTime() * 8 + i) * 7;
            core.gear().setRotate(gearMotion.angle + pinnedOffset);
            Cylinder pillar = carrierPillars.get(i);
            pillar.setMaterial(pinned ? coreActivityPinned
                    : working ? coreActivityActive : activeSlot);
            pillar.setOpacity(pinned || working ? 0.9 : 0.18);
            pillar.setScaleY(pinned ? 0.92 + 0.08 * Math.sin(displayTime() * 8 + i)
                    : working ? 0.94 + 0.06 * Math.sin(displayTime() * 11 + i) : 1);
            for (int sparkIndex = 0; sparkIndex < activity.sparks().size(); sparkIndex++) {
                Sphere spark = activity.sparks().get(sparkIndex);
                double sparkSpeed = pinned ? 0.55 : 1.75;
                double cycle = (displayTime() * sparkSpeed + sparkIndex / 3.0 + i * 0.13) % 1;
                spark.setTranslateX((sparkIndex - 1) * 2.1
                        + Math.sin(displayTime() * 4 + sparkIndex) * 0.55);
                spark.setTranslateY(4.5 + cycle * 13);
                spark.setTranslateZ(30 + Math.cos(displayTime() * 3 + sparkIndex) * 1.4);
                spark.setOpacity((1 - cycle) * (pinned ? 0.75 : 1));
                spark.setVisible(working || pinned);
            }
        }
    }

    private void syncApplicationTaskIngress() {
        double bootOpacity = clamp((displayBootT() - 2.4) / 0.6, 0, 1);
        applicationTaskIngress.setOpacity(bootOpacity);
        boolean flowing = displayVts().stream().anyMatch(vt -> vt.state() != Sim.VtState.DEAD);
        for (int i = 0; i < applicationTaskCards.size(); i++) {
            Group card = applicationTaskCards.get(i);
            double cycle = (displayTime() * 0.46 + i / (double) applicationTaskCards.size()) % 1;
            card.setTranslateY(94 - cycle * 10);
            card.setTranslateZ(Math.sin(displayTime() * 1.7 + i) * 2.2);
            card.setRotate(Math.sin(displayTime() * 2.3 + i) * 9);
            card.setOpacity(flowing ? 0.2 + Math.sin(Math.PI * cycle) * 0.8 : 0.18);
        }
    }

    private void syncQueuePressure(double dt) {
        int waiting = displayStats().runnable();
        int lanes = Math.max(1, displayCarrierCount());
        double target = clamp(waiting / (lanes * 8.0), 0, 1);
        displayedQueuePressure += (target - displayedQueuePressure) * Math.min(1, dt * 6);
        double scale = Math.max(0.025, displayedQueuePressure);
        queuePressureBar.setScaleX(scale);
        queuePressureBar.setTranslateX(-65 + 65 * scale);
        queuePressureBar.setScaleY(waiting > lanes
                ? 1 + 0.18 * Math.sin(displayTime() * 7) : 1);
        queuePressureBar.setVisible(displayBootT() >= 3 && waiting > 0);
        queuePressureBar.setOpacity(0.25 + displayedQueuePressure * 0.75);
        String pressure = waiting == 0 ? "EMPTY"
                : waiting > lanes ? waiting + " WAITING · BACKPRESSURE"
                : waiting + " READY";
        queuePressureLabel.setText("RUN QUEUE · " + pressure);
        queuePressureLabel.setVisible(waiting > 0);
        int chapter = displayChapter();
        externalIoVisible = displayFreeRun() || chapter == 2 || chapter == 3
                || chapter == 5 || chapter == 6 || chapter == 7 || chapter == 9;
        heapLabel.setVisible(externalIoVisible);
        for (Node node : externalIoNodes) node.setVisible(externalIoVisible);
        for (Label label : externalIoLabels) label.setVisible(externalIoVisible);
    }

    private void syncParticles() {
        pickedVts.clear();
        int index = 0;
        for (ThreadView vt : displayVts()) {
            if (index >= particles.size()) break;
            Sphere particle = particles.get(index);
            Sphere glow = glows.get(index);
            Sphere ioMarker = ioMarkers.get(index);
            Box stackChunk = stackChunks.get(index);
            Cylinder ioLink = ioLinks.get(index);
            Sphere ioSignal = ioSignals.get(index);
            VtColor color = colorFor(vt);
            double scale = scaleFor(vt);
            boolean terminating = vt.state() == Sim.VtState.DONE || vt.state() == Sim.VtState.DEAD;
            double dissolve = terminating ? clamp(vt.lifecycleAge(displayTime()) / 1.35, 0, 1) : 0;
            double opacity = terminating ? Math.pow(1 - dissolve, 1.4) : 1;
            setPosition(particle, vt);
            setPosition(glow, vt);
            particle.setScaleX(scale);
            particle.setScaleY(scale);
            particle.setScaleZ(scale);
            glow.setScaleX(scale * 1.15);
            glow.setScaleY(scale * 1.15);
            glow.setScaleZ(scale * 1.15);
            ioMarker.setTranslateX(vt.x() + 3.0 * scale);
            ioMarker.setTranslateY(vt.y() + 2.3 * scale
                    + Math.sin(displayTime() * 5 + vt.id()) * 0.7);
            ioMarker.setTranslateZ(vt.z());
            particle.setMaterial(materials.get(color));
            glow.setMaterial(glowMaterials.get(color));
            particle.setOpacity(opacity);
            glow.setOpacity(opacity * 0.75);
            particle.setVisible(true);
            boolean detail = effectiveHighQuality();
            boolean waiting = vt.state() == Sim.VtState.PARKING || vt.state() == Sim.VtState.PARKED;
            boolean important = vt.hero() || waiting
                    || vt == followedVt
                    || vt.state() == Sim.VtState.RUNNING || vt.state() == Sim.VtState.MOUNTING
                    || vt.carrierPinned();
            glow.setVisible(opacity > 0.02 && (detail || important));
            boolean ioBound = vt.profile() == Sim.TaskProfile.IO_BOUND;
            ioMarker.setVisible(!terminating && ioBound && !waiting && (detail || important || index % 4 == 0));

            boolean stackInTransit = waiting || vt.resumed() && switch (vt.state()) {
                case TO_QUEUE, QUEUED, MOUNTING -> true;
                default -> false;
            };
            stackChunk.setTranslateX(vt.x() - 0.8);
            stackChunk.setTranslateY(vt.y() - 3.2);
            stackChunk.setTranslateZ(vt.z() + 2.4);
            stackChunk.setRotate(Math.sin(displayTime() * 4 + vt.id()) * 12);
            stackChunk.setOpacity(waiting ? 1 : 0.72);
            stackChunk.setVisible(stackInTransit && (detail || important));

            Vec3 endpoint = vt.ioDevice() == null ? null : ioEndpoints.get(vt.ioDevice());
            boolean connected = externalIoVisible && waiting && endpoint != null;
            ioLink.setVisible(connected);
            ioSignal.setVisible(connected);
            if (connected) {
                ioLink.setMaterial(vt.resourcePermit() ? resourceLinkMaterial : ioLinkMaterial);
                ioSignal.setMaterial(materials.get(vt.resourcePermit() ? VtColor.GREEN : VtColor.PURPLE));
                setCylinderBetween(ioLink, vt.x(), vt.y(), vt.z(),
                        endpoint.x, endpoint.y, endpoint.z);
                ioLink.setOpacity(0.22 + 0.20 * (0.5 + 0.5 * Math.sin(displayTime() * 5 + vt.id())));
                double signalT = (displayTime() * 0.72 + vt.id() * 0.071) % 1.0;
                ioSignal.setTranslateX(lerp(vt.x(), endpoint.x, signalT));
                ioSignal.setTranslateY(lerp(vt.y(), endpoint.y, signalT)
                        + Math.sin(Math.PI * signalT) * 6);
                ioSignal.setTranslateZ(lerp(vt.z(), endpoint.z, signalT));
            }
            pickedVts.put(particle, vt);
            index++;
        }
        for (int i = index; i < particles.size(); i++) {
            particles.get(i).setVisible(false);
            particles.get(i).setOpacity(1);
            glows.get(i).setVisible(false);
            glows.get(i).setOpacity(1);
            ioMarkers.get(i).setVisible(false);
            stackChunks.get(i).setVisible(false);
            ioLinks.get(i).setVisible(false);
            ioSignals.get(i).setVisible(false);
        }
    }

    private VtColor colorFor(ThreadView vt) {
        if (vt.state() == Sim.VtState.DONE || vt.state() == Sim.VtState.DEAD) {
            return switch (vt.outcome()) {
                case FAILED -> VtColor.RED;
                case CANCELLED -> VtColor.AMBER;
                default -> VtColor.WHITE;
            };
        }
        return switch (vt.state()) {
            case TO_QUEUE, QUEUED -> VtColor.GREEN;
            case MOUNTING, RUNNING -> vt.carrierPinned()
                    ? VtColor.RED : VtColor.BLUE;
            case PARKING, PARKED -> VtColor.PURPLE;
            case DONE, DEAD -> VtColor.WHITE;
        };
    }

    private double scaleFor(ThreadView vt) {
        double scale = switch (vt.state()) {
            case RUNNING -> 1.5 + 0.12 * Math.sin(displayTime() * 7 + vt.id());
            case MOUNTING -> 1.5;
            case PARKING, PARKED -> 1.5 + 0.08 * Math.sin(displayTime() * 5 + vt.id());
            case DONE, DEAD -> 1.35 - clamp(vt.lifecycleAge(displayTime()) / 1.35, 0, 1) * 0.85;
            default -> 1;
        };
        if (vt.hero()) scale *= 1.45;
        if (vt == followedVt) {
            scale *= 1.85 + 0.12 * Math.sin(displayTime() * 12);
        }
        return scale;
    }

    private void syncHero() {
        ThreadView hero = displayHero();
        if (hero == null) {
            trail.forEach(node -> node.setVisible(false));
            heroLabel.setVisible(false);
            heroId = -1;
            return;
        }
        if (hero.id() != heroId) {
            heroId = hero.id();
            for (Sphere dot : trail) dot.setTranslateY(-999);
            heroLabel.setText("VT-" + hero.id());
        }
        for (int i = 0; i < trail.size() - 1; i++) {
            Sphere current = trail.get(i);
            Sphere next = trail.get(i + 1);
            current.setTranslateX(next.getTranslateX());
            current.setTranslateY(next.getTranslateY());
            current.setTranslateZ(next.getTranslateZ());
            current.setVisible(true);
        }
        Sphere head = trail.getLast();
        setPosition(head, hero);
        head.setVisible(true);

        ProjectedLabel heroProjection = projectedLabels.getLast();
        heroProjection.anchor.setTranslateX(hero.x());
        heroProjection.anchor.setTranslateY(hero.y() + 8);
        heroProjection.anchor.setTranslateZ(hero.z());
        // A followed hero already has the contextual parked badge/lifecycle card.
        heroLabel.setVisible(hero != followedVt);
    }

    private void syncParkedBadges() {
        List<ThreadView> labelled = new ArrayList<>(PARKED_BADGE_LIMIT);
        if (followedVt != null && followedVt.state() == Sim.VtState.PARKED) {
            labelled.add(followedVt);
        }
        int contextualLabels = 0;
        for (ThreadView vt : displayVts()) {
            if (vt.state() != Sim.VtState.PARKED || vt == followedVt || vt.hero()) continue;
            labelled.add(vt);
            if (++contextualLabels >= PARKED_BADGE_LIMIT - 1) break;
        }

        int badgeIndex = 0;
        for (ThreadView vt : labelled) {
            Label badge = parkedBadges.get(badgeIndex++);
            Point3D scenePoint = world.localToScene(vt.x(), vt.y() + 5.4, vt.z(), true);
            Point3D local = labelOverlay.sceneToLocal(scenePoint);
            if (!Double.isFinite(local.getX()) || !Double.isFinite(local.getY())) {
                badge.setVisible(false);
                continue;
            }
            badge.setText("VT-" + vt.id());
            badge.setAccessibleText("Virtual thread " + vt.id() + " parked for I/O in the heap area");
            badge.autosize();
            badge.relocate(clamp(local.getX() - badge.getWidth() / 2, 3,
                            Math.max(3, labelOverlay.getWidth() - badge.getWidth() - 3)),
                    clamp(local.getY() - badge.getHeight(), 3,
                            Math.max(3, labelOverlay.getHeight() - badge.getHeight() - 3)));
            badge.setVisible(true);
        }
        for (int i = badgeIndex; i < parkedBadges.size(); i++) parkedBadges.get(i).setVisible(false);
        int parkedTotal = displayStats().parked();
        heapLabel.setText("HEAP · " + parkedTotal + " PARKED STACK CHUNK" + (parkedTotal == 1 ? "" : "S")
                + " · I/O EXTERNAL");
    }

    private void syncTerminationEffects() {
        GraphicsContext graphics = terminationCanvas.getGraphicsContext2D();
        graphics.clearRect(0, 0, terminationCanvas.getWidth(), terminationCanvas.getHeight());
        for (ThreadView vt : displayVts()) {
            if (vt.state() != Sim.VtState.DONE) continue;
            double progress = clamp(vt.lifecycleAge(displayTime()) / 1.35, 0, 1);
            Point3D scenePoint = world.localToScene(vt.x(), vt.y(), vt.z(), true);
            Point3D local = terminationCanvas.sceneToLocal(scenePoint);
            if (!Double.isFinite(local.getX()) || !Double.isFinite(local.getY())) continue;
            double alpha = Math.pow(1 - progress, 1.3);
            double radius = 3 + progress * 25;
            double size = 0.8 + (1 - progress) * 2.2;
            Color sparkColor = switch (vt.outcome()) {
                case FAILED -> RED;
                case CANCELLED -> AMBER;
                default -> WHITE;
            };
            graphics.setFill(new Color(sparkColor.getRed(), sparkColor.getGreen(),
                    sparkColor.getBlue(), alpha));
            for (int spark = 0; spark < 10; spark++) {
                double angle = vt.id() * 0.73 + spark * Math.PI * 2 / 10;
                double stagger = 0.45 + (spark % 4) * 0.18;
                double x = local.getX() + Math.cos(angle) * radius * stagger;
                double y = local.getY() + Math.sin(angle) * radius * stagger - progress * 7;
                graphics.fillOval(x - size / 2, y - size / 2, size, size);
            }
        }
    }

    private void syncFollowOverlay() {
        if (followedVt == null) {
            followOverlay.setVisible(false);
            return;
        }
        ThreadView vt = followedVt;
        followOverlay.setVisible(true);
        followTitle.setText("FOLLOWING VT-" + vt.id() + " · " + vt.profile().display());
        int index = 0;
        for (Sim.LifecyclePhase phase : Sim.LifecyclePhase.values()) {
            Label label = followDurations[index++];
            label.setText(phase.display() + "\n"
                    + String.format(Locale.ROOT, "%.1fs", vt.lifecycleSeconds(phase, displayTime())));
            label.getStyleClass().remove("follow-current");
            if (phase == vt.lifecyclePhase()) label.getStyleClass().add("follow-current");
        }
        String detail = switch (vt.lifecyclePhase()) {
            case RUNNABLE -> "Waiting in the scheduler run queue";
            case MOUNTED -> vt.carrierIndex() < 0 ? "Moving onto a carrier"
                    : "Executing on carrier C" + (vt.carrierIndex() + 1)
                            + (vt.carrierPinned() ? " · PINNED" : "");
            case PARKED -> "Stack chunks stored in heap · waiting on "
                    + (vt.ioDevice() == null ? "external I/O" : vt.ioDevice().display())
                    + (vt.live() ? "" : " · " + String.format(Locale.ROOT, "%.1fs left", Math.max(0, vt.io())));
            case TERMINATED -> (vt.outcome() == Sim.Outcome.FAILED ? "Failed"
                    : vt.outcome() == Sim.Outcome.CANCELLED ? "Cancelled by parent scope" : "Completed")
                    + (vt.state() == Sim.VtState.DONE
                            ? " · carrier released · dissolving" : " · dissolved");
        };
        followStatus.setText(vt.lifecyclePhase().display() + " · " + detail);
        followOverlay.setAccessibleText(followTitle.getText() + ". " + followStatus.getText());
    }

    private void syncComparisonOverlay() {
        boolean parkMoment = displayChapter() == 2;
        boolean pinMoment = displayChapter() == 4;
        comparisonOverlay.setVisible(parkMoment || pinMoment);
        comparisonParkCard.getStyleClass().remove("comparison-active");
        comparisonPinCard.getStyleClass().remove("comparison-active");
        if (parkMoment) comparisonParkCard.getStyleClass().add("comparison-active");
        if (pinMoment) comparisonPinCard.getStyleClass().add("comparison-active");
        int pinned = 0;
        for (int i = 0; i < displayCarrierCount(); i++) if (displayCarrierPinned(i)) pinned++;
        int waiting = displayStats().runnable();
        comparisonParkValue.setText("carrier released\n" + displayStats().parked()
                + " stack" + (displayStats().parked() == 1 ? "" : "s") + " in heap · " + waiting + " queued");
        comparisonPinValue.setText("carrier retained\n" + pinned
                + " lane" + (pinned == 1 ? "" : "s") + " blocked · " + waiting + " queued");
    }

    private void syncResourcePool() {
        boolean visible = displayScenario() == Sim.Scenario.RESOURCE_POOL;
        Sim.ResourcePoolStats pool = displayResourcePoolStats();
        for (int i = 0; i < databasePermitSlots.size(); i++) {
            Box slot = databasePermitSlots.get(i);
            slot.setVisible(visible && i < pool.capacity());
            slot.setMaterial(i < pool.inUse() ? resourceActiveMaterial : resourceIdleMaterial);
            slot.setScaleY(i < pool.inUse() ? 1.0 + 0.12 * Math.sin(displayTime() * 7 + i) : 1.0);
        }
        databasePoolLabel.setVisible(visible);
        databasePoolLabel.setText("DB CONNECTION POOL · " + pool.inUse() + "/" + pool.capacity()
                + " IN USE · " + pool.waiting() + " WAITING");
    }

    private void drawLessonOverlay() {
        Sim.Scenario scenario = displayScenario();
        lessonCanvas.setVisible(scenario != Sim.Scenario.NONE);
        if (scenario == Sim.Scenario.NONE) return;
        GraphicsContext graphics = lessonCanvas.getGraphicsContext2D();
        double width = lessonCanvas.getWidth();
        double height = lessonCanvas.getHeight();
        graphics.clearRect(0, 0, width, height);
        graphics.setFill(Color.web("#08111d", 0.94));
        graphics.fillRoundRect(0, 0, width, height, 18, 18);
        graphics.setStroke(Color.web("#26364a", 0.95));
        graphics.setLineWidth(1.2);
        graphics.strokeRoundRect(0.6, 0.6, width - 1.2, height - 1.2, 18, 18);
        switch (scenario) {
            case PLATFORM_COMPARISON -> drawPlatformComparison(graphics);
            case RESOURCE_POOL -> drawResourceLimit(graphics);
            case CPU_BOUND -> drawCpuPlateau(graphics);
            case STRUCTURED -> drawStructuredScopes(graphics);
            case NONE -> { }
        }
    }

    private void drawPlatformComparison(GraphicsContext graphics) {
        int tasks = displayScenarioSubmitted();
        lessonText(graphics, "SAME BLOCKING I/O WORKLOAD · " + tasks + " TASKS", 18, 22, WHITE, 11, true);
        graphics.setStroke(Color.web("#26364a"));
        graphics.strokeLine(340, 34, 340, 151);

        lessonText(graphics, "PLATFORM THREAD PER TASK", 18, 46, AMBER, 10, true);
        drawDotGrid(graphics, Math.min(tasks, 30), 18, 61, 10, 17, AMBER, true);
        lessonText(graphics, tasks + " PLATFORM THREADS", 18, 130, WHITE, 11, true);
        lessonText(graphics, tasks + " OS THREADS HELD WHILE BLOCKED", 18, 148, Color.web("#8ea2b8"), 9, false);

        lessonText(graphics, "VIRTUAL THREAD PER TASK", 360, 46, PURPLE, 10, true);
        drawDotGrid(graphics, Math.min(tasks, 30), 360, 61, 10, 17, PURPLE, false);
        lessonText(graphics, tasks + " VTs · " + displayCarrierCount() + " CARRIER THREADS", 360, 130, WHITE, 11, true);
        lessonText(graphics, displayStats().parked() + " PARKED · CARRIERS REUSED", 360, 148, GREEN, 9, false);
        lessonCanvas.setAccessibleText("Platform versus virtual threads. The same " + tasks
                + " blocking tasks use " + tasks + " platform OS threads, or " + displayCarrierCount()
                + " virtual-thread carriers with " + displayStats().parked() + " tasks currently parked.");
    }

    private void drawResourceLimit(GraphicsContext graphics) {
        Sim.ResourcePoolStats pool = displayResourcePoolStats();
        lessonText(graphics, "DOWNSTREAM BOTTLENECK · DATABASE CONNECTIONS", 18, 22, PURPLE, 11, true);
        lessonText(graphics, "PARKED WAITERS", 18, 49, Color.web("#b6c6d8"), 9, true);
        drawDotGrid(graphics, Math.min(pool.waiting(), 20), 18, 64, 10, 16, PURPLE, false);
        lessonText(graphics, pool.waiting() + " VTs WAIT WITHOUT CARRIERS", 18, 127, WHITE, 10, true);

        graphics.setStroke(Color.web("#53657a"));
        graphics.setLineWidth(2);
        graphics.strokeLine(212, 88, 290, 88);
        graphics.strokeLine(422, 88, 500, 88);
        lessonText(graphics, "ACQUIRE", 227, 79, Color.web("#8ea2b8"), 8, false);
        lessonText(graphics, "I/O", 455, 79, Color.web("#8ea2b8"), 8, false);

        lessonText(graphics, "CONNECTION POOL", 292, 49, GREEN, 9, true);
        for (int permit = 0; permit < pool.capacity(); permit++) {
            double x = 300 + permit * 39;
            graphics.setFill(permit < pool.inUse() ? GREEN : Color.web("#26364a"));
            graphics.fillRoundRect(x, 67, 28, 42, 7, 7);
            lessonText(graphics, "P" + (permit + 1), x + 7, 93,
                    permit < pool.inUse() ? Color.web("#07110d") : Color.web("#8ea2b8"), 9, true);
        }
        lessonText(graphics, pool.inUse() + "/" + pool.capacity() + " IN USE", 315, 127, WHITE, 10, true);

        graphics.setFill(Color.web("#4f46a5"));
        graphics.fillOval(527, 62, 92, 22);
        graphics.fillRect(527, 73, 92, 43);
        graphics.fillOval(527, 104, 92, 22);
        graphics.setStroke(PURPLE);
        graphics.strokeOval(527, 62, 92, 22);
        graphics.strokeOval(527, 82, 92, 22);
        graphics.strokeOval(527, 104, 92, 22);
        lessonText(graphics, "DATABASE", 543, 96, WHITE, 10, true);
        lessonText(graphics, "LIMIT = " + pool.capacity() + "", 542, 145, AMBER, 10, true);
        lessonCanvas.setAccessibleText("Database connection pool. " + pool.inUse() + " of "
                + pool.capacity() + " permits are in use and " + pool.waiting()
                + " virtual threads are parked waiting for a permit.");
    }

    private void drawCpuPlateau(GraphicsContext graphics) {
        int carriers = displayCarrierCount();
        int load = displayStats().mounted() + displayStats().runnable();
        lessonText(graphics, "CPU-BOUND WORK · PARALLELISM HAS A HARD CEILING", 18, 22, BLUE, 11, true);
        graphics.setStroke(Color.web("#53657a"));
        graphics.setLineWidth(1);
        graphics.strokeLine(42, 130, 344, 130);
        graphics.strokeLine(42, 48, 42, 130);
        lessonText(graphics, "THROUGHPUT", 48, 48, Color.web("#8ea2b8"), 8, false);
        lessonText(graphics, "RUNNABLE VTs →", 252, 146, Color.web("#8ea2b8"), 8, false);
        graphics.setStroke(BLUE);
        graphics.setLineWidth(3);
        graphics.beginPath();
        graphics.moveTo(48, 124);
        graphics.quadraticCurveTo(118, 60, 174, 60);
        graphics.lineTo(332, 60);
        graphics.stroke();
        double markerX = 48 + Math.min(284, load * 7.0);
        double markerY = markerX < 174 ? 124 - (markerX - 48) * 64 / 126 : 60;
        graphics.setFill(WHITE);
        graphics.fillOval(markerX - 4, markerY - 4, 8, 8);
        graphics.setStroke(Color.web("#60a5fa", 0.35));
        graphics.strokeLine(174, 54, 174, 132);
        lessonText(graphics, "PLATEAU", 183, 76, BLUE, 9, true);

        lessonText(graphics, carriers + " CARRIERS / CPU LANES", 395, 49, AMBER, 10, true);
        for (int i = 0; i < carriers; i++) {
            int column = i % 5;
            int row = i / 5;
            double x = 398 + column * 48;
            double y = 65 + row * 38;
            graphics.setFill(i < displayStats().mounted() ? AMBER : Color.web("#26364a"));
            graphics.fillRoundRect(x, y, 36, 25, 6, 6);
            lessonText(graphics, "C" + (i + 1), x + 10, y + 17,
                    i < displayStats().mounted() ? Color.web("#1c1508") : Color.web("#8ea2b8"), 9, true);
        }
        lessonText(graphics, displayStats().runnable() + " QUEUED · "
                + Math.round(displayCarrierUtilization() * 100) + "% UTILIZED", 395, 146, GREEN, 10, true);
        lessonCanvas.setAccessibleText("CPU-bound workload. " + displayStats().mounted() + " of " + carriers
                + " carriers are busy and " + displayStats().runnable()
                + " tasks are queued. Throughput plateaus at the carrier and core count.");
    }

    private void drawStructuredScopes(GraphicsContext graphics) {
        lessonText(graphics, "STRUCTURED CONCURRENCY · FORK → CONTAIN → JOIN", 18, 22, GREEN, 11, true);
        List<Sim.ScopeStats> scopes = displayStructuredScopes();
        for (int scopeIndex = 0; scopeIndex < scopes.size(); scopeIndex++) {
            Sim.ScopeStats scope = scopes.get(scopeIndex);
            double left = 18 + scopeIndex * 220;
            Color scopeColor = scope.failed() > 0 ? RED : scope.joined() ? GREEN : BLUE;
            graphics.setStroke(Color.web("#26364a"));
            graphics.strokeRoundRect(left, 37, 202, 113, 12, 12);
            graphics.setFill(Color.web("#0d1826"));
            graphics.fillRoundRect(left + 1, 38, 200, 111, 12, 12);
            graphics.setStroke(scopeColor);
            graphics.strokeLine(left + 101, 69, left + 101, 89);
            graphics.strokeLine(left + 42, 89, left + 160, 89);
            lessonText(graphics, scope.name() + " SCOPE", left + 12, 58, scopeColor, 10, true);
            for (int child = 0; child < scope.total(); child++) {
                double x = left + 42 + child * 39;
                graphics.strokeLine(x, 89, x, 99);
                graphics.setFill(scopeChildColor(scope, child));
                graphics.fillOval(x - 7, 98, 14, 14);
            }
            String state = scope.joined()
                    ? scope.failed() > 0 ? "JOINED · FAILURE CONTAINED" : "JOINED · SUCCESS"
                    : "WAITING FOR " + scope.active() + " CHILD" + (scope.active() == 1 ? "" : "REN");
            lessonText(graphics, state, left + 12, 136, scopeColor, 8, true);
        }
        lessonCanvas.setAccessibleText("Structured concurrency with three scopes. " + scopes.stream()
                .map(scope -> scope.name() + ": " + scope.succeeded() + " succeeded, " + scope.failed()
                        + " failed, " + scope.cancelled() + " cancelled")
                .reduce((left, right) -> left + "; " + right).orElse("starting"));
    }

    private static Color scopeChildColor(Sim.ScopeStats scope, int child) {
        if (child < scope.succeeded()) return GREEN;
        if (child < scope.succeeded() + scope.failed()) return RED;
        if (child < scope.succeeded() + scope.failed() + scope.cancelled()) return AMBER;
        return BLUE;
    }

    private static void drawDotGrid(GraphicsContext graphics, int count, double x, double y,
            int columns, double spacing, Color color, boolean square) {
        graphics.setFill(color);
        for (int i = 0; i < count; i++) {
            double dotX = x + i % columns * spacing;
            double dotY = y + i / columns * spacing;
            if (square) graphics.fillRoundRect(dotX, dotY, 9, 9, 2, 2);
            else graphics.fillOval(dotX, dotY, 9, 9);
        }
    }

    private static void lessonText(GraphicsContext graphics, String text, double x, double y,
            Color color, double size, boolean bold) {
        graphics.setTextAlign(TextAlignment.LEFT);
        graphics.setFill(color);
        graphics.setFont(Font.font("IBM Plex Mono",
                bold ? javafx.scene.text.FontWeight.SEMI_BOLD : javafx.scene.text.FontWeight.NORMAL, size));
        graphics.fillText(text, x, y);
    }

    private void projectLabels() {
        for (ProjectedLabel projection : projectedLabels) {
            Label label = projection.label;
            if (!label.isVisible()) continue;
            Point3D scenePoint = projection.anchor.localToScene(0, 0, 0, true);
            Point3D local = labelOverlay.sceneToLocal(scenePoint);
            if (!Double.isFinite(local.getX()) || !Double.isFinite(local.getY())) {
                continue;
            }
            label.autosize();
            double x = projection.alignment == AnchorAlignment.RIGHT
                    ? local.getX() - label.getWidth() : local.getX() - label.getWidth() / 2;
            double clampedX = clamp(x, 3, Math.max(3, labelOverlay.getWidth() - label.getWidth() - 3));
            double clampedY = clamp(local.getY() - label.getHeight() / 2, 3,
                    Math.max(3, labelOverlay.getHeight() - label.getHeight() - 3));
            label.relocate(clampedX, clampedY);
        }
    }

    /** Supplies measured renderer performance and drives AUTO quality hysteresis. */
    public void setPerformance(double fps, double frameMillis) {
        if (requestedQuality == Quality.AUTO) {
            if (!autoLow && (fps < 48 || frameMillis > 22 || displayVts().size() > 320)) autoLow = true;
            else if (autoLow && fps > 56 && frameMillis < 18 && displayVts().size() < 260) autoLow = false;
        }
        diagnostics.setText("%.0f FPS · %.1f ms · %s".formatted(fps, frameMillis, qualityLabel()));
    }

    public String cycleQuality() {
        requestedQuality = switch (requestedQuality) {
            case AUTO -> Quality.HIGH;
            case HIGH -> Quality.LOW;
            case LOW -> Quality.AUTO;
        };
        return qualityLabel();
    }

    public String qualityLabel() {
        return requestedQuality == Quality.AUTO ? "AUTO/" + (autoLow ? "LOW" : "HIGH") : requestedQuality.name();
    }

    private boolean effectiveHighQuality() {
        return requestedQuality == Quality.HIGH || requestedQuality == Quality.AUTO && !autoLow;
    }

    public void highlightVt(long id) {
        for (ThreadView vt : displayVts()) {
            if (vt.id() == id) {
                follow(vt);
                break;
            }
        }
        tooltip.setVisible(false);
    }

    public boolean clearFollow() {
        if (followedId < 0) return false;
        followedVt = null;
        followedId = -1;
        followOverlay.setVisible(false);
        return true;
    }

    private void follow(ThreadView vt) {
        followedVt = vt;
        followedId = vt.id();
        followOverlay.setVisible(true);
        tooltip.setVisible(false);
    }

    public void setReplayFrame(ReplayFrame frame) {
        replayFrame = frame;
        rebindFollow();
    }

    public void clearReplayFrame() {
        replayFrame = null;
        rebindFollow();
    }

    private void rebindFollow() {
        if (followedId < 0) return;
        followedVt = displayVts().stream().filter(vt -> vt.id() == followedId).findFirst().orElse(null);
    }

    public void setPresenterMode(boolean presenter) {
        cameraButtons.setVisible(!presenter);
        cameraButtons.setManaged(!presenter);
        shortcut.setVisible(!presenter);
        shortcut.setManaged(!presenter);
    }

    public void setHighContrast(boolean enabled) {
        highContrast = enabled;
        getStyleClass().remove("high-contrast-machine");
        if (enabled) getStyleClass().add("high-contrast-machine");
        subScene.setFill(Color.web(enabled ? "#000000" : "#070b12"));
        setStyle("-fx-background-color: " + (enabled ? "#000000" : "#070b12") + ";");
        if (enabled) autoLow = true;
    }

    public boolean highContrast() { return highContrast; }

    private double displayTime() { return replayFrame == null ? sim.time() : replayFrame.time(); }
    private double displayBootT() { return replayFrame == null ? sim.bootT() : replayFrame.bootT(); }
    private int displayChapter() { return replayFrame == null ? sim.chapter() : replayFrame.chapter(); }
    private boolean displayFreeRun() { return replayFrame == null ? sim.freeRun() : replayFrame.freeRun(); }
    private Sim.Stats displayStats() { return replayFrame == null ? sim.stats() : replayFrame.stats(); }
    private Sim.Scenario displayScenario() {
        return replayFrame == null ? sim.scenario() : replayFrame.scenario();
    }
    private int displayScenarioSubmitted() {
        return replayFrame == null ? sim.scenarioSubmitted() : replayFrame.scenarioSubmitted();
    }
    private double displayCarrierUtilization() {
        return replayFrame == null ? sim.carrierUtilization() : replayFrame.carrierUtilization();
    }
    private Sim.ResourcePoolStats displayResourcePoolStats() {
        return replayFrame == null ? sim.resourcePoolStats() : replayFrame.resourcePoolStats();
    }
    private List<Sim.ScopeStats> displayStructuredScopes() {
        return replayFrame == null ? sim.structuredScopes() : replayFrame.structuredScopes();
    }
    private List<? extends ThreadView> displayVts() {
        return replayFrame == null ? sim.vts() : replayFrame.vts();
    }
    private ThreadView displayHero() {
        return displayVts().stream().filter(ThreadView::hero).findFirst().orElse(null);
    }
    private int displayCarrierCount() {
        return replayFrame == null ? sim.carriers().size() : replayFrame.carriers().size();
    }
    private boolean displayCarrierPinned(int index) {
        return replayFrame == null ? sim.carriers().get(index).pinned()
                : replayFrame.carriers().get(index).pinned();
    }
    private double displayCarrierHeat(int index) {
        return replayFrame == null ? sim.carriers().get(index).heat()
                : replayFrame.carriers().get(index).heat();
    }
    private boolean displayCarrierMounted(int index) {
        return replayFrame == null ? sim.carriers().get(index).mounted() != null
                : replayFrame.carriers().get(index).mountedVtId() >= 0;
    }

    public void cameraPreset(CameraRig.Preset preset) {
        camera.toPreset(preset);
    }

    public void cameraForChapter(int chapter) {
        camera.toPreset(switch (Math.floorMod(chapter, Sim.CHAPTER_COUNT)) {
            case 0, 5, 6, 8, 9 -> CameraRig.Preset.OVERVIEW;
            case 2, 7 -> CameraRig.Preset.HEAP;
            default -> CameraRig.Preset.CARRIERS;
        });
    }

    private static void setPosition(Node node, ThreadView thread) {
        node.setTranslateX(thread.x());
        node.setTranslateY(thread.y());
        node.setTranslateZ(thread.z());
    }

    private static void setCylinderBetween(Cylinder cylinder,
            double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        Point3D from = new Point3D(fromX, fromY, fromZ);
        Point3D to = new Point3D(toX, toY, toZ);
        Point3D delta = to.subtract(from);
        double length = delta.magnitude();
        if (length < 0.001) return;
        Point3D midpoint = from.midpoint(to);
        cylinder.setHeight(length);
        cylinder.setTranslateX(midpoint.getX());
        cylinder.setTranslateY(midpoint.getY());
        cylinder.setTranslateZ(midpoint.getZ());
        Point3D yAxis = new Point3D(0, 1, 0);
        Point3D axis = yAxis.crossProduct(delta);
        double cosine = clamp(yAxis.dotProduct(delta) / length, -1, 1);
        cylinder.getTransforms().clear();
        if (axis.magnitude() > 0.0001) {
            cylinder.getTransforms().add(new Rotate(Math.toDegrees(Math.acos(cosine)), axis));
        } else if (cosine < 0) {
            cylinder.getTransforms().add(new Rotate(180, Rotate.X_AXIS));
        }
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private static PhongMaterial material(Color color) {
        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(color.brighter());
        return material;
    }

    private static PhongMaterial transparentMaterial(Color color, double opacity) {
        Color translucent = new Color(color.getRed(), color.getGreen(), color.getBlue(), opacity);
        PhongMaterial material = new PhongMaterial(translucent);
        material.setSpecularColor(translucent);
        return material;
    }

    private static PhongMaterial glowMaterial(Color color) {
        PhongMaterial material = transparentMaterial(color, 0.22);
        material.setSelfIlluminationMap(null);
        return material;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
