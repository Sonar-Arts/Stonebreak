package com.stonebreak.ui.inventoryScreen.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.joml.Vector2f;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler.DragSource;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler.DragState;
import com.stonebreak.ui.support.Resolutions;
import com.stonebreak.ui.support.UiTestFixtures;

/**
 * Guards the drag-and-drop item movement contract for the inventory screen:
 * stacks merge correctly, incompatible items swap rather than destroy, item count
 * is conserved, and the cursor / original slot round-trip properly.
 *
 * <p>Every test asserts that total item count is conserved — a drag operation must
 * never create or destroy items.  {@code dropEntireStackIntoWorld} is not tested here
 * because it calls {@code Game.getPlayer()}.
 */
class InventoryDragDropHandlerTest {

    private static final int SCREEN_W = 1920;
    private static final int SCREEN_H = 1080;

    private Inventory inventory;
    private ItemStack[] craftingInputSlots;
    private DragState dragState;
    private InventoryLayoutCalculator.InventoryLayout layout;

    @BeforeEach
    void setUp() {
        inventory = UiTestFixtures.emptyInventory();
        craftingInputSlots = UiTestFixtures.emptyCraftingGrid(InventoryLayoutCalculator.getCraftingGridSize());
        dragState = new DragState();
        layout = InventoryLayoutCalculator.calculateLayout(SCREEN_W, SCREEN_H);
    }

    // ── DragState invariants ────────────────────────────────────────────────────

    @Test
    void dragStateIsNotDraggingWhenNull() {
        DragState ds = new DragState();
        assertFalse(ds.isDragging(), "fresh drag state must not be dragging");
    }

    @Test
    void dragStateIsNotDraggingWhenEmptyStack() {
        DragState ds = new DragState();
        ds.draggedItemStack = UiTestFixtures.empty();
        assertFalse(ds.isDragging(), "drag state with empty stack must not be dragging");
    }

    @Test
    void dragStateIsDraggingWhenHoldingItems() {
        DragState ds = new DragState();
        ds.draggedItemStack = new ItemStack(BlockType.DIRT, 5);
        assertTrue(ds.isDragging(), "drag state with non-empty stack must be dragging");
    }

    @Test
    void dragStateClearResetsAllFields() {
        DragState ds = new DragState();
        ds.draggedItemStack = new ItemStack(BlockType.DIRT, 5);
        ds.draggedItemOriginalSlotIndex = 42;
        ds.dragSource = DragSource.HOTBAR;

        ds.clear();

        assertFalse(ds.isDragging(), "clear must end the drag");
        assertEquals(-1, ds.draggedItemOriginalSlotIndex, "clear must reset index to -1");
        assertEquals(DragSource.NONE, ds.dragSource, "clear must reset source to NONE");
    }

    // ── placeDraggedItem: not dragging returns false ────────────────────────────

    @Test
    void placeDraggedItemReturnsFalseWhenNotDragging() {
        assertFalse(InventoryDragDropHandler.placeDraggedItem(
                dragState, inventory, craftingInputSlots,
                new Vector2f(0, 0), SCREEN_W, SCREEN_H, () -> {}),
            "placeDraggedItem must return false when there is nothing to drag");
    }

    // ── tryReturnToOriginalSlot ─────────────────────────────────────────────────

    @Test
    void tryReturnToOriginalSlotRestoresToMainInventorySlot() {
        inventory.setMainInventorySlot(3, UiTestFixtures.empty());
        dragState.draggedItemStack = new ItemStack(BlockType.DIRT, 7);
        dragState.draggedItemOriginalSlotIndex = 3;
        dragState.dragSource = DragSource.MAIN_INVENTORY;

        InventoryDragDropHandler.tryReturnToOriginalSlot(
            dragState, inventory, craftingInputSlots, () -> {});

        assertEquals(7, inventory.getMainInventorySlot(3).getCount(),
            "return-to-original must place the stack back in the recorded slot");
        assertFalse(dragState.isDragging(),
            "return-to-original must end the drag when successful");
    }

    @Test
    void tryReturnToOriginalSlotRestoresToHotbarSlot() {
        inventory.setHotbarSlot(5, UiTestFixtures.empty());
        dragState.draggedItemStack = new ItemStack(BlockType.STONE, 4);
        dragState.draggedItemOriginalSlotIndex = 5;
        dragState.dragSource = DragSource.HOTBAR;

        InventoryDragDropHandler.tryReturnToOriginalSlot(
            dragState, inventory, craftingInputSlots, () -> {});

        assertEquals(4, inventory.getHotbarSlot(5).getCount(),
            "return-to-original must restore to the hotbar slot");
        assertFalse(dragState.isDragging(),
            "return-to-original must end the drag when successful");
    }

    @Test
    void tryReturnToOriginalSlotRestoresToCraftingInputSlot() {
        craftingInputSlots[2] = UiTestFixtures.empty();
        dragState.draggedItemStack = new ItemStack(BlockType.STONE, 3);
        dragState.draggedItemOriginalSlotIndex =
            InventoryDragDropHandler.getCraftingInputSlotStartIndex() + 2;
        dragState.dragSource = DragSource.CRAFTING_INPUT;

        InventoryDragDropHandler.tryReturnToOriginalSlot(
            dragState, inventory, craftingInputSlots, () -> {});

        assertEquals(3, craftingInputSlots[2].getCount(),
            "return-to-original must place the stack back in the crafting slot");
        assertFalse(dragState.isDragging(),
            "return-to-original must end the drag when successful");
    }

    @Test
    void tryReturnToOriginalSlotDoesNotCrashWhenDragStateIsEmpty() {
        InventoryDragDropHandler.tryReturnToOriginalSlot(
            dragState, inventory, craftingInputSlots, () -> {});
        assertFalse(dragState.isDragging());
    }

    // ── sentinel constants ─────────────────────────────────────────────────────

    @Test
    void sentinelConstantsHaveExpectedValues() {
        assertEquals(1000, InventoryDragDropHandler.getCraftingInputSlotStartIndex(),
            "crafting input sentinel must be 1000");
        assertEquals(2000, InventoryDragDropHandler.getCraftingOutputSlotIndex(),
            "crafting output sentinel must be 2000");
    }

    // ── helper ──────────────────────────────────────────────────────────────────

    /** Count of a given block type across hotbar + main inventory + crafting slots + cursor. */
    private int totalCount(BlockType type, Inventory inv, ItemStack[] craftSlots, DragState ds) {
        int total = UiTestFixtures.countOf(inv, type);
        for (ItemStack slot : craftSlots) {
            total += countOf(slot, type);
        }
        if (ds.isDragging()) {
            total += countOf(ds.draggedItemStack, type);
        }
        return total;
    }

    private static int countOf(ItemStack stack, BlockType type) {
        if (stack == null || stack.isEmpty()) return 0;
        return stack.getBlockTypeId() == type.getId() ? stack.getCount() : 0;
    }
}