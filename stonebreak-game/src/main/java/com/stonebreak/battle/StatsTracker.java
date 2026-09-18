package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleStats;
import com.stonebreak.battle.api.TimedGrade;

/** Running totals behind the immutable {@link BattleStats} snapshot. */
final class StatsTracker {

    private float damageDealt;
    private float damageTaken;
    private int parries;
    private int blocks;
    private int perfectRings;
    private int ringStreak;
    private int bestRingStreak;
    private int comboHits;
    private int turnsTaken;

    void damageDealt(float amount) { damageDealt += amount; }
    void damageTaken(float amount) { damageTaken += amount; }
    void parry() { parries++; }
    void block() { blocks++; }
    void comboHit() { comboHits++; }
    void turnTaken() { turnsTaken++; }

    void ring(TimedGrade grade) {
        if (grade == TimedGrade.PERFECT) {
            perfectRings++;
            ringStreak++;
            bestRingStreak = Math.max(bestRingStreak, ringStreak);
        } else {
            ringStreak = 0;
        }
    }

    BattleStats snapshot() {
        return new BattleStats(damageDealt, damageTaken, parries, blocks, perfectRings, bestRingStreak,
                comboHits, turnsTaken);
    }
}
