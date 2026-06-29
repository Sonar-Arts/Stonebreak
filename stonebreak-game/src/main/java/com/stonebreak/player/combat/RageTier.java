package com.stonebreak.player.combat;

/**
 * Discrete Rage thresholds for the Berserker class. Treated as a tier, not a continuous
 * value — bonuses are additive/stackable as the tier rises (T2 includes T1's bonus, etc).
 */
public enum RageTier {
    NONE,
    T1,
    T2,
    T3;

    /** True if this tier is at or above {@code other} (used for additive/stackable bonus checks). */
    public boolean atLeast(RageTier other) {
        return this.ordinal() >= other.ordinal();
    }
}
