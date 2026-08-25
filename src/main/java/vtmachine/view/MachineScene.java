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
import javafx.scene.Cursor;
import javafx.scene.DepthTest;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.Shape3D;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import vtmachine.model.Carrier;
import vtmachine.model.Sim;
import vtmachine.model.Vec3;
import vtmachine.model.Vt;

/** The complete 3D machine, its projected labels, tooltip, and camera controls. */
public final class MachineScene extends StackPane {
    private static final Color GREEN = Color.web("#34d399");
    private static final Color BLUE = Color.web("#60a5fa");
    private static final Color PURPLE = Color.web("#a78bfa");
    private static final Color AMBER = Color.web("#f5b84c");
    private static final Color RED = Color.web("#f87171");
    private static final Color WHITE = Color.web("#e6edf3");

    private enum VtColor { GREEN, BLUE, PURPLE, RED, WHITE }
    private enum AnchorAlignment { CENTER, RIGHT }
    private record ProjectedLabel(Group anchor, Label label, AnchorAlignment alignment) {}
    private record BootLayer(Group group, double restY, int order) {}

    private final Sim sim;
    private final Group root3d = new Group();
    private final Group world = new Group();
    private final SubScene subScene;
    private final Pane labelOverlay = new Pane();
    private final CameraRig camera = new CameraRig();
    private final Label tooltip = new Label();

    private final List<BootLayer> bootLayers = new ArrayList<>();
    private final List<ProjectedLabel> projectedLabels = new ArrayList<>();
    private final List<Shape3D> cores = new ArrayList<>();
    private final List<TorusMesh> slots = new ArrayList<>();
    private final List<Label> pinnedLabels = new ArrayList<>();
    private final List<Sphere> particles = new ArrayList<>();
    private final List<Sphere> glows = new ArrayList<>();
    private final List<Sphere> trail = new ArrayList<>();
    private final Map<Node, Vt> pickedVts = new IdentityHashMap<>();
    private final EnumMap<VtColor, PhongMaterial> materials = new EnumMap<>(VtColor.class);
    private final EnumMap<VtColor, PhongMaterial> glowMaterials = new EnumMap<>(VtColor.class);
    private final List<PhongMaterial> coreHeatMaterials = new ArrayList<>();

    private final PhongMaterial idleSlot = material(Color.web("#24425f"));
    private final PhongMaterial activeSlot = material(BLUE);
    private final PhongMaterial pinnedSlot = material(RED);
    private final PhongMaterial pinnedCore = material(Color.web("#8f2f31"));
    private final PhongMaterial schedulerBright = material(PURPLE);
    private final PhongMaterial schedulerDim = material(Color.web("#2a2145"));

    private TorusMesh schedulerRing;
    private Shape3D heapGhost;
    private Label heroLabel;
    private long heroId = -1;
    private boolean dragging;

