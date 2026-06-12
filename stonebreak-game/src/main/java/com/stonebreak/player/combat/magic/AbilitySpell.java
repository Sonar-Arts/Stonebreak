package com.stonebreak.player.combat.magic;

import com.stonebreak.player.Player;

/**
 * A class ability that is also a spell: it belongs to a {@link MagicSchool}, costs mana,
 * and participates in school-tracking passives (e.g. the Arcanist's Resonance). The
 * caster's controller owns the single cast path — it previews the passive, spends mana,
 * then calls {@link #tryActivate} — so school tracking can never be bypassed.
 */
public interface AbilitySpell {

    /** The school this spell belongs to. */
    MagicSchool getMagicSchool();

    /** Base mana cost before any passive cost modifiers. */
    float getBaseManaCost();

    /** True while the spell is mid-execution or cooling down (cannot be recast). */
    boolean isActive();

    boolean isOnCooldown();

    float getCooldownRemaining();

    /**
     * Attempts to cast. Routes internally to the Overloaded variant when
     * {@code cast.overloaded()} is true. Returns false (without consuming the cooldown)
     * when the cast has no valid target.
     */
    boolean tryActivate(Player player, SpellCast cast);

    void update(float deltaTime, Player player);

    /** Clears cooldown and transient state (world reload). */
    void reset();
}
