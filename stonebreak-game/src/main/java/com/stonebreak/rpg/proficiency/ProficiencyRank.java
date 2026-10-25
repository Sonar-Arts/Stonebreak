package com.stonebreak.rpg.proficiency;

/**
 * How well a character wields a {@link com.stonebreak.items.weapon.WeaponGroup}
 * (pf2e-style). A weapon's authored swing time is the Trained timing; other
 * ranks divide it by {@link #attackSpeedMultiplier()}.
 */
public enum ProficiencyRank {
    UNTRAINED("Untrained", 0.75f, 1),
    TRAINED("Trained", 1.00f, 1),
    EXPERT("Expert", 1.15f, 5),
    MASTER("Master", 1.30f, 10);

    private final String displayName;
    private final float attackSpeedMultiplier;
    private final int minCharacterLevel;

    ProficiencyRank(String displayName, float attackSpeedMultiplier, int minCharacterLevel) {
        this.displayName = displayName;
        this.attackSpeedMultiplier = attackSpeedMultiplier;
        this.minCharacterLevel = minCharacterLevel;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Swing-speed factor; >1 = faster swings. */
    public float attackSpeedMultiplier() {
        return attackSpeedMultiplier;
    }

    /** Character level needed before this rank can be reached. */
    public int minCharacterLevel() {
        return minCharacterLevel;
    }

    /** The rank above this one, or {@code null} at Master. */
    public ProficiencyRank next() {
        int i = ordinal() + 1;
        return i < values().length ? values()[i] : null;
    }

    /** Rank for a stored skill level; levels outside the range clamp to Untrained/Master. */
    public static ProficiencyRank fromLevel(int level) {
        ProficiencyRank[] ranks = values();
        return ranks[Math.max(0, Math.min(level, ranks.length - 1))];
    }
}
