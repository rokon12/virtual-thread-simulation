package vtmachine.model;

import java.util.concurrent.CompletableFuture;

import static vtmachine.model.Sim.VtState;
import static vtmachine.model.Sim.TaskProfile;

/** Mutable virtual-thread state owned exclusively by {@link Sim}. */
public final class Vt {
    final long id;
    VtState state;
    final Vec3 pos;
    double work;
    final double work0;
    final TaskProfile profile;
    final double plannedIoSeconds;
    final double ioTriggerWork;
    double io;
    double parkedAt;
    Carrier carrier;
    boolean resumed;
    boolean hero;
    boolean live;
    boolean liveParkPending;
    CompletableFuture<Void> firstMountSignal;
    CompletableFuture<Void> resumeMountSignal;
    Tween tween;

    Vt(long id, Vec3 pos, double work, TaskProfile profile,
            double plannedIoSeconds, double ioTriggerWork) {
        this.id = id;
        this.state = VtState.TO_QUEUE;
        this.pos = pos;
        this.work = work;
        this.work0 = work;
        this.profile = profile;
        this.plannedIoSeconds = plannedIoSeconds;
        this.ioTriggerWork = ioTriggerWork;
    }

    public long id() { return id; }
    public VtState state() { return state; }
    public Vec3 pos() { return pos; }
    public double work() { return work; }
    public double work0() { return work0; }
    public TaskProfile profile() { return profile; }
    public double plannedIoSeconds() { return plannedIoSeconds; }
    public double io() { return io; }
    public Carrier carrier() { return carrier; }
    public boolean resumed() { return resumed; }
    public boolean hero() { return hero; }
    public boolean live() { return live; }
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
