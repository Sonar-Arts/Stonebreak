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
import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.ui.TabStripLayout;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator.InventoryLayout;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator.InventoryLayout3Col;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.inventoryScreen.handlers.PointerFrame;
import java.util.List;
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
    private long now = 1_000L;

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
        return inventoryScreen(new CraftingManager());
    }

    private InventoryInputManager inventoryScreen(CraftingManager recipes) {
        InventoryCraftingManager grid = new InventoryCraftingManager(recipes);
        return new InventoryInputManager(null, inventory, new InventorySlotManager(inventory, grid), grid);
    }

    /** An inventory screen whose double-click window is measured on {@link #now}, not the wall clock. */
    private InventoryInputManager clockedInventoryScreen(CraftingManager recipes) {
        InventoryInputManager inv = inventoryScreen(recipes);
        inv.useClock(() -> now);
        return inv;
    }

    private static void press(ContainerSlotInput screen, float x, float y, int button) {
        screen.handlePointer(new PointerFrame(x, y, button == 0, button == 1, button == 1, false, false), W, H);
        screen.handlePointer(PointerFrame.idle(x, y), W, H);
    }

    /** Where the workbench draws main slot {@code i} (the legacy layout, which the document reproduces). */
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

    /**
     * Issue #352: at small UI scales the next slot's centre is closer than the old fixed 40 px
     * double-click radius, so a quick pick-up-then-place gathered instead of placing. Double-clicks
     * are counted on the same slot only, so the second press places the stack.
     */
    @Test
    void quickClickOnTheNextSlotPlacesInsteadOfGathering() {
        Settings.getInstance().setUiScale(0.75f);
        InventoryInputManager inv = clockedInventoryScreen(new CraftingManager());
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 10));
        inventory.setMainInventorySlot(1, new ItemStack(BlockType.DIRT, 10));
        float[] first = inv.slotCentre("main:0", W, H);
        float[] second = inv.slotCentre("main:1", W, H);

        press(inv, first[0], first[1], 0);
        press(inv, second[0], second[1], 0);

        assertFalse(inv.getDragState().isDragging(), "the stack was placed, not gathered");
        assertEquals(20, inventory.getMainInventorySlot(1).getCount(), "merged into the second slot");
        assertEquals(20, countOf(inventory, BlockType.DIRT), "nothing created or lost");
    }

    /** The legitimate double-click gather survives at small scales when both presses are on one slot. */
    @Test
    void quickSecondClickOnTheSameSlotStillGathers() {
        Settings.getInstance().setUiScale(0.75f);
        InventoryInputManager inv = clockedInventoryScreen(new CraftingManager());
        inventory.setMainInventorySlot(2, new ItemStack(BlockType.DIRT, 10));
        inventory.setMainInventorySlot(5, new ItemStack(BlockType.DIRT, 5));
        float[] centre = inv.slotCentre("main:2", W, H);

        press(inv, centre[0], centre[1], 0);
        press(inv, centre[0], centre[1], 0);

        assertTrue(inv.getDragState().isDragging(), "the gathered stack stays on the cursor");
        assertEquals(15, inv.getDragState().draggedItemStack.getCount());
        assertEquals(0, countOf(inventory, BlockType.DIRT), "every matching stack was gathered");
    }

    /** Past the window a second press on the same slot is an ordinary click: the stack goes back. */
    @Test
    void slowSecondClickOnTheSameSlotPutsTheStackBack() {
        InventoryInputManager inv = clockedInventoryScreen(new CraftingManager());
        inventory.setMainInventorySlot(2, new ItemStack(BlockType.DIRT, 10));
        inventory.setMainInventorySlot(5, new ItemStack(BlockType.DIRT, 5));
        float[] centre = inv.slotCentre("main:2", W, H);

        press(inv, centre[0], centre[1], 0);
        now += 351;
        press(inv, centre[0], centre[1], 0);

        assertFalse(inv.getDragState().isDragging());
        assertEquals(10, inventory.getMainInventorySlot(2).getCount(), "put back");
        assertEquals(5, inventory.getMainInventorySlot(5).getCount(), "nothing gathered");
    }

    /**
     * Issue #352 review: only the pick-up arms a double-click. Picking up from main:0, right-clicking
     * one item back into main:0 and left-clicking main:0 inside the window sets the rest down; before,
     * the left press still matched the pick-up's slot and gathered every matching stack instead.
     */
    @Test
    void placeOneThenPlaceTheRestOnTheOriginDoesNotGather() {
        InventoryInputManager inv = clockedInventoryScreen(new CraftingManager());
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 10));
        inventory.setMainInventorySlot(7, new ItemStack(BlockType.DIRT, 4));
        float[] origin = inv.slotCentre("main:0", W, H);

        press(inv, origin[0], origin[1], 0);
        press(inv, origin[0], origin[1], 1);
        assertEquals(9, inv.getDragState().draggedItemStack.getCount(), "one placed back");
        press(inv, origin[0], origin[1], 0);

        assertFalse(inv.getDragState().isDragging(), "the rest was placed, not gathered");
        assertEquals(10, inventory.getMainInventorySlot(0).getCount());
        assertEquals(4, inventory.getMainInventorySlot(7).getCount(), "the other stack was not gathered");
    }

    /**
     * Issue #352 review: a quick second press on the crafting output that cannot craft another
     * batch (here the grid is used up) does not gather the inventory's matching stacks.
     */
    @Test
    void quickSecondClickOnTheCraftOutputNeverGathers() {
        CraftingManager recipes = new CraftingManager();
        recipes.registerRecipe(new Recipe("dirtToSticks",
            List.of(List.of(new ItemStack(BlockType.DIRT, 1))), new ItemStack(ItemType.STICK, 4)));
        InventoryInputManager inv = clockedInventoryScreen(recipes);
        inv.getCraftingManager().setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 1));
        inv.getCraftingManager().updateCraftingOutput();
        inventory.setMainInventorySlot(4, new ItemStack(ItemType.STICK, 8));
        float[] output = inv.slotCentre("craft-output", W, H);

        press(inv, output[0], output[1], 0);
        assertEquals(4, inv.getDragState().draggedItemStack.getCount(), "one batch taken");
        press(inv, output[0], output[1], 0);

        assertEquals(8, inventory.getMainInventorySlot(4).getCount(), "the stick stack was not gathered");
    }

    /**
     * Issue #350: shift-clicking a crafting cell moved items by id, so water buckets merged into an
     * empty-bucket stack (and became empty). Both grids: the inventory's 2x2 and the workbench's 3x3.
     */
    @Test
    void shiftClickingAGridCellKeepsTheBucketState() {
        InventoryCraftingManager small = new InventoryCraftingManager(new CraftingManager());
        assertShiftClickKeepsState(small, InventoryLayoutCalculator.calculateLayout(W, H));
        inventory = emptyInventory();
        InventoryCraftingManager large = new InventoryCraftingManager(new CraftingManager(),
            InventoryLayoutCalculator.getWorkbenchCraftingGridSize());
        assertShiftClickKeepsState(large, InventoryLayoutCalculator.calculateWorkbenchLayout(W, H));
    }

    private void assertShiftClickKeepsState(InventoryCraftingManager grid, InventoryLayout layout) {
        InventorySlotManager slots = new InventorySlotManager(inventory, grid);
        inventory.setHotbarSlot(0, new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_EMPTY));
        grid.setCraftingInputSlot(0, new ItemStack(ItemType.WOODEN_BUCKET, 2, ItemType.BUCKET_STATE_WATER));
        int ss = InventoryLayoutCalculator.getSlotSize();

        assertTrue(slots.tryShiftClickCraftingInput(layout.craftingElementsStartX + ss / 2f,
            layout.craftingGridStartY + ss / 2f, layout));

        assertEquals(1, inventory.getHotbarSlot(0).getCount(), "the empty bucket is not joined");
        assertEquals(ItemType.BUCKET_STATE_EMPTY, inventory.getHotbarSlot(0).getState());
        assertEquals(2, inventory.getHotbarSlot(1).getCount(), "the water buckets keep their own stack");
        assertEquals(ItemType.BUCKET_STATE_WATER, inventory.getHotbarSlot(1).getState());
        assertTrue(grid.getCraftingInputSlots()[0].isEmpty(), "the cell was emptied");
    }
}
