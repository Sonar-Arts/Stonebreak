package com.stonebreak.battle.api;

/** Battle-local status effects (independent of the overworld StatusEffectType). */
public enum BattleStatus {
    HASTE("Haste", true),
    STUNNED("Stunned", false),
    CHILLED("Chilled", false),
    GUARDING("Guard", true),
    SURGE("Surge", true);

    private final String label;
    private final boolean beneficial;

    BattleStatus(String label, boolean beneficial) {
        this.label = label;
        this.beneficial = beneficial;
    }

    public String label() { return label; }
    public boolean beneficial() { return beneficial; }
}
