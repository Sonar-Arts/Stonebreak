package com.stonebreak.battle.timed;

import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.TimedGrade;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComboStringTest {

    private final ComboString string = new ComboString(
            List.of(ComboDirection.UP, ComboDirection.LEFT, ComboDirection.DOWN), 0.9f);

    @Test
    void correctDirectionIsGradedByWhetherTheClipHadToWait() {
        assertEquals(TimedGrade.PERFECT, string.press(0, ComboDirection.UP, 0f, false));
        assertEquals(TimedGrade.PERFECT, string.press(0, ComboDirection.UP, 0.37f, false));
        assertEquals(TimedGrade.GOOD, string.press(0, ComboDirection.UP, 0.37f, true));
        assertEquals(TimedGrade.GOOD, string.press(1, ComboDirection.LEFT, 0.89f, true));
    }

    @Test
    void wrongDirectionLatePressAndBadIndexMiss() {
        assertEquals(TimedGrade.MISS, string.press(0, ComboDirection.DOWN, 0.1f, false));
        assertEquals(TimedGrade.MISS, string.press(0, ComboDirection.UP, 0.9f, true));
        assertEquals(TimedGrade.MISS, string.press(0, null, 0.1f, false));
        assertEquals(TimedGrade.MISS, string.press(-1, ComboDirection.UP, 0.1f, false));
        assertEquals(TimedGrade.MISS, string.press(3, ComboDirection.UP, 0.1f, false));
    }

    @Test
    void expiresAtTheStepDuration() {
        assertFalse(string.expired(0.89f));
        assertTrue(string.expired(0.9f));
    }

    @Test
    void randomStringsAreSeededAndImmutable() {
        ComboString a = ComboString.random(new Random(7), 6, 0.9f);
        ComboString b = ComboString.random(new Random(7), 6, 0.9f);
        assertEquals(6, a.length());
        assertEquals(a.sequence(), b.sequence());
        assertThrows(UnsupportedOperationException.class, () -> a.sequence().add(ComboDirection.UP));

        // Across many seeds every direction shows up: the generator is not stuck on one value.
        boolean[] seen = new boolean[ComboDirection.values().length];
        for (int seed = 0; seed < 20; seed++) {
            for (ComboDirection d : ComboString.random(new Random(seed), 6, 0.9f).sequence()) {
                seen[d.ordinal()] = true;
            }
        }
        for (boolean s : seen) assertTrue(s);
    }
}
