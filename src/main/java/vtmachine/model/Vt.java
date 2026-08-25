package vtmachine.model;

import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;

import static vtmachine.model.Sim.IoDevice;
import static vtmachine.model.Sim.LifecyclePhase;
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
    IoDevice ioDevice;
    final EnumMap<LifecyclePhase, Double> lifecycleSeconds = new EnumMap<>(LifecyclePhase.class);
    LifecyclePhase lifecyclePhase = LifecyclePhase.RUNNABLE;
    double lifecycleEnteredAt;
    boolean lifecycleClosed;
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
            double plannedIoSeconds, double ioTriggerWork, IoDevice ioDevice, double createdAt) {
        this.id = id;
        this.state = VtState.TO_QUEUE;
        this.pos = pos;
        this.work = work;
        this.work0 = work;
        this.profile = profile;
        this.plannedIoSeconds = plannedIoSeconds;
        this.ioTriggerWork = ioTriggerWork;
        this.ioDevice = ioDevice;
        this.lifecycleEnteredAt = createdAt;
        for (LifecyclePhase phase : LifecyclePhase.values()) lifecycleSeconds.put(phase, 0.0);
    }

    public long id() { return id; }
    public VtState state() { return state; }
    public Vec3 pos() { return pos; }
    public double work() { return work; }
    public double work0() { return work0; }
    public TaskProfile profile() { return profile; }
    public double plannedIoSeconds() { return plannedIoSeconds; }
    public IoDevice ioDevice() { return ioDevice; }
    public double io() { return io; }
    public Carrier carrier() { return carrier; }
    public boolean resumed() { return resumed; }
    public boolean hero() { return hero; }
    public boolean live() { return live; }
    public boolean isTweening() { return tween != null; }
    public LifecyclePhase lifecyclePhase() { return lifecyclePhase; }

    public double lifecycleSeconds(LifecyclePhase phase, double now) {
        double elapsed = lifecycleSeconds.getOrDefault(phase, 0.0);
        return phase == lifecyclePhase && !lifecycleClosed
                ? elapsed + Math.max(0, now - lifecycleEnteredAt) : elapsed;
    }

    public double lifecycleAge(double now) {
        return Math.max(0, now - lifecycleEnteredAt);
    }

    void transitionTo(VtState next, double now) {
        LifecyclePhase nextPhase = phaseFor(next);
        if (next == VtState.DEAD) {
            if (!lifecycleClosed) {
                lifecycleSeconds.merge(lifecyclePhase, Math.max(0, now - lifecycleEnteredAt), Double::sum);
                lifecycleEnteredAt = now;
                lifecycleClosed = true;
            }
            state = next;
            return;
        }
        if (nextPhase != lifecyclePhase) {
            lifecycleSeconds.merge(lifecyclePhase, Math.max(0, now - lifecycleEnteredAt), Double::sum);
            lifecyclePhase = nextPhase;
            lifecycleEnteredAt = now;
        }
        state = next;
    }

    private static LifecyclePhase phaseFor(VtState state) {
        return switch (state) {
            case TO_QUEUE, QUEUED -> LifecyclePhase.RUNNABLE;
            case MOUNTING, RUNNING -> LifecyclePhase.MOUNTED;
            case PARKING, PARKED -> LifecyclePhase.PARKED;
            case DONE, DEAD -> LifecyclePhase.TERMINATED;
        };
    }

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
