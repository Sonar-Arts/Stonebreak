package com.stonebreak.ui.hotbar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.stonebreak.items.Inventory;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator.HotbarLayout;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator.SlotPosition;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import com.stonebreak.ui.support.Resolutions;
import com.stonebreak.ui.support.UiLayoutAssert;
import com.stonebreak.ui.support.UiLayoutAssert.Rect;

/**
 * Guards the hotbar's geometry <-> hit-test contract.
 *
 * <p>The regression this exists to catch: a slot stride or bounds change that leaves
 * {@code calculateSlotPosition} (what gets drawn) disagreeing with {@code getSlotIndexAt} (what
 * gets clicked). That class of bug is invisible in a screenshot — the hotbar looks right and
 * clicks land on the wrong slot — so nothing but a round-trip assertion will find it.
 *
 * <p>Assertions are invariants, not pinned coordinates, so a visual nudge to padding or offsets
 * does not break this file. That matters here because the numbers depend on the player's UI scale
 * (see below), which the test must not mutate.
 *
 * <p><b>UI scale is read, never written.</b> {@code Settings} is a process-global singleton and
 * surefire runs the whole module in one JVM, so writing it would leak into unrelated tests. Every
 * assertion below therefore has to hold at whatever scale the developer's {@code settings.json}
 * happens to supply.
 *
 * <p><b>The hotbar derives its slot metrics live from {@code InventoryLayoutCalculator}</b>
 * so the UI scale is applied exactly once. {@code slotMetricsComeFromTheInventoryCalculatorUnscaledTwice}
 * guards this invariant at any UI scale.
 */
class HotbarLayoutCalculatorTest {

    private static Rect toRect(SlotPosition pos) {
        return new Rect(pos.x, pos.y, pos.width, pos.height);
    }

    private static List<Rect> allSlots(HotbarLayout layout) {
        List<Rect> rects = new ArrayList<>();
        for (int i = 0; i < layout.slotCount; i++) {
            rects.add(toRect(HotbarLayoutCalculator.calculateSlotPosition(i, layout)));
        }
        return rects;
    }

    @Test
    void slotCentersRoundTripToTheirOwnIndex() {
        for (Resolutions.Size size : Resolutions.ALL) {
            HotbarLayout layout = HotbarLayoutCalculator.calculateLayout(size.width(), size.height());
            for (int i = 0; i < layout.slotCount; i++) {
                SlotPosition pos = HotbarLayoutCalculator.calculateSlotPosition(i, layout);
                assertEquals(i, HotbarLayoutCalculator.getSlotIndexAt(pos.centerX, pos.centerY, layout),
                    size + ": center of slot " + i + " did not resolve back to slot " + i);
            }
        }
    }

    @Test
    void isPointInSlotAgreesWithGetSlotIndexAt() {
        for (Resolutions.Size size : Resolutions.ALL) {
            HotbarLayout layout = HotbarLayoutCalculator.calculateLayout(size.width(), size.height());
            for (int i = 0; i < layout.slotCount; i++) {
                SlotPosition pos = HotbarLayoutCalculator.calculateSlotPosition(i, layout);
                // The two hit-test entry points must never disagree about the same point.
                assertTrue(HotbarLayoutCalculator.isPointInSlot(pos.centerX, pos.centerY, i, layout),
                    size + ": slot " + i + " does not contain its own center");
                assertEquals(i, HotbarLayoutCalculator.getSlotIndexAt(pos.centerX, pos.centerY, layout),
                    size + ": hit-test entry points disagree for slot " + i);
            }
        }
    }

    @Test
    void pointsOutsideTheHotbarResolveToNoSlot() {
        for (Resolutions.Size size : Resolutions.ALL) {
            HotbarLayout layout = HotbarLayoutCalculator.calculateLayout(size.width(), size.height());
            SlotPosition first = HotbarLayoutCalculator.calculateSlotPosition(0, layout);
            SlotPosition last = HotbarLayoutCalculator.calculateSlotPosition(layout.slotCount - 1, layout);

            assertEquals(-1, HotbarLayoutCalculator.getSlotIndexAt(first.x - 1, first.centerY, layout),
                size + ": a point left of the first slot claimed a slot");
            assertEquals(-1, HotbarLayoutCalculator.getSlotIndexAt(last.x + last.width + 1, last.centerY, layout),
                size + ": a point right of the last slot claimed a slot");
            assertEquals(-1, HotbarLayoutCalculator.getSlotIndexAt(first.centerX, first.y - 1, layout),
                size + ": a point above the hotbar claimed a slot");
            assertEquals(-1, HotbarLayoutCalculator.getSlotIndexAt(first.centerX, first.y + first.height + 1, layout),
                size + ": a point below the hotbar claimed a slot");
        }
    }

    @Test
    void slotsAreOrderedLeftToRightAndNeverOverlap() {
        for (Resolutions.Size size : Resolutions.ALL) {
            HotbarLayout layout = HotbarLayoutCalculator.calculateLayout(size.width(), size.height());
            List<Rect> slots = allSlots(layout);

            UiLayoutAssert.assertOrderedHorizontally(slots, size.toString());
            UiLayoutAssert.assertNoOverlap(slots, size.toString());
            for (Rect slot : slots) {
                UiLayoutAssert.assertPositiveSize(slot, size.toString());
            }
        }
    }

    @Test
    void everySlotSitsInsideTheHotbarBackground() {
        for (Resolutions.Size size : Resolutions.ALL) {
            HotbarLayout layout = HotbarLayoutCalculator.calculateLayout(size.width(), size.height());
            Rect background = new Rect(layout.backgroundX, layout.backgroundY,
                layout.backgroundWidth, layout.backgroundHeight);

            for (Rect slot : allSlots(layout)) {
                UiLayoutAssert.assertContains(background, slot, size.toString());
            }
        }
    }

