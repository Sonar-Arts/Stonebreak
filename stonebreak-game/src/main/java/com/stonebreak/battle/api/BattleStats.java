package com.stonebreak.battle.api;

/** Running totals shown on the result panel. */
public record BattleStats(float damageDealt, float damageTaken, int parries, int blocks,
                          int perfectRings, int bestRingStreak, int comboHits, int turnsTaken) {
    public static final BattleStats ZERO = new BattleStats(0f, 0f, 0, 0, 0, 0, 0, 0);
}
