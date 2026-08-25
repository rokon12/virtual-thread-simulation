package vtmachine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AppSettingsTest {
    @Test
    void parsesFlagsSnapshotChapterAndBounds() {
        App.Settings settings = App.Settings.from(List.of("--live", "--presenter", "--snapshot-follow",
                "--snapshot-replay",
                "--carriers=99", "--max-threads=12", "--task-rate=2.5",
                "--seed=42", "--snapshot-chapter=99"));

        assertTrue(settings.live());
        assertTrue(settings.presenter());
        assertTrue(settings.snapshotFollow());
        assertTrue(settings.snapshotReplay());
        assertEquals(10, settings.carriers());
        assertEquals(50, settings.maxThreads());
        assertEquals(2.5, settings.taskRate());
        assertEquals(42, settings.seed());
        assertEquals(10, settings.snapshotChapter());
    }
}
