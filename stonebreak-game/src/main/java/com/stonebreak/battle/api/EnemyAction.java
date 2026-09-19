package com.stonebreak.battle.api;

/**
 * Ice Archon attacks. Durations and impact times are the authored values of the clips shipped in
 * {@code SB_Ice_Archon.sbe}, so telegraph bars, parry windows and animation agree by construction.
 */
public enum EnemyAction {
    SLASH("Frost Slash", "attack_slash", 1.65f, 0.66f),
    OVERHEAD("Glacial Overhead", "attack_overhead", 2.05f, 1.03f),
    FROST_CAST("Rime Burst", "attack_frost_cast", 2.40f, 1.11f);

    private final String displayName;
    private final String sbeState;
    private final float clipDuration;
    private final float impactTime;

    EnemyAction(String displayName, String sbeState, float clipDuration, float impactTime) {
        this.displayName = displayName;
        this.sbeState = sbeState;
        this.clipDuration = clipDuration;
        this.impactTime = impactTime;
    }

    public String displayName() { return displayName; }
    /** SBE animation-state name of the clip. */
    public String sbeState() { return sbeState; }
    public float clipDuration() { return clipDuration; }
    /** Seconds from clip start to the moment the blow lands. */
    public float impactTime() { return impactTime; }
}
