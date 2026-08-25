package vtmachine.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import vtmachine.model.Vt.Tween;
import vtmachine.model.Vt.Tween.Arrival;

/**
 * JavaFX-free simulation model. All mutations happen on the caller's thread.
 */
public final class Sim {
    public static final int DEFAULT_CARRIERS = 4;
    public static final int DEFAULT_MAX_THREADS = 500;
    public static final double DEFAULT_TASK_RATE = 1.4;
    public static final int CHAPTER_COUNT = 10;
    public static final int DATABASE_PERMITS = 3;

    public record Stats(int runnable, int mounted, int parked, int completed) {}
    public record ProfileStats(int fast, int compute, int ioBound) {}
    public record ResourcePoolStats(int capacity, int inUse, int waiting) {}
    public record ScopeStats(int id, String name, int total, int active,
            int succeeded, int failed, int cancelled, boolean joined) {}

    public enum Scenario {
        NONE, PLATFORM_COMPARISON, RESOURCE_POOL, CPU_BOUND, STRUCTURED
    }

    public enum Outcome {
        ACTIVE, COMPLETED, FAILED, CANCELLED
    }

    public enum TaskProfile {
        FAST("FAST CPU"), COMPUTE("COMPUTE"), IO_BOUND("I/O BOUND");

        private final String display;
        TaskProfile(String display) { this.display = display; }
        public String display() { return display; }
    }

    public enum IoDevice {
        NETWORK("NETWORK"), DISK("DISK"), TIMER("TIMER"), DATABASE("DATABASE");

        private final String display;
        IoDevice(String display) { this.display = display; }
        public String display() { return display; }
    }

    public enum LifecyclePhase {
        RUNNABLE("RUNNABLE"), MOUNTED("MOUNTED"), PARKED("PARKED / I/O"), TERMINATED("TERMINATED");

        private final String display;
        LifecyclePhase(String display) { this.display = display; }
        public String display() { return display; }
    }

    public enum VtState {
        TO_QUEUE("toQueue"), QUEUED("queued"), MOUNTING("mounting"),
        RUNNING("running"), PARKING("parking"), PARKED("parked"),
        DONE("done"), DEAD("dead");

        private final String display;

        VtState(String display) { this.display = display; }
        public String display() { return display; }
    }

    public enum Flash { MOUNT, PARK, RESUME, PIN }

    /** Messages accepted from the optional real virtual-thread workload. */
    public sealed interface LiveEvent permits LiveSpawned, LiveProgress, LiveParked,
            LivePermitAcquired, LiveResumed, LiveCompleted, LivePinned {}
    public record LiveSpawned(long threadId, CompletableFuture<Void> firstMount,
            CompletableFuture<Void> resumeMount, TaskProfile profile,
            double plannedIoSeconds) implements LiveEvent {}
    public record LiveProgress(long threadId, double progress) implements LiveEvent {}
    public record LiveParked(long threadId) implements LiveEvent {}
    public record LivePermitAcquired(long threadId) implements LiveEvent {}
    public record LiveResumed(long threadId, double ioSeconds) implements LiveEvent {}
    public record LiveCompleted(long threadId) implements LiveEvent {}
    public record LivePinned(long threadId, double durationSeconds, String reason) implements LiveEvent {}

    private final int maxThreads;
    private final double taskRate;
    private final long seed;
    private RandomGenerator random;

    private final List<Vt> vts = new ArrayList<>();
    private final List<Vt> readOnlyVts = Collections.unmodifiableList(vts);
    private final List<Vt> queue = new ArrayList<>();
    private final List<Carrier> carriers = new ArrayList<>();
    private final List<Carrier> readOnlyCarriers = Collections.unmodifiableList(carriers);
    private final Deque<String> log = new ArrayDeque<>(9);
    private final EnumMap<Flash, Double> flashes = new EnumMap<>(Flash.class);
    private final ConcurrentLinkedQueue<LiveEvent> liveEvents = new ConcurrentLinkedQueue<>();
    private final Map<Long, Vt> liveById = new HashMap<>();
    private final Deque<Vt> permitWaiters = new ArrayDeque<>();
    private final Map<Integer, ScopeProgress> scopes = new LinkedHashMap<>();

    private long nextId;
    private double time;
    private double bootT;
    private double speed = 0.75;
    private double spawnRate;
    private double spawnAcc;
    private int burst;
    private int completed;
    private double ioSecondsTotal;
    private int ioSamples;
    private int chapter;
    private volatile boolean running = true;
    private boolean freeRun;
    private boolean chaos;
    private boolean pendingPark;
    private boolean pendingPin;
    private boolean bootAutoAdvanced;
    private boolean liveMode;
    private Scenario scenario = Scenario.NONE;
    private int scenarioSubmitted;
    private int scenarioSpawned;
    private int permitsInUse;
    private Vt hero;

    private static final class ScopeProgress {
        final int id;
        final String name;
        final int total;
        int succeeded;
        int failed;
        int cancelled;

        ScopeProgress(int id, String name, int total) {
            this.id = id;
            this.name = name;
            this.total = total;
        }

