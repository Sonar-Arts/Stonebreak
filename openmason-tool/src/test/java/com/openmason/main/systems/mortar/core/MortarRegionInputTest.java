package com.openmason.main.systems.mortar.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GL-free half of {@link MortarRegion#render()}: hit-testing queued part rects, turning
 * mouse samples into a {@link MortarFrameResult}, retargeting each part's animation and evicting
 * states of parts no longer drawn. No Skija context or ImGui frame is involved.
 */
class MortarRegionInputTest {

    private static final MortarPart NOOP = (g, x, y, w, h, state) -> { };

    private final MortarRegion region = new MortarRegion();

    /** One frame without paint: queue, prepare states, then resolve the given samples (NaN = not hovered). */
    private MortarFrameResult frame(float mx, float my, boolean active,
                                    boolean left, boolean dbl, boolean right, Runnable queue) {
        region.begin(300, 200);
        queue.run();
        region.prepareStates();
        String hovered = Float.isNaN(mx) ? null : region.hitTest(mx, my);
        return region.finishFrame(hovered, active, left, dbl, right);
    }

    private MortarFrameResult idleFrame(float mx, float my, Runnable queue) {
        return frame(mx, my, false, false, false, false, queue);
    }

    private void twoCards() {
        region.add("a", 10, 10, 100, 50, NOOP);
        region.add("b", 120, 10, 100, 50, true, NOOP);
    }

    @Test
    void hitTestIsEdgeInclusiveAndMissesGaps() {
        region.begin(300, 200);
        twoCards();
        assertEquals("a", region.hitTest(10, 10));
        assertEquals("a", region.hitTest(110, 60));
        assertNull(region.hitTest(115, 30), "the gap between parts hits nothing");
        assertNull(region.hitTest(9.99f, 30));
        assertEquals("b", region.hitTest(220, 60));
        assertNull(region.hitTest(220.01f, 60));
    }

    @Test
    void laterPartWinsOverlaps() {
        region.begin(300, 200);
        region.add("under", 0, 0, 100, 100, NOOP);
        region.add("over", 50, 50, 100, 100, NOOP);
        assertEquals("over", region.hitTest(75, 75));
        assertEquals("under", region.hitTest(25, 25));
    }

    @Test
    void beginClearsQueuedParts() {
        region.begin(300, 200);
        twoCards();
        region.begin(300, 200);
        assertNull(region.hitTest(50, 30));
    }

    @Test
    void clicksAreReportedForTheHoveredPartOnly() {
        MortarFrameResult r = frame(50, 30, false, true, true, true, this::twoCards);
        assertEquals("a", r.hovered());
        assertEquals("a", r.clicked());
        assertEquals("a", r.doubleClicked());
        assertEquals("a", r.rightClicked());
        assertTrue(r.isClicked("a"));
        assertFalse(r.isClicked("b"));
        assertFalse(r.isClicked(null));
    }

    @Test
    void clicksOverNoPartAreDropped() {
        MortarFrameResult r = frame(115, 30, true, true, true, true, this::twoCards);
        assertNull(r.hovered());
        assertNull(r.clicked());
        assertNull(r.doubleClicked());
        assertNull(r.rightClicked());
        assertFalse(r.isDoubleClicked(null));
        assertFalse(r.isRightClicked(null));
    }

    @Test
    void hoverWithoutButtonsReportsNoClicks() {
        MortarFrameResult r = idleFrame(150, 30, this::twoCards);
        assertEquals("b", r.hovered());
        assertNull(r.clicked());
        assertFalse(r.isDoubleClicked("b"));
        assertFalse(r.isRightClicked("b"));
    }

    @Test
    void retargetDrivesHoverPressAndSelectedBlends() {
        frame(50, 30, true, false, false, false, this::twoCards);
        region.update(10f);
        PartState a = region.stateOf("a");
        PartState b = region.stateOf("b");
        assertEquals(1f, a.hover());
        assertEquals(1f, a.press(), "held while hovered = pressed");
        assertEquals(0f, a.selected());
        assertEquals(0f, b.hover());
        assertEquals(0f, b.press(), "an active region presses only the hovered part");
        assertEquals(1f, b.selected());
    }

    @Test
    void activeRegionWithNoHoveredPartPressesNothing() {
        frame(Float.NaN, Float.NaN, true, false, false, false, this::twoCards);
        region.update(10f);
        assertEquals(0f, region.stateOf("a").press());
        assertEquals(0f, region.stateOf("b").press());
    }

    @Test
    void statePersistsAcrossFramesForAnimationContinuity() {
        idleFrame(50, 30, this::twoCards);
        PartState first = region.stateOf("a");
        region.update(1f / 60f);
        float midHover = first.hover();
        assertTrue(midHover > 0f && midHover < 1f);

        idleFrame(50, 30, this::twoCards);
        assertSame(first, region.stateOf("a"), "a part drawn every frame keeps its state");
        assertEquals(midHover, region.stateOf("a").hover(), "and its blend is not reset");
    }

    @Test
    void partsNotQueuedThisFrameAreEvicted() {
        idleFrame(50, 30, this::twoCards);
        assertNotNull(region.stateOf("b"));

        idleFrame(50, 30, () -> region.add("a", 10, 10, 100, 50, NOOP));
        assertNotNull(region.stateOf("a"));
        assertNull(region.stateOf("b"), "a part that disappears drops its state");

        // Coming back starts from rest rather than resuming a stale blend.
        idleFrame(150, 30, this::twoCards);
        assertEquals(0f, region.stateOf("b").hover());
    }

    @Test
    void movingTheMouseAwayRetargetsHoverToZero() {
        idleFrame(50, 30, this::twoCards);
        region.update(10f);
        assertEquals(1f, region.stateOf("a").hover());
        idleFrame(Float.NaN, Float.NaN, this::twoCards);
        region.update(10f);
        assertEquals(0f, region.stateOf("a").hover());
    }

    @Test
    void closeDropsAllStatesAndQueuedParts() {
        idleFrame(50, 30, this::twoCards);
        region.close();
        assertNull(region.stateOf("a"));
        assertNull(region.hitTest(50, 30));
    }

    @Test
    void noneResultReportsNothing() {
        MortarFrameResult none = MortarFrameResult.NONE;
        assertNull(none.hovered());
        assertFalse(none.isClicked("a"));
        assertFalse(none.isDoubleClicked("a"));
        assertFalse(none.isRightClicked("a"));
    }
}
