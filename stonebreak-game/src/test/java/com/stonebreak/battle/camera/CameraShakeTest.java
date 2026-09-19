package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.CombatantId;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CameraShakeTest {

    private static final float DT = 1f / 60f;
    private static final CameraFrame BASE = new CameraFrame(new Vector3f(-9f, 4.5f, 17f), new Vector3f(0f, 1.6f, -2f), 0f, 70f);

    private static BattleEvent damage(float amount, DamageFlavor flavor) {
        return new BattleEvent.DamageDealt(CombatantId.ARCHON, amount, flavor);
    }

    @Test void restingShakeLeavesTheFrameUntouched() {
        CameraShake shake = new CameraShake(1L);
        shake.update(DT);
        assertSame(BASE, shake.apply(BASE, 1f));
        assertFalse(shake.active());
    }

    @Test void traumaDecaysToExactlyZeroAndTheFrameSettles() {
        CameraShake shake = new CameraShake(2L);
        shake.onEvent(new BattleEvent.ComboFinished(6, true));
        assertEquals(1f, shake.trauma(), 1.0e-6f);
        float previous = shake.trauma();
        boolean moved = false;
        for (int i = 0; i < 120; i++) {
            shake.update(DT);
            assertTrue(shake.trauma() <= previous, "trauma never grows on its own");
            previous = shake.trauma();
            moved |= shake.apply(BASE, 1f).eye().distance(BASE.eye()) > 0.01f;
        }
        assertTrue(moved, "a flawless finisher must visibly shake");
        assertEquals(0f, shake.trauma(), 0f, "1.0 trauma at 1.5/s is gone well inside 2 s");
        assertFalse(shake.active(), "FOV punch has died too");
        assertSame(BASE, shake.apply(BASE, 1f));
    }

    @Test void decayRateIsAboutOnePointFivePerSecond() {
        CameraShake shake = new CameraShake(3L);
        shake.addTrauma(1f);
        for (int i = 0; i < 30; i++) shake.update(DT);
        assertEquals(1f - 1.5f * 0.5f, shake.trauma(), 1.0e-3f);
    }

    @Test void amplitudeIsBoundedEvenUnderAHailOfEvents() {
        CameraShake shake = new CameraShake(4L);
        float eyeBound = CameraShake.MAX_EYE_OFFSET * (float) Math.sqrt(3) + 1.0e-4f;
        float targetBound = CameraShake.MAX_TARGET_OFFSET * (float) Math.sqrt(3) + 1.0e-4f;
        for (int i = 0; i < 600; i++) {
            shake.onEvent(damage(500f, DamageFlavor.CRITICAL));
            shake.onEvent(damage(500f, DamageFlavor.PARRIED));
            shake.onEvent(new BattleEvent.ComboFinished(6, true));
            shake.update(DT);
            assertTrue(shake.trauma() <= 1f);
            CameraFrame f = shake.apply(BASE, 1f);
            assertTrue(f.eye().distance(BASE.eye()) <= eyeBound);
            assertTrue(f.target().distance(BASE.target()) <= targetBound);
            assertTrue(Math.abs(f.rollDeg()) <= CameraShake.MAX_ROLL_DEG + 1.0e-4f);
            assertTrue(Math.abs(f.fovDeg() - 70f) <= CameraShake.MAX_FOV_PUNCH_DEG + 1.0e-4f);
        }
    }

    @Test void offsetScalesWithTraumaSquaredSoSmallHitsBarelyRegister() {
        CameraShake small = new CameraShake(5L);
        CameraShake big = new CameraShake(5L);
        small.addTrauma(0.2f);
        big.addTrauma(0.8f);
        // Same seed and clock ⇒ same noise sample, so the ratio is exactly (0.8/0.2)² = 16.
        assertEquals(16f, big.eyeOffset(1f).length() / small.eyeOffset(1f).length(), 1.0e-2f);
    }

    @Test void eventsAreRankedBlockedBelowNormalBelowCritical() {
        float blocked = traumaAfter(damage(40f, DamageFlavor.BLOCKED));
        float normal = traumaAfter(damage(40f, DamageFlavor.NORMAL));
        float critical = traumaAfter(damage(40f, DamageFlavor.CRITICAL));
        float heavy = traumaAfter(damage(90f, DamageFlavor.NORMAL));
        assertTrue(blocked > 0f && blocked < normal && normal < critical, blocked + " < " + normal + " < " + critical);
        assertTrue(heavy > normal, "scaled by amount");
        assertEquals(1f, traumaAfter(new BattleEvent.ComboFinished(6, true)), 1.0e-6f);
        assertEquals(0f, traumaAfter(new BattleEvent.Healed(CombatantId.MONK, 30f)), 0f);
    }

    @Test void parryIsASnapZoomInAndCriticalPunchesToo() {
        CameraShake shake = new CameraShake(6L);
        shake.onEvent(damage(0f, DamageFlavor.PARRIED));
        assertTrue(shake.trauma() >= 0.5f);
        assertTrue(shake.fovOffset(1f) <= -6f, "parry punches the FOV in");
        float first = shake.fovOffset(1f);
        for (int i = 0; i < 12; i++) shake.update(DT);
        assertTrue(Math.abs(shake.fovOffset(1f)) < Math.abs(first) * 0.25f, "the punch is short (≈0.2 s)");

        CameraShake normal = new CameraShake(6L);
        normal.onEvent(damage(40f, DamageFlavor.NORMAL));
        assertEquals(0f, normal.fovOffset(1f), 0f);
    }

    @Test void sameSeedSameShake() {
        CameraShake a = new CameraShake(77L);
        CameraShake b = new CameraShake(77L);
        CameraShake c = new CameraShake(78L);
        boolean differs = false;
        for (int i = 0; i < 40; i++) {
            if (i % 10 == 0) { a.addTrauma(0.6f); b.addTrauma(0.6f); c.addTrauma(0.6f); }
            a.update(DT); b.update(DT); c.update(DT);
            assertEquals(a.apply(BASE, 1f), b.apply(BASE, 1f));
            differs |= !a.apply(BASE, 1f).equals(c.apply(BASE, 1f));
        }
        assertTrue(differs, "another seed gives another noise track");
    }

    private static float traumaAfter(BattleEvent event) {
        CameraShake shake = new CameraShake(9L);
        shake.onEvent(event);
        return shake.trauma();
    }
}