        int terminal() { return succeeded + failed + cancelled; }
        ScopeStats snapshot() {
            return new ScopeStats(id, name, total, Math.max(0, total - terminal()),
                    succeeded, failed, cancelled, terminal() == total);
        }
    }

    public Sim() {
        this(DEFAULT_CARRIERS, DEFAULT_MAX_THREADS, DEFAULT_TASK_RATE, System.nanoTime());
    }

    public Sim(int carrierCount, int maxThreads, double taskRate, long seed) {
        if (carrierCount < 2 || carrierCount > 10) {
            throw new IllegalArgumentException("carriers must be between 2 and 10");
        }
        if (maxThreads < 50 || maxThreads > 800) {
            throw new IllegalArgumentException("maxThreads must be between 50 and 800");
        }
        if (taskRate < 0.3 || taskRate > 6.0) {
            throw new IllegalArgumentException("taskRate must be between 0.3 and 6.0");
        }
        this.maxThreads = maxThreads;
        this.taskRate = taskRate;
        this.seed = seed;
        reset(carrierCount);
    }

    /** Advance one simulation step. The application supplies an already speed-scaled dt. */
    public void tick(double dt) {
        if (!running || dt <= 0) return;
        time += dt;

        if (bootT < 3.0) {
            double before = bootT;
            bootT = Math.min(3.0, bootT + dt);
            if (before < 3.0 && bootT >= 3.0 && !bootAutoAdvanced) {
                bootAutoAdvanced = true;
                addLog("scheduler online");
                gotoChapter(1);
            }
            return;
        }

        if (liveMode) drainLiveEvents();
        else spawnPhase(dt);
        tweenPhase(dt);
        driftPhase(dt);
        carrierPhase(dt);
        runPhase(dt);
        parkedPhase(dt);
        reapPhase();
    }

    private void spawnPhase(double dt) {
        spawnAcc += spawnRate * dt;
        int spawnedThisFrame = 0;
        while ((spawnAcc >= 1 || burst > 0) && vts.size() < maxThreads && spawnedThisFrame < 6) {
            if (burst > 0) burst--;
            else spawnAcc -= 1;
            spawn();
            spawnedThisFrame++;
        }
    }

    private void tweenPhase(double dt) {
        for (Vt vt : vts) {
            Tween tween = vt.tween;
            if (tween == null) continue;

            tween.elapsed += dt;
            double t = Math.min(1, tween.elapsed / tween.duration);
            double e = t * t * (3 - 2 * t);
            vt.pos.set(
                    lerp(tween.from.x, tween.to.x, e),
                    lerp(tween.from.y, tween.to.y, e) + Math.sin(Math.PI * e) * tween.arc,
                    lerp(tween.from.z, tween.to.z, e));

            if (t >= 1) {
                vt.pos.set(tween.to);
                vt.tween = null;
                VtState arrived = switch (tween.arrival) {
                    case QUEUED -> VtState.QUEUED;
                    case RUNNING -> VtState.RUNNING;
                    case PARKED -> VtState.PARKED;
                    case DEAD -> VtState.DEAD;
                };
                vt.transitionTo(arrived, time);
                if (tween.arrival == Arrival.RUNNING && vt.live) {
                    CompletableFuture<Void> signal = vt.resumed ? vt.resumeMountSignal : vt.firstMountSignal;
                    if (signal != null) signal.complete(null);
                }
            }
        }
    }

    private void driftPhase(double dt) {
        double k = Math.min(1, 4 * dt);
        for (int i = 0; i < queue.size(); i++) {
            Vt vt = queue.get(i);
            if (vt.state == VtState.QUEUED && vt.tween == null) {
                drift(vt.pos, queueTarget(vt, i), k);
            }
        }

        int parkedIndex = 0;
        for (Vt vt : vts) {
            if (vt.state == VtState.PARKED && vt.tween == null) {
                drift(vt.pos, heapSlot(parkedIndex++), k);
            }
        }
    }

    private void carrierPhase(double dt) {
        for (Carrier carrier : carriers) {
            carrier.heat = Math.max(0, carrier.heat - 0.8 * dt);
            if (carrier.pinT > 0) {
                carrier.pinT -= dt;
                if (carrier.pinT <= 0) {
                    carrier.pinT = 0;
                    if (carrier.mounted != null) {
                        addLog("VT-" + carrier.mounted.id + " unpinned · resumes");
                        if (carrier.mounted.liveParkPending) {
                            carrier.mounted.liveParkPending = false;
                            park(carrier.mounted);
                        }
                    }
                }
            }

            if (carrier.mounted == null) {
                Vt next = firstMountable();
                if (next != null) mount(next, carrier);
            }
        }
    }

