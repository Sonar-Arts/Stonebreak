package com.stonebreak.battle.timed;

import com.stonebreak.battle.api.TimedGrade;

/**
 * Pure grader for one Flurry timing ring. All times are seconds from the moment the ring opened;
 * both windows are inclusive at both ends.
 */
public record TimingRing(float duration, float perfectStart, float perfectEnd, float goodStart, float goodEnd) {

    /**
     * A ring whose windows are centred on a contact {@code lead} seconds after it opens: PERFECT is the
     * contact ± {@code perfectWindow}, GOOD the contact ± {@code goodWindow}, and the ring closes with
     * the GOOD window. The HUD ring therefore converges on its target exactly as the fist lands.
     */
    public static TimingRing around(float lead, float perfectWindow, float goodWindow) {
        float good = Math.max(0f, goodWindow);
        float perfect = Math.max(0f, Math.min(perfectWindow, good));
        return new TimingRing(lead + good, lead - perfect, lead + perfect, lead - good, lead + good);
    }

    /** Grade of a confirm press {@code time} seconds after the ring opened. */
    public TimedGrade press(float time) {
        if (time >= perfectStart && time <= perfectEnd) return TimedGrade.PERFECT;
        if (time >= goodStart && time <= goodEnd) return TimedGrade.GOOD;
        return TimedGrade.MISS;
    }

    /** True once the ring has run out without a press (an automatic MISS). */
    public boolean expired(float time) {
        return time >= duration;
    }
}
