package vtmachine.model;

/** A tiny mutable vector that keeps the simulation model independent of JavaFX. */
public final class Vec3 {
    public double x;
    public double y;
    public double z;

    public Vec3(double x, double y, double z) {
        set(x, y, z);
    }

    public Vec3(Vec3 other) {
        this(other.x, other.y, other.z);
    }

    public Vec3 set(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public Vec3 set(Vec3 other) {
        return set(other.x, other.y, other.z);
    }
}
