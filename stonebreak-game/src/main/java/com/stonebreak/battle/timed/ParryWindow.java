package com.stonebreak.battle.timed;

import com.stonebreak.battle.api.TimedGrade;

/**
 * Pure grader for a parry attempt against a telegraphed attack. Times are seconds from the start of
 * the windup; the window is inclusive at both ends. A parry has no GOOD band: it lands or it does not.
 */
public record ParryWindow(float start, float end) {

    /** Window of {@code lead} seconds before and {@code lag} seconds after {@code impactTime}. */
    public static ParryWindow around(float impactTime, float lead, float lag) {
        return new ParryWindow(Math.max(0f, impactTime - lead), impactTime + lag);
    }

    public boolean contains(float time) {
        return time >= start && time <= end;
    }

    public TimedGrade press(float time) {
        return contains(time) ? TimedGrade.PERFECT : TimedGrade.MISS;
    }
}
