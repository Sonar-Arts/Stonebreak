package com.stonebreak.player.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link BowController} — draw progress scales with the archer's draw-speed multiplier. Headless. */
class BowControllerTest {

    @Test
    void aFasterArcherReachesFullDrawSooner() {
        BowController normal = new BowController();
        BowController fast = new BowController();
        normal.startDrawing(1.0f);
        fast.startDrawing(1.5f);
        normal.update(2.0f);
        fast.update(2.0f);
        assertFalse(normal.isFullyDrawn());
        assertTrue(fast.isFullyDrawn());
    }

    @Test
    void aSlowerArcherDrawsProportionallyLess() {
        BowController slow = new BowController();
        slow.startDrawing(0.75f);
        slow.update(1.0f);
        assertEquals(0.25f, slow.getDrawProgress(), 1e-4f);
    }

    @Test
    void releaseNeedsTheMinimumDrawAtTheArchersSpeed() {
        BowController slow = new BowController();
        slow.startDrawing(0.5f);
        slow.update(0.3f); // 0.15 s of effective draw, under the 0.25 s minimum
        assertFalse(slow.releaseAndFire());
    }
}
