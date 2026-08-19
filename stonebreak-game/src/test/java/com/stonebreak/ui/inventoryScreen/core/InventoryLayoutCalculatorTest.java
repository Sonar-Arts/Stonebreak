package com.stonebreak.ui.inventoryScreen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator.InventoryLayout;
import com.stonebreak.ui.support.Resolutions;
import com.stonebreak.ui.support.UiLayoutAssert;
import com.stonebreak.ui.support.UiLayoutAssert.Rect;

/**
 * Guards the inventory panel's layout math: every resolution in {@link Resolutions#ALL} must
 * produce a panel with positive extent, non-overlapping sections, and reasonable scale factors.
 *
 * <p>The regression this prevents: a change to the constants or formula that silently shifts the
 * hotbar row into the main inventory area, or makes the crafting grid overlap the output slot —
 * bugs that only show as visual glitches, not exceptions.
 */
class InventoryLayoutCalculatorTest {

    private static int slotSize() {
        return InventoryLayoutCalculator.getSlotSize();
    }

    // ── geometry helpers: extract section rects from an InventoryLayout ──────────

    private Rect craftingGridRect(InventoryLayout layout) {
        int gs = InventoryLayoutCalculator.getCraftingGridSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        int w = gs * slotSize() + (gs - 1) * pad;
        int h = gs * slotSize() + (gs - 1) * pad;
        return new Rect(layout.craftingElementsStartX, layout.craftingGridStartY, w, h);
    }

    private Rect outputSlotRect(InventoryLayout layout) {
        return new Rect(layout.outputSlotX, layout.outputSlotY, slotSize(), slotSize());
    }

    private Rect mainInventoryRect(InventoryLayout layout) {
        int rows = com.stonebreak.items.Inventory.MAIN_INVENTORY_ROWS;
        int cols = com.stonebreak.items.Inventory.MAIN_INVENTORY_COLS;
        int pad = InventoryLayoutCalculator.getSlotPadding();
        int w = cols * slotSize() + (cols - 1) * pad;
        int h = rows * slotSize() + (rows - 1) * pad;
        return new Rect(layout.inventorySectionStartX, layout.mainInvContentStartY, w, h);
    }

    private Rect hotbarRowRect(InventoryLayout layout) {
        int cols = com.stonebreak.items.Inventory.HOTBAR_SIZE;
        int pad = InventoryLayoutCalculator.getSlotPadding();
        int w = cols * slotSize() + (cols - 1) * pad;
        return new Rect(layout.inventorySectionStartX, layout.hotbarRowY, w, slotSize());
    }

    private Rect panelRect(InventoryLayout layout) {
        return new Rect(layout.panelStartX, layout.panelStartY,
                layout.inventoryPanelWidth, layout.inventoryPanelHeight);
    }

    // ── tests ─────────────────────────────────────────────────────────────────────

