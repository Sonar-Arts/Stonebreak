package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.STAMINA_DRAIN_RATE;
import static com.stonebreak.player.PlayerConstants.STAMINA_REGEN_RATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StaminaController} — drain while sprinting, regen otherwise,
 * all-or-nothing consume, and max-stamina changes. Headless: no Game, World, or OpenGL.
 */
class StaminaControllerTest {

    private static final float MAX_STAMINA = 100f;

    private StaminaController sc;

    @BeforeEach
    void setUp() {
        sc = new StaminaController(MAX_STAMINA);
    }

    // ── Basic construction ─────────────────────────────────────────────────────

    @Test
    void startsFull() {
        assertEquals(MAX_STAMINA, sc.getStamina(), 1e-4f);
        assertEquals(MAX_STAMINA, sc.getMaxStamina(), 1e-4f);
    }

    // ── Drain / Regen ──────────────────────────────────────────────────────────

    @Test
    void sprintingDrainsAtDrainRate() {
        sc.setSprinting(true);
        sc.update(1f);

        assertEquals(100f - STAMINA_DRAIN_RATE, sc.getStamina(), 1e-4f);
    }

    @Test
    void notSprintingRegeneratesAtRegenRate() {
        // Drain first: sprint for 5 seconds (50 stamina remaining).
        sc.setSprinting(true);
        for (int i = 0; i < 5; i++) {
            sc.update(1f);
        }
        assertEquals(50f, sc.getStamina(), 1e-4f);

        // Stop sprinting and regen for 1 second.
        sc.setSprinting(false);
        sc.update(1f);

        assertEquals(50f + STAMINA_REGEN_RATE, sc.getStamina(), 1e-4f);
    }

    @Test
    void regenClampsAtMaximum() {
        sc.setSprinting(false);
        sc.update(100f);

        assertEquals(MAX_STAMINA, sc.getStamina(), 1e-4f);
    }

    @Test
    void drainClampsAtZero() {
        sc.setSprinting(true);
        sc.update(100f);

        assertEquals(0f, sc.getStamina(), 1e-4f);
    }

    @Test
    void hasStaminaIsFalseAtExactlyZero() {
        // Drain to exactly zero.
        sc.setSprinting(true);
        sc.update(100f);

        // This is the flag Player.processMovement reads to decide whether sprinting
        // is allowed, so an exhausted player drops to walking speed.
        assertFalse(sc.hasStamina());
    }

    @Test
    void updateAdvancesExactlyOneInterval() {
        sc.setSprinting(true);
        sc.update(0.5f);
        sc.update(0.5f);

        // Each call consumes only its own dt; two calls of 0.5s equal 1s total.
        assertEquals(100f - STAMINA_DRAIN_RATE * 1.0f, sc.getStamina(), 1e-4f);
    }

    // ── Afford / Consume ───────────────────────────────────────────────────────

    @Test
    void canAffordMatchesAvailableStamina() {
        assertTrue(sc.canAfford(100f));   // boundary is inclusive
        assertFalse(sc.canAfford(100.1f));
    }

    @Test
    void consumeSpendsWhenAffordable() {
        boolean ok = sc.consume(15f); // 15 is the in-game dodge cost
        assertTrue(ok);
        assertEquals(85f, sc.getStamina(), 1e-4f);
    }

    @Test
    void consumeIsAllOrNothingWhenShort() {
        // Drain to 10 stamina.
        sc.setSprinting(true);
        for (int i = 0; i < 9; i++) {
            sc.update(1f);
        }
        float before = sc.getStamina(); // 10f remaining

        boolean ok = sc.consume(20f);
        assertFalse(ok);
        assertEquals(before, sc.getStamina(), 1e-4f); // unchanged
    }

    // ── Max stamina changes ────────────────────────────────────────────────────

    @Test
    void raisingMaxStaminaGrantsTheDifference() {
        // Drain to 50 stamina.
        sc.setSprinting(true);
        for (int i = 0; i < 5; i++) {
            sc.update(1f);
        }
        assertEquals(50f, sc.getStamina(), 1e-4f);

        sc.setMaxStamina(150f);

        // Current grows by the diff (50 + 50 = 100), clamped to new max.
        assertEquals(100f, sc.getStamina(), 1e-4f);
        assertEquals(150f, sc.getMaxStamina(), 1e-4f);
    }

    @Test
    void loweringMaxStaminaClampsCurrent() {
        sc.setMaxStamina(60f);

        assertEquals(60f, sc.getStamina(), 1e-4f);
        assertEquals(60f, sc.getMaxStamina(), 1e-4f);
    }

    @Test
    void loweringMaxStaminaLeavesLowCurrentAlone() {
        // Drain to 20 stamina.
        sc.setSprinting(true);
        for (int i = 0; i < 8; i++) {
            sc.update(1f);
        }
        assertEquals(20f, sc.getStamina(), 1e-4f);

        sc.setMaxStamina(60f);

        assertEquals(20f, sc.getStamina(), 1e-4f); // unchanged — already under new max
        assertEquals(60f, sc.getMaxStamina(), 1e-4f);
    }

    // ── Sprinting flag ─────────────────────────────────────────────────────────

    @Test
    void sprintingFlagIsReported() {
        sc.setSprinting(true);
        assertTrue(sc.isSprinting());

        sc.setSprinting(false);
        assertFalse(sc.isSprinting());
    }
}