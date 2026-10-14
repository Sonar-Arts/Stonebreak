package com.stonebreak.items.weapon;

/**
 * Melee stats of a held item.
 *
 * @param group        how the weapon is wielded (selects the proficiency rank)
 * @param damageType   kind of damage a hit deals
 * @param damage       base damage per hit
 * @param swingSeconds base time between hits for a trained wielder
 */
public record WeaponStats(WeaponGroup group, DamageType damageType, float damage, float swingSeconds) {

    /** Bare fist, and any item without weapon stats. */
    public static final WeaponStats UNARMED = new WeaponStats(WeaponGroup.UNARMED, DamageType.BLUNT, 1.0f, 0.30f);

    public WeaponStats {
        if (group == null || damageType == null) {
            throw new IllegalArgumentException("group and damageType are required");
        }
        if (damage < 0f || swingSeconds <= 0f) {
            throw new IllegalArgumentException("damage must be >= 0 and swingSeconds > 0");
        }
    }
}
