package com.stonebreak.vehicles;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each oar rows at its own rate, and an unpulled oar settles to the model's rest pose. */
class OarStrokeTest {

    private static final float DT = 1f / 60f;

    private static void run(OarStroke stroke, OarInput input, float seconds) {
        for (float t = 0f; t < seconds; t += DT) {
            stroke.update(input, DT);
        }
    }

    @Test
    void restingOarsLeaveTheModelAlone() {
        OarStroke stroke = new OarStroke();
        run(stroke, OarInput.IDLE, 0.5f);
        assertNull(stroke.extraRotationDeg(OarStroke.LEFT_OAR_PART));
        assertNull(stroke.extraRotationDeg(OarStroke.RIGHT_OAR_PART));
        assertNull(stroke.extraRotationDeg("hull_floor"));
    }

    @Test
    void gentleTurnRowsTheInnerOarAtHalfRate() {
        OarStroke stroke = new OarStroke();
        OarInput gentleRight = OarInput.fromKeys(true, false, false, true); // L 1, R 0.5
        float steps = 10;
        for (int i = 0; i < steps; i++) {
            stroke.update(gentleRight, DT);
        }
        assertEquals(2f, stroke.leftPhase() / stroke.rightPhase(), 1e-3f);
    }

    @Test
    void hardTurnRowsOneOarOnly() {
        OarStroke stroke = new OarStroke();
        run(stroke, OarInput.fromKeys(false, false, false, true), 1f); // L 1, R 0
        assertNotNull(stroke.extraRotationDeg(OarStroke.LEFT_OAR_PART), "the left oar rows");
        assertNull(stroke.extraRotationDeg(OarStroke.RIGHT_OAR_PART), "the right oar rests");
    }

    @Test
    void stoppingFadesTheStrokeOut() {
        OarStroke stroke = new OarStroke();
        run(stroke, OarInput.fromKeys(true, false, false, false), 1f);
        assertEquals(1f, stroke.leftAmount(), 1e-6f);
        run(stroke, OarInput.IDLE, 0.1f);
        assertTrue(stroke.leftAmount() > 0f && stroke.leftAmount() < 1f, "fading, not snapped");
        run(stroke, OarInput.IDLE, 1f);
        assertNull(stroke.extraRotationDeg(OarStroke.LEFT_OAR_PART));
    }

    @Test
    void oarsMirrorEachOther() {
        OarStroke stroke = new OarStroke();
        run(stroke, OarInput.fromKeys(true, false, false, false), 0.37f);
        Vector3f l = new Vector3f(stroke.extraRotationDeg(OarStroke.LEFT_OAR_PART));
        Vector3f r = new Vector3f(stroke.extraRotationDeg(OarStroke.RIGHT_OAR_PART));
        assertEquals(-r.y, l.y, 1e-4f);
        assertEquals(-r.z, l.z, 1e-4f);
    }

    @Test
    void straightRowingBringsTheOarsBackIntoStep() {
        OarStroke stroke = new OarStroke();
        run(stroke, OarInput.fromKeys(true, false, false, true), 0.8f); // out of step
        run(stroke, OarInput.fromKeys(true, false, false, false), 3f);
        float diff = Math.abs(stroke.leftPhase() - stroke.rightPhase());
        assertTrue(diff < 0.05f || Math.abs(diff - 2f * (float) Math.PI) < 0.05f, "in step: " + diff);
    }
}
