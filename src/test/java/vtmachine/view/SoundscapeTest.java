package vtmachine.view;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SoundscapeTest {
    @Test
    void everyCueSynthesizesAQuietNonEmptyPcmSignal() {
        int previousLength = -1;
        for (Soundscape.Cue cue : Soundscape.Cue.values()) {
            byte[] sample = Soundscape.synthesize(cue);
            assertTrue(sample.length > 4_000, cue + " should be audible long enough to perceive");
            assertTrue(sample.length % 2 == 0, cue + " must contain complete 16-bit frames");
            boolean hasSignal = false;
            for (byte value : sample) {
                if (value != 0) {
                    hasSignal = true;
                    break;
                }
            }
            assertTrue(hasSignal, cue + " must not be silent");
            if (cue == Soundscape.Cue.PIN) assertNotEquals(previousLength, sample.length);
            previousLength = sample.length;
        }
    }
}
