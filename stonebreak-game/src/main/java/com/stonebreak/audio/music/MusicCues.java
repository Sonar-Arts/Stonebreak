package com.stonebreak.audio.music;

import java.util.List;
import java.util.function.Supplier;

/**
 * Decides when a {@link MusicCue} starts. Each cue is armed until it fires; it re-arms when its
 * {@link MusicCue.Time} window closes (a night cue at daybreak) or on {@link #reset} (leaving the
 * world). While any cue is due, the player's place is checked every {@link #CHECK_INTERVAL} seconds
 * (a window that just opened checks at once); with none due, nothing is asked.
 *
 * <p>GL-, audio- and world-free: the caller supplies the clock and the place.
 */
public final class MusicCues {

    /** Seconds between place checks while a cue is due. */
    public static final float CHECK_INTERVAL = 0.5f;

    private final List<MusicCue> cues;
    private final boolean[] armed;
    private float sinceCheck = CHECK_INTERVAL;

    public MusicCues(List<MusicCue> cues) {
        this.cues = List.copyOf(cues);
        this.armed = new boolean[this.cues.size()];
        reset();
    }

    /** Re-arms every cue: a new world (or no world). */
    public void reset() {
        java.util.Arrays.fill(armed, true);
        sinceCheck = CHECK_INTERVAL;
    }

    /**
     * Advances one frame. {@code night} is the world clock's night; {@code place} is asked at most
     * once per check and only when a due cue has a place condition. Returns the cue that should
     * start now (it will not fire again until re-armed), or null.
     */
    public MusicCue update(boolean night, float deltaTime, Supplier<MusicPlace> place) {
        boolean anyDue = false;
        for (int i = 0; i < armed.length; i++) {
            if (!cues.get(i).time().open(night)) {
                armed[i] = true; // its window closed: the next one may play it again
            } else if (armed[i]) {
                anyDue = true;
            }
        }
        if (!anyDue) {
            sinceCheck = CHECK_INTERVAL;
            return null;
        }
        sinceCheck += deltaTime;
        if (sinceCheck < CHECK_INTERVAL) return null;
        sinceCheck = 0f;

        MusicPlace here = null;
        for (int i = 0; i < armed.length; i++) {
            MusicCue cue = cues.get(i);
            if (!armed[i] || !cue.time().open(night)) continue;
            if (cue.needsPlace()) {
                if (here == null) here = place.get();
                if (here == null || !cue.matches(here)) continue;
            }
            armed[i] = false;
            return cue;
        }
        return null;
    }
}
