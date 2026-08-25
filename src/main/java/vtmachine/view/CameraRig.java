package vtmachine.view;

import javafx.geometry.Point3D;
import javafx.scene.PerspectiveCamera;
import javafx.scene.transform.Affine;

/** Orbit camera with the four exact presets from the reference implementation. */
public final class CameraRig {
    public enum Preset { OVERVIEW, CARRIERS, HEAP, TOP }

    private record Orbit(double theta, double phi, double distance, double targetY) {}

    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private double theta = 0.65;
    private double phi = 1.12;
    private double distance = 260;
    private double targetY = 45;
    private Orbit goal;
    private double pointerX;
    private double pointerY;

    public CameraRig() {
        camera.setFieldOfView(45);
        camera.setNearClip(1);
        camera.setFarClip(1000);
        apply();
    }

    public PerspectiveCamera camera() {
        return camera;
    }

    public void toPreset(Preset preset) {
        goal = switch (preset) {
            case OVERVIEW -> new Orbit(0.65, 1.12, 260, 45);
            case CARRIERS -> new Orbit(0.35, 1.25, 150, 30);
            case HEAP -> new Orbit(-0.55, 1.15, 170, 45);
            case TOP -> new Orbit(0.65, 0.35, 300, 40);
        };
    }

    public void beginDrag(double x, double y) {
        pointerX = x;
        pointerY = y;
        goal = null;
    }

    public void drag(double x, double y) {
        theta -= (x - pointerX) * 0.005;
        phi = clamp(phi - (y - pointerY) * 0.004, 0.15, 1.45);
        pointerX = x;
        pointerY = y;
        apply();
    }

    public void zoom(double deltaY) {
        distance = clamp(distance + deltaY * 0.4, 80, 480);
        goal = null;
        apply();
    }

    public void sync() {
        if (goal != null) {
            theta += (goal.theta - theta) * 0.06;
            phi += (goal.phi - phi) * 0.06;
            distance += (goal.distance - distance) * 0.06;
            targetY += (goal.targetY - targetY) * 0.06;
            if (Math.abs(goal.distance - distance) < 1 && Math.abs(goal.theta - theta) < 0.01) {
                theta = goal.theta;
                phi = goal.phi;
                distance = goal.distance;
                targetY = goal.targetY;
                goal = null;
            }
        }
        apply();
    }

    private void apply() {
        double x = Math.sin(theta) * Math.sin(phi) * distance;
        double y = Math.cos(phi) * distance + targetY;
        double z = Math.cos(theta) * Math.sin(phi) * distance;
        Point3D eye = new Point3D(x, -y, z);
        Point3D target = new Point3D(0, -targetY, 0);
        camera.getTransforms().setAll(lookAt(eye, target));
    }

    static Affine lookAt(Point3D eye, Point3D target) {
        Point3D up = new Point3D(0, -1, 0);
        Point3D forward = target.subtract(eye).normalize();
        Point3D right = up.crossProduct(forward).normalize();
        // JavaFX camera-local +Y points down the screen, so the second basis
        // vector is world-down (the opposite of the conventional lookAt up).
        Point3D down = right.crossProduct(forward);
        return new Affine(
                right.getX(), down.getX(), forward.getX(), eye.getX(),
                right.getY(), down.getY(), forward.getY(), eye.getY(),
                right.getZ(), down.getZ(), forward.getZ(), eye.getZ());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
