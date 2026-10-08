package com.stonebreak.ui.inventoryScreen.core;

import static com.stonebreak.ui.support.UiTestFixtures.countOf;
import static com.stonebreak.ui.support.UiTestFixtures.emptyInventory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator.InventoryLayout;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler.DragState;
import org.joml.Vector2f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * Issue #317: the crafting table's 3x3 grid gets every inventory-screen interaction because it
 * shares {@link InventoryInputManager#handleMouseInput} instead of keeping its own copy. Drives
 * the real dispatch frame by frame through a mocked {@link InputHandler}, against the real
 * workbench layout. Slot centres transcribe {@link InventorySlotManager}'s geometry (see
 * {@code InventorySlotManagerTest}).
 */
class WorkbenchInputDispatchTest {

    private static final int SCREEN_W = 1920;
    private static final int SCREEN_H = 1080;

    private Inventory inventory;
    private InventoryCraftingManager grid;
    private InputHandler input;
    private WorkbenchInputManager workbench;
    private InventoryLayout layout;

    @BeforeEach
    void setUp() {
        inventory = emptyInventory();
        grid = new InventoryCraftingManager(new CraftingManager(),
                InventoryLayoutCalculator.getWorkbenchCraftingGridSize());
        input = mock(InputHandler.class);
        workbench = new WorkbenchInputManager(input, inventory,
                new InventorySlotManager(inventory, grid), grid);
        layout = InventoryLayoutCalculator.calculateWorkbenchLayout(SCREEN_W, SCREEN_H);
    }

    // ---- frame driver -------------------------------------------------------------------------

    /** One frame of input: cursor position plus this frame's button edges/levels. */
    private void frame(float[] at, boolean leftPressed, boolean rightPressed, boolean rightDown) {
        when(input.getMousePosition()).thenReturn(new Vector2f(at[0], at[1]));
        when(input.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)).thenReturn(leftPressed);
        when(input.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)).thenReturn(rightPressed);
        when(input.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT)).thenReturn(rightDown);
        workbench.handleMouseInput(SCREEN_W, SCREEN_H);
    }

    private void leftClick(float[] at) {
        frame(at, true, false, false);
    }

    private void hold(ItemStack stack) {
        DragState drag = workbench.getDragState();
        drag.draggedItemStack = stack;
        drag.dragSource = com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler.DragSource.NONE;
        drag.draggedItemOriginalSlotIndex = -1;
    }

    // ---- geometry: transcriptions of the production slot math ---------------------------------

    private static int stride() {
        return InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding();
    }

    private float[] craftingCellCenter(int index) {
        int gridSize = grid.getCraftingGridSize();
        return center(layout.craftingElementsStartX + (index % gridSize) * stride(),
                      layout.craftingGridStartY + (index / gridSize) * stride());
    }

    private float[] mainSlotCenter(int index) {
        int x = InventoryLayoutCalculator.gridStartX(layout) + (index % Inventory.MAIN_INVENTORY_COLS) * stride();
        int y = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding()
                + (index / Inventory.MAIN_INVENTORY_COLS) * stride();
        return center(x, y);
    }

    private static float[] center(int x, int y) {
        float half = InventoryLayoutCalculator.getSlotSize() / 2f;
        return new float[] {x + half, y + half};
    }

    // ---- tests --------------------------------------------------------------------------------

    @Test
    void rightDragDistributesOneItemPerThreeByThreeCell() {
        hold(new ItemStack(BlockType.DIRT, 10));

        frame(craftingCellCenter(0), false, true, true);   // press over cell 0
        frame(craftingCellCenter(4), false, false, true);  // sweep, button held
        frame(craftingCellCenter(8), false, false, true);  // a cell the 2x2 grid doesn't have
        frame(craftingCellCenter(8), false, false, true);  // lingering must not add a second item

        assertEquals(1, grid.getCraftingInputSlots()[0].getCount());
        assertEquals(1, grid.getCraftingInputSlots()[4].getCount());
        assertEquals(1, grid.getCraftingInputSlots()[8].getCount());
        assertEquals(7, workbench.getDragState().draggedItemStack.getCount(), "nothing created or lost");

        frame(craftingCellCenter(8), false, false, false); // release ends the sweep
        frame(craftingCellCenter(0), false, true, true);   // a new sweep may revisit cell 0
        assertEquals(2, grid.getCraftingInputSlots()[0].getCount());
        assertEquals(6, workbench.getDragState().draggedItemStack.getCount());
    }

    @Test
    void doubleClickGathersMatchingItemsOntoTheCursor() {
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 10));
        inventory.setMainInventorySlot(5, new ItemStack(BlockType.DIRT, 20));
        inventory.setHotbarSlot(2, new ItemStack(BlockType.DIRT, 5));
        inventory.setMainInventorySlot(9, new ItemStack(BlockType.STONE, 4));

        leftClick(mainSlotCenter(0));                      // pick up the 10
        assertEquals(10, workbench.getDragState().draggedItemStack.getCount());
        leftClick(mainSlotCenter(0));                      // second click within the window

        DragState drag = workbench.getDragState();
        assertTrue(drag.isDragging(), "the gathered stack stays on the cursor");
        assertEquals(35, drag.draggedItemStack.getCount());
        assertEquals(0, countOf(inventory, BlockType.DIRT), "every matching stack was gathered");
        assertEquals(4, countOf(inventory, BlockType.STONE), "other items are left alone");
    }

    @Test
    void heldStackIsPlacedIntoAThreeByThreeCell() {
        hold(new ItemStack(BlockType.STONE, 12));

        leftClick(craftingCellCenter(8));

        assertFalse(workbench.getDragState().isDragging());
        assertEquals(12, grid.getCraftingInputSlots()[8].getCount());
    }

    @Test
    void workbenchHasNoCharacterTabHotspots() {
        assertFalse(workbench.hasCharacterTabs(), "the workbench draws no tab strip to click");
    }
}
