package vtmachine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class SimTest {
    private static final double STEP = 1.0 / 60.0;

    @Test
    void bootAutoEntersMountAndStartsSixTasks() {
        Sim sim = new Sim(4, 500, 1.4, 42);
        advance(sim, 3.1);

        assertEquals(1, sim.chapter());
        assertTrue(sim.log().stream().anyMatch(line -> line.endsWith("scheduler online")));

        advance(sim, 0.2);
        assertEquals(6, sim.vts().size());
        assertEquals(6, sim.stats().runnable());
    }

    @Test
    void aForcedParkReleasesItsCarrierInTheSameTick() {
        Sim sim = runningMachine(7);
        int mountedBefore = sim.stats().mounted();

        assertTrue(sim.forcePark());
        assertEquals(mountedBefore - 1, sim.stats().mounted());
        assertEquals(1, sim.stats().parked());
        assertTrue(sim.log().peekFirst().contains("I/O wait · carrier released"));
    }

    @Test
    void pinFreezesWorkUntilItExpires() {
        Sim sim = runningMachine(19);
        Vt running = sim.vts().stream().filter(v -> v.state() == Sim.VtState.RUNNING).findFirst().orElseThrow();

        assertTrue(sim.forcePin());
        double work = running.work();
        advance(sim, 1.0);
        assertEquals(work, running.work(), 1e-9);
        assertTrue(running.carrier().pinned());

        advance(sim, 4.0);
        assertFalse(running.carrier() != null && running.carrier().pinned());
    }

    @Test
    void pinnedChapterCreatesVisibleQueuePressureBehindTheBlockedCarrier() {
        Sim sim = runningMachine(23);
        sim.gotoChapter(4);
        advance(sim, 0.25);

        assertTrue(sim.carriers().stream().anyMatch(Carrier::pinned));
        assertTrue(sim.stats().runnable() > 0, "the comparison should show work waiting behind saturated lanes");
    }

    @Test
    void sameSeedAndFixedStepsProduceTheSameEventLog() {
        Sim first = new Sim(4, 100, 4.0, 8675309);
        Sim second = new Sim(4, 100, 4.0, 8675309);
        advance(first, 3.1);
        advance(second, 3.1);
        first.setFreeRun(true);
        second.setFreeRun(true);
        advance(first, 60);
        advance(second, 60);

        assertEquals(List.copyOf(first.log()), List.copyOf(second.log()));
        assertEquals(first.stats(), second.stats());
    }

    @Test
    void chaptersWrapInBothDirections() {
        Sim sim = new Sim(4, 500, 1.4, 3);
        sim.gotoChapter(-1);
        assertEquals(9, sim.chapter());
        sim.gotoChapter(Sim.CHAPTER_COUNT);
        assertEquals(0, sim.chapter());
    }

    @Test
    void comparisonRunsTheSameBlockingWorkloadOnAFixedCarrierPool() {
        Sim sim = new Sim(4, 100, 1.4, 101);
        advance(sim, 3.1);
        sim.gotoChapter(6);
        advance(sim, 2.5);

        assertEquals(Sim.Scenario.PLATFORM_COMPARISON, sim.scenario());
        assertEquals(32, sim.scenarioSubmitted());
        assertTrue(sim.vts().stream().allMatch(vt -> vt.profile() == Sim.TaskProfile.IO_BOUND));
        assertTrue(sim.stats().parked() > 0);
        assertTrue(sim.stats().mounted() <= sim.carriers().size());
    }

    @Test
    void databasePoolCapsPermitsAndParksExcessWaiters() {
        Sim sim = new Sim(4, 100, 1.4, 202);
        advance(sim, 3.1);
        sim.gotoChapter(7);
        advance(sim, 3.0);

        Sim.ResourcePoolStats pool = sim.resourcePoolStats();
        assertEquals(3, pool.capacity());
        assertEquals(3, pool.inUse());
        assertTrue(pool.waiting() > 0);
        assertTrue(sim.vts().stream().filter(Vt::resourcePermit)
                .allMatch(vt -> vt.ioDevice() == Sim.IoDevice.DATABASE));
        assertTrue(sim.invariantViolations().isEmpty());
    }

    @Test
    void cpuBoundChapterSaturatesCarriersButDoesNotCreateParallelism() {
        Sim sim = new Sim(6, 100, 1.4, 303);
        advance(sim, 3.1);
        sim.gotoChapter(8);
        advance(sim, 2.0);

        assertEquals(Sim.Scenario.CPU_BOUND, sim.scenario());
        assertEquals(6, sim.stats().mounted());
        assertTrue(sim.stats().runnable() > 0);
        assertEquals(0, sim.stats().parked());
        assertTrue(sim.vts().stream().allMatch(vt -> vt.profile() == Sim.TaskProfile.COMPUTE));
    }

    @Test
    void structuredFailureCancelsOnlyItsSiblingScopeAndJoins() {
        Sim sim = new Sim(4, 100, 1.4, 404);
        advance(sim, 3.1);
        sim.gotoChapter(9);
        advance(sim, 7.0);

        Sim.ScopeStats checkout = sim.structuredScopes().stream()
                .filter(scope -> scope.name().equals("CHECKOUT"))
                .findFirst().orElseThrow();
        assertTrue(checkout.joined());
        assertEquals(1, checkout.failed());
        assertTrue(checkout.cancelled() > 0);
        assertTrue(sim.structuredScopes().stream()
                .filter(scope -> !scope.name().equals("CHECKOUT"))
                .allMatch(scope -> scope.failed() == 0 && scope.cancelled() == 0));
        assertTrue(sim.invariantViolations().isEmpty());
    }

    @Test
    void scaleChapterFillsButNeverExceedsTheConfiguredPool() {
        Sim sim = new Sim(4, 50, 1.4, 11);
        advance(sim, 3.1);
        sim.gotoChapter(5);
        advance(sim, 0.25);

        assertEquals(50, sim.vts().size());
        advance(sim, 10);
        assertTrue(sim.vts().size() <= 50);
    }

    @Test
    void resetPreservesTheSelectedSpeed() {
        Sim sim = new Sim(4, 500, 1.4, 5);
        sim.setSpeed(2.25);
        sim.gotoChapter(0);

        assertEquals(2.25, sim.speed());
        assertEquals(0, sim.chapter());
        assertEquals(0, sim.vts().size());
    }

    @Test
    void tenCarrierLanesFitTheMachineAndMixedTasksGetRandomDurations() {
        Sim sim = new Sim(10, 100, 1.4, 4242);
        assertEquals(10, sim.carriers().size());
        assertEquals(-65, sim.laneX(0), 1e-9);
        assertEquals(65, sim.laneX(9), 1e-9);

        advance(sim, 3.1);
        sim.gotoChapter(5);
        advance(sim, 0.4);
        Sim.ProfileStats mix = sim.profileStats();
        assertTrue(mix.fast() > 0);
        assertTrue(mix.compute() > 0);
        assertTrue(mix.ioBound() > 0);
        assertEquals(sim.vts().size(), mix.fast() + mix.compute() + mix.ioBound());
        assertTrue(sim.vts().stream().filter(vt -> vt.profile() == Sim.TaskProfile.IO_BOUND)
                .allMatch(vt -> vt.plannedIoSeconds() >= 1 && vt.plannedIoSeconds() <= 8));

        advance(sim, 12);
        assertTrue(sim.averageIoSeconds() > 0, "I/O-bound tasks should visibly park and resume");
    }

    @Test
    void followedLifecycleRecordsDurationsAndCompletionDissolvesAtTheCarrier() {
        Sim sim = runningMachine(4242);
        assertTrue(sim.forcePark());
        Vt followed = sim.vts().stream()
                .filter(vt -> vt.state() == Sim.VtState.PARKING)
                .findFirst().orElseThrow();

        assertTrue(followed.lifecycleSeconds(Sim.LifecyclePhase.MOUNTED, sim.time()) > 0);
        assertTrue(followed.ioDevice() != null, "every parked VT should connect to an external I/O endpoint");
        advance(sim, 0.9);
        assertEquals(Sim.LifecyclePhase.PARKED, followed.lifecyclePhase());

        followed.work = 10;
        followed.io = 0.05;
        advance(sim, 1.6);
        assertTrue(followed.lifecycleSeconds(Sim.LifecyclePhase.PARKED, sim.time()) > 0.05);
        assertEquals(Sim.VtState.RUNNING, followed.state());
        followed.work = 0.01;
        advance(sim, 0.2);

        assertEquals(Sim.VtState.DONE, followed.state());
        assertEquals(Sim.LifecyclePhase.TERMINATED, followed.lifecyclePhase());
        assertTrue(Math.abs(followed.pos().x) <= 70, "completion should dissolve at its carrier lane");
        assertTrue(sim.log().stream().anyMatch(line -> line.contains("free · terminated")));

        advance(sim, 1.5);
        assertFalse(sim.vts().contains(followed), "the dissolve should eventually leave the scene");
        double terminatedSeconds = followed.lifecycleSeconds(Sim.LifecyclePhase.TERMINATED, sim.time());
        advance(sim, 1.0);
        assertEquals(terminatedSeconds,
                followed.lifecycleSeconds(Sim.LifecyclePhase.TERMINATED, sim.time()), 1e-9,
                "the follow card should freeze its final duration after the dissolve");
    }

    @Test
    void invariantsHoldAcrossASeededActionStorm() {
        Sim sim = new Sim(6, 200, 5.5, 0x5eed);
        SplittableRandom actions = new SplittableRandom(99);
        advance(sim, 3.2);
        sim.setFreeRun(true);
        for (int step = 0; step < 18_000; step++) {
            if (step % 73 == 0) {
                switch (actions.nextInt(6)) {
                    case 0 -> sim.burst(actions.nextInt(1, 40));
                    case 1 -> sim.forcePark();
                    case 2 -> sim.forcePin();
                    case 3 -> sim.setSpeed(actions.nextDouble(0.25, 3.0));
                    case 4 -> sim.setFreeRun(actions.nextBoolean());
                    default -> sim.gotoChapter(actions.nextInt(1, Sim.CHAPTER_COUNT));
                }
            }
            sim.tick(STEP);
            assertTrue(sim.invariantViolations().isEmpty(), () -> String.join("; ", sim.invariantViolations()));
        }
    }

    @Test
    void deterministicScenarioKeepsItsGoldenEventVocabulary() {
        Sim sim = new Sim(4, 80, 1.4, 42);
        StringBuilder witnessed = new StringBuilder();
        advance(sim, 4.5);
        witnessed.append(String.join("\n", sim.log())).append('\n');
        assertTrue(sim.forcePark());
        witnessed.append(sim.log().peekFirst()).append('\n');
        advance(sim, 1.0);
        assertTrue(sim.forcePin());
        witnessed.append(sim.log().peekFirst()).append('\n');
        advance(sim, 5.0);

        String log = witnessed.append(String.join("\n", sim.log())).toString();
        for (String expected : List.of("scheduler online", "mounted on C", "I/O wait · carrier released",
                "PINNED on C", "unpinned · resumes")) {
            assertTrue(log.contains(expected), () -> "Missing golden event: " + expected + "\n" + log);
        }
    }

    @Test
    void scaleWorkloadStaysWithinAReasonableModelBudget() {
        assertTimeout(Duration.ofSeconds(3), () -> {
            Sim sim = new Sim(4, 800, 6.0, 77);
            advance(sim, 3.1);
            sim.gotoChapter(5);
            advance(sim, 45);
            assertTrue(sim.invariantViolations().isEmpty());
        });
    }

    private static Sim runningMachine(long seed) {
        Sim sim = new Sim(4, 500, 1.4, seed);
        advance(sim, 4.5);
        assertTrue(sim.stats().mounted() > 0);
        return sim;
    }

    private static void advance(Sim sim, double seconds) {
        int steps = (int) Math.ceil(seconds / STEP);
        for (int i = 0; i < steps; i++) sim.tick(STEP);
    }
}
