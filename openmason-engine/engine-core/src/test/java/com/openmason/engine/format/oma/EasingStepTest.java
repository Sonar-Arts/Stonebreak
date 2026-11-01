package com.openmason.engine.format.oma;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** STEP holds the outgoing pose for the whole segment; older names still parse. */
class EasingStepTest {

    @Test
    void stepHoldsUntilTheSegmentEnd() {
        assertEquals(0f, Easing.STEP.apply(0f));
        assertEquals(0f, Easing.STEP.apply(0.5f));
        assertEquals(0f, Easing.STEP.apply(0.999f));
        assertEquals(1f, Easing.STEP.apply(1f));
        assertEquals(1f, Easing.STEP.apply(2f), "clamped like every curve");
    }

    @Test
    void stepParsesCaseInsensitivelyAndUnknownFallsBackToLinear() {
        assertEquals(Easing.STEP, Easing.fromString("step"));
        assertEquals(Easing.LINEAR, Easing.fromString("bounce"));
    }

    @Test
    void everyCurveIsMonotoneFromZeroToOne() {
        for (Easing e : Easing.values()) {
            float prev = -1f;
            for (int i = 0; i <= 20; i++) {
                float v = e.apply(i / 20f);
                assertEquals(true, v >= prev - 1e-6f, e + " decreased at " + i);
                prev = v;
            }
            assertEquals(0f, e.apply(0f), 1e-6f);
            assertEquals(1f, e.apply(1f), 1e-6f);
        }
    }
}
