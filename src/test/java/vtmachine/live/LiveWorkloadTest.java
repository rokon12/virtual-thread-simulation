package vtmachine.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import vtmachine.model.Sim;

class LiveWorkloadTest {
    private static final double STEP = 1.0 / 60.0;

    @Test
    void realVirtualThreadCompletesThroughTheModelBridge() throws Exception {
        Sim sim = new Sim(4, 50, 1.4, 123);
        sim.setLiveMode(true);
        advance(sim, 3.1);
        try (LiveWorkload workload = new LiveWorkload(sim, 50, 1.4, 123)) {
            assertEquals(1, workload.submit(1));
            long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
            boolean expedited = false;
            while (sim.stats().completed() == 0 && System.nanoTime() < deadline) {
                sim.tick(STEP);
                if (!expedited && sim.stats().parked() > 0) expedited = workload.expediteIo();
                Thread.sleep(2);
            }
            assertEquals(1, sim.stats().completed());
            assertTrue(workload.finishedTasks() >= 1);
            assertTrue(sim.averageIoSeconds() > 0, "live I/O duration should use the measured wall time");
            assertTrue(sim.invariantViolations().isEmpty(), sim.invariantViolations().toString());
        }
    }

    @Test
    void realVirtualThreadsAlsoQueueBehindTheDatabasePermitLimit() throws Exception {
        Sim sim = new Sim(4, 50, 1.4, 456);
        sim.setLiveMode(true);
        advance(sim, 3.1);
        sim.gotoChapter(7);
        try (LiveWorkload workload = new LiveWorkload(sim, 50, 1.4, 456)) {
            workload.onChapter(7);
            long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
            Sim.ResourcePoolStats pool = sim.resourcePoolStats();
            while ((pool.inUse() < pool.capacity() || pool.waiting() == 0)
                    && System.nanoTime() < deadline) {
                sim.tick(STEP);
                Thread.sleep(2);
                pool = sim.resourcePoolStats();
            }
            assertEquals(3, pool.inUse());
            assertTrue(pool.waiting() > 0);
            assertTrue(sim.invariantViolations().isEmpty(), sim.invariantViolations().toString());
        }
    }

    private static void advance(Sim sim, double seconds) {
        for (int i = 0; i < Math.ceil(seconds / STEP); i++) sim.tick(STEP);
    }
}
