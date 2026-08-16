package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.RAGE_COMBAT_TIMEOUT;
import static com.stonebreak.player.PlayerConstants.RAGE_DECAY_PER_SECOND;
import static com.stonebreak.player.PlayerConstants.RAGE_GAIN_PER_HIT_DEALT;
import static com.stonebreak.player.PlayerConstants.RAGE_GAIN_PER_HIT_RECEIVED;
import static com.stonebreak.player.PlayerConstants.RAGE_MAX;
import static com.stonebreak.player.PlayerConstants.RAGE_T1_THRESHOLD;
import static com.stonebreak.player.PlayerConstants.RAGE_T2_THRESHOLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Berserker's Rage economy: builds from trading melee hits, holds while combat stays hot,
 * bleeds out fast once it goes quiet, and is spent one whole tier per cast — with the cast
 * scaling off the tier that was active <em>before</em> payment, per the class design. Headless,
 * like {@link StaminaControllerTest}.
 */
class RageControllerTest {

    private RageController rage;

    @BeforeEach
    void setUp() {
        rage = new RageController();
    }

    private void hitUntil(float target) {
        while (rage.getRage() < target) {
            rage.onMeleeHitDealt();
        }
    }

    @Test
    void startsEmptyAtTierNone() {
        assertEquals(0f, rage.getRage(), 1e-4f);
        assertEquals(RageTier.NONE, rage.getTier());
    }

    @Test
    void dealingAndReceivingHitsBothBuildRage() {
        rage.onMeleeHitDealt();
        rage.onHitReceived();

        assertEquals(RAGE_GAIN_PER_HIT_DEALT + RAGE_GAIN_PER_HIT_RECEIVED, rage.getRage(), 1e-4f);
    }

    @Test
    void rageCapsAtItsMaximum() {
        for (int i = 0; i < 100; i++) {
            rage.onHitReceived();
        }

        assertEquals(RAGE_MAX, rage.getRage(), 1e-4f);
        assertEquals(RageTier.T3, rage.getTier());
    }

    @Test
    void tiersEscalateAtTheirThresholds() {
        hitUntil(RAGE_T1_THRESHOLD);
        assertEquals(RageTier.T1, rage.getTier());

        hitUntil(RAGE_T2_THRESHOLD);
        assertEquals(RageTier.T2, rage.getTier());

        hitUntil(RAGE_MAX);
        assertEquals(RageTier.T3, rage.getTier());
    }

    @Test
    void rageHoldsWhileCombatStaysHot() {
        hitUntil(RAGE_T1_THRESHOLD);
        float before = rage.getRage();

        rage.update(RAGE_COMBAT_TIMEOUT - 0.1f);

        assertEquals(before, rage.getRage(), 1e-4f, "no decay inside the combat window");
    }

    @Test
    void rageBleedsOutOnceCombatGoesQuiet() {
        hitUntil(RAGE_MAX);

        rage.update(RAGE_COMBAT_TIMEOUT);

        assertEquals(RAGE_MAX - RAGE_DECAY_PER_SECOND * RAGE_COMBAT_TIMEOUT,
                rage.getRage(), 1e-3f);
    }

    @Test
    void aFreshHitResetsTheDecayClock() {
        hitUntil(RAGE_T1_THRESHOLD);
        rage.update(RAGE_COMBAT_TIMEOUT - 0.1f);
        rage.onMeleeHitDealt(); // combat is hot again
        float afterHit = rage.getRage();

        rage.update(RAGE_COMBAT_TIMEOUT - 0.1f);

        assertEquals(afterHit, rage.getRage(), 1e-4f);
    }

    @Test
    void decayNeverGoesBelowZero() {
        rage.onMeleeHitDealt();

        rage.update(1000f);

        assertEquals(0f, rage.getRage(), 1e-4f);
    }

    @Test
    void aCastSpendsExactlyOneTierAndScalesOffThePreTier() {
        hitUntil(RAGE_T2_THRESHOLD);

        assertEquals(RageTier.T2, rage.consumeThresholdForCast(),
                "the ability scales off the tier active before payment");
        assertEquals(RageTier.T1, rage.getTier(), "payment drops exactly one tier");

        assertEquals(RageTier.T1, rage.consumeThresholdForCast());
        assertEquals(RageTier.NONE, rage.getTier());
    }

    @Test
    void aTierZeroCastIsFreeAndStillReportsNone() {
        assertEquals(RageTier.NONE, rage.consumeThresholdForCast());
        assertEquals(0f, rage.getRage(), 1e-4f);
    }

    @Test
    void tierComparisonIsAtLeast() {
        assertTrue(RageTier.T2.atLeast(RageTier.T1), "higher tiers include lower bonuses");
        assertTrue(RageTier.T2.atLeast(RageTier.T2));
        assertFalse(RageTier.T1.atLeast(RageTier.T2));
        assertTrue(RageTier.NONE.atLeast(RageTier.NONE));
    }
}
