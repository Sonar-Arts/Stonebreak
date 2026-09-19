package com.stonebreak.battle.timed;

import com.stonebreak.battle.api.TimedGrade;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParryWindowTest {

    @Test
    void windowSitsAroundTheImpact() {
        ParryWindow w = ParryWindow.around(1.0f, 0.22f, 0.06f);
        assertEquals(0.78f, w.start(), 1.0e-6f);
        assertEquals(1.06f, w.end(), 1.0e-6f);
    }

    @Test
    void edgesAreInclusiveAndThereIsNoGoodBand() {
        ParryWindow w = new ParryWindow(0.5f, 0.75f);
        assertEquals(TimedGrade.PERFECT, w.press(0.5f));
        assertEquals(TimedGrade.PERFECT, w.press(0.6f));
        assertEquals(TimedGrade.PERFECT, w.press(0.75f));
        assertEquals(TimedGrade.MISS, w.press(Math.nextDown(0.5f)));
        assertEquals(TimedGrade.MISS, w.press(Math.nextUp(0.75f)));
        assertTrue(w.contains(0.5f));
        assertFalse(w.contains(0f));
    }

    @Test
    void windowNeverStartsBeforeTheWindup() {
        assertEquals(0f, ParryWindow.around(0.1f, 0.22f, 0.06f).start());
    }
}
