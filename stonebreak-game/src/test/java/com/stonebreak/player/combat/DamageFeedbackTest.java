package com.stonebreak.player.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The hurt flash, low-health pulse and camera jolt as pure functions of the health over time. */
class DamageFeedbackTest {

    private static final float DT = 1f / 60f;
    private static final float MAX = 20f;

    private static DamageFeedback primed(float health) {
        DamageFeedback fb = new DamageFeedback();
        fb.update(DT, health, MAX, false, true);
        return fb;
    }

    private static void run(DamageFeedback fb, float seconds, float health, boolean motion) {
        for (float t = 0f; t < seconds; t += DT) {
            fb.update(DT, health, MAX, false, motion);
        }
    }

    @Test
    void theFirstFrameOnlySetsTheBaseline() {
        DamageFeedback fb = primed(6f);
        assertEquals(0f, fb.flash());
        assertEquals(0f, fb.trauma());
    }

    @Test
    void aHitFlashesAndJoltsThenSettles() {
        DamageFeedback fb = primed(MAX);
        fb.update(DT, MAX - 4f, MAX, false, true);
        assertTrue(fb.flash() > 0.5f, "flash " + fb.flash());
        assertTrue(fb.trauma() > 0.3f, "trauma " + fb.trauma());
        assertTrue(fb.shakeRoll() != 0f || fb.shakePitch() != 0f, "the camera moves");
        assertEquals(fb.flash(), fb.vignette(), "no low-health pulse at 80%");

        run(fb, 1.5f, MAX - 4f, true);
        assertEquals(0f, fb.flash());
        assertEquals(0f, fb.trauma());
        assertEquals(0f, fb.shakeYaw());
        assertEquals(0f, fb.shakePitch());
        assertEquals(0f, fb.shakeRoll());
    }

    @Test
    void biggerHitsHitHarder() {
        DamageFeedback small = primed(MAX);
        small.update(DT, MAX - 1f, MAX, false, true);
        DamageFeedback big = primed(MAX);
        big.update(DT, MAX - 8f, MAX, false, true);
        assertTrue(big.flash() > small.flash());
        assertTrue(big.trauma() > small.trauma());
    }

    @Test
    void healingAndRespawnsAreNotHits() {
        DamageFeedback fb = primed(4f);
        fb.update(DT, MAX, MAX, false, true);
        assertEquals(0f, fb.flash());
        assertEquals(0f, fb.trauma());
    }

    @Test
    void reducedMotionKeepsTheVignetteButNotTheShake() {
        DamageFeedback fb = primed(MAX);
        fb.update(DT, MAX - 6f, MAX, false, false);
        assertTrue(fb.flash() > 0f);
        assertEquals(0f, fb.shakeYaw());
        assertEquals(0f, fb.shakePitch());
        assertEquals(0f, fb.shakeRoll());
    }

    @Test
    void theHurtTiltAlternatesSides() {
        DamageFeedback fb = primed(MAX);
        fb.update(DT, MAX - 1f, MAX, false, true);
        // small hits: the jitter (<= MAX_ROLL x trauma^2, ~0.4 deg) cannot outweigh the ~4 deg kick
        float first = Math.signum(fb.shakeRoll());
        run(fb, 1.5f, MAX - 1f, true);
        fb.update(DT, MAX - 2f, MAX, false, true);
        float second = Math.signum(fb.shakeRoll());
        assertTrue(first != second, "roll kick flips side: " + first + " then " + second);
    }

    @Test
    void lowHealthBeatsOnlyBelowTheThresholdAndNotWhenDead() {
        DamageFeedback healthy = primed(MAX * 0.5f);
        run(healthy, 2f, MAX * 0.5f, true);
        assertEquals(0f, healthy.lowHealth());

        DamageFeedback low = primed(MAX * 0.1f);
        float min = 1f;
        float max = 0f;
        for (float t = 0f; t < 3f; t += DT) {
            low.update(DT, MAX * 0.1f, MAX, false, true);
            if (t > 1.5f) {
                min = Math.min(min, low.lowHealth());
                max = Math.max(max, low.lowHealth());
            }
        }
        assertTrue(max > 0.5f, "pulse peak " + max);
        assertTrue(max - min > 0.2f, "it beats: " + min + ".." + max);

        for (float t = 0f; t < 3f; t += DT) {
            low.update(DT, 0f, MAX, true, true);
        }
        assertEquals(0f, low.lowHealth(), "no pulse behind the death screen");
    }

    @Test
    void shakeStaysWithinItsAmplitudes() {
        DamageFeedback fb = primed(MAX);
        float limitRoll = DamageFeedback.MAX_ROLL + DamageFeedback.KICK_ROLL;
        float limitPitch = DamageFeedback.MAX_PITCH + Math.abs(DamageFeedback.KICK_PITCH);
        float health = MAX;
        for (int i = 0; i < 600; i++) {
            if (i % 20 == 0 && health > 2f) {
                health -= 2f; // a hit every third of a second keeps trauma pinned
            }
            fb.update(DT, health, MAX, false, true);
            assertTrue(Math.abs(fb.shakeYaw()) <= DamageFeedback.MAX_YAW + 1e-4f);
            assertTrue(Math.abs(fb.shakePitch()) <= limitPitch + 1e-4f);
            assertTrue(Math.abs(fb.shakeRoll()) <= limitRoll + 1e-4f);
            assertTrue(fb.flash() <= 1f && fb.trauma() <= 1f);
        }
    }

    @Test
    void noiseIsBoundedAndContinuous() {
        float prev = DamageFeedback.noise(0f, 5);
        for (float t = 0.01f; t < 50f; t += 0.01f) {
            float n = DamageFeedback.noise(t, 5);
            assertTrue(n >= -1f && n <= 1f, "noise " + n);
            assertTrue(Math.abs(n - prev) < 0.1f, "smooth at t=" + t);
            prev = n;
        }
    }

    @Test
    void resetForgetsEverything() {
        DamageFeedback fb = primed(MAX);
        fb.update(DT, 2f, MAX, false, true);
        fb.reset();
        assertEquals(0f, fb.vignette());
        fb.update(DT, 1f, MAX, false, true); // re-baselines: not a hit
        assertEquals(0f, fb.flash());
    }
}
