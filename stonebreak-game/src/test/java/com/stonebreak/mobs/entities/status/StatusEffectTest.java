package com.stonebreak.mobs.entities.status;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timed-debuff clock every BURNING/BLEED/SHAKEN instance runs on. The rules that matter:
 * DOT ticks fire once per interval regardless of frame rate (an accumulator, not a modulo),
 * refresh extends but never shortens a debuff, and expiry is exact — an effect that outlives
 * its duration by a frame deals a whole extra tick of damage.
 */
class StatusEffectTest {

    @Test
    void aDotTickFiresOncePerIntervalAtAnyFrameRate() {
        StatusEffect coarse = new StatusEffect(StatusEffectType.BURNING, 10f, 2f);
        StatusEffect fine = new StatusEffect(StatusEffectType.BURNING, 10f, 2f);

        int coarseTicks = 0;
        for (int i = 0; i < 3; i++) {
            if (coarse.tick(1.0f)) coarseTicks++;
        }
        int fineTicks = 0;
        for (int i = 0; i < 60; i++) {
            if (fine.tick(0.05f)) fineTicks++;
        }

        assertEquals(3, coarseTicks, "one DOT tick per elapsed second at 1 Hz");
        assertEquals(3, fineTicks, "and exactly the same three ticks at 20 Hz");
    }

    @Test
    void aFreshEffectHasNotTickedYet() {
        StatusEffect effect = new StatusEffect(StatusEffectType.BURNING, 5f, 1f);

        assertFalse(effect.tick(0.5f), "half an interval in, no damage yet");
        assertFalse(effect.isExpired());
    }

    @Test
    void expiryLandsWhenTheDurationRunsOut() {
        StatusEffect effect = new StatusEffect(StatusEffectType.SHAKEN, 2f, 0.15f);

        effect.tick(1.5f);
        assertFalse(effect.isExpired());
        effect.tick(0.5f); // binary-exact steps: 2 − 1.5 − 0.5 reaches 0 with no float residue
        assertTrue(effect.isExpired());
    }

    @Test
    void refreshExtendsButNeverShortens() {
        StatusEffect effect = new StatusEffect(StatusEffectType.SHAKEN, 5f, 0.15f);

        effect.refresh(2f, effect.getMagnitude());
        effect.tick(3f);

        assertFalse(effect.isExpired(),
                "a shorter re-application must not cut an effect's remaining time");

        effect.refresh(10f, effect.getMagnitude());
        effect.tick(9f);
        assertFalse(effect.isExpired(), "a longer re-application extends it");
        effect.tick(1.1f);
        assertTrue(effect.isExpired());
    }

    @Test
    void refreshReplacesMagnitudeWithTheLatestApplication() {
        StatusEffect effect = new StatusEffect(StatusEffectType.SHAKEN, 4f, 0.15f);

        effect.refresh(4f, 0.45f);

        assertEquals(0.45f, effect.getMagnitude(), 1e-6f,
                "a reapplication must adopt the latest magnitude (issue #232) — an "
                + "Illusionist's hesitation must rise to 3 stacks, not stay at one");
    }
}
