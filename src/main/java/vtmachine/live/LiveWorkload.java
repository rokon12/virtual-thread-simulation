package vtmachine.live;

import java.time.Duration;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import vtmachine.model.Sim;
import vtmachine.model.Sim.TaskProfile;

/**
 * A real {@code Thread.ofVirtual()} workload that drives the JavaFX-free model
 * through its thread-safe event inbox. Visual lanes remain illustrative because
 * the Java API intentionally does not expose ordinary carrier identity.
 */
public final class LiveWorkload implements AutoCloseable {
    private final Sim sim;
    private final int maxActive;
    private final double taskRate;
    private final long seed;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger submitted = new AtomicInteger();
    private final AtomicInteger finished = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Semaphore databasePermits;
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> ioWaits = new ConcurrentLinkedQueue<>();
    private final Set<CompletableFuture<Void>> mountSignals = ConcurrentHashMap.newKeySet();
    private final RecordingStream recordingStream;
    private double spawnAccumulator;
    private int pendingScenarioTasks;
    private TaskProfile pendingScenarioProfile;
    private boolean pendingDatabaseLimit;

    public LiveWorkload(Sim sim, int maxActive, double taskRate, long seed) {
        this.sim = sim;
        this.maxActive = maxActive;
        this.taskRate = taskRate;
        this.seed = seed;
        databasePermits = new Semaphore(Math.min(Sim.DATABASE_PERMITS, sim.carriers().size()), true);
        recordingStream = startPinWatch();
    }

    public int submit(int requested) {
        return submit(requested, null);
    }

    private int submit(int requested, TaskProfile forcedProfile) {
        return submit(requested, forcedProfile, false);
    }

    private int submit(int requested, TaskProfile forcedProfile, boolean databaseLimited) {
        if (requested <= 0 || closed.get()) return 0;
        int accepted = 0;
        while (accepted < requested) {
            int current = active.get();
            if (current >= maxActive || !active.compareAndSet(current, current + 1)) {
                if (current >= maxActive) break;
                continue;
            }
            int sequence = submitted.incrementAndGet();
            executor.submit(() -> runTask(sequence, forcedProfile, databaseLimited));
            accepted++;
        }
        if (accepted > 0) sim.recordMessage("LIVE: " + accepted + " virtual tasks submitted");
        return accepted;
    }

    public void tick(double wallSeconds, boolean continuous) {
        drainScheduledScenario();
        if (!continuous || closed.get()) {
            spawnAccumulator = 0;
            return;
        }
        spawnAccumulator += taskRate * wallSeconds;
        int count = Math.min(6, (int) spawnAccumulator);
        if (count > 0) {
            int accepted = submit(count);
            spawnAccumulator -= accepted;
            if (accepted == 0) spawnAccumulator = Math.min(spawnAccumulator, 1);
        }
    }

    public void onChapter(int chapter) {
        pendingScenarioTasks = 0;
        pendingScenarioProfile = null;
        pendingDatabaseLimit = false;
        switch (Math.floorMod(chapter, Sim.CHAPTER_COUNT)) {
            case 1 -> submit(6);
            case 2 -> submit(Math.max(0, 4 - active.get()));
            case 3 -> {
                expediteIo();
                submit(Math.max(0, 2 - active.get()));
            }
            case 4 -> submit(Math.max(0,
                    Math.min(maxActive, sim.carriers().size() * 4) - active.get()));
            case 5 -> submit(maxActive - active.get());
            case 6 -> scheduleScenario(Math.min(maxActive, Math.max(24, sim.carriers().size() * 8)),
                    TaskProfile.IO_BOUND, false);
            case 7 -> scheduleScenario(Math.min(maxActive, Math.max(18, sim.carriers().size() * 5)),
                    TaskProfile.IO_BOUND, true);
            case 8 -> scheduleScenario(Math.min(maxActive, Math.max(24, sim.carriers().size() * 6)),
                    TaskProfile.COMPUTE, false);
            case 9 -> scheduleScenario(Math.min(maxActive, 12), null, false);
            default -> { }
        }
    }

    private void scheduleScenario(int count, TaskProfile profile, boolean databaseLimited) {
        pendingScenarioTasks = count;
        pendingScenarioProfile = profile;
        pendingDatabaseLimit = databaseLimited;
        drainScheduledScenario();
    }

    private void drainScheduledScenario() {
        if (pendingScenarioTasks <= 0 || closed.get()) return;
        int accepted = submit(Math.min(6, pendingScenarioTasks),
                pendingScenarioProfile, pendingDatabaseLimit);
        pendingScenarioTasks -= accepted;
    }

    public boolean expediteIo() {
        CompletableFuture<Void> gate;
        while ((gate = ioWaits.poll()) != null) {
            if (gate.complete(null)) return true;
        }
        return false;
    }