    public MachineScene(Sim sim) {
        this.sim = sim;
        setMinSize(0, 0);
        setStyle("-fx-background-color: #070b12;");
        root3d.setDepthTest(DepthTest.ENABLE);
        world.getTransforms().add(new Scale(1, -1, 1));
        root3d.getChildren().add(world);

        subScene = new SubScene(root3d, 960, 760, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.web("#070b12"));
        subScene.setCamera(camera.camera());
        subScene.widthProperty().bind(widthProperty());
        subScene.heightProperty().bind(heightProperty());

        labelOverlay.setMouseTransparent(true);
        tooltip.getStyleClass().add("vt-tooltip");
        tooltip.setManaged(false);
        tooltip.setVisible(false);
        labelOverlay.getChildren().add(tooltip);

        initialiseMaterials();
        buildLights();
        buildStatics();
        buildPool();
        buildTrail();

        HBox cameraButtons = buildCameraButtons();
        Label shortcut = new Label("SPACE play/pause · ← → chapters · 1–4 cameras · drag to orbit · scroll to zoom");
        shortcut.getStyleClass().add("shortcut-hint");
        shortcut.setMouseTransparent(true);

        getChildren().addAll(subScene, labelOverlay, cameraButtons, shortcut);
        StackPane.setAlignment(cameraButtons, Pos.TOP_RIGHT);
        StackPane.setMargin(cameraButtons, new Insets(14, 16, 0, 0));
        StackPane.setAlignment(shortcut, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(shortcut, new Insets(0, 16, 16, 0));

        installPointerControls();
    }

    private void initialiseMaterials() {
        materials.put(VtColor.GREEN, material(GREEN));
        materials.put(VtColor.BLUE, material(BLUE));
        materials.put(VtColor.PURPLE, material(PURPLE));
        materials.put(VtColor.RED, material(RED));
        materials.put(VtColor.WHITE, material(WHITE));
        glowMaterials.put(VtColor.GREEN, glowMaterial(GREEN));
        glowMaterials.put(VtColor.BLUE, glowMaterial(BLUE));
        glowMaterials.put(VtColor.PURPLE, glowMaterial(PURPLE));
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
        Box ghost = new Box(36, 44, 36);
        ghost.setTranslateY(24);
        ghost.setMaterial(transparentMaterial(PURPLE, 0.07));
        ghost.setCullFace(CullFace.NONE);
        heapGhost = ghost;
        heap.getChildren().add(ghost);
        world.getChildren().add(heap);
        bootLayers.add(new BootLayer(heap, 30, 4));

        for (int i = 0; i < sim.carriers().size(); i++) buildLane(i);

        schedulerRing = new TorusMesh(14, 0.9f, 48, 10);
        schedulerRing.setMaterial(schedulerDim);
        schedulerRing.setTranslateY(56);
        schedulerRing.setRotationAxis(Rotate.Y_AXIS);
        world.getChildren().add(schedulerRing);

        addProjectedLabel("OS THREADS / CPU CORES", "amber", -81, 7, 0, AnchorAlignment.RIGHT);
        addProjectedLabel("CARRIER THREADS", "blue", -81, 33, 0, AnchorAlignment.RIGHT);
        addProjectedLabel("SCHEDULER · ForkJoinPool", "purple", -81, 59, 0, AnchorAlignment.RIGHT);
        addProjectedLabel("VIRTUAL THREADS · runnable", "green", -91, 85, 0, AnchorAlignment.RIGHT);
        addProjectedLabel("CONTINUATION SNAPSHOTS · heap", "purple", 118, 74, 0, AnchorAlignment.CENTER);
        addProjectedLabel("APPLICATION TASKS ↓", "muted", -130, 112, 0, AnchorAlignment.CENTER);
        Label completed = addProjectedLabel("COMPLETED →", "subtle", 140, -6, 40, AnchorAlignment.CENTER);
        completed.setStyle("-fx-font-size: 9px;");
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
        Box core = new Box(12, 8, 12);
        core.setTranslateX(x);
        core.setTranslateY(6);
        core.setMaterial(coreHeatMaterials.getFirst());
        core.setCullFace(CullFace.NONE);
        cores.add(core);

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
        world.getChildren().addAll(core, pillar, slot);

        addProjectedLabel("C" + (index + 1), "lane", x, 22, 16, AnchorAlignment.CENTER)
                .setStyle("-fx-font-size: 10px;");
        Label pinned = addProjectedLabel("PINNED", "red", x, 40, 0, AnchorAlignment.CENTER);
        pinned.setVisible(false);
        pinnedLabels.add(pinned);
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
            particles.add(particle);
            glows.add(glow);
            world.getChildren().addAll(glow, particle);
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
            dragging = true;
            tooltip.setVisible(false);
            camera.beginDrag(event.getSceneX(), event.getSceneY());
            subScene.setCursor(Cursor.CLOSED_HAND);
            subScene.requestFocus();
        });
        subScene.addEventHandler(MouseEvent.MOUSE_DRAGGED, event ->
                camera.drag(event.getSceneX(), event.getSceneY()));
        subScene.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> endDrag());
        subScene.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            endDrag();
            tooltip.setVisible(false);
        });
        subScene.setOnScroll(event -> {
            camera.zoom(event.getDeltaY());
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
        Vt vt = pickedVts.get(event.getPickResult().getIntersectedNode());
        if (vt == null) {
            tooltip.setVisible(false);
            return;
        }
        int carrierIndex = vt.carrier() == null ? -1 : vt.carrier().index();
        int progress = vt.work0() == 0 ? 0 : (int) Math.round((1 - vt.work() / vt.work0()) * 100);
        progress = Math.max(0, Math.min(100, progress));
        String text = "VT-" + vt.id() + " · " + vt.state().display()
                + (carrierIndex >= 0 ? " on C" + (carrierIndex + 1) : "")
                + (vt.state() == Sim.VtState.RUNNING ? " · " + progress + "% done" : "")
                + (vt.state() == Sim.VtState.PARKED
                        ? " · I/O " + String.format(Locale.ROOT, "%.1f", vt.io()) + "s" : "");
        tooltip.setText(text);
        tooltip.autosize();
        tooltip.relocate(Math.min(getWidth() - tooltip.getWidth() - 4, event.getX() + 14),
                Math.min(getHeight() - tooltip.getHeight() - 4, event.getY() + 10));
        tooltip.setVisible(true);
        tooltip.toFront();
    }

    public void sync(double dt) {
        syncBoot();
        schedulerRing.setRotate(sim.time() * 1.5 * 180 / Math.PI);
        double schedulerRise = clamp((sim.bootT() - 1.0) / 0.5, 0, 1);
        schedulerRing.setMaterial(schedulerRise > 0.6 ? schedulerBright : schedulerDim);
        syncCarriers();
        syncParticles();
        syncHero();
        camera.sync();
        projectLabels();
    }

    private void syncBoot() {
        for (BootLayer layer : bootLayers) {
            double amount = clamp((sim.bootT() - layer.order * 0.5) / 0.5, 0, 1);
            layer.group.setTranslateY(layer.restY - (1 - amount) * 18);
            if (layer.order == 4) heapGhost.setOpacity(amount);
        }
    }

    private void syncCarriers() {
        for (int i = 0; i < sim.carriers().size(); i++) {
            Carrier carrier = sim.carriers().get(i);
            boolean pinned = carrier.pinned();
            int heatLevel = (int) Math.round(clamp(carrier.heat(), 0, 1) * (coreHeatMaterials.size() - 1));
            cores.get(i).setMaterial(pinned ? pinnedCore : coreHeatMaterials.get(heatLevel));
            slots.get(i).setMaterial(pinned ? pinnedSlot : carrier.mounted() == null ? idleSlot : activeSlot);
            double scale = pinned ? 1 + Math.sin(sim.time() * 8) * 0.08 : 1;
            slots.get(i).setScaleX(scale);
            slots.get(i).setScaleY(scale);
            slots.get(i).setScaleZ(scale);
            pinnedLabels.get(i).setVisible(pinned);
        }
    }

    private void syncParticles() {
        pickedVts.clear();
        int index = 0;
        for (Vt vt : sim.vts()) {
            if (index >= particles.size()) break;
            Sphere particle = particles.get(index);
            Sphere glow = glows.get(index);
            VtColor color = colorFor(vt);
            double scale = scaleFor(vt);
            setPosition(particle, vt.pos());
            setPosition(glow, vt.pos());
            particle.setScaleX(scale);
            particle.setScaleY(scale);
            particle.setScaleZ(scale);
            glow.setScaleX(scale * 1.15);
            glow.setScaleY(scale * 1.15);
            glow.setScaleZ(scale * 1.15);
            particle.setMaterial(materials.get(color));
            glow.setMaterial(glowMaterials.get(color));
            particle.setVisible(true);
            glow.setVisible(true);
            pickedVts.put(particle, vt);
            index++;
        }
        for (int i = index; i < particles.size(); i++) {
            particles.get(i).setVisible(false);
            glows.get(i).setVisible(false);
        }
    }

    private VtColor colorFor(Vt vt) {
        return switch (vt.state()) {
            case TO_QUEUE, QUEUED -> VtColor.GREEN;
            case MOUNTING, RUNNING -> vt.carrier() != null && vt.carrier().pinned()
                    ? VtColor.RED : VtColor.BLUE;
            case PARKING, PARKED -> VtColor.PURPLE;
            case DONE, DEAD -> VtColor.WHITE;
        };
    }

    private double scaleFor(Vt vt) {
        double scale = switch (vt.state()) {
            case RUNNING -> 1.5 + 0.12 * Math.sin(sim.time() * 7 + vt.id());
            case MOUNTING -> 1.5;
            case PARKING, PARKED -> 0.9;
            default -> 1;
        };
        return vt.hero() ? scale * 1.45 : scale;
    }

    private void syncHero() {
        Vt hero = sim.hero();
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
        setPosition(head, hero.pos());
        head.setVisible(true);

        ProjectedLabel heroProjection = projectedLabels.getLast();
        heroProjection.anchor.setTranslateX(hero.pos().x);
        heroProjection.anchor.setTranslateY(hero.pos().y + 8);
        heroProjection.anchor.setTranslateZ(hero.pos().z);
        heroLabel.setVisible(true);
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
            label.relocate(x, local.getY() - label.getHeight() / 2);
        }
    }

    public void cameraPreset(CameraRig.Preset preset) {
        camera.toPreset(preset);
    }

    public void cameraForChapter(int chapter) {
        camera.toPreset(switch (Math.floorMod(chapter, 6)) {
            case 0, 5 -> CameraRig.Preset.OVERVIEW;
            case 2 -> CameraRig.Preset.HEAP;
            default -> CameraRig.Preset.CARRIERS;
        });
    }

    private static void setPosition(Node node, Vec3 pos) {
        node.setTranslateX(pos.x);
        node.setTranslateY(pos.y);
        node.setTranslateZ(pos.z);
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