    @Test
    void panelHasPositiveExtentAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayout layout = InventoryLayoutCalculator.calculateLayout(size.width(), size.height());
            UiLayoutAssert.assertPositiveSize(panelRect(layout), size.toString());
        }
    }

    @Test
    void craftingGridAndOutputSlotDoNotOverlap() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayout layout = InventoryLayoutCalculator.calculateLayout(size.width(), size.height());
            assertTrue(!craftingGridRect(layout).overlaps(outputSlotRect(layout)),
                size + ": crafting grid overlaps the output slot");
        }
    }

    @Test
    void mainInventoryAndHotbarDoNotOverlap() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayout layout = InventoryLayoutCalculator.calculateLayout(size.width(), size.height());
            assertTrue(!mainInventoryRect(layout).overlaps(hotbarRowRect(layout)),
                size + ": main inventory section overlaps the hotbar row");
        }
    }

    @Test
    void craftingGridOutputAndMainInventoryDoNotOverlap() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayout layout = InventoryLayoutCalculator.calculateLayout(size.width(), size.height());
            assertTrue(!craftingGridRect(layout).overlaps(mainInventoryRect(layout)),
                size + ": crafting grid overlaps main inventory");
            assertTrue(!outputSlotRect(layout).overlaps(mainInventoryRect(layout)),
                size + ": output slot overlaps main inventory");
        }
    }

    @Test
    void sectionsAreOrderedTopToBottom() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayout layout = InventoryLayoutCalculator.calculateLayout(size.width(), size.height());
            assertTrue(layout.craftingGridStartY < layout.mainInvContentStartY,
                size + ": crafting grid must start above main inventory");
            assertTrue(layout.mainInvContentStartY < layout.hotbarRowY,
                size + ": main inventory must start above hotbar");
        }
    }

    @Test
    void workbenchLayoutHasPositiveExtentAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayout layout = InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());
            UiLayoutAssert.assertPositiveSize(panelRect(layout), size.toString());
        }
    }

    @Test
    void workbenchLayoutHasAtLeastAsWideCraftingGridAsRegular() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayout regular = InventoryLayoutCalculator.calculateLayout(size.width(), size.height());
            InventoryLayout workbench = InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());

            assertTrue(workbench.craftInputGridVisualWidth >= regular.craftInputGridVisualWidth,
                size + ": workbench crafting grid (" + workbench.craftInputGridVisualWidth
                    + ") must be at least as wide as regular (" + regular.craftInputGridVisualWidth + ")");
        }
    }

    @Test
    void staticConstantsReturnPositiveValues() {
        assertTrue(InventoryLayoutCalculator.getSlotSize() > 0, "slot size must be positive");
        assertTrue(InventoryLayoutCalculator.getSlotPadding() > 0, "slot padding must be positive");
        assertTrue(InventoryLayoutCalculator.getCraftingGridSize() > 0, "crafting grid size must be positive");
        assertEquals(2, InventoryLayoutCalculator.getCraftingGridSize(),
            "the regular inventory has a 2x2 crafting grid");
        assertEquals(3, InventoryLayoutCalculator.getWorkbenchCraftingGridSize(),
            "the workbench has a 3x3 crafting grid");
    }

    @Test
    void isScreenSizeAdequateIsTrueAtMinimumAndFalseOnePixelBelow() {
        int minW = InventoryLayoutCalculator.getMinimumRecommendedWidth();
        int minH = InventoryLayoutCalculator.getMinimumRecommendedHeight();

        assertTrue(InventoryLayoutCalculator.isScreenSizeAdequate(minW, minH),
            "exactly at the reported minimums must count as adequate");
        assertFalse(InventoryLayoutCalculator.isScreenSizeAdequate(minW - 1, minH),
            "one pixel below the minimum width must not count as adequate");
        assertFalse(InventoryLayoutCalculator.isScreenSizeAdequate(minW, minH - 1),
            "one pixel below the minimum height must not count as adequate");
    }

    @Test
    void scaleFactorIsUnityWhenAdequateAndNeverCollapsesBelowFloor() {
        int minW = InventoryLayoutCalculator.getMinimumRecommendedWidth();
        int minH = InventoryLayoutCalculator.getMinimumRecommendedHeight();

        assertEquals(1.0f, InventoryLayoutCalculator.calculateScaleFactor(minW, minH), 0.0001f,
            "an adequate screen must return a scale factor of 1.0");

        float tiny = InventoryLayoutCalculator.calculateScaleFactor(64, 64);
        assertTrue(tiny >= 0.7f && tiny <= 1.0f,
            "scale factor must stay within [0.7, 1.0] but was " + tiny);
    }

    @Test
    void craftingArrowAutoAlignsWithTheOutputSlot() {
        for (Resolutions.Size size : Resolutions.ALL) {
            int arrowSize = Math.round(20f * com.stonebreak.config.Settings.getInstance().getUiScale());

            InventoryLayout regular = InventoryLayoutCalculator.calculateLayout(size.width(), size.height());
            assertArrowAligned(regular, arrowSize, size.toString());

            InventoryLayout workbench = InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());
            assertArrowAligned(workbench, arrowSize, size.toString());
        }
    }

    private void assertArrowAligned(InventoryLayout layout, int arrowSize, String label) {
        float gridRight = layout.craftingElementsStartX + layout.craftInputGridVisualWidth;
        float[] arrow = MPainter.craftingArrowPlacement(
                gridRight, layout.outputSlotX, layout.outputSlotY, slotSize(), arrowSize);

        assertEquals(layout.outputSlotY + slotSize() / 2f, arrow[1] + arrowSize / 2f, 0.001f,
            label + ": arrow must be vertically centred on the output slot");
        assertTrue(arrow[0] >= gridRight, label + ": arrow must start after the input grid");
        assertTrue(arrow[0] + arrowSize <= layout.outputSlotX,
            label + ": arrow must not overlap the output slot");
    }
}