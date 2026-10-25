package com.stonebreak.vehicles;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rower's STR → thrust curve of {@link DinghyPhysics#rowingPower}. */
class DinghyPhysicsTest {

    @Test
    void averageStrengthRowsAtBasePower() {
        assertEquals(1f, DinghyPhysics.rowingPower(10), 1e-6f);
    }

    @Test
    void strongerRowersPullHarder() {
        assertTrue(DinghyPhysics.rowingPower(16) > DinghyPhysics.rowingPower(12));
        assertEquals(1.5f, DinghyPhysics.rowingPower(20), 1e-6f);
        assertEquals(0.75f, DinghyPhysics.rowingPower(5), 1e-6f);
    }

    @Test
    void powerIsCappedAndNeverStalls() {
        assertEquals(0.55f, DinghyPhysics.rowingPower(1), 1e-6f);
        assertEquals(DinghyPhysics.MAX_POWER, DinghyPhysics.rowingPower(99), 1e-6f);
    }
}
