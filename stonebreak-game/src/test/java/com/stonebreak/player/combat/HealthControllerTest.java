package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.HEALTH_PER_HEART;
import static com.stonebreak.player.PlayerConstants.MAX_HEALTH;
import static com.stonebreak.player.PlayerConstants.SPAWN_PROTECTION_DURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The player's health ledger: damage funnels to a terminal death flag, healing respects both the
 * cap and the grave, hearts round up for the HUD, max-health changes grant their difference, and
 * the spawn-protection window ends on its timer or early once the player lands safely.
 */
class HealthControllerTest {

    private HealthController health;

    @BeforeEach
    void setUp() {
        health = new HealthController();
    }

    @Test
    void startsFullAliveAndProtected() {
        assertEquals(MAX_HEALTH, health.getHealth(), 1e-4f);
        assertFalse(health.isDead());
        assertTrue(health.hasSpawnProtection());
    }

    @Test
    void damageDeductsAndZeroTriggersDeath() {
        health.damage(MAX_HEALTH - 1f);
        assertFalse(health.isDead());

        health.damage(1f);

        assertTrue(health.isDead());
        assertEquals(0f, health.getHealth(), 1e-4f, "health never shows negative");
    }

    @Test
    void theDeadTakeNoFurtherDamageAndCannotBeHealed() {
        health.damage(MAX_HEALTH + 100f);

        health.damage(5f);
        health.heal(50f);

        assertTrue(health.isDead());
        assertEquals(0f, health.getHealth(), 1e-4f, "only a respawn revives — not a stray heal");
    }

    @Test
    void healingClampsAtTheCap() {
        health.damage(3f);

        health.heal(1000f);

        assertEquals(MAX_HEALTH, health.getHealth(), 1e-4f);
    }

    @Test
    void heartsRoundUpSoASliverStillShowsAHeart() {
        health.damage(MAX_HEALTH - 0.5f); // half a point left

        assertEquals(1, health.getHearts(), "a barely-alive player must not show an empty bar");
        health.heal(HEALTH_PER_HEART);
        assertEquals(2, health.getHearts());
    }

    @Test
    void raisingMaxHealthGrantsTheDifference() {
        health.damage(4f);
        float before = health.getHealth();

        health.applyNewMaxHealth(MAX_HEALTH + 6f);

        assertEquals(before + 6f, health.getHealth(), 1e-4f);
    }

    @Test
    void loweringMaxHealthClampsThePool() {
        health.applyNewMaxHealth(MAX_HEALTH - 5f);

        assertEquals(MAX_HEALTH - 5f, health.getHealth(), 1e-4f);
    }

    @Test
    void restoreFullHealthRevives() {
        health.damage(MAX_HEALTH);
        assertTrue(health.isDead());

        health.restoreFullHealth();

        assertFalse(health.isDead());
        assertEquals(health.getMaxHealth(), health.getHealth(), 1e-4f);
    }

    // ── Spawn protection ─────────────────────────────────────────────────────

    @Test
    void protectionExpiresOnItsTimer() {
        health.updateSpawnProtection(SPAWN_PROTECTION_DURATION, false);

        assertFalse(health.hasSpawnProtection());
    }

    @Test
    void protectionEndsEarlyOnceSafelyGrounded() {
        health.updateSpawnProtection(0.6f, true);

        assertFalse(health.hasSpawnProtection(),
                "standing on the ground past the grace period ends protection");
    }

    @Test
    void anInstantGroundTouchDoesNotEndProtection() {
        health.updateSpawnProtection(0.3f, true);

        assertTrue(health.hasSpawnProtection(),
                "the first half-second is grace — spawning on solid ground keeps protection briefly");
    }

    @Test
    void protectionCanBeReArmed() {
        health.updateSpawnProtection(SPAWN_PROTECTION_DURATION, false);
        assertFalse(health.hasSpawnProtection());

        health.enableSpawnProtection();
        assertTrue(health.hasSpawnProtection());
        health.updateSpawnProtection(0.1f, false);
        assertTrue(health.hasSpawnProtection(), "the re-armed timer starts from zero");
    }
}
