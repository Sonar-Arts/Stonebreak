package com.stonebreak.mobs.entities.status;

/**
 * Kinds of timed debuffs that can be applied to a {@link com.stonebreak.mobs.entities.LivingEntity}.
 * Kept intentionally small — only the effects the Berserker's and Ranger's abilities actually produce.
 */
public enum StatusEffectType {
    /** Damage-over-time. {@code magnitude} is damage per second. */
    BURNING,
    /** Prevents AI/movement updates while active. {@code magnitude} is unused. */
    STUNNED,
    /** Increases incoming damage. {@code magnitude} is a fractional bonus (e.g. 0.35 = +35% damage taken). */
    ARMOR_BREAK,
    /** Damage-over-time. {@code magnitude} is damage per second. */
    BLEED,
    /** Suppresses locomotion (AI keeps running) while active. {@code magnitude} is unused. */
    ROOT,
    /** Increases incoming damage from all sources. {@code magnitude} is a fractional bonus (e.g. 0.25 = +25% damage taken). */
    EXPOSED,
    /** Reduces movement speed. {@code magnitude} is a fractional reduction (e.g. 0.6 = 60% slower). */
    CRIPPLE,
    /**
     * Increases incoming magical damage while active. {@code magnitude} is a fractional
     * bonus (e.g. 0.20 = +20% magical damage taken). Refreshes on reapplication.
     */
    AMPLIFIED,
    /**
     * The next arcane spell hit deals bonus damage, then consumes the mark — one proc per
     * application. {@code magnitude} is unused (the bonus is a global constant).
     */
    SPELLMARKED
}
