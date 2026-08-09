package com.stonebreak.ui.worldSelect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Guards SectionBounds containment geometry: corners and edges are handled
 * consistently using inclusive bounds {@code [x, x+width] x [y, y+height]}, and
 * points outside are rejected on all four sides.
 *
 * <p>Regression: prevents off-by-one errors in hit-testing where a point exactly
 * on the right or bottom edge would incorrectly fall outside the section.
 */
class SectionBoundsTest {

    @Test
    void containsTrueForPointInsideRect() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertTrue(bounds.contains(60, 45), "point clearly inside must be contained");
    }

    @Test
    void containsTrueForLeftEdge() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertTrue(bounds.contains(10, 45), "point exactly on left edge must be contained");
    }

    @Test
    void containsTrueForRightEdge() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertTrue(bounds.contains(110, 45), "point exactly on right edge (x+width) must be contained");
    }

    @Test
    void containsTrueForTopEdge() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertTrue(bounds.contains(60, 20), "point exactly on top edge must be contained");
    }

    @Test
    void containsTrueForBottomEdge() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertTrue(bounds.contains(60, 70), "point exactly on bottom edge (y+height) must be contained");
    }

    @Test
    void containsTrueForAllFourCorners() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertTrue(bounds.contains(10, 20), "top-left corner must be contained");
        assertTrue(bounds.contains(110, 20), "top-right corner must be contained");
        assertTrue(bounds.contains(10, 70), "bottom-left corner must be contained");
        assertTrue(bounds.contains(110, 70), "bottom-right corner must be contained");
    }

    @Test
    void containsFalseForPointOutsideLeft() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertFalse(bounds.contains(9, 45), "point just left of rect must not be contained");
        assertFalse(bounds.contains(0, 45), "point far left must not be contained");
    }

    @Test
    void containsFalseForPointOutsideRight() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertFalse(bounds.contains(111, 45), "point just right of rect must not be contained");
        assertFalse(bounds.contains(200, 45), "point far right must not be contained");
    }

    @Test
    void containsFalseForPointOutsideTop() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertFalse(bounds.contains(60, 19), "point just above rect must not be contained");
        assertFalse(bounds.contains(60, 0), "point far above must not be contained");
    }

    @Test
    void containsFalseForPointOutsideBottom() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertFalse(bounds.contains(60, 71), "point just below rect must not be contained");
        assertFalse(bounds.contains(60, 200), "point far below must not be contained");
    }

    @Test
    void containsFalseForDiagonalOutsideCorners() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertFalse(bounds.contains(9, 19), "top-left diagonal must not be contained");
        assertFalse(bounds.contains(111, 19), "top-right diagonal must not be contained");
        assertFalse(bounds.contains(9, 71), "bottom-left diagonal must not be contained");
        assertFalse(bounds.contains(111, 71), "bottom-right diagonal must not be contained");
    }

    @Test
    void getRightAndBottomAreComputedCorrectly() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertEquals(110, bounds.getRight(), 0.001f, "right must be x + width");
        assertEquals(70, bounds.getBottom(), 0.001f, "bottom must be y + height");
    }

    @Test
    void getCenterXAndCenterYAreComputedCorrectly() {
        SectionBounds bounds = new SectionBounds(10, 20, 100, 50);
        assertEquals(60, bounds.getCenterX(), 0.001f, "centerX must be x + width/2");
        assertEquals(45, bounds.getCenterY(), 0.001f, "centerY must be y + height/2");
    }

    @Test
    void gettersReturnConstructionValues() {
        SectionBounds bounds = new SectionBounds(100, 200, 300, 400);
        assertEquals(100, bounds.getX(), 0.001f);
        assertEquals(200, bounds.getY(), 0.001f);
        assertEquals(300, bounds.getWidth(), 0.001f);
        assertEquals(400, bounds.getHeight(), 0.001f);
    }
}