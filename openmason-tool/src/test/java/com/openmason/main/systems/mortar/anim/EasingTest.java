package com.openmason.main.systems.mortar.anim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EasingTest {

    private static final float EPS = 1e-6f;

    @Test
    void endpointsAreZeroAndOne() {
        assertEquals(0f, Easing.linear(0f), EPS);
        assertEquals(1f, Easing.linear(1f), EPS);
        assertEquals(0f, Easing.easeOutCubic(0f), EPS);
        assertEquals(1f, Easing.easeOutCubic(1f), EPS);
        assertEquals(0f, Easing.easeInCubic(0f), EPS);
        assertEquals(1f, Easing.easeInCubic(1f), EPS);
        assertEquals(0f, Easing.easeInOutCubic(0f), EPS);
        assertEquals(1f, Easing.easeInOutCubic(1f), EPS);
    }

    @Test
    void inputIsClamped() {
        assertEquals(0f, Easing.easeOutCubic(-2f), EPS);
        assertEquals(1f, Easing.easeOutCubic(5f), EPS);
        assertEquals(0f, Easing.clamp01(-0.3f), EPS);
        assertEquals(1f, Easing.clamp01(1.4f), EPS);
    }

    @Test
    void curvesAreMonotonicallyIncreasing() {
        assertMonotonic(Easing::easeOutCubic);
        assertMonotonic(Easing::easeInCubic);
        assertMonotonic(Easing::easeInOutCubic);
    }

    @Test
    void easeOutCubicDeceleratesEarly() {
        // A decelerating curve is above the diagonal in its first half.
        assertTrue(Easing.easeOutCubic(0.25f) > 0.25f);
        assertTrue(Easing.easeOutCubic(0.5f) > 0.5f);
    }

    private interface Curve {
        float at(float t);
    }

    private static void assertMonotonic(Curve curve) {
        float prev = curve.at(0f);
        for (int i = 1; i <= 100; i++) {
            float v = curve.at(i / 100f);
            assertTrue(v >= prev - 1e-5f, "not monotonic at t=" + (i / 100f));
            prev = v;
        }
    }
}
