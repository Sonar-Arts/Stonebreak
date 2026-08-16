package com.stonebreak.player.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Arcanist's mana pool: continuous regen up to the cap, all-or-nothing spends, refunds for
 * casts that fail after payment, and the WIS-driven max changes that must grant the difference
 * on a raise (matching how max-health works) rather than leaving the pool proportionally short.
 */
class ManaControllerTest {

    private static final float MAX = 100f;
    private static final float REGEN = 5f;

    private ManaController mana;

    @BeforeEach
    void setUp() {
        mana = new ManaController(MAX, REGEN);
    }

    @Test
    void startsFull() {
        assertEquals(MAX, mana.getMana(), 1e-4f);
        assertEquals(MAX, mana.getMaxMana(), 1e-4f);
    }

    @Test
    void anAffordableSpendDeductsExactly() {
        assertTrue(mana.trySpend(30f));
        assertEquals(MAX - 30f, mana.getMana(), 1e-4f);
    }

    @Test
    void anUnaffordableSpendDeductsNothing() {
        assertTrue(mana.trySpend(90f));

        assertFalse(mana.trySpend(20f), "a cast the pool cannot cover must fail whole");
        assertEquals(10f, mana.getMana(), 1e-4f, "and must not partially drain the pool");
    }

    @Test
    void regenFollowsTheRateAndClampsAtMax() {
        assertTrue(mana.trySpend(50f));

        mana.update(2f);
        assertEquals(50f + REGEN * 2f, mana.getMana(), 1e-4f);

        mana.update(1000f);
        assertEquals(MAX, mana.getMana(), 1e-4f);
    }

    @Test
    void aRefundReturnsThePaymentButNeverOverfills() {
        assertTrue(mana.trySpend(30f));

        mana.refund(30f);
        assertEquals(MAX, mana.getMana(), 1e-4f);

        mana.refund(30f);
        assertEquals(MAX, mana.getMana(), 1e-4f, "a stray refund must not mint mana past the cap");
    }

    @Test
    void raisingTheMaximumGrantsTheDifference() {
        assertTrue(mana.trySpend(50f)); // 50/100

        mana.setMaxMana(140f);

        assertEquals(90f, mana.getMana(), 1e-4f,
                "a WIS increase hands the player the new headroom immediately");
    }

    @Test
    void loweringTheMaximumClampsThePool() {
        mana.setMaxMana(60f);

        assertEquals(60f, mana.getMana(), 1e-4f);
    }

    @Test
    void aChangedRegenRateTakesEffect() {
        assertTrue(mana.trySpend(50f));
        mana.setRegenRate(20f);

        mana.update(1f);

        assertEquals(70f, mana.getMana(), 1e-4f);
    }
}
