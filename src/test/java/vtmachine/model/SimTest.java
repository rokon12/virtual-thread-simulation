package vtmachine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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
        assertTrue(sim.log().peekFirst().contains("parked · carrier released"));
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
        assertEquals(5, sim.chapter());
        sim.gotoChapter(6);
        assertEquals(0, sim.chapter());
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
