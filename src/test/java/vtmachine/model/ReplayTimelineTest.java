package vtmachine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ReplayTimelineTest {
    private static final double STEP = 1.0 / 60.0;

    @Test
    void capturesImmutableFramesAndLifecycleMarkersWithoutRewindingTheModel() {
        Sim sim = new Sim(4, 100, 1.4, 91);
        advance(sim, 4.5);
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.capture(sim, true);
        Vt parked = sim.vts().stream()
                .filter(vt -> vt.state() == Sim.VtState.RUNNING)
                .findFirst().orElseThrow();
        double capturedX = timeline.latest().vts().stream()
                .filter(vt -> vt.id() == parked.id()).findFirst().orElseThrow().x();

        assertTrue(sim.forcePark());
        timeline.capture(sim, true);
        assertTrue(timeline.markers().stream().anyMatch(marker ->
                marker.type() == ReplayTimeline.EventType.PARK && marker.vtId() == parked.id()));

        parked.pos.x += 500;
        assertNotEquals(capturedX, parked.pos.x);
        assertEquals(capturedX, timeline.frame(0).vts().stream()
                .filter(vt -> vt.id() == parked.id()).findFirst().orElseThrow().x(), 1e-9);
    }

    @Test
    void historyIsBoundedAndMarkerIndicesStayInsideTheRetainedWindow() {
        Sim sim = new Sim(4, 50, 1.4, 92);
        ReplayTimeline timeline = new ReplayTimeline();
        for (int frame = 0; frame < ReplayTimeline.MAX_FRAMES + 40; frame++) {
            sim.tick(STEP);
            timeline.capture(sim, true);
        }

        assertEquals(ReplayTimeline.MAX_FRAMES, timeline.size());
        assertTrue(timeline.markers().stream().allMatch(marker ->
                marker.frameIndex() >= 0 && marker.frameIndex() < timeline.size()));
    }

    @Test
    void sampledHistoryIncludesChapterSpawnAndMountEvents() {
        Sim sim = new Sim(4, 100, 1.4, 93);
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.capture(sim, true);
        for (int step = 0; step < 300; step++) {
            sim.tick(STEP);
            timeline.capture(sim);
        }

        assertTrue(timeline.markers().stream().anyMatch(marker ->
                marker.type() == ReplayTimeline.EventType.CHAPTER));
        assertTrue(timeline.markers().stream().anyMatch(marker ->
                marker.type() == ReplayTimeline.EventType.SPAWN));
        assertTrue(timeline.markers().stream().anyMatch(marker ->
                marker.type() == ReplayTimeline.EventType.MOUNT));
    }

    @Test
    void scaleHistoryRemainsWithinTheReplayBudget() {
        assertTimeout(Duration.ofSeconds(3), () -> {
            Sim sim = new Sim(4, 500, 1.4, 94);
            advance(sim, 3.1);
            sim.gotoChapter(5);
            ReplayTimeline timeline = new ReplayTimeline();
            for (int frame = 0; frame < ReplayTimeline.MAX_FRAMES + 20; frame++) {
                advance(sim, ReplayTimeline.SAMPLE_SECONDS);
                timeline.capture(sim, true);
            }
            assertEquals(ReplayTimeline.MAX_FRAMES, timeline.size());
            assertTrue(timeline.latest().vts().size() <= 500);
        });
    }

    @Test
    void structuredPresentationStateIsCapturedImmutably() {
        Sim sim = new Sim(4, 100, 1.4, 95);
        advance(sim, 3.1);
        sim.gotoChapter(9);
        advance(sim, 0.3);
        ReplayTimeline timeline = new ReplayTimeline();
        timeline.capture(sim, true);

        assertEquals(Sim.JoinPolicy.SHUTDOWN_ON_FAILURE,
                timeline.latest().structuredStory().policy());
        assertTrue(sim.cycleStructuredPolicy());
        timeline.capture(sim, true);

        assertEquals(Sim.JoinPolicy.SHUTDOWN_ON_FAILURE,
                timeline.frame(0).structuredStory().policy());
        assertEquals(Sim.JoinPolicy.SHUTDOWN_ON_SUCCESS,
                timeline.latest().structuredStory().policy());
    }

    private static void advance(Sim sim, double seconds) {
        for (int i = 0; i < Math.ceil(seconds / STEP); i++) sim.tick(STEP);
    }
}
