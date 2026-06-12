package com.stonebreak.player.combat.arcanist;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.magic.AbilitySpell;
import com.stonebreak.player.combat.magic.SpellCast;

/**
 * Owns the Arcanist's two ability spells (Leyline Breach, Null Spike) and the Resonance
 * passive that fuels them. Mirrors the controller-per-concern pattern used by
 * {@link com.stonebreak.player.combat.berserker.BerserkerAbilityController} and
 * {@link com.stonebreak.player.combat.ranger.RangerAbilityController}.
 *
 * <p>Casting requires the Arcanist class to be selected, the spell's CP slot unlocked,
 * and enough mana. Every cast goes through the single {@link #castSpell} path, so
 * Resonance tracking (school changes, Overloaded discharge, same-school penalties) can
 * never be bypassed, and a blocked cast never advances Resonance or burns mana.</p>
 */
public class ArcanistAbilityController {

    public static final String CLASS_ID = "arcanist";
    public static final String LEYLINE_BREACH_KEY = CLASS_ID + ":0";
    public static final String NULL_SPIKE_KEY = CLASS_ID + ":1";

    private final ResonanceTracker resonance = new ResonanceTracker();
    private final LeylineBreachAbility leylineBreach = new LeylineBreachAbility();
    private final NullSpikeAbility nullSpike = new NullSpikeAbility();

    public void update(float deltaTime, Player player) {
        leylineBreach.update(deltaTime, player);
        nullSpike.update(deltaTime, player);
    }

    public boolean tryCastLeylineBreach(Player player) {
        return castSpell(player, leylineBreach, LEYLINE_BREACH_KEY);
    }

    public boolean tryCastNullSpike(Player player) {
        return castSpell(player, nullSpike, NULL_SPIKE_KEY);
    }

    /** Clears Resonance and spell cooldowns (world reload). */
    public void reset() {
        resonance.reset();
        leylineBreach.reset();
        nullSpike.reset();
    }

    /**
     * The single cast path for all Arcanist spells: preview Resonance modifiers, pay the
     * (possibly discounted) mana cost, execute, then commit the cast to Resonance.
     * Mana is refunded when activation fails after payment (e.g. no ground target).
     */
    private boolean castSpell(Player player, AbilitySpell spell, String abilityKey) {
        if (!isUnlocked(player, abilityKey) || spell.isActive()) return false;

        SpellCast cast = resonance.preview(spell.getMagicSchool());
        float cost = spell.getBaseManaCost() * cast.manaCostMult();
        if (!player.getManaController().trySpend(cost)) return false;
        if (!spell.tryActivate(player, cast)) {
            player.getManaController().refund(cost);
            return false;
        }
        resonance.commitCast(spell.getMagicSchool());
        return true;
    }

    private boolean isUnlocked(Player player, String abilityKey) {
        CharacterStats stats = player.getCharacterStats();
        return CLASS_ID.equals(stats.getSelectedClassId()) && stats.getSpentCp(abilityKey) > 0;
    }

    public ResonanceTracker getResonance() { return resonance; }

    public LeylineBreachAbility getLeylineBreach() { return leylineBreach; }

    public NullSpikeAbility getNullSpike() { return nullSpike; }
}
