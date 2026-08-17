package com.stonebreak.player.combat.arcanist;

import static com.stonebreak.player.PlayerConstants.ARCANIST_RESONANCE_MAX_STACKS;
import static com.stonebreak.player.PlayerConstants.ARCANIST_SAME_SCHOOL_COST_REDUCTION_PER_CAST;
import static com.stonebreak.player.PlayerConstants.ARCANIST_SAME_SCHOOL_DAMAGE_PENALTY_PER_CAST;
import static com.stonebreak.player.PlayerConstants.ARCANIST_SAME_SCHOOL_MAX_COST_REDUCTION;
import static com.stonebreak.player.PlayerConstants.ARCANIST_SAME_SCHOOL_MIN_DAMAGE_MULTIPLIER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.player.combat.magic.MagicSchool;
import com.stonebreak.player.combat.magic.SpellCast;
import org.junit.jupiter.api.Test;

/**
 * The Arcanist's Resonance passive: varying schools builds stacks toward Overloaded, repeating a
 * school trades damage for mana instead, and the two-phase preview/commit split guarantees a
 * blocked cast never advances the passive. That last contract is the load-bearing one — preview
 * runs on every keypress, and if it ever mutated state, failed casts would silently farm stacks.
 */
class ResonanceTrackerTest {

    private final ResonanceTracker resonance = new ResonanceTracker();

    private void buildToOverload() {
        resonance.commitCast(MagicSchool.ARCANA);
        resonance.commitCast(MagicSchool.CONJURATION);
        resonance.commitCast(MagicSchool.ILLUSION);
        resonance.commitCast(MagicSchool.ENCHANTMENT);
    }

    @Test
    void aFreshTrackerPreviewsAPlainCast() {
        assertEquals(SpellCast.BASE, resonance.preview(MagicSchool.ARCANA));
    }

    @Test
    void previewIsPureAndNeverAdvancesThePassive() {
        for (int i = 0; i < 10; i++) {
            resonance.preview(MagicSchool.ARCANA);
        }

        assertEquals(0, resonance.getResonanceStacks());
        assertEquals(0, resonance.getSameSchoolCastCount());
        assertFalse(resonance.isOverloaded(),
                "a blocked or spammed cast key must not farm Resonance");
    }

    @Test
    void varyingSchoolsBuildsOneStackPerChange() {
        resonance.commitCast(MagicSchool.ARCANA);
        assertEquals(1, resonance.getResonanceStacks(), "the first cast counts as a change");

        resonance.commitCast(MagicSchool.CONJURATION);
        assertEquals(2, resonance.getResonanceStacks());
        assertFalse(resonance.isOverloaded());
    }

    @Test
    void theFourthSchoolChangeOverloads() {
        buildToOverload();

        assertEquals(ARCANIST_RESONANCE_MAX_STACKS, resonance.getResonanceStacks());
        assertTrue(resonance.isOverloaded());

        SpellCast cast = resonance.preview(MagicSchool.DIVINATION);
        assertTrue(cast.overloaded(), "the next cast executes its empowered variant");
        assertEquals(1f, cast.manaCostMult(), 1e-6f, "the Overloaded cast pays full price");
        assertEquals(1f, cast.damageMult(), 1e-6f);
    }

    @Test
    void theOverloadedCastDischargesEverything() {
        buildToOverload();

        resonance.commitCast(MagicSchool.DIVINATION);

        assertEquals(0, resonance.getResonanceStacks());
        assertFalse(resonance.isOverloaded());
        assertEquals(0, resonance.getSameSchoolCastCount());
    }

    @Test
    void repeatingASchoolTradesDamageForManaInsteadOfBuildingStacks() {
        resonance.commitCast(MagicSchool.ARCANA);

        SpellCast repeat = resonance.preview(MagicSchool.ARCANA);

        assertEquals(1, resonance.getResonanceStacks(), "repeats build no Resonance");
        assertEquals(1f - ARCANIST_SAME_SCHOOL_COST_REDUCTION_PER_CAST, repeat.manaCostMult(), 1e-6f);
        assertEquals(1f - ARCANIST_SAME_SCHOOL_DAMAGE_PENALTY_PER_CAST, repeat.damageMult(), 1e-6f);
        assertFalse(repeat.overloaded());
    }

    @Test
    void theRepeatDiscountCapsAndTheDamageFloorHolds() {
        resonance.commitCast(MagicSchool.ARCANA);
        for (int i = 0; i < 25; i++) {
            resonance.commitCast(MagicSchool.ARCANA);
        }

        SpellCast worn = resonance.preview(MagicSchool.ARCANA);

        assertEquals(1f - ARCANIST_SAME_SCHOOL_MAX_COST_REDUCTION, worn.manaCostMult(), 1e-6f,
                "spamming one school can never make casts free");
        assertEquals(ARCANIST_SAME_SCHOOL_MIN_DAMAGE_MULTIPLIER, worn.damageMult(), 1e-6f,
                "nor reduce them to zero damage");
    }

    @Test
    void switchingSchoolsResetsTheRepeatPenalty() {
        resonance.commitCast(MagicSchool.ARCANA);
        resonance.commitCast(MagicSchool.ARCANA);
        resonance.commitCast(MagicSchool.ARCANA);

        resonance.commitCast(MagicSchool.ILLUSION);

        assertEquals(0, resonance.getSameSchoolCastCount());
        assertEquals(SpellCast.BASE.manaCostMult(),
                resonance.preview(MagicSchool.ENCHANTMENT).manaCostMult(), 1e-6f,
                "a fresh school casts at full price and full damage");
    }

    @Test
    void resetClearsEverythingForWorldReload() {
        buildToOverload();

        resonance.reset();

        assertEquals(0, resonance.getResonanceStacks());
        assertFalse(resonance.isOverloaded());
        assertEquals(SpellCast.BASE, resonance.preview(MagicSchool.ARCANA));
    }
}