    @Test
    void hotbarIsHorizontallyCenteredAndOnScreen() {
        for (Resolutions.Size size : Resolutions.ALL) {
            HotbarLayout layout = HotbarLayoutCalculator.calculateLayout(size.width(), size.height());
            Rect background = new Rect(layout.backgroundX, layout.backgroundY,
                layout.backgroundWidth, layout.backgroundHeight);

            // Centering holds unconditionally: integer division means the left and right margins
            // may differ by at most one pixel, even if the bar is wider than the screen.
            int leftMargin = layout.backgroundX;
            int rightMargin = size.width() - (layout.backgroundX + layout.backgroundWidth);
            assertTrue(Math.abs(leftMargin - rightMargin) <= 1,
                size + ": hotbar is not centered — margins " + leftMargin + " vs " + rightMargin);

            // Staying on screen is only meaningful when the bar actually fits. Nine slots at a
            // large UI scale legitimately exceed a narrow viewport, and the test must not fail on
            // one developer's settings.json for that.
            if (layout.backgroundWidth <= size.width() && layout.backgroundHeight <= size.height()) {
                UiLayoutAssert.assertOnScreen(background, size.width(), size.height(), size.toString());
            }
        }
    }

    @Test
    void slotCountMatchesTheHotbarInventorySize() {
        HotbarLayout layout = HotbarLayoutCalculator.calculateLayout(1920, 1080);
        assertEquals(Inventory.HOTBAR_SIZE, layout.slotCount,
            "layout must expose exactly as many slots as the inventory has hotbar slots");
    }

    @Test
    void slotMetricsComeFromTheInventoryCalculatorUnscaledTwice() {
        assertEquals(InventoryLayoutCalculator.getSlotSize(), HotbarLayoutCalculator.getSlotSize(),
            "hotbar slot size must be the inventory's scaled slot size, applied exactly once");
        assertEquals(InventoryLayoutCalculator.getSlotPadding(), HotbarLayoutCalculator.getSlotPadding(),
            "hotbar slot padding must be the inventory's scaled padding, applied exactly once");

        HotbarLayout layout = HotbarLayoutCalculator.calculateLayout(1920, 1080);
        SlotPosition slot = HotbarLayoutCalculator.calculateSlotPosition(0, layout);
        assertEquals(InventoryLayoutCalculator.getSlotSize(), slot.width,
            "a drawn hotbar slot must be exactly one scaled slot wide");
        assertEquals(InventoryLayoutCalculator.getSlotSize(), slot.height,
            "a drawn hotbar slot must be exactly one scaled slot tall");
    }

    @Test
    void outOfRangeSlotIndexIsRejected() {
        HotbarLayout layout = HotbarLayoutCalculator.calculateLayout(1920, 1080);

        assertThrows(IllegalArgumentException.class,
            () -> HotbarLayoutCalculator.calculateSlotPosition(-1, layout),
            "negative slot index must be rejected rather than silently computing a position");
        assertThrows(IllegalArgumentException.class,
            () -> HotbarLayoutCalculator.calculateSlotPosition(layout.slotCount, layout),
            "slot index at the count boundary must be rejected");
    }

    @Test
    void tooltipStaysOnScreenEvenForEdgeSlots() {
        for (Resolutions.Size size : Resolutions.ALL) {
            HotbarLayout layout = HotbarLayoutCalculator.calculateLayout(size.width(), size.height());
            // A tooltip wider than a slot is the case that pushes past the screen edge.
            float tooltipWidth = 300f;
            float tooltipHeight = 40f;

            for (int i : new int[] { 0, layout.slotCount - 1 }) {
                HotbarLayoutCalculator.TooltipPosition tip =
                    HotbarLayoutCalculator.calculateTooltipPosition(i, layout, tooltipWidth, tooltipHeight, size.width());

                assertTrue(tip.x >= 0, size + ": tooltip for slot " + i + " starts off the left edge at x=" + tip.x);
                assertTrue(tip.x + tip.width <= size.width(),
                    size + ": tooltip for slot " + i + " runs past the right edge");
            }
        }
    }

    @Test
    void adequacyThresholdsAgreeWithTheReportedMinimums() {
        int minWidth = HotbarLayoutCalculator.getMinimumRecommendedWidth();
        int minHeight = HotbarLayoutCalculator.getMinimumRecommendedHeight();

        assertTrue(HotbarLayoutCalculator.isScreenSizeAdequate(minWidth, minHeight),
            "a screen exactly at the reported minimum must count as adequate");
        assertFalse(HotbarLayoutCalculator.isScreenSizeAdequate(minWidth - 1, minHeight),
            "one pixel below the minimum width must not count as adequate");
        assertFalse(HotbarLayoutCalculator.isScreenSizeAdequate(minWidth, minHeight - 1),
            "one pixel below the minimum height must not count as adequate");
    }

    @Test
    void scaleFactorIsUnityWhenAdequateAndNeverCollapses() {
        int minWidth = HotbarLayoutCalculator.getMinimumRecommendedWidth();
        int minHeight = HotbarLayoutCalculator.getMinimumRecommendedHeight();

        assertEquals(1.0f, HotbarLayoutCalculator.calculateScaleFactor(minWidth, minHeight), 0.0001f,
            "an adequate screen must not be scaled down");

        // Well below the minimum, the calculator still refuses to shrink past 70%.
        float tiny = HotbarLayoutCalculator.calculateScaleFactor(64, 64);
        assertTrue(tiny >= 0.7f && tiny <= 1.0f,
            "scale factor must stay within [0.7, 1.0] but was " + tiny);
    }
}
