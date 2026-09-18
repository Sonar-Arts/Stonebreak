package com.stonebreak.battle.timed;

import com.stonebreak.battle.api.TimedGrade;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingRingTest {

    private final TimingRing ring = new TimingRing(0.9f, 0.55f, 0.69f, 0.46f, 0.78f);

    @Test
    void perfectWindowIsInclusiveAtBothEdges() {
        assertEquals(TimedGrade.PERFECT, ring.press(0.55f));
        assertEquals(TimedGrade.PERFECT, ring.press(0.62f));
        assertEquals(TimedGrade.PERFECT, ring.press(0.69f));
        assertEquals(TimedGrade.GOOD, ring.press(Math.nextDown(0.55f)));
        assertEquals(TimedGrade.GOOD, ring.press(Math.nextUp(0.69f)));
    }

    @Test
    void goodWindowIsInclusiveAtBothEdges() {
        assertEquals(TimedGrade.GOOD, ring.press(0.46f));
        assertEquals(TimedGrade.GOOD, ring.press(0.78f));
        assertEquals(TimedGrade.MISS, ring.press(Math.nextDown(0.46f)));
        assertEquals(TimedGrade.MISS, ring.press(Math.nextUp(0.78f)));
    }

    @Test
    void earlyAndLatePressesMiss() {
        assertEquals(TimedGrade.MISS, ring.press(0f));
        assertEquals(TimedGrade.MISS, ring.press(0.89f));
        assertEquals(TimedGrade.MISS, ring.press(-1f));
    }

    @Test
    void aRingBuiltAroundAContactIsCentredOnIt() {
        TimingRing centred = TimingRing.around(0.42f, 0.06f, 0.13f);
        assertEquals(0.55f, centred.duration(), 1.0e-6f, "closes with the GOOD window");
        assertEquals(0.42f, (centred.perfectStart() + centred.perfectEnd()) * 0.5f, 1.0e-6f);
        assertEquals(0.42f, (centred.goodStart() + centred.goodEnd()) * 0.5f, 1.0e-6f);
        assertEquals(0.12f, centred.perfectEnd() - centred.perfectStart(), 1.0e-6f);
        assertEquals(0.26f, centred.goodEnd() - centred.goodStart(), 1.0e-6f);
        assertEquals(TimedGrade.PERFECT, centred.press(0.42f));
        assertEquals(TimedGrade.PERFECT, centred.press(0.37f));
        assertEquals(TimedGrade.PERFECT, centred.press(0.47f));
        assertEquals(TimedGrade.GOOD, centred.press(0.30f));
        assertEquals(TimedGrade.GOOD, centred.press(0.54f));
        assertEquals(TimedGrade.MISS, centred.press(0.28f));
        assertEquals(TimedGrade.MISS, centred.press(0.56f));
        assertTrue(centred.expired(centred.goodEnd()));
    }

    @Test
    void aRingThatOpenedLateKeepsItsWindowsOnTheContact() {
        // Only 0.05 s of lead left: the early halves are cut off by the opening, the late halves are whole.
        TimingRing late = TimingRing.around(0.05f, 0.06f, 0.13f);
        assertEquals(TimedGrade.PERFECT, late.press(0f));
        assertEquals(TimedGrade.PERFECT, late.press(0.11f));
        assertEquals(TimedGrade.GOOD, late.press(0.17f));
        assertEquals(TimedGrade.MISS, late.press(0.19f));
    }

    @Test
    void expiresAtItsDuration() {
        assertFalse(ring.expired(0.89f));
        assertTrue(ring.expired(0.9f));
        assertTrue(ring.expired(5f));
    }
}
