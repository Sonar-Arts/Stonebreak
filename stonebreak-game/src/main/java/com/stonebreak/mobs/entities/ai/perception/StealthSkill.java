package com.stonebreak.mobs.entities.ai.perception;

/**
 * What the player's Stealth skill does to mob perception (#321): each point makes them quieter and
 * slower to notice — 10% per point, applied as {@code 1 / (1 + 0.1 × points)} so no number of
 * points ever makes a player silent or invisible.
 *
 * <p>"Slower to notice" scales the detection rate rather than the mob's thresholds: filling a meter
 * 10% slower is the same as a threshold 10% higher, and it never pushes a threshold past the
 * meter's ceiling.
 */
public final class StealthSkill {

    /** The skill's id in the skill list every class can invest in. */
    public static final String SKILL_ID = "stealth";

    /** Worth of one point. */
    public static final float PER_POINT = 0.1f;

    private StealthSkill() {}

    /** The multiplier {@code points} puts on the player's loudness and on detection of them: 1 at 0 points. */
    public static float factor(float points) {
        return 1f / (1f + PER_POINT * Math.max(0f, points));
    }
}