    private void runPhase(double dt) {
        for (Vt vt : vts) {
            if (vt.state != VtState.RUNNING) continue;
            Carrier carrier = vt.carrier;
            carrier.heat = Math.min(1, carrier.heat + 2 * dt);

            if (pendingPark) {
                pendingPark = false;
                park(vt);
                continue;
            }
            if (pendingPin) {
                pendingPin = false;
                pin(vt);
            }
            if (carrier.pinned()) continue;
            if (vt.live) continue;

            vt.work -= dt;
            if (scenario == Scenario.STRUCTURED && vt.failureCandidate
                    && vt.work <= vt.work0 * 0.55) {
                fail(vt, carrier);
                continue;
            }
            if (!vt.resumed && vt.profile == TaskProfile.IO_BOUND && vt.work <= vt.ioTriggerWork) {
                park(vt);
                continue;
            }
            if (chaos && vt.work > 0.4) {
                double chance = random.nextDouble();
                if (!vt.resumed && chance < 0.30 * dt) {
                    park(vt);
                    continue;
                } else if (chance > 1 - 0.03 * dt) {
                    pin(vt);
                }
            }

            if (vt.work <= 0) complete(vt, carrier);
        }
    }

    private void parkedPhase(double dt) {
        for (Vt vt : vts) {
            if (vt.state != VtState.PARKED) continue;
            if (vt.live) continue;
            if (vt.waitingForPermit) continue;
            vt.io -= dt;
            if (vt.io <= 0) {
                releasePermit(vt);
                resume(vt);
            }
        }
    }

    private void reapPhase() {
        for (Iterator<Vt> iterator = vts.iterator(); iterator.hasNext();) {
            Vt vt = iterator.next();
            if (vt.state == VtState.DONE && vt.lifecycleAge(time) >= 1.35) {
                vt.transitionTo(VtState.DEAD, time);
            }
            if (vt.state == VtState.DEAD) {
                iterator.remove();
                liveById.remove(vt.id);
                if (hero == vt) hero = null;
            }
        }
    }

    private void spawn() {
        TaskProfile profile = scenarioProfile();
        double work = scenarioWork(profile);
        double plannedIo = profile == TaskProfile.IO_BOUND
                ? scenario == Scenario.PLATFORM_COMPARISON || scenario == Scenario.RESOURCE_POOL
                        ? 3.5 + random.nextDouble() * 3.5
                        : scenario == Scenario.STRUCTURED ? 1.8 + random.nextDouble() * 2.2
                        : 1.0 + random.nextDouble() * 7.0
                : 0;
        double ioTrigger = profile == TaskProfile.IO_BOUND
                ? work * (scenario == Scenario.PLATFORM_COMPARISON || scenario == Scenario.RESOURCE_POOL
                        ? 0.80 + random.nextDouble() * 0.10
                        : 0.38 + random.nextDouble() * 0.34)
                : -1;
        IoDevice ioDevice = profile == TaskProfile.IO_BOUND
                ? scenario == Scenario.RESOURCE_POOL ? IoDevice.DATABASE
                        : IoDevice.values()[random.nextInt(IoDevice.values().length)] : null;
        Vt vt = new Vt(nextId++, new Vec3(
                -130 + random.nextDouble() * 20 - 10,
                105 + random.nextDouble() * 12,
                random.nextDouble() * 20 - 10), work, profile, plannedIo, ioTrigger, ioDevice, time);
        configureScenarioTask(vt);
        if (hero == null) {
            hero = vt;
            vt.hero = true;
        }
        tween(vt, queueTarget(vt, queue.size()), 0.7, Arrival.QUEUED);
        queue.add(vt);
        vts.add(vt);
    }

    private TaskProfile scenarioProfile() {
        return switch (scenario) {
            case PLATFORM_COMPARISON, RESOURCE_POOL -> TaskProfile.IO_BOUND;
            case CPU_BOUND -> TaskProfile.COMPUTE;
            case STRUCTURED -> switch (scenarioSpawned % 4) {
                case 0 -> TaskProfile.FAST;
                case 1, 3 -> TaskProfile.IO_BOUND;
                default -> TaskProfile.COMPUTE;
            };
            case NONE -> randomProfile();
        };
    }

    private double scenarioWork(TaskProfile profile) {
        return switch (scenario) {
            case PLATFORM_COMPARISON, RESOURCE_POOL -> 0.75 + random.nextDouble() * 0.45;
            case CPU_BOUND -> 5.0 + random.nextDouble() * 4.0;
            case STRUCTURED -> switch (profile) {
                case FAST -> 0.8 + random.nextDouble() * 0.7;
                case COMPUTE -> 1.8 + random.nextDouble() * 2.0;
                case IO_BOUND -> 1.0 + random.nextDouble() * 1.1;
            };
            case NONE -> switch (profile) {
                case FAST -> 0.45 + random.nextDouble() * 1.05;
                case COMPUTE -> 2.2 + random.nextDouble() * 4.8;
                case IO_BOUND -> 0.8 + random.nextDouble() * 1.8;
            };
        };
    }

    private void configureScenarioTask(Vt vt) {
        if (scenario == Scenario.STRUCTURED) {
            int sequence = scenarioSpawned++;
            vt.scopeId = sequence / 4 + 1;
            vt.scopeChildIndex = sequence % 4;
            vt.failureCandidate = sequence == 6;
        } else {
            scenarioSpawned++;
        }
    }

