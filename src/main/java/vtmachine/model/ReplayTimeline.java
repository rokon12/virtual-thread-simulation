package vtmachine.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded, deterministic replay history sampled from the JavaFX-free model. */
public final class ReplayTimeline {
    public static final int MAX_FRAMES = 360;
    public static final double SAMPLE_SECONDS = 0.15;

    public enum EventType { CHAPTER, SPAWN, MOUNT, PARK, RESUME, PIN, COMPLETE, FAIL, CANCEL }
    public record Marker(int frameIndex, EventType type, String label, long vtId) {}

    private final List<ReplayFrame> frames = new ArrayList<>();
    private final List<Marker> markers = new ArrayList<>();

    public boolean capture(Sim sim) { return capture(sim, false); }

    public boolean capture(Sim sim, boolean force) {
        if (!frames.isEmpty() && sim.time() + 1e-9 < frames.getLast().time()) clear();
        ReplayFrame previous = frames.isEmpty() ? null : frames.getLast();
        if (!force && previous != null && previous.chapter() == sim.chapter()
                && sim.time() - previous.time() < SAMPLE_SECONDS) return false;

        ReplayFrame current = ReplayFrame.capture(sim);
        if (frames.size() == MAX_FRAMES) dropOldest();
        int frameIndex = frames.size();
        frames.add(current);
        detectMarkers(previous, current, frameIndex);
        return true;
    }

    private void detectMarkers(ReplayFrame previous, ReplayFrame current, int frameIndex) {
        if (previous == null || previous.chapter() != current.chapter()) {
            markers.add(new Marker(frameIndex, EventType.CHAPTER,
                    "Chapter " + (current.chapter() + 1), -1));
        }
        Map<Long, ThreadView> before = new HashMap<>();
        if (previous != null) for (ThreadView vt : previous.vts()) before.put(vt.id(), vt);
        EnumMap<EventType, List<Long>> events = new EnumMap<>(EventType.class);
        for (ThreadView vt : current.vts()) {
            ThreadView old = before.get(vt.id());
            if (old == null) {
                events.computeIfAbsent(EventType.SPAWN, ignored -> new ArrayList<>()).add(vt.id());
                continue;
            }
            if (!old.carrierPinned() && vt.carrierPinned()) add(events, EventType.PIN, vt.id());
            if (old.outcome() != vt.outcome()) {
                switch (vt.outcome()) {
                    case FAILED -> add(events, EventType.FAIL, vt.id());
                    case CANCELLED -> add(events, EventType.CANCEL, vt.id());
                    case COMPLETED -> add(events, EventType.COMPLETE, vt.id());
                    case ACTIVE -> { }
                }
            } else if (!mounted(old.state()) && mounted(vt.state())) {
                add(events, EventType.MOUNT, vt.id());
            } else if (!parked(old.state()) && parked(vt.state())) {
                add(events, EventType.PARK, vt.id());
            } else if (parked(old.state()) && runnable(vt.state()) && vt.resumed()) {
                add(events, EventType.RESUME, vt.id());
            }
        }
        for (Map.Entry<EventType, List<Long>> entry : events.entrySet()) {
            List<Long> ids = entry.getValue();
            long id = ids.size() == 1 ? ids.getFirst() : -1;
            String label = ids.size() == 1 ? "VT-" + id + " " + verb(entry.getKey())
                    : ids.size() + " VTs " + verb(entry.getKey());
            markers.add(new Marker(frameIndex, entry.getKey(), label, id));
        }
    }

    private static void add(EnumMap<EventType, List<Long>> events, EventType type, long id) {
        events.computeIfAbsent(type, ignored -> new ArrayList<>()).add(id);
    }

    private static boolean runnable(Sim.VtState state) {
        return state == Sim.VtState.TO_QUEUE || state == Sim.VtState.QUEUED;
    }

    private static boolean mounted(Sim.VtState state) {
        return state == Sim.VtState.MOUNTING || state == Sim.VtState.RUNNING;
    }

    private static boolean parked(Sim.VtState state) {
        return state == Sim.VtState.PARKING || state == Sim.VtState.PARKED;
    }

    private static String verb(EventType type) {
        return switch (type) {
            case SPAWN -> "spawned";
            case MOUNT -> "mounted";
            case PARK -> "parked";
            case RESUME -> "resumed";
            case PIN -> "pinned";
            case COMPLETE -> "completed";
            case FAIL -> "failed";
            case CANCEL -> "cancelled";
            case CHAPTER -> "chapter";
        };
    }

    private void dropOldest() {
        frames.removeFirst();
        for (int i = markers.size() - 1; i >= 0; i--) {
            Marker marker = markers.get(i);
            if (marker.frameIndex() == 0) markers.remove(i);
            else markers.set(i, new Marker(marker.frameIndex() - 1,
                    marker.type(), marker.label(), marker.vtId()));
        }
    }

    public void clear() {
        frames.clear();
        markers.clear();
    }

    public int size() { return frames.size(); }
    public ReplayFrame frame(int index) {
        if (frames.isEmpty()) throw new IllegalStateException("replay history is empty");
        return frames.get(Math.max(0, Math.min(index, frames.size() - 1)));
    }
    public ReplayFrame latest() { return frames.isEmpty() ? null : frames.getLast(); }
    public List<ReplayFrame> frames() { return List.copyOf(frames); }
    public List<Marker> markers() { return List.copyOf(markers); }
    public List<Marker> markersAt(int frameIndex) {
        return markers.stream().filter(marker -> marker.frameIndex() == frameIndex).toList();
    }
}