    private void runTask(int sequence, TaskProfile forcedProfile, boolean databaseLimited) {
        CompletableFuture<Void> firstMount = new CompletableFuture<>();
        CompletableFuture<Void> resumeMount = new CompletableFuture<>();
        mountSignals.add(firstMount);
        mountSignals.add(resumeMount);
        long threadId = Thread.currentThread().threadId();
        SplittableRandom random = new SplittableRandom(seed ^ (sequence * 0x9e3779b97f4a7c15L));
        TaskProfile profile = forcedProfile == null ? randomProfile(random) : forcedProfile;
        int firstCpuMillis = switch (profile) {
            case FAST -> 45 + random.nextInt(150);
            case COMPUTE -> 350 + random.nextInt(651);
            case IO_BOUND -> 80 + random.nextInt(181);
        };
        int ioMillis = profile == TaskProfile.IO_BOUND ? 1_000 + random.nextInt(6_501) : 0;
        try {
            sim.post(new Sim.LiveSpawned(threadId, firstMount, resumeMount, profile, ioMillis / 1_000.0));
            await(firstMount);
            if (profile != TaskProfile.IO_BOUND) {
                cpuPhase(threadId, 0.0, 0.98, firstCpuMillis);
                sim.post(new Sim.LiveCompleted(threadId));
                finished.incrementAndGet();
                return;
            }
            cpuPhase(threadId, 0.0, 0.38, firstCpuMillis);

            sim.post(new Sim.LiveParked(threadId));
            boolean permitAcquired = false;
            if (databaseLimited) {
                databasePermits.acquire();
                permitAcquired = true;
                sim.post(new Sim.LivePermitAcquired(threadId));
            }
            CompletableFuture<Void> ioGate = new CompletableFuture<>();
            ioWaits.add(ioGate);
            long ioStarted = System.nanoTime();
            try {
                ioGate.get(ioMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expected) {
                // A real timed wait completed; the virtual thread was unmounted meanwhile.
            } finally {
                ioWaits.remove(ioGate);
                if (permitAcquired) databasePermits.release();
            }

            double ioSeconds = (System.nanoTime() - ioStarted) / 1_000_000_000.0;
            sim.post(new Sim.LiveResumed(threadId, ioSeconds));
            await(resumeMount);
            cpuPhase(threadId, 0.52, 0.98, 70 + random.nextInt(151));
            sim.post(new Sim.LiveCompleted(threadId));
            finished.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException cancelled) {
            // Reset or mode switch: the model deliberately released this task.
        } finally {
            mountSignals.remove(firstMount);
            mountSignals.remove(resumeMount);
            active.decrementAndGet();
        }
    }

    private void cpuPhase(long threadId, double from, double to, int millis) throws InterruptedException {
        int slices = 10;
        long sliceNanos = Duration.ofMillis(millis).toNanos() / slices;
        long checksum = threadId;
        for (int slice = 1; slice <= slices; slice++) {
            while (!sim.running() && !closed.get()) LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            if (Thread.interrupted() || closed.get()) throw new InterruptedException();
            long until = System.nanoTime() + sliceNanos;
            while (System.nanoTime() < until) {
                checksum = Long.rotateLeft(checksum ^ 0x9e3779b97f4a7c15L, 7) * 31 + slice;
                Thread.onSpinWait();
            }
            sim.post(new Sim.LiveProgress(threadId, from + (to - from) * slice / slices));
        }
        if (checksum == Long.MIN_VALUE) System.out.print(""); // preserve the calculation
    }

    private static void await(CompletableFuture<Void> signal) throws InterruptedException, ExecutionException {
        signal.get();
    }

    private static TaskProfile randomProfile(SplittableRandom random) {
        double sample = random.nextDouble();
        if (sample < 0.36) return TaskProfile.FAST;
        if (sample < 0.68) return TaskProfile.COMPUTE;
        return TaskProfile.IO_BOUND;
    }

    private RecordingStream startPinWatch() {
        try {
            RecordingStream stream = new RecordingStream();
            stream.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(1));
            stream.onEvent("jdk.VirtualThreadPinned", this::onPinned);
            stream.startAsync();
            return stream;
        } catch (RuntimeException unavailable) {
            sim.recordMessage("JFR pin watch unavailable");
            return null;
        }
    }

    private void onPinned(RecordedEvent event) {
        if (closed.get() || event.getThread() == null) return;
        long threadId = event.getThread().getJavaThreadId();
        String reason = field(event, "pinnedReason", "native/VM frame");
        sim.post(new Sim.LivePinned(threadId,
                Math.max(0.001, event.getDuration().toNanos() / 1_000_000_000.0), reason));
    }

    private static String field(RecordedEvent event, String name, String fallback) {
        try {
            Object value = event.hasField(name) ? event.getValue(name) : null;
            return value == null ? fallback : value.toString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public int activeTasks() { return active.get(); }
    public int submittedTasks() { return submitted.get(); }
    public int finishedTasks() { return finished.get(); }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (recordingStream != null) recordingStream.close();
        for (CompletableFuture<Void> signal : mountSignals) signal.cancel(true);
        for (CompletableFuture<Void> gate : ioWaits) gate.cancel(true);
        executor.shutdownNow();
    }
}