    private void drainLiveEvents() {
        int processed = 0;
        int spawned = 0;
        LiveEvent deferredSpawn = null;
        while (processed < 96) {
            LiveEvent event = liveEvents.poll();
            if (event == null) break;
            if (event instanceof LiveSpawned spawnedEvent) {
                if (spawned >= 6) {
                    deferredSpawn = event;
                    break;
                }
                spawnLive(spawnedEvent);
                spawned++;
            } else if (event instanceof LiveProgress progress) {
                Vt vt = liveById.get(progress.threadId());
                if (vt != null) {
                    double value = Math.max(0, Math.min(1, progress.progress()));
                    vt.work = vt.work0 * (1 - value);
                    if (scenario == Scenario.STRUCTURED && vt.failureCandidate && value >= 0.45
                            && vt.outcome == Outcome.ACTIVE && vt.carrier != null) {
                        fail(vt, vt.carrier);
                    }
                }
            } else if (event instanceof LiveParked parked) {
                Vt vt = liveById.get(parked.threadId());
                if (vt != null && vt.state == VtState.RUNNING) {
                    if (vt.carrier != null && vt.carrier.pinned()) vt.liveParkPending = true;
                    else park(vt);
                }
            } else if (event instanceof LivePermitAcquired acquired) {
                Vt vt = liveById.get(acquired.threadId());
                if (vt != null && scenario == Scenario.RESOURCE_POOL && vt.waitingForPermit) {
                    permitWaiters.remove(vt);
                    vt.waitingForPermit = false;
                    vt.resourcePermit = true;
                    permitsInUse++;
                    addLog("VT-" + vt.id + " acquired DB permit " + permitsInUse + "/" + resourceCapacity());
                }
            } else if (event instanceof LiveResumed resumed) {
                Vt vt = liveById.get(resumed.threadId());
                if (vt != null && (vt.state == VtState.PARKED || vt.state == VtState.PARKING)) {
                    releasePermit(vt);
                    resume(vt, resumed.ioSeconds());
                }
            } else if (event instanceof LiveCompleted completedEvent) {
                Vt vt = liveById.get(completedEvent.threadId());
                if (vt != null && vt.state == VtState.RUNNING && vt.carrier != null) {
                    vt.work = 0;
                    complete(vt, vt.carrier);
                }
            } else if (event instanceof LivePinned pinned) {
                Vt vt = liveById.get(pinned.threadId());
                if (vt != null && vt.state == VtState.RUNNING && vt.carrier != null) {
                    vt.carrier.pinT = Math.max(0.25, pinned.durationSeconds());
                    flash(Flash.PIN);
                    addLog("VT-" + vt.id + " REAL PIN · " + pinned.reason());
                }
            }
            processed++;
        }
        if (deferredSpawn != null) liveEvents.add(deferredSpawn);
    }

    private void spawnLive(LiveSpawned event) {
        if (!liveMode || vts.size() >= maxThreads || liveById.containsKey(event.threadId())) {
            var failure = new IllegalStateException("live feed is full or inactive");
            event.firstMount().completeExceptionally(failure);
            event.resumeMount().completeExceptionally(failure);
            return;
        }
        TaskProfile profile = scenario == Scenario.PLATFORM_COMPARISON
                || scenario == Scenario.RESOURCE_POOL ? TaskProfile.IO_BOUND
                : scenario == Scenario.CPU_BOUND ? TaskProfile.COMPUTE : event.profile();
        double plannedIo = profile == TaskProfile.IO_BOUND
                ? Math.max(2.5, event.plannedIoSeconds()) : 0;
        Vt vt = new Vt(event.threadId(), new Vec3(
                -130 + random.nextDouble() * 20 - 10,
                105 + random.nextDouble() * 12,
                random.nextDouble() * 20 - 10), 1.0, profile,
                plannedIo, -1,
                profile == TaskProfile.IO_BOUND
                        ? scenario == Scenario.RESOURCE_POOL ? IoDevice.DATABASE
                                : IoDevice.values()[random.nextInt(IoDevice.values().length)] : null,
                time);
        configureScenarioTask(vt);
        vt.live = true;
        vt.firstMountSignal = event.firstMount();
        vt.resumeMountSignal = event.resumeMount();
        if (hero == null) {
            hero = vt;
            vt.hero = true;
        }
        tween(vt, queueTarget(vt, queue.size()), 0.7, Arrival.QUEUED);
        queue.add(vt);
        vts.add(vt);
        liveById.put(vt.id, vt);
    }

    private Vt firstMountable() {
        for (int i = 0; i < queue.size(); i++) {
            Vt vt = queue.get(i);
            if (vt.state == VtState.QUEUED && vt.tween == null) {
                queue.remove(i);
                return vt;
            }
        }
        return null;
    }

    private void mount(Vt vt, Carrier carrier) {
        carrier.mounted = vt;
        vt.carrier = carrier;
        vt.transitionTo(VtState.MOUNTING, time);
        tween(vt, new Vec3(laneX(carrier.index()), 30, 0), 0.55, Arrival.RUNNING);
        flash(Flash.MOUNT);
        addLog("VT-" + vt.id + (vt.resumed ? " resumed on C" : " mounted on C") + (carrier.index() + 1));
    }

