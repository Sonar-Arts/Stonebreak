package com.stonebreak.battle.api;

/** The two sides of the proof-of-concept encounter. */
public enum CombatantId {
    MONK,
    ARCHON;

    public CombatantId opponent() {
        return this == MONK ? ARCHON : MONK;
    }
}
