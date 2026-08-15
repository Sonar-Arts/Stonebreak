package com.stonebreak.ui.support;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;

/**
 * Reusable layout invariants for UI tests, in the spirit of the engine's {@code MeshInvariants}.
 *
 * <p>These assert <em>properties</em>, never pixel coordinates. A designer nudging a panel by 4px
 * should not break a test; a panel sliding off screen, two slot rows overlapping, or a hit test
 * disagreeing with the rectangle it was derived from should. Golden coordinates would invert that
 * — they churn on every visual tweak while still missing the bugs that matter.
 *
 * <p>Every helper takes a {@code context} string so a failure inside a loop over
 * {@link Resolutions#ALL} names the resolution that broke.
 */
public final class UiLayoutAssert {

    private UiLayoutAssert() {}

    /** An axis-aligned rectangle under test. */
    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }

        public boolean overlaps(Rect other) {
            return x < other.right() && other.x < right()
                && y < other.bottom() && other.y < bottom();
        }

        @Override
        public String toString() {
            return "[" + x + "," + y + " " + width + "x" + height + "]";
        }
    }

    /** Fails unless the rectangle lies entirely within the given viewport. */
    public static void assertOnScreen(Rect rect, int screenWidth, int screenHeight, String context) {
        assertTrue(rect.x() >= 0 && rect.y() >= 0
                && rect.right() <= screenWidth && rect.bottom() <= screenHeight,
            context + ": " + rect + " escapes the " + screenWidth + "x" + screenHeight + " viewport");
    }

    /** Fails unless the rectangle has strictly positive extent. */
    public static void assertPositiveSize(Rect rect, String context) {
        assertTrue(rect.width() > 0 && rect.height() > 0,
            context + ": " + rect + " has non-positive extent");
    }

    /**
     * Fails if any two rectangles in the list overlap. Reports the first offending pair, since
     * "3 rectangles overlap" is not actionable but "slot 4 overlaps slot 5" is.
     */
    public static void assertNoOverlap(List<Rect> rects, String context) {
        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                if (rects.get(i).overlaps(rects.get(j))) {
                    fail(context + ": rect " + i + " " + rects.get(i)
                        + " overlaps rect " + j + " " + rects.get(j));
                }
            }
        }
    }

    /** Fails unless {@code inner} is fully contained by {@code outer}. */
    public static void assertContains(Rect outer, Rect inner, String context) {
        assertTrue(inner.x() >= outer.x() && inner.y() >= outer.y()
                && inner.right() <= outer.right() && inner.bottom() <= outer.bottom(),
            context + ": " + inner + " is not contained by " + outer);
    }

    /**
     * Fails unless the rectangles are laid out left-to-right with strictly increasing x and no
     * backtracking — catches an off-by-one in a column stride that would otherwise only show as a
     * subtle visual overlap.
     */
    public static void assertOrderedHorizontally(List<Rect> rects, String context) {
        for (int i = 1; i < rects.size(); i++) {
            assertTrue(rects.get(i).x() > rects.get(i - 1).x(),
                context + ": rect " + i + " " + rects.get(i)
                    + " does not advance past rect " + (i - 1) + " " + rects.get(i - 1));
        }
    }

    /**
     * Fails unless the rectangles are laid out top-to-bottom with strictly increasing y.
     */
    public static void assertOrderedVertically(List<Rect> rects, String context) {
        for (int i = 1; i < rects.size(); i++) {
            assertTrue(rects.get(i).y() > rects.get(i - 1).y(),
                context + ": rect " + i + " " + rects.get(i)
                    + " does not advance past rect " + (i - 1) + " " + rects.get(i - 1));
        }
    }
}