    private void park(Vt vt) {
        Carrier carrier = vt.carrier;
        if (carrier != null) carrier.mounted = null;
        vt.carrier = null;
        vt.transitionTo(VtState.PARKING, time);
        if (vt.ioDevice == null) vt.ioDevice = IoDevice.values()[random.nextInt(IoDevice.values().length)];
        vt.io = vt.live ? Double.POSITIVE_INFINITY
                : vt.plannedIoSeconds > 0 ? vt.plannedIoSeconds : 1.0 + random.nextDouble() * 7.0;
        if (scenario == Scenario.RESOURCE_POOL && vt.ioDevice == IoDevice.DATABASE) {
            if (vt.live) {
                vt.waitingForPermit = true;
                vt.io = Double.POSITIVE_INFINITY;
                permitWaiters.addLast(vt);
            } else if (permitsInUse < resourceCapacity()) {
                permitsInUse++;
                vt.resourcePermit = true;
                addLog("VT-" + vt.id + " acquired DB permit " + permitsInUse + "/" + resourceCapacity());
            } else {
                vt.waitingForPermit = true;
                vt.io = Double.POSITIVE_INFINITY;
                permitWaiters.addLast(vt);
            }
        }
        vt.parkedAt = time;
        int parkedCount = 0;
        for (Vt candidate : vts) {
            if (candidate != vt && (candidate.state == VtState.PARKED || candidate.state == VtState.PARKING)) {
                parkedCount++;
            }
        }
        tween(vt, heapSlot(parkedCount), 0.85, Arrival.PARKED);
        flash(Flash.PARK);
        addLog("VT-" + vt.id + (vt.waitingForPermit
                ? " parked · waiting for DB permit" : " I/O wait · carrier released"));
    }

    private void resume(Vt vt) {
        resume(vt, Math.max(0, time - vt.parkedAt));
    }

    private void resume(Vt vt, double observedIoSeconds) {
        if (vt.parkedAt > 0 || vt.live) {
            ioSecondsTotal += Math.max(0, observedIoSeconds);
            ioSamples++;
            vt.parkedAt = 0;
        }
        vt.transitionTo(VtState.TO_QUEUE, time);
        vt.resumed = true;
        vt.io = 0;
        queue.addFirst(vt);
        tween(vt, queueTarget(vt, 0), 0.85, Arrival.QUEUED);
        flash(Flash.RESUME);
        addLog("VT-" + vt.id + " I/O done · runnable");
    }

    private void releasePermit(Vt vt) {
        permitWaiters.remove(vt);
        vt.waitingForPermit = false;
        if (!vt.resourcePermit) return;
        vt.resourcePermit = false;
        permitsInUse = Math.max(0, permitsInUse - 1);
        if (!vt.live) grantNextPermit();
    }

    private void grantNextPermit() {
        while (permitsInUse < resourceCapacity() && !permitWaiters.isEmpty()) {
            Vt next = permitWaiters.removeFirst();
            if (next.outcome != Outcome.ACTIVE
                    || next.state != VtState.PARKED && next.state != VtState.PARKING) continue;
            next.waitingForPermit = false;
            next.resourcePermit = true;
            next.io = next.plannedIoSeconds > 0 ? next.plannedIoSeconds
                    : 2.5 + random.nextDouble() * 3.0;
            permitsInUse++;
            addLog("VT-" + next.id + " acquired DB permit " + permitsInUse + "/" + resourceCapacity());
        }
    }

    private int resourceCapacity() {
        return Math.min(DATABASE_PERMITS, Math.max(1, carriers.size()));
    }

    private void pin(Vt vt) {
        if (vt.carrier == null) return;
        vt.carrier.pinT = 2.6 + random.nextDouble() * 1.2;
        flash(Flash.PIN);
        addLog("VT-" + vt.id + (vt.live ? " demo native/foreign PIN on C" : " PINNED on C")
                + (vt.carrier.index() + 1));
    }

    private void complete(Vt vt, Carrier carrier) {
        carrier.mounted = null;
        vt.carrier = null;
        vt.tween = null;
        vt.outcome = Outcome.COMPLETED;
        vt.transitionTo(VtState.DONE, time);
        completed++;
        recordScopeOutcome(vt);
        addLog("VT-" + vt.id + " completed · C" + (carrier.index() + 1) + " free · terminated");
    }

    private void fail(Vt vt, Carrier carrier) {
        if (vt.outcome != Outcome.ACTIVE) return;
        carrier.mounted = null;
        vt.carrier = null;
        vt.tween = null;
        vt.outcome = Outcome.FAILED;
        vt.transitionTo(VtState.DONE, time);
        recordScopeOutcome(vt);
        addLog("VT-" + vt.id + " failed · scope " + vt.scopeId + " cancelling siblings");
        cancelScope(vt.scopeId, vt);
    }

