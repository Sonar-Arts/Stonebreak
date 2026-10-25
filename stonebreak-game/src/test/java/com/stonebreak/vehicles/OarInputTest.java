package com.stonebreak.vehicles;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The key → oar table: a right turn works the LEFT oar, and the key combo picks how hard. */
class OarInputTest {

    private static void assertOars(float left, float right, OarInput in) {
        assertEquals(left, in.left(), 1e-6f, "left oar");
        assertEquals(right, in.right(), 1e-6f, "right oar");
    }

    @Test
    void noKeysRestsBothOars() {
        assertTrue(OarInput.fromKeys(false, false, false, false).isIdle());
        // opposite keys cancel
        assertTrue(OarInput.fromKeys(true, true, true, true).isIdle());
    }

    @Test
    void forwardPullsBothOarsFully() {
        assertOars(1f, 1f, OarInput.fromKeys(true, false, false, false));
    }

    @Test
    void forwardAndRightSlowsTheRightOar() {
        assertOars(1f, 0.5f, OarInput.fromKeys(true, false, false, true));
    }

    @Test
    void hardRightRowsOnlyTheLeftOar() {
        assertOars(1f, 0f, OarInput.fromKeys(false, false, false, true));
    }

    @Test
    void leftMirrorsRight() {
        assertOars(0.5f, 1f, OarInput.fromKeys(true, false, true, false));
        assertOars(0f, 1f, OarInput.fromKeys(false, false, true, false));
    }

    @Test
    void backingWaterReversesBothOars() {
        assertOars(-1f, -1f, OarInput.fromKeys(false, true, false, false));
        assertOars(-0.5f, -1f, OarInput.fromKeys(false, true, false, true));
        assertOars(-1f, -0.5f, OarInput.fromKeys(false, true, true, false));
    }

    @Test
    void dAlwaysSwingsTheBowRight() {
        assertTrue(OarInput.fromKeys(true, false, false, true).turnRight() > 0f);
        assertTrue(OarInput.fromKeys(false, false, false, true).turnRight() > 0f);
        assertTrue(OarInput.fromKeys(false, true, false, true).turnRight() > 0f);
        assertTrue(OarInput.fromKeys(false, false, true, false).turnRight() < 0f);
    }

    @Test
    void thrustFollowsTheMeanPull() {
        assertEquals(1f, OarInput.fromKeys(true, false, false, false).thrust(), 1e-6f);
        assertEquals(0.75f, OarInput.fromKeys(true, false, false, true).thrust(), 1e-6f);
        assertEquals(-1f, OarInput.fromKeys(false, true, false, false).thrust(), 1e-6f);
    }

    @Test
    void wireFormRoundTrips() {
        for (OarInput in : new OarInput[]{
                OarInput.fromKeys(true, false, false, true),
                OarInput.fromKeys(false, true, true, false),
                OarInput.fromKeys(false, false, false, true),
                OarInput.IDLE}) {
            assertEquals(in, OarInput.decode(in.encodeLeft(), in.encodeRight()));
        }
    }
}
