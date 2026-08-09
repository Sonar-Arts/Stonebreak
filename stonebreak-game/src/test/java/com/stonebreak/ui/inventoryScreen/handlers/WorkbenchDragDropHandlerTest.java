package com.stonebreak.ui.inventoryScreen.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler.DragState;
import com.stonebreak.ui.support.Resolutions;
import com.stonebreak.ui.support.UiTestFixtures;

/**
 * Guards the workbench drag-and-drop handler's 3x3 grid operations: the workbench
 * uses {@code calculateWorkbenchLayout} and a 3x3 crafting grid rather than the
 * regular inventory's 2x2.  {@code dropEntireStackIntoWorld} is not tested here
 * because it calls {@code Game.getPlayer()}.
 */
class WorkbenchDragDropHandlerTest {

    private static final int SCREEN_W = 1920;
    private static final int SCREEN_H = 1080;

    private Inventory inventory;
    private ItemStack[] craftingInputSlots;
    private DragState dragState;
    private InventoryLayoutCalculator.InventoryLayout layout;

    @BeforeEach
    void setUp() {
        inventory = UiTestFixtures.emptyInventory();
        craftingInputSlots = UiTestFixtures.emptyCraftingGrid(
            InventoryLayoutCalculator.getWorkbenchCraftingGridSize());
        dragState = new DragState();
        layout = InventoryLayoutCalculator.calculateWorkbenchLayout(SCREEN_W, SCREEN_H);
    }

    // ── grid size constants ─────────────────────────────────────────────────────

    @Test
    void workbenchCraftingGridSizeIs3() {
        assertEquals(3, InventoryLayoutCalculator.getWorkbenchCraftingGridSize(),
            "workbench grid size constant must be 3");
        assertEquals(9, InventoryLayoutCalculator.getWorkbenchCraftingInputSlotsCount(),
            "workbench must expose 9 crafting input slots (3x3)");
    }

    @Test
    void regularCraftingGridSizeIs2() {
        assertEquals(2, InventoryLayoutCalculator.getCraftingGridSize(),
            "regular inventory grid size constant must be 2");
        assertEquals(4, InventoryLayoutCalculator.getCraftingInputSlotsCount(),
            "regular inventory must expose 4 crafting input slots (2x2)");
    }

    // ── layout geometry ─────────────────────────────────────────────────────────

    @Test
    void workbenchLayoutHasPositiveExtentAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayoutCalculator.InventoryLayout wl =
                InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());
            assertTrue(wl.inventoryPanelWidth > 0 && wl.inventoryPanelHeight > 0,
                size + ": workbench panel must have positive extent");
        }
    }

    @Test
    void workbenchCraftingGridIsWiderThanRegular() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayoutCalculator.InventoryLayout regular =
                InventoryLayoutCalculator.calculateLayout(size.width(), size.height());
            InventoryLayoutCalculator.InventoryLayout workbench =
                InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());

            assertTrue(workbench.craftInputGridVisualWidth >= regular.craftInputGridVisualWidth,
                size + ": workbench crafting grid (" + workbench.craftInputGridVisualWidth
                    + ") must be at least as wide as regular (" + regular.craftInputGridVisualWidth + ")");
        }
    }

    // ── tryReturnToOriginalSlot with empty drag state ───────────────────────────

    @Test
    void tryReturnToOriginalSlotDoesNotCrashWhenDragStateIsEmpty() {
        WorkbenchDragDropHandler.tryReturnToOriginalSlot(
            dragState, inventory, craftingInputSlots, layout, () -> {});
        assertFalse(dragState.isDragging());
    }
}