    private void cancelScope(int scopeId, Vt failed) {
        for (Vt sibling : vts) {
            if (sibling == failed || sibling.scopeId != scopeId || sibling.outcome != Outcome.ACTIVE) continue;
            if (sibling.carrier != null) sibling.carrier.mounted = null;
            sibling.carrier = null;
            queue.remove(sibling);
            permitWaiters.remove(sibling);
            releasePermit(sibling);
            sibling.tween = null;
            sibling.outcome = Outcome.CANCELLED;
            sibling.transitionTo(VtState.DONE, time);
            recordScopeOutcome(sibling);
        }
        addLog("scope " + scopeId + " joined exceptionally");
    }

    private void recordScopeOutcome(Vt vt) {
        if (vt.scopeId <= 0) return;
        ScopeProgress scope = scopes.get(vt.scopeId);
        if (scope == null) return;
        switch (vt.outcome) {
            case COMPLETED -> scope.succeeded++;
            case FAILED -> scope.failed++;
            case CANCELLED -> scope.cancelled++;
            case ACTIVE -> { }
        }
        if (scope.terminal() == scope.total) {
            addLog("scope " + scope.id + (scope.failed > 0 ? " joined with failure" : " joined successfully"));
        }
    }

    private void tween(Vt vt, Vec3 target, double duration, Arrival arrival) {
        vt.tween = new Tween(new Vec3(vt.pos), target, duration, 8 + random.nextDouble() * 10, arrival);
    }

