package com.stonebreak.ui.inventoryScreen.core;

import static com.stonebreak.ui.support.UiTestFixtures.countOf;
import static com.stonebreak.ui.support.UiTestFixtures.emptyInventory;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.config.Settings;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.ui.TabStripLayout;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator.InventoryLayout;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator.InventoryLayout3Col;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.inventoryScreen.handlers.PointerFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Legacy container-screen bugs found by the #283 ledger audit and fixed before the #300 migration,
 * driven through the screens' real pointer rules ({@link ContainerSlotInput#handlePointer}):
 * <ul>
 *   <li>the workbench's pick-up, right-click and shift-click hitboxes sat one slot padding left of
 *       the slots it draws (every rule now hit-tests {@link InventoryLayoutCalculator#gridStartX});</li>
 *   <li>a stack released over the inventory screen's side columns or tab strip was dropped into the
 *       world (the drop test used the centre column's bounds); it now goes back where it came from;</li>
 *   <li>right-click "place one" built a fresh item and lost the stack's state.</li>
 * </ul>
 * World drops cannot happen here (no player), so a stack that would have been dropped is simply gone:
 * conservation of the item count is the assertion.
 */
class ContainerSlotRulesRegressionTest {

    private static final int W = 1920;
    private static final int H = 1080;

    private float previousScale;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        previousScale = Settings.getInstance().getUiScale();
        inventory = emptyInventory();
    }

    @AfterEach
    void restoreScale() {
        Settings.getInstance().setUiScale(previousScale);
    }

    private WorkbenchInputManager workbench() {
        InventoryCraftingManager grid = new InventoryCraftingManager(new CraftingManager(),
            InventoryLayoutCalculator.getWorkbenchCraftingGridSize());
        return new WorkbenchInputManager(null, inventory, new InventorySlotManager(inventory, grid), grid);
    }

    private InventoryInputManager inventoryScreen() {
        InventoryCraftingManager grid = new InventoryCraftingManager(new CraftingManager());
        return new InventoryInputManager(null, inventory, new InventorySlotManager(inventory, grid), grid);
    }

    private static void press(ContainerSlotInput screen, float x, float y, int button) {
        screen.handlePointer(new PointerFrame(x, y, button == 0, button == 1, button == 1, false, false), W, H);
        screen.handlePointer(PointerFrame.idle(x, y), W, H);
    }

    /** Where the workbench draws main slot {@code i} (WorkbenchRenderCoordinator). */
    private static int[] drawnMain(InventoryLayout l, int i) {
        int pitch = InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding();
        return new int[]{l.inventorySectionStartX + InventoryLayoutCalculator.getSlotPadding() + (i % 9) * pitch,
            l.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() + (i / 9) * pitch};
    }

    @Test
    void workbenchSlotAddressesAreTheDrawnSlots() {
        for (float scale : new float[]{0.75f, 1f, 1.25f, 2f}) {
            Settings.getInstance().setUiScale(scale);
            assertDrawnAddresses();
        }
    }

    private void assertDrawnAddresses() {
        WorkbenchInputManager wb = workbench();
        InventoryLayout l = InventoryLayoutCalculator.calculateWorkbenchLayout(W, H);
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int[] d = drawnMain(l, i);
            assertArrayEquals(new float[]{d[0], d[1]}, wb.slotOrigin("main:" + i, W, H), "main:" + i);
        }
        int pitch = InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            float[] o = wb.slotOrigin("hotbar:" + i, W, H);
            assertEquals(l.inventorySectionStartX + InventoryLayoutCalculator.getSlotPadding() + i * pitch, o[0], "hotbar:" + i);
            assertEquals(l.hotbarRowY, o[1], "hotbar:" + i);
        }
    }

    @Test
    void workbenchPicksUpWhereTheSlotIsDrawn() {
        WorkbenchInputManager wb = workbench();
        InventoryLayout l = InventoryLayoutCalculator.calculateWorkbenchLayout(W, H);
        inventory.setMainInventorySlot(3, new ItemStack(BlockType.DIRT, 7));
        int[] d = drawnMain(l, 3);
        int ss = InventoryLayoutCalculator.getSlotSize();
        // near the drawn slot's right edge: inside it, but past the old (padding-left) hitbox
        press(wb, d[0] + ss - 2, d[1] + ss / 2f, 0);
        assertTrue(wb.getDragState().isDragging(), "the click on the drawn slot picked its stack up");
        assertEquals(7, wb.getDragState().draggedItemStack.getCount());

        // and the old hitbox's sliver left of the drawn slot is the gap now: the stack goes back
        press(wb, d[0] - 4, d[1] + ss / 2f, 0);
        assertFalse(wb.getDragState().isDragging());
        assertEquals(7, inventory.getMainInventorySlot(3).getCount(), "back in its own slot");
    }

    @Test
    void stackReleasedOverTheInventorySideColumnsGoesBack() {
        InventoryInputManager inv = inventoryScreen();
        InventoryLayout3Col l3 = InventoryLayoutCalculator.calculateThreeColumnLayout(W, H);
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.STONE, 12));
        float[] slot0 = inv.slotCentre("main:0", W, H);

        float[][] bare = {
            {l3.leftColX + l3.leftColW / 2f, l3.leftColY + l3.leftColH - 4},    // left column
            {l3.rightColX + l3.rightColW / 2f, l3.rightColY + l3.rightColH - 4}, // right column
            {TabStripLayout.startX(W) + 2, TabStripLayout.tabY(l3.panelStartY) + 2}, // the tab strip
        };
        for (float[] p : bare) {
            press(inv, slot0[0], slot0[1], 0);
            assertTrue(inv.getDragState().isDragging());
            press(inv, p[0], p[1], 0);
            assertFalse(inv.getDragState().isDragging());
            assertEquals(12, countOf(inventory, BlockType.STONE), "nothing dropped into the world at " + p[0] + "," + p[1]);
            assertEquals(12, inventory.getMainInventorySlot(0).getCount());
        }

        // beyond everything the screen draws, the stack still goes into the world
        press(inv, slot0[0], slot0[1], 0);
        press(inv, l3.panelStartX - 20, l3.panelStartY + 10, 0);
        assertEquals(0, countOf(inventory, BlockType.STONE), "outside the panel the stack is dropped");
    }

    @Test
    void panelAddressPutsAHeldStackBack() {
        assertPanelReturns(inventoryScreen());
        inventory = emptyInventory();
        assertPanelReturns(workbench());
    }

    private void assertPanelReturns(ContainerSlotInput screen) {
        inventory.setHotbarSlot(4, new ItemStack(BlockType.SAND, 5));
        float[] hot = screen.slotCentre("hotbar:4", W, H);
        press(screen, hot[0], hot[1], 0);
        assertTrue(screen.getDragState().isDragging());
        float[] panel = screen.slotCentre("panel", W, H);
        press(screen, panel[0], panel[1], 0);
        assertFalse(screen.getDragState().isDragging());
        assertEquals(5, inventory.getHotbarSlot(4).getCount(), screen.getClass().getSimpleName());
    }

    @Test
    void rightClickPlaceOneKeepsTheStacksState() {
        InventoryInputManager inv = inventoryScreen();
        inventory.setMainInventorySlot(0, new ItemStack(ItemType.STICK, 4, "worn=2"));
        float[] from = inv.slotCentre("main:0", W, H);
        float[] to = inv.slotCentre("main:8", W, H);
        press(inv, from[0], from[1], 0);
        press(inv, to[0], to[1], 1);
        ItemStack placed = inventory.getMainInventorySlot(8);
        assertEquals(1, placed.getCount());
        assertEquals("worn=2", placed.getState(), "one of the held stack, state and all");
        assertEquals(3, inv.getDragState().draggedItemStack.getCount());
        assertTrue(inventory.getMainInventorySlot(0).isEmpty());
    }
}
