package com.stonebreak.ui.recipeScreen.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Guards the hit-test contract of {@link PositionCalculator#isPointInBounds}:
 * the method is inclusive on every edge and corner.
 *
 * <p>Regression: a boundary-condition change (e.g. switching from {@code <=} to
 * {@code <} on one edge) would silently miss clicks on panel borders.
 *
 * <p>{@link PositionCalculator#calculatePanelDimensions()} is deliberately NOT
 * tested — it reaches {@code Game.getWindowWidth()} and {@code Game.getWindowHeight()}.
 */
class PositionCalculatorTest {

    private static final int BX = 100, BY = 50, BW = 200, BH = 100;

    private boolean inBounds(float x, float y) {
        return PositionCalculator.isPointInBounds(x, y, BX, BY, BW, BH);
    }

    // ---- center is inside ---------------------------------------------------------------------

    @Test
    void centerIsInside() {
        assertTrue(inBounds(BX + BW / 2f, BY + BH / 2f),
            "point at the center of bounds must be inside");
    }

    // ---- exact edges are inside (inclusive) ---------------------------------------------------

    @Test
    void leftEdgeIsInside() {
        assertTrue(inBounds(BX, BY + 10f),
            "point on the exact left edge (x=" + BX + ") must be inside");
    }

    @Test
    void rightEdgeIsInside() {
        assertTrue(inBounds(BX + BW, BY + 10f),
            "point on the exact right edge (x=" + (BX + BW) + ") must be inside");
    }

    @Test
    void topEdgeIsInside() {
        assertTrue(inBounds(BX + 10f, BY),
            "point on the exact top edge (y=" + BY + ") must be inside");
    }

    @Test
    void bottomEdgeIsInside() {
        assertTrue(inBounds(BX + 10f, BY + BH),
            "point on the exact bottom edge (y=" + (BY + BH) + ") must be inside");
    }

    // ---- exact corners are inside -------------------------------------------------------------

    @Test
    void topLeftCornerIsInside() {
        assertTrue(inBounds(BX, BY),
            "top-left corner (" + BX + "," + BY + ") must be inside");
    }

    @Test
    void topRightCornerIsInside() {
        assertTrue(inBounds(BX + BW, BY),
            "top-right corner (" + (BX + BW) + "," + BY + ") must be inside");
    }

    @Test
    void bottomLeftCornerIsInside() {
        assertTrue(inBounds(BX, BY + BH),
            "bottom-left corner (" + BX + "," + (BY + BH) + ") must be inside");
    }

    @Test
    void bottomRightCornerIsInside() {
        assertTrue(inBounds(BX + BW, BY + BH),
            "bottom-right corner (" + (BX + BW) + "," + (BY + BH) + ") must be inside");
    }

    // ---- just outside each side is outside ----------------------------------------------------

    @Test
    void justOutsideLeftEdgeIsOutside() {
        assertFalse(inBounds(BX - 0.001f, BY + 10f),
            "point just left of left edge must be outside");
    }

    @Test
    void justOutsideRightEdgeIsOutside() {
        assertFalse(inBounds(BX + BW + 0.001f, BY + 10f),
            "point just right of right edge must be outside");
    }

    @Test
    void justOutsideTopEdgeIsOutside() {
        assertFalse(inBounds(BX + 10f, BY - 0.001f),
            "point just above top edge must be outside");
    }

    @Test
    void justOutsideBottomEdgeIsOutside() {
        assertFalse(inBounds(BX + 10f, BY + BH + 0.001f),
            "point just below bottom edge must be outside");
    }

    // ---- far outside is outside ---------------------------------------------------------------

    @Test
    void pointFarOutsideAllSidesIsOutside() {
        assertFalse(inBounds(-1000f, -1000f),
            "point far outside all sides must be outside");
        assertFalse(inBounds(10000f, 10000f),
            "point far outside all sides (positive) must be outside");
    }

    // ---- zero-size bounds ---------------------------------------------------------------------

    @Test
    void zeroSizeBoundsContainsOriginPoint() {
        // A zero-width, zero-height bounds still contains the single point at (bx, by)
        assertTrue(PositionCalculator.isPointInBounds(100f, 50f, 100, 50, 0, 0),
            "zero-size bounds must contain the point exactly at its origin");
        assertFalse(PositionCalculator.isPointInBounds(100.001f, 50f, 100, 50, 0, 0),
            "zero-size bounds must reject point just to the right");
        assertFalse(PositionCalculator.isPointInBounds(100f, 50.001f, 100, 50, 0, 0),
            "zero-size bounds must reject point just below");
    }

    // ---- non-zero origin bounds ---------------------------------------------------------------

    @Test
    void boundsAtNonZeroOriginWorkCorrectly() {
        // bounds at (200, 150) with size 100x80
        assertTrue(PositionCalculator.isPointInBounds(200f, 150f, 200, 150, 100, 80),
            "top-left of offset bounds must be inside");
        assertTrue(PositionCalculator.isPointInBounds(300f, 230f, 200, 150, 100, 80),
            "bottom-right of offset bounds must be inside");
        assertFalse(PositionCalculator.isPointInBounds(199f, 150f, 200, 150, 100, 80),
            "point just left of offset bounds must be outside");
    }
}