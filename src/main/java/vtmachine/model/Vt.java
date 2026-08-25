package vtmachine.model;

import static vtmachine.model.Sim.VtState;

/** Mutable virtual-thread state owned exclusively by {@link Sim}. */
public final class Vt {
    final long id;
    VtState state;
    final Vec3 pos;
    double work;
    final double work0;
    double io;
    Carrier carrier;
    boolean resumed;
    boolean hero;
    Tween tween;

    Vt(long id, Vec3 pos, double work) {
        this.id = id;
        this.state = VtState.TO_QUEUE;
        this.pos = pos;
        this.work = work;
        this.work0 = work;
    }

    public long id() { return id; }
    public VtState state() { return state; }
    public Vec3 pos() { return pos; }
    public double work() { return work; }
    public double work0() { return work0; }
    public double io() { return io; }
    public Carrier carrier() { return carrier; }
    public boolean resumed() { return resumed; }
    public boolean hero() { return hero; }
    public boolean isTweening() { return tween != null; }

    static final class Tween {
        enum Arrival { QUEUED, RUNNING, PARKED, DEAD }

        final Vec3 from;
        final Vec3 to;
        final double duration;
        final double arc;
        final Arrival arrival;
        double elapsed;

        Tween(Vec3 from, Vec3 to, double duration, double arc, Arrival arrival) {
            this.from = from;
            this.to = to;
            this.duration = duration;
            this.arc = arc;
            this.arrival = arrival;
        }
    }
}