    private static void drift(Vec3 pos, Vec3 target, double k) {
        pos.x += (target.x - pos.x) * k;
        pos.y += (target.y - pos.y) * k;
        pos.z += (target.z - pos.z) * k;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public boolean forcePark() {
        for (Vt vt : vts) {
            if (vt.state == VtState.RUNNING && vt.carrier != null && !vt.carrier.pinned()) {
                park(vt);
                return true;
            }
        }
        addLog("no unpinned running VT");
        return false;
    }

    public boolean forcePin() {
        for (Vt vt : vts) {
            if (vt.state == VtState.RUNNING && vt.carrier != null && !vt.carrier.pinned()) {
                pin(vt);
                return true;
            }
        }
        addLog("no running VT");
        return false;
    }

    public void burst(int count) {
        if (count <= 0) return;
        if (scenario == Scenario.STRUCTURED) {
            addLog("structured scenario has a fixed scope tree");
            return;
        }
        burst += count;
        addLog("burst: " + count + " tasks submitted");
    }

    public void gotoChapter(int requested) {
        int selected = Math.floorMod(requested, CHAPTER_COUNT);
        Scenario previousScenario = scenario;
        if (selected >= 6 || previousScenario != Scenario.NONE) clearWorkload();
        chapter = selected;
        freeRun = false;
        chaos = false;
        spawnRate = 0;
        pendingPark = false;
        pendingPin = false;
        scenario = Scenario.NONE;
        scenarioSubmitted = 0;
        scenarioSpawned = 0;

        switch (selected) {
            case 0 -> reset(carriers.isEmpty() ? DEFAULT_CARRIERS : carriers.size());
            case 1 -> {
                if (!liveMode) burst += 6;
                addLog("chapter: mount");
            }
            case 2 -> {
                if (!liveMode && vts.stream().noneMatch(v -> v.state == VtState.RUNNING)) burst += 4;
                pendingPark = true;
                addLog("chapter: park");
            }
            case 3 -> {
                if (!liveMode) {
                    Vt parked = vts.stream().filter(v -> v.state == VtState.PARKED).findFirst().orElse(null);
                    if (parked != null) parked.io = Math.min(parked.io, 0.8);
                    else {
                        burst += 2;
                        pendingPark = true;
                    }
                }
                addLog("chapter: resume");
            }
            case 4 -> {
                if (!liveMode) {
                    if (vts.stream().noneMatch(v -> v.state == VtState.RUNNING)) burst += 4;
                    burst += carriers.size() * 3;
                }
                pendingPin = true;
                addLog("chapter: pinned");
            }
            case 5 -> {
                if (!liveMode) burst += Math.max(0, maxThreads - vts.size());
                chaos = !liveMode;
                addLog("chapter: scale — flooding tasks");
            }
            case 6 -> {
                scenario = Scenario.PLATFORM_COMPARISON;
                scenarioSubmitted = Math.min(maxThreads, Math.max(24, carriers.size() * 8));
                if (!liveMode) burst += scenarioSubmitted;
                addLog("chapter: platform vs virtual — same I/O workload");
            }
            case 7 -> {
                scenario = Scenario.RESOURCE_POOL;
                scenarioSubmitted = Math.min(maxThreads, Math.max(18, carriers.size() * 5));
                if (!liveMode) burst += scenarioSubmitted;
                addLog("chapter: connection pool — " + resourceCapacity() + " permits");
            }
            case 8 -> {
                scenario = Scenario.CPU_BOUND;
                scenarioSubmitted = Math.min(maxThreads, Math.max(24, carriers.size() * 6));
                if (!liveMode) burst += scenarioSubmitted;
                addLog("chapter: CPU bound — throughput plateaus at carrier count");
            }
            case 9 -> {
                scenario = Scenario.STRUCTURED;
                scenarioSubmitted = 12;
                scopes.put(1, new ScopeProgress(1, "SEARCH", 4));
                scopes.put(2, new ScopeProgress(2, "CHECKOUT", 4));
                scopes.put(3, new ScopeProgress(3, "REPORT", 4));
                if (!liveMode) burst += scenarioSubmitted;
                addLog("chapter: structured scopes — fork, cancel, join");
            }
            default -> throw new AssertionError("unreachable");
        }
    }

    public void setFreeRun(boolean enabled) {
        if (scenario == Scenario.STRUCTURED && enabled) {
            addLog("structured scenario uses a fixed scope tree");
            return;
        }
        freeRun = enabled;
        chaos = enabled && !liveMode && scenario == Scenario.NONE;
        spawnRate = enabled && !liveMode ? taskRate : 0;
        addLog(enabled ? "free run: continuous load" : "guided mode");
    }

    public void reset(int carrierCount) {
        if (carrierCount < 2 || carrierCount > 10) {
            throw new IllegalArgumentException("carriers must be between 2 and 10");
        }
        random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        clearWorkload();
        carriers.clear();
        for (int i = 0; i < carrierCount; i++) carriers.add(new Carrier(i));
        log.clear();
        log.addFirst("boot: power on");
        flashes.clear();
        for (Flash flash : Flash.values()) flashes.put(flash, -9.0);
        nextId = 1;
        time = 0;
        bootT = 0;
        spawnRate = 0;
        spawnAcc = 0;
        burst = 0;
        completed = 0;
        ioSecondsTotal = 0;
        ioSamples = 0;
        chapter = 0;
        running = true;
        freeRun = false;
        chaos = false;
        pendingPark = false;
        pendingPin = false;
        bootAutoAdvanced = false;
        scenario = Scenario.NONE;
        scenarioSubmitted = 0;
        scenarioSpawned = 0;
        hero = null;
    }

    private void clearWorkload() {
        var cancelled = new IllegalStateException("simulation scenario changed");
        for (Vt vt : vts) {
            if (vt.firstMountSignal != null) vt.firstMountSignal.completeExceptionally(cancelled);
            if (vt.resumeMountSignal != null) vt.resumeMountSignal.completeExceptionally(cancelled);
        }
        for (LiveEvent event : liveEvents) {
            if (event instanceof LiveSpawned spawn) {
                spawn.firstMount().completeExceptionally(cancelled);
                spawn.resumeMount().completeExceptionally(cancelled);
            }
        }
        liveEvents.clear();
        liveById.clear();
        vts.clear();
        queue.clear();
        permitWaiters.clear();
        scopes.clear();
        permitsInUse = 0;
        burst = 0;
        spawnAcc = 0;
        completed = 0;
        ioSecondsTotal = 0;
        ioSamples = 0;
        hero = null;
        for (Carrier carrier : carriers) {
            carrier.mounted = null;
            carrier.pinT = 0;
            carrier.heat = 0;
        }
    }

    public void setLiveMode(boolean enabled) {
        if (liveMode == enabled) return;
        liveMode = enabled;
        reset(carriers.isEmpty() ? DEFAULT_CARRIERS : carriers.size());
        addLog(enabled ? "LIVE JDK workload · lanes illustrative" : "synthetic workload");
    }

    public void post(LiveEvent event) {
        if (event != null) liveEvents.add(event);
    }

    public void recordMessage(String message) {
        if (message != null && !message.isBlank()) addLog(message);
    }

    private void flash(Flash flash) {
        flashes.put(flash, time);
    }

    private void addLog(String message) {
        String formatted = "%03ds %s".formatted((int) Math.floor(time), message);
        log.addFirst(formatted);
        while (log.size() > 9) log.removeLast();
    }

    public Vec3 queueSlot(int index) {
        double angle = index * 0.55;
        double radius = 7 + 2.6 * Math.sqrt(index);
        return new Vec3(Math.cos(angle) * radius * 1.7, 80.5, Math.sin(angle) * radius * 0.62);
    }

    private Vec3 queueTarget(Vt vt, int index) {
        if (scenario == Scenario.STRUCTURED && vt.scopeId > 0) {
            return new Vec3(-45 + vt.scopeChildIndex * 30, 80.5, -18 + (vt.scopeId - 1) * 18);
        }
        return queueSlot(index);
    }

    public Vec3 heapSlot(int index) {
        int level = index / 25;
        int item = index % 25;
        return new Vec3(118 + (item % 5 - 2) * 6.8, 34 + level * 6.8,
                (item / 5 - 2) * 6.8);
    }

    public double laneX(int index) {
        double spacing = carriers.size() <= 1 ? 0 : Math.min(26, 130.0 / (carriers.size() - 1));
        return (index - (carriers.size() - 1) / 2.0) * spacing;
    }

    public List<Vt> vts() { return readOnlyVts; }
    public List<Carrier> carriers() { return readOnlyCarriers; }
    public Deque<String> log() { return new ArrayDeque<>(log); }
    public Vt hero() { return hero; }
    public int chapter() { return chapter; }
    public double bootT() { return bootT; }
    public double time() { return time; }
    public double speed() { return speed; }
    public boolean running() { return running; }
    public boolean freeRun() { return freeRun; }
    public boolean liveMode() { return liveMode; }
    public Scenario scenario() { return scenario; }
    public int scenarioSubmitted() { return scenarioSubmitted; }
    public int maxThreads() { return maxThreads; }
    public long seed() { return seed; }

    public void setRunning(boolean running) { this.running = running; }
    public void setSpeed(double speed) { this.speed = Math.max(0.25, Math.min(3.0, speed)); }

    public Stats stats() {
        int mounted = 0;
        for (Carrier carrier : carriers) if (carrier.mounted != null) mounted++;
        int parked = 0;
        for (Vt vt : vts) if (vt.state == VtState.PARKED || vt.state == VtState.PARKING) parked++;
        return new Stats(queue.size(), mounted, parked, completed);
    }

    public ProfileStats profileStats() {
        int fast = 0;
        int compute = 0;
        int ioBound = 0;
        for (Vt vt : vts) {
            switch (vt.profile) {
                case FAST -> fast++;
                case COMPUTE -> compute++;
                case IO_BOUND -> ioBound++;
            }
        }
        return new ProfileStats(fast, compute, ioBound);
    }

    private TaskProfile randomProfile() {
        double sample = random.nextDouble();
        if (sample < 0.36) return TaskProfile.FAST;
        if (sample < 0.68) return TaskProfile.COMPUTE;
        return TaskProfile.IO_BOUND;
    }

    /** Fraction of carrier slots currently occupied, in the range 0..1. */
    public double carrierUtilization() {
        if (carriers.isEmpty()) return 0;
        return (double) stats().mounted() / carriers.size();
    }

    /** Mean observed park duration for tasks that have resumed. */
    public double averageIoSeconds() {
        return ioSamples == 0 ? 0 : ioSecondsTotal / ioSamples;
    }

    public ResourcePoolStats resourcePoolStats() {
        return new ResourcePoolStats(resourceCapacity(), permitsInUse, permitWaiters.size());
    }

    public List<ScopeStats> structuredScopes() {
        return scopes.values().stream().map(ScopeProgress::snapshot).toList();
    }

    /**
     * Checks internal ownership and queue/carrier invariants. Kept public so
     * diagnostics and property-style tests can inspect a running model.
     */
    public List<String> invariantViolations() {
        List<String> violations = new ArrayList<>();
        if (vts.size() > maxThreads) violations.add("virtual-thread pool exceeds configured maximum");
        var ids = new java.util.HashSet<Long>();
        var queued = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Vt, Boolean>());
        for (Vt vt : queue) {
            if (!queued.add(vt)) violations.add("virtual thread appears twice in runnable queue: " + vt.id);
            if (!vts.contains(vt)) violations.add("runnable queue contains unknown virtual thread: " + vt.id);
        }
        var mounted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Vt, Boolean>());
        for (Carrier carrier : carriers) {
            Vt vt = carrier.mounted;
            if (vt == null) continue;
            if (!mounted.add(vt)) violations.add("virtual thread mounted on multiple carriers: " + vt.id);
            if (vt.carrier != carrier) violations.add("carrier/thread ownership mismatch: " + vt.id);
            if (vt.state != VtState.MOUNTING && vt.state != VtState.RUNNING) {
                violations.add("carrier owns non-running virtual thread: " + vt.id);
            }
        }
        for (Vt vt : vts) {
            if (!ids.add(vt.id)) violations.add("duplicate virtual-thread id: " + vt.id);
            boolean queueState = vt.state == VtState.TO_QUEUE || vt.state == VtState.QUEUED;
            if (queueState != queued.contains(vt)) violations.add("runnable queue/state mismatch: " + vt.id);
            boolean mountedState = vt.state == VtState.MOUNTING || vt.state == VtState.RUNNING;
            if (mountedState != (vt.carrier != null)) violations.add("mounted state/carrier mismatch: " + vt.id);
        }
        long permitHolders = vts.stream().filter(vt -> vt.resourcePermit).count();
        if (permitHolders != permitsInUse) violations.add("database permit count mismatch");
        if (permitsInUse > resourceCapacity()) violations.add("database permit pool exceeds capacity");
        var waitingForPermit = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Vt, Boolean>());
        for (Vt vt : permitWaiters) {
            if (!waitingForPermit.add(vt)) violations.add("virtual thread waits twice for a database permit: " + vt.id);
            if (!vt.waitingForPermit) violations.add("database permit queue/state mismatch: " + vt.id);
            if (!vts.contains(vt)) violations.add("database permit queue contains unknown virtual thread: " + vt.id);
        }
        for (Vt vt : vts) {
            if (vt.waitingForPermit != waitingForPermit.contains(vt)) {
                violations.add("database permit waiter/state mismatch: " + vt.id);
            }
            if (vt.waitingForPermit && vt.resourcePermit) {
                violations.add("virtual thread both holds and waits for a database permit: " + vt.id);
            }
        }
        return List.copyOf(violations);
    }

    public double flashAge(Flash flash) {
        return Math.max(0, time - flashes.getOrDefault(flash, -9.0));
    }
}
