package vtmachine.view;

import java.util.EnumMap;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;

/** Small locally synthesized presentation cues; no external audio assets required. */
final class Soundscape implements AutoCloseable {
    enum Cue { MOUNT, PARK, RESUME, PIN, COMPLETE }

    private static final float SAMPLE_RATE = 44_100;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    private final EnumMap<Cue, Clip> clips = new EnumMap<>(Cue.class);
    private boolean enabled;
    private boolean initialised;
    private boolean available = true;

    boolean toggle() {
        setEnabled(!enabled);
        return enabled;
    }

    void setEnabled(boolean requested) {
        if (requested && !initialised) initialise();
        enabled = requested && available;
    }

    boolean enabled() { return enabled; }

    void play(Cue cue) {
        if (!enabled) return;
        Clip clip = clips.get(cue);
        if (clip == null) return;
        if (clip.isRunning()) clip.stop();
        clip.setFramePosition(0);
        clip.start();
    }

    private void initialise() {
        initialised = true;
        try {
            for (Cue cue : Cue.values()) {
                byte[] sample = synthesize(cue);
                Clip clip = AudioSystem.getClip();
                clip.open(FORMAT, sample, 0, sample.length);
                clips.put(cue, clip);
            }
        } catch (LineUnavailableException | IllegalArgumentException unavailable) {
            available = false;
            close();
        }
    }

    static byte[] synthesize(Cue cue) {
        double duration = switch (cue) {
            case MOUNT -> 0.10;
            case PARK, RESUME, COMPLETE -> 0.18;
            case PIN -> 0.28;
        };
        int frames = (int) (duration * SAMPLE_RATE);
        byte[] pcm = new byte[frames * 2];
        long noise = 0x5eedL + cue.ordinal() * 97L;
        for (int frame = 0; frame < frames; frame++) {
            double t = frame / SAMPLE_RATE;
            double progress = frame / (double) Math.max(1, frames - 1);
            double attack = Math.min(1, progress / 0.08);
            double release = Math.min(1, (1 - progress) / 0.22);
            double envelope = attack * release;
            double startHz;
            double endHz;
            double harmonic = 0;
            switch (cue) {
                case MOUNT -> { startHz = 360; endHz = 620; }
                case PARK -> { startHz = 620; endHz = 180; }
                case RESUME -> { startHz = 330; endHz = 880; harmonic = 0.22; }
                case PIN -> { startHz = 145; endHz = 125; harmonic = 0.38; }
                case COMPLETE -> { startHz = 660; endHz = 1_080; harmonic = 0.28; }
                default -> throw new AssertionError("unreachable");
            }
            double hz = startHz + (endHz - startHz) * progress;
            double tone = Math.sin(2 * Math.PI * hz * t)
                    + harmonic * Math.sin(2 * Math.PI * hz * 2.01 * t);
            if (cue == Cue.PARK) {
                noise ^= noise << 13;
                noise ^= noise >>> 7;
                noise ^= noise << 17;
                tone += ((noise & 0xffff) / 32767.5 - 1) * 0.18 * (1 - progress);
            }
            if (cue == Cue.PIN) tone *= 0.72 + 0.28 * Math.sin(2 * Math.PI * 7 * t);
            short value = (short) Math.round(Math.max(-1, Math.min(1, tone * 0.12 * envelope)) * 32767);
            pcm[frame * 2] = (byte) (value & 0xff);
            pcm[frame * 2 + 1] = (byte) ((value >>> 8) & 0xff);
        }
        return pcm;
    }

    @Override
    public void close() {
        for (Clip clip : clips.values()) {
            clip.stop();
            clip.close();
        }
        clips.clear();
        enabled = false;
    }
}
