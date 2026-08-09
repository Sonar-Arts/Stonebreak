package com.stonebreak.rendering.UI.masonryUI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the inertial scroll math shared by every MasonryUI scroll container.
 *
 * <p>The invariant that matters is containment: the offset must never leave {@code [0, maxOffset]},
 * no matter what sequence of wheel events and frame steps it sees. The momentum term is applied
 * <em>after</em> the lerp and can push past either end on its own, so the clamp is load-bearing —
 * losing it would let a menu scroll into blank space above its first row or past its last, which
 * looks like content loss rather than a scrolling bug.
 *
 * <p>The second invariant is convergence: once the wheel stops, repeated frames must settle exactly
 * on the target rather than jittering around it forever. {@code update} snaps when it gets within
 * half a pixel, so a decaying velocity that never dies would show up as a permanently twitching
 * panel.
 *
 * <p>Frames are stepped with explicit deltas — no sleeping, so the test is deterministic.
 */
class MScrollMathTest {

    private static final float FRAME = 1f / 60f;

    private MScrollMath scroll;

    @BeforeEach
    void setUp() {
        scroll = new MScrollMath();
    }

    /** Runs enough frames for the lerp to settle and the momentum to decay away. */
    private void settle() {
        for (int i = 0; i < 400; i++) {
            scroll.update(FRAME);
        }
    }

    private void assertWithinBounds(String context) {
        assertTrue(scroll.offset() >= 0f,
            context + ": offset went negative (" + scroll.offset() + ")");
        assertTrue(scroll.offset() <= scroll.maxOffset(),
            context + ": offset " + scroll.offset() + " exceeded maxOffset " + scroll.maxOffset());
    }

    // ---- bounds ------------------------------------------------------------------------------

    @Test
    void maxOffsetIsZeroWhenContentFitsInsideTheViewport() {
        scroll.updateBounds(500f, 200f);

        assertEquals(0f, scroll.maxOffset(), 0.0001f,
            "content shorter than the viewport must not be scrollable");
        assertFalse(scroll.isScrollNeeded(),
            "isScrollNeeded must be false when the content fits");
    }

    @Test
    void maxOffsetCoversTheContentOverflowWhenContentIsTaller() {
        scroll.updateBounds(200f, 500f);

        assertEquals(300f, scroll.maxOffset(), 0.0001f,
            "maxOffset must equal the content overflow when padding is zero");
        assertTrue(scroll.isScrollNeeded(),
            "isScrollNeeded must be true when content is taller than the viewport");
    }

    @Test
    void scrollingIsPinnedAtZeroWhenThereIsNothingToScroll() {
        scroll.updateBounds(500f, 200f);

        scroll.handleWheel(-10f); // try to scroll down
        settle();

        assertEquals(0f, scroll.offset(), 0.0001f,
            "a non-scrollable container must stay pinned at 0");
    }

    @Test
    void offsetStaysInBoundsAcrossAnAggressiveWheelSequence() {
        scroll.updateBounds(200f, 500f); // maxOffset 300

        // Alternating hard flicks in both directions, stepping frames in between — the momentum
        // term is applied after the lerp, so this is the sequence most likely to overshoot.
        float[] flicks = { -50f, 50f, -120f, 5f, -7f, 200f, -200f };
        for (float flick : flicks) {
            scroll.handleWheel(flick);
            for (int i = 0; i < 10; i++) {
                scroll.update(FRAME);
                assertWithinBounds("after wheel " + flick);
            }
        }

        settle();
        assertWithinBounds("after settling");
    }

    @Test
    void scrollToClampsToBothEnds() {
        scroll.updateBounds(200f, 500f); // maxOffset 300

        scroll.scrollTo(9999f);
        assertEquals(300f, scroll.offset(), 0.0001f, "scrollTo past the end must clamp to maxOffset");

        scroll.scrollTo(-9999f);
        assertEquals(0f, scroll.offset(), 0.0001f, "scrollTo before the start must clamp to 0");
    }

