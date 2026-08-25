package vtmachine.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
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

    public record Stats(int runnable, int mounted, int parked, int completed) {}
    public record ProfileStats(int fast, int compute, int ioBound) {}

    public enum TaskProfile {
        FAST("FAST CPU"), COMPUTE("COMPUTE"), IO_BOUND("I/O BOUND");

        private final String display;
        TaskProfile(String display) { this.display = display; }
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
            LiveResumed, LiveCompleted, LivePinned {}
    public record LiveSpawned(long threadId, CompletableFuture<Void> firstMount,
            CompletableFuture<Void> resumeMount, TaskProfile profile,
            double plannedIoSeconds) implements LiveEvent {}
    public record LiveProgress(long threadId, double progress) implements LiveEvent {}
    public record LiveParked(long threadId) implements LiveEvent {}
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
    private Vt hero;

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
                vt.state = switch (tween.arrival) {
                    case QUEUED -> VtState.QUEUED;
                    case RUNNING -> VtState.RUNNING;
                    case PARKED -> VtState.PARKED;
                    case DEAD -> VtState.DEAD;
                };
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
                drift(vt.pos, queueSlot(i), k);
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
            vt.io -= dt;
            if (vt.io <= 0) {
                resume(vt);
            }
        }
    }

    private void reapPhase() {
        for (Iterator<Vt> iterator = vts.iterator(); iterator.hasNext();) {
            Vt vt = iterator.next();
            if (vt.state == VtState.DEAD) {
                iterator.remove();
                liveById.remove(vt.id);
                if (hero == vt) hero = null;
            }
        }
    }

    private void spawn() {
        TaskProfile profile = randomProfile();
        double work = switch (profile) {
            case FAST -> 0.45 + random.nextDouble() * 1.05;
            case COMPUTE -> 2.2 + random.nextDouble() * 4.8;
            case IO_BOUND -> 0.8 + random.nextDouble() * 1.8;
        };
        double plannedIo = profile == TaskProfile.IO_BOUND ? 1.0 + random.nextDouble() * 7.0 : 0;
        double ioTrigger = profile == TaskProfile.IO_BOUND
                ? work * (0.38 + random.nextDouble() * 0.34) : -1;
        Vt vt = new Vt(nextId++, new Vec3(
                -130 + random.nextDouble() * 20 - 10,
                105 + random.nextDouble() * 12,
                random.nextDouble() * 20 - 10), work, profile, plannedIo, ioTrigger);
        if (hero == null) {
            hero = vt;
            vt.hero = true;
        }
        tween(vt, queueSlot(queue.size()), 0.7, Arrival.QUEUED);
        queue.add(vt);
        vts.add(vt);
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
                }
            } else if (event instanceof LiveParked parked) {
                Vt vt = liveById.get(parked.threadId());
                if (vt != null && vt.state == VtState.RUNNING) {
                    if (vt.carrier != null && vt.carrier.pinned()) vt.liveParkPending = true;
                    else park(vt);
                }
            } else if (event instanceof LiveResumed resumed) {
                Vt vt = liveById.get(resumed.threadId());
                if (vt != null && (vt.state == VtState.PARKED || vt.state == VtState.PARKING)) {
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
        Vt vt = new Vt(event.threadId(), new Vec3(
                -130 + random.nextDouble() * 20 - 10,
                105 + random.nextDouble() * 12,
                random.nextDouble() * 20 - 10), 1.0, event.profile(),
                event.plannedIoSeconds(), -1);
        vt.live = true;
        vt.firstMountSignal = event.firstMount();
        vt.resumeMountSignal = event.resumeMount();
        if (hero == null) {
            hero = vt;
            vt.hero = true;
        }
        tween(vt, queueSlot(queue.size()), 0.7, Arrival.QUEUED);
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
        vt.state = VtState.MOUNTING;
        tween(vt, new Vec3(laneX(carrier.index()), 30, 0), 0.55, Arrival.RUNNING);
        flash(Flash.MOUNT);
        addLog("VT-" + vt.id + (vt.resumed ? " resumed on C" : " mounted on C") + (carrier.index() + 1));
    }

    private void park(Vt vt) {
        Carrier carrier = vt.carrier;
        if (carrier != null) carrier.mounted = null;
        vt.carrier = null;
        vt.state = VtState.PARKING;
        vt.io = vt.live ? Double.POSITIVE_INFINITY
                : vt.plannedIoSeconds > 0 ? vt.plannedIoSeconds : 1.0 + random.nextDouble() * 7.0;
        vt.parkedAt = time;
        int parkedCount = 0;
        for (Vt candidate : vts) {
            if (candidate != vt && (candidate.state == VtState.PARKED || candidate.state == VtState.PARKING)) {
                parkedCount++;
            }
        }
        tween(vt, heapSlot(parkedCount), 0.85, Arrival.PARKED);
        flash(Flash.PARK);
        addLog("VT-" + vt.id + " I/O wait · carrier released");
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
        vt.state = VtState.TO_QUEUE;
        vt.resumed = true;
        vt.io = 0;
        queue.addFirst(vt);
        tween(vt, queueSlot(0), 0.85, Arrival.QUEUED);
        flash(Flash.RESUME);
        addLog("VT-" + vt.id + " I/O done · runnable");
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
        vt.state = VtState.DONE;
        tween(vt, new Vec3(155, 10, 90), 0.8, Arrival.DEAD);
        completed++;
        addLog("VT-" + vt.id + " completed · C" + (carrier.index() + 1) + " free · GC eligible");
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
        burst += count;
        addLog("burst: " + count + " tasks submitted");
    }

    public void gotoChapter(int requested) {
        int selected = Math.floorMod(requested, 6);
        chapter = selected;
        freeRun = false;
        chaos = false;
        spawnRate = 0;
        pendingPark = false;
        pendingPin = false;

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
                if (!liveMode && vts.stream().noneMatch(v -> v.state == VtState.RUNNING)) burst += 4;
                pendingPin = true;
                addLog("chapter: pinned");
            }
            case 5 -> {
                if (!liveMode) burst += Math.max(0, maxThreads - vts.size());
                chaos = !liveMode;
                addLog("chapter: scale — flooding tasks");
            }
            default -> throw new AssertionError("unreachable");
        }
    }

    public void setFreeRun(boolean enabled) {
        freeRun = enabled;
        chaos = enabled && !liveMode;
        spawnRate = enabled && !liveMode ? taskRate : 0;
        addLog(enabled ? "free run: continuous load" : "guided mode");
    }

    public void reset(int carrierCount) {
        if (carrierCount < 2 || carrierCount > 10) {
            throw new IllegalArgumentException("carriers must be between 2 and 10");
        }
        random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        var cancelled = new IllegalStateException("simulation reset");
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
        hero = null;
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

    public Vec3 heapSlot(int index) {
        int level = index / 25;
        int item = index % 25;
        return new Vec3(118 + (item % 5 - 2) * 6.4, 34 + level * 6.4,
                (item / 5 - 2) * 6.4);
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
        return List.copyOf(violations);
    }

    public double flashAge(Flash flash) {
        return Math.max(0, time - flashes.getOrDefault(flash, -9.0));
    }
}
