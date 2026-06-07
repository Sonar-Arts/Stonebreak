package com.stonebreak.mobs.entities.status;

/**
 * Kinds of timed debuffs that can be applied to a {@link com.stonebreak.mobs.entities.LivingEntity}.
 * Kept intentionally small — only the effects the Berserker's abilities actually produce.
 */
public enum StatusEffectType {
    /** Damage-over-time. {@code magnitude} is damage per second. */
    BURNING,
    /** Prevents AI/movement updates while active. {@code magnitude} is unused. */
    STUNNED,
    /** Increases incoming damage. {@code magnitude} is a fractional bonus (e.g. 0.35 = +35% damage taken). */
    ARMOR_BREAK
}
