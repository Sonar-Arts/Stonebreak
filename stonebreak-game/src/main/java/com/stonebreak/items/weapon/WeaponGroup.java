package com.stonebreak.items.weapon;

/**
 * How a weapon is wielded. Proficiency ranks are tracked per group, so a
 * player trained in one-handed weapons swings every sword and dagger faster.
 */
public enum WeaponGroup {
    UNARMED("Unarmed"),
    ONE_HANDED("One-Handed"),
    TWO_HANDED("Two-Handed"),
    RANGED("Ranged");

    private final String displayName;

    WeaponGroup(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