    @Test
    void shrinkingTheContentPullsAnOutOfRangeOffsetBackIntoBounds() {
        scroll.updateBounds(200f, 500f);
        scroll.scrollTo(300f); // parked at the very bottom

        // The container's content shrinks (e.g. a settings category with fewer rows).
        scroll.updateBounds(200f, 250f); // maxOffset now 50

        assertEquals(50f, scroll.maxOffset(), 0.0001f);
        assertWithinBounds("after content shrank");
        assertEquals(50f, scroll.offset(), 0.0001f,
            "an offset left past the new end must be pulled back to it, not left dangling");
    }

    // ---- convergence -------------------------------------------------------------------------

    @Test
    void repeatedFramesSettleExactlyOnTheTargetAndStopMoving() {
        scroll.updateBounds(200f, 500f);

        scroll.handleWheel(-4f);
        settle();

        float resting = scroll.offset();
        // Once settled, further frames must not move it at all.
        for (int i = 0; i < 20; i++) {
            scroll.update(FRAME);
        }
        assertEquals(resting, scroll.offset(), 0.0001f,
            "a settled scroller must be completely at rest, not drifting or jittering");
        assertWithinBounds("at rest");
    }

    @Test
    void aWheelFlickActuallyMovesTheOffset() {
        scroll.updateBounds(200f, 500f);
        float before = scroll.offset();

        scroll.handleWheel(-4f); // negative delta scrolls toward the end
        settle();

        assertTrue(scroll.offset() > before,
            "a downward flick must advance the offset, but it stayed at " + scroll.offset());
    }

    @Test
    void updateWithoutAnyWheelInputChangesNothing() {
        scroll.updateBounds(200f, 500f);
        scroll.scrollTo(120f);

        settle();

        assertEquals(120f, scroll.offset(), 0.0001f,
            "frames with no input must not move a parked scroller");
    }

    // ---- reset -------------------------------------------------------------------------------

    @Test
    void resetReturnsEveryFieldToZero() {
        scroll.updateBounds(200f, 500f);
        scroll.handleWheel(-20f);
        settle();

        scroll.reset();

        assertEquals(0f, scroll.offset(), 0.0001f, "offset must reset to 0");
        assertEquals(0f, scroll.maxOffset(), 0.0001f, "maxOffset must reset to 0");
        assertEquals(0f, scroll.viewportHeight(), 0.0001f, "viewport height must reset to 0");
        assertEquals(0f, scroll.contentHeight(), 0.0001f, "content height must reset to 0");
    }

    @Test
    void aResetScrollerDoesNotDriftOnTheNextFrame() {
        scroll.updateBounds(200f, 500f);
        scroll.handleWheel(-20f);
        scroll.update(FRAME); // leave momentum in flight

        scroll.reset();
        scroll.update(FRAME);

        assertEquals(0f, scroll.offset(), 0.0001f,
            "leftover velocity must not survive a reset and drag the offset off zero");
    }

    // ---- tuning setters ----------------------------------------------------------------------

    @Test
    void paddingWidensTheScrollableRange() {
        MScrollMath padded = new MScrollMath().padding(10f);
        padded.updateBounds(200f, 500f);

        // maxOffset = content - viewport + padding*2
        assertEquals(320f, padded.maxOffset(), 0.0001f,
            "padding must extend the scrollable range at both ends");
    }

    @Test
    void builderSettersReturnTheSameInstanceSoCallsCanChain() {
        MScrollMath instance = new MScrollMath();
        MScrollMath chained = instance
            .wheelSensitivity(10f)
            .velocityFactor(0.5f)
            .velocityDecay(0.9f)
            .lerpSpeed(4f)
            .padding(2f);

        assertSame(instance, chained,
            "the fluent setters must return this so a configuration chain works");
    }
}
