package com.stonebreak.player.combat.magic;

/**
 * Snapshot of the modifiers a spell cast should apply, captured from the caster's
 * passive resource (e.g. the Arcanist's Resonance) at the moment the cast is attempted.
 * Computed by a pure preview so a blocked cast (no mana, no valid target) never
 * mutates passive state.
 *
 * @param manaCostMult multiplier on the spell's base mana cost (1.0 = full price)
 * @param damageMult   multiplier on the spell's damage (1.0 = full damage)
 * @param overloaded   true when the cast should execute its empowered Overloaded variant
 * @param stacksAtCast passive stacks visible to the player when the cast key was pressed
 */
public record SpellCast(float manaCostMult, float damageMult, boolean overloaded, int stacksAtCast) {

    /** A plain, unmodified cast. */
    public static final SpellCast BASE = new SpellCast(1f, 1f, false, 0);
}
