package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.ATTACK_ANIMATION_DURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AttackController} — the weapon-swing hit cooldown, its
 * speed multiplier, and cosmetic swings that never gate attacks. Headless.
 */
class AttackControllerTest {

    private AttackController ac;

    @BeforeEach
    void setUp() {
        ac = new AttackController();
    }

    @Test
    void secondSwingIsRefusedUntilTheFirstElapses() {
        assertTrue(ac.tryBeginSwing(0.5f));
        assertFalse(ac.tryBeginSwing(0.5f));
        ac.update(0.49f);
        assertFalse(ac.isReady());
        ac.update(0.02f);
        assertTrue(ac.isReady());
        assertTrue(ac.tryBeginSwing(0.5f));
    }

    @Test
    void animationLastsAsLongAsTheSwing() {
        ac.tryBeginSwing(0.8f);
        ac.update(0.4f);
        assertTrue(ac.isAttacking());
        assertEquals(0.5f, ac.getRawAnimationProgress(), 1e-4f);
        ac.update(0.41f);
        assertFalse(ac.isAttacking());
    }

    @Test
    void speedMultiplierShortensTheCooldown() {
        ac.setAnimationSpeedMultiplier(2f);
        ac.tryBeginSwing(0.5f);
        ac.update(0.26f);
        assertTrue(ac.isReady());
    }

    @Test
    void cosmeticSwingsNeverBlockAttacks() {
        ac.startAttackAnimation();
        ac.beginAttackForBreak();
        assertTrue(ac.isAttacking());
        assertTrue(ac.isReady());
        assertTrue(ac.tryBeginSwing(0.5f));
    }

    @Test
    void cosmeticSwingUsesTheDefaultDurationAfterAWeaponSwing() {
        ac.tryBeginSwing(0.9f);
        ac.update(1f);
        ac.startAttackAnimation();
        ac.update(ATTACK_ANIMATION_DURATION / 2f);
        assertEquals(0.5f, ac.getRawAnimationProgress(), 1e-4f);
    }

    @Test
    void resetClearsTheCooldown() {
        ac.tryBeginSwing(1f);
        ac.reset();
        assertTrue(ac.isReady());
        assertFalse(ac.isAttacking());
    }
}
