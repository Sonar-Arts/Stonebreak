package com.stonebreak.items.weapon;

/**
 * What kind of harm a hit does, independent of who dealt it
 * ({@code LivingEntity.DamageSource}). Resistances and armor key off this.
 */
public enum DamageType {
    SLASHING("Slashing"),
    PIERCING("Piercing"),
    BLUNT("Blunt"),
    FIRE("Fire"),
    ARCANE("Arcane"),
    UNTYPED("Untyped");

    private final String displayName;

    DamageType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
