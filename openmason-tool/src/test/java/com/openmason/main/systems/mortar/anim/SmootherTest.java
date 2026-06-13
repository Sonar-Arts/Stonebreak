package com.openmason.main.systems.mortar.anim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmootherTest {

    @Test
    void snapToSetsValueAndTarget() {
        Smoother s = new Smoother(10f);
        s.snapTo(0.5f);
        assertEquals(0.5f, s.getValue(), 1e-6f);
        assertEquals(0.5f, s.getTarget(), 1e-6f);
        assertTrue(s.isSettled());
    }

    @Test
    void convergesTowardTarget() {
        Smoother s = new Smoother(10f, 0f);
        s.setTarget(1f);
        for (int i = 0; i < 600; i++) {
            s.update(1f / 60f);
        }
        assertEquals(1f, s.getValue(), 1e-3f);
        assertTrue(s.isSettled());
    }

    @Test
    void neverOvershoots() {
        Smoother s = new Smoother(40f, 0f);
        s.setTarget(1f);
        for (int i = 0; i < 200; i++) {
            s.update(1f / 60f);
            assertTrue(s.getValue() <= 1f + 1e-6f, "overshot above target");
            assertTrue(s.getValue() >= 0f, "moved below start");
        }
    }

    @Test
    void isFrameRateIndependent() {
        // Exponential smoothing leaves the same remaining fraction for a given
        // elapsed time regardless of how many steps it's split into.
        float totalTime = 0.1f;
        float rate = 5f;
        float target = 100f;

        Smoother coarse = new Smoother(rate, 0f);
        coarse.setTarget(target);
        coarse.update(totalTime);

        Smoother fine = new Smoother(rate, 0f);
        fine.setTarget(target);
        int steps = 50;
        for (int i = 0; i < steps; i++) {
            fine.update(totalTime / steps);
        }

        assertEquals(coarse.getValue(), fine.getValue(), 1e-2f);
    }

    @Test
    void nonPositiveDeltaIsIgnored() {
        Smoother s = new Smoother(10f, 0.2f);
        s.setTarget(1f);
        s.update(0f);
        s.update(-1f);
        s.update(Float.NaN);
        assertEquals(0.2f, s.getValue(), 1e-6f);
        assertFalse(s.isSettled());
    }
}
