package com.stonebreak.ui.inventoryScreen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator.InventoryLayout;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler.DragSource;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler.DragState;
import com.stonebreak.ui.support.UiTestFixtures;

/**
 * Guards the inventory slot interaction semantics: pick-up, shift-click transfer, single-item
 * deposit, right-drag painting, and double-click gather.
 *
 * <p>These are the rules players notice immediately when they break — shift-clicking a stack that
 * silently vanishes, a right-drag that deposits two items into one slot, a gather that overfills
 * past the max stack size — and none of them are visible to any rendering assertion. The item
 * counts are the whole contract, so every test below asserts on totals as well as slot contents:
 * an operation that moves items must never create or destroy them.
 *
 * <p>Geometry note: {@code InventorySlotManager} recomputes each slot's rectangle inline from
 * {@link InventoryLayoutCalculator} rather than exposing it, so the helpers here mirror that math.
 * They are deliberately a transcription of the production formulas — if the layout changes, these
 * helpers must change with it, and the round-trip assertions in
 * {@code HotbarLayoutCalculatorTest} cover the equivalent contract where it <em>is</em> exposed.
 *
 * <p>Scope: {@code tryShiftClickCraftingOutput}'s inventory-full branch calls {@code Game.getPlayer()}
 * to drop the item into the world and is therefore not exercised here — {@code Game.getInstance()}
 * returns a null-ish instance under test rather than throwing, so a test covering it would pass
 * while asserting nothing.
 */
class InventorySlotManagerTest {

    private static final int SCREEN_W = 1920;
    private static final int SCREEN_H = 1080;

    private Inventory inventory;
    private InventoryCraftingManager crafting;
    private InventorySlotManager slots;
    private InventoryLayout layout;
    private DragState drag;

    @BeforeEach
    void setUp() {
        inventory = UiTestFixtures.emptyInventory();
        crafting = new InventoryCraftingManager(new CraftingManager());
        slots = new InventorySlotManager(inventory, crafting);
        layout = InventoryLayoutCalculator.calculateLayout(SCREEN_W, SCREEN_H);
        drag = new DragState();
    }

    // ---- geometry helpers: transcriptions of the production slot math -------------------------

    private static int stride() {
        return InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding();
    }

    /** Center of main inventory slot {@code index}, matching InventorySlotManager's own math. */
    private float[] mainSlotCenter(int index) {
        int width = Inventory.MAIN_INVENTORY_COLS * stride() - InventoryLayoutCalculator.getSlotPadding();
        int startX = layout.panelStartX + (layout.inventoryPanelWidth - width) / 2;
        int row = index / Inventory.MAIN_INVENTORY_COLS;
        int col = index % Inventory.MAIN_INVENTORY_COLS;
        int x = startX + col * stride();
        int y = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() + row * stride();
        return center(x, y);
    }

    /** Center of hotbar slot {@code index}. */
    private float[] hotbarSlotCenter(int index) {
        int width = Inventory.HOTBAR_SIZE * stride() - InventoryLayoutCalculator.getSlotPadding();
        int startX = layout.panelStartX + (layout.inventoryPanelWidth - width) / 2;
        int x = startX + index * stride();
        return center(x, layout.hotbarRowY);
    }

    /** Center of crafting input slot {@code index}. */
    private float[] craftingSlotCenter(int index) {
        int gridSize = crafting.getCraftingGridSize();
        int r = index / gridSize;
        int c = index % gridSize;
        int x = layout.craftingElementsStartX + c * stride();
        int y = layout.craftingGridStartY + r * stride();
        return center(x, y);
    }

    /** Center of the crafting output slot. */
    private float[] craftingOutputCenter() {
        return center(layout.outputSlotX, layout.outputSlotY);
    }

    private static float[] center(int x, int y) {
        float half = InventoryLayoutCalculator.getSlotSize() / 2f;
        return new float[] { x + half, y + half };
    }

    private int totalDirt() {
        return UiTestFixtures.countOf(inventory, BlockType.DIRT);
    }

    /** Total DIRT across the crafting input cells (the balance logic works on the grid, not the inventory). */
    private int craftingDirtTotal() {
        int total = 0;
        for (int i = 0; i < 4; i++) {
            ItemStack slot = crafting.getCraftingInputSlot(i);
            if (slot.getBlockTypeId() == BlockType.DIRT.getId()) {
                total += slot.getCount();
            }
        }
        return total;
    }

    // ---- pick-up ------------------------------------------------------------------------------

    @Test
    void pickingUpFromMainInventoryMovesTheStackIntoTheDragState() {
        inventory.setMainInventorySlot(4, new ItemStack(BlockType.DIRT, 12));
        float[] p = mainSlotCenter(4);

        assertTrue(slots.tryPickUpFromMainInventory(p[0], p[1], layout, drag),
            "clicking a populated slot must pick it up");

        assertTrue(drag.isDragging(), "drag state must be active after a pick-up");
        assertEquals(12, drag.draggedItemStack.getCount(), "the whole stack moves to the cursor");
        assertEquals(4, drag.draggedItemOriginalSlotIndex);
        assertEquals(DragSource.MAIN_INVENTORY, drag.dragSource);
        assertTrue(inventory.getMainInventorySlot(4).isEmpty(), "the source slot must be cleared");
    }

    @Test
    void pickingUpFromAnEmptySlotDoesNothing() {
        float[] p = mainSlotCenter(4);

        assertFalse(slots.tryPickUpFromMainInventory(p[0], p[1], layout, drag),
            "an empty slot must not start a drag");
        assertFalse(drag.isDragging());
        assertEquals(DragSource.NONE, drag.dragSource);
    }

    @Test
    void pickingUpFromHotbarRecordsTheHotbarAsTheSource() {
        inventory.setHotbarSlot(2, new ItemStack(BlockType.STONE, 5));
        float[] p = hotbarSlotCenter(2);

        assertTrue(slots.tryPickUpFromHotbar(p[0], p[1], layout, drag));
        assertEquals(DragSource.HOTBAR, drag.dragSource,
            "source must be recorded so a cancelled drag returns to the right place");
        assertEquals(2, drag.draggedItemOriginalSlotIndex);
        assertTrue(inventory.getHotbarSlot(2).isEmpty());
    }

    @Test
    void pickingUpFromCraftingInputOffsetsTheOriginalIndexBySentinel() {
        crafting.setCraftingInputSlot(1, new ItemStack(BlockType.DIRT, 3));
        float[] p = craftingSlotCenter(1);

        assertTrue(slots.tryPickUpFromCraftingInput(p[0], p[1], layout, drag));

        // Crafting slots share one index space with the inventory, disambiguated by a sentinel base.
        assertEquals(InventoryDragDropHandler.getCraftingInputSlotStartIndex() + 1,
            drag.draggedItemOriginalSlotIndex,
            "crafting input indices must be offset by the sentinel so return-to-origin works");
        assertEquals(DragSource.CRAFTING_INPUT, drag.dragSource);
        assertTrue(crafting.getCraftingInputSlot(1).isEmpty());
    }

    @Test
    void clickingOutsideEverySlotPicksUpNothing() {
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 8));

        // Far outside the panel entirely.
        assertFalse(slots.tryPickUpFromMainInventory(-500f, -500f, layout, drag));
        assertFalse(slots.tryPickUpFromHotbar(-500f, -500f, layout, drag));
        assertFalse(drag.isDragging());
        assertEquals(8, inventory.getMainInventorySlot(0).getCount(), "the stack must be untouched");
    }

    // ---- shift-click transfer ------------------------------------------------------------------

    @Test
    void shiftClickMovesAStackFromMainInventoryToAnEmptyHotbar() {
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 20));
        int before = totalDirt();
        float[] p = mainSlotCenter(0);

        assertTrue(slots.tryShiftClickMainInventoryToHotbar(p[0], p[1], layout));

        assertTrue(inventory.getMainInventorySlot(0).isEmpty(), "the source slot must be emptied");
        assertEquals(20, inventory.getHotbarSlot(0).getCount(), "the stack lands in the first free hotbar slot");
        assertEquals(before, totalDirt(), "transfer must conserve item count");
    }

    @Test
    void shiftClickConsolidatesIntoAnExistingPartialStackBeforeUsingEmptySlots() {
        inventory.setHotbarSlot(3, new ItemStack(BlockType.DIRT, 10));
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 5));
        int before = totalDirt();
        float[] p = mainSlotCenter(0);

        assertTrue(slots.tryShiftClickMainInventoryToHotbar(p[0], p[1], layout));

        assertEquals(15, inventory.getHotbarSlot(3).getCount(),
            "items must merge into the existing partial stack, not open a new one");
        assertTrue(inventory.getHotbarSlot(0).isEmpty(), "no empty slot should have been consumed");
        assertEquals(before, totalDirt(), "consolidation must conserve item count");
    }

    @Test
    void shiftClickSplitsAcrossStacksWhenTheFirstTargetOverflows() {
        int max = UiTestFixtures.fullStack(BlockType.DIRT).getMaxStackSize();
        // One hotbar stack one item short of full, everything else empty.
        inventory.setHotbarSlot(0, new ItemStack(BlockType.DIRT, max - 1));
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 5));
        int before = totalDirt();
        float[] p = mainSlotCenter(0);

        assertTrue(slots.tryShiftClickMainInventoryToHotbar(p[0], p[1], layout));

        assertEquals(max, inventory.getHotbarSlot(0).getCount(), "the partial stack must top out at max");
        assertEquals(4, inventory.getHotbarSlot(1).getCount(), "the remainder must spill into the next free slot");
        assertTrue(inventory.getMainInventorySlot(0).isEmpty());
        assertEquals(before, totalDirt(), "splitting must conserve item count");
    }

    @Test
    void shiftClickLeavesTheSourceAloneWhenTheHotbarIsCompletelyFull() {
        UiTestFixtures.fillHotbar(inventory, BlockType.STONE);
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 7));
        float[] p = mainSlotCenter(0);

        assertFalse(slots.tryShiftClickMainInventoryToHotbar(p[0], p[1], layout),
            "a transfer with nowhere to go must report failure");
        assertEquals(7, inventory.getMainInventorySlot(0).getCount(),
            "a failed transfer must not consume the source stack");
    }

    @Test
    void shiftClickMovesAStackFromHotbarToMainInventory() {
        inventory.setHotbarSlot(1, new ItemStack(BlockType.DIRT, 9));
        int before = totalDirt();
        float[] p = hotbarSlotCenter(1);

        assertTrue(slots.tryShiftClickHotbarToMainInventory(p[0], p[1], layout));

        assertTrue(inventory.getHotbarSlot(1).isEmpty());
        assertEquals(9, inventory.getMainInventorySlot(0).getCount());
        assertEquals(before, totalDirt(), "reverse transfer must conserve item count");
    }

    @Test
    void shiftClickOnAnEmptySlotIsANoOp() {
        float[] p = mainSlotCenter(0);
        assertFalse(slots.tryShiftClickMainInventoryToHotbar(p[0], p[1], layout),
            "shift-clicking nothing must not report a transfer");
    }

    @Test
    void shiftClickFromCraftingInputReturnsIngredientsToTheInventory() {
        crafting.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 6));
        float[] p = craftingSlotCenter(0);

        assertTrue(slots.tryShiftClickCraftingInput(p[0], p[1], layout));

        assertTrue(crafting.getCraftingInputSlot(0).isEmpty(),
            "the crafting cell must be cleared once its contents are absorbed");
        assertEquals(6, totalDirt(), "the ingredients must reappear in the inventory");
    }

    @Test
    void shiftClickingTheCraftingOutputCraftsEveryPossibleBatch() {
        CraftingManager cm = new CraftingManager();
        cm.registerRecipe(new Recipe("dirtToSticks",
            List.of(List.of(new ItemStack(BlockType.DIRT, 1))),
            new ItemStack(ItemType.STICK, 4)));
        InventoryCraftingManager crafting = new InventoryCraftingManager(cm);
        InventorySlotManager slots = new InventorySlotManager(inventory, crafting);
        crafting.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 3));
        crafting.updateCraftingOutput();

        float[] p = craftingOutputCenter();
        assertTrue(slots.tryShiftClickCraftingOutput(p[0], p[1], layout));

        assertTrue(crafting.getCraftingInputSlot(0).isEmpty(),
            "shift-clicking the result must consume every ingredient");
        assertTrue(crafting.getCraftingOutputSlot().isEmpty(), "the output must be spent");
        assertEquals(12, inventory.getItemCount(ItemType.STICK),
            "all three batches land in the inventory (3 ingredients x 4 sticks)");
    }

    @Test
    void shiftClickingAnEmptyCraftingOutputDoesNothing() {
        float[] p = craftingOutputCenter();
        assertFalse(slots.tryShiftClickCraftingOutput(p[0], p[1], layout),
            "an empty result slot must not report a craft");
    }

    // ---- middle-click balancing ---------------------------------------------------------------

    @Test
    void middleClickBalancesUnevenStacksAcrossMatchingCraftingCells() {
        crafting.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 3));
        crafting.setCraftingInputSlot(1, new ItemStack(BlockType.DIRT, 1));
        float[] p = craftingSlotCenter(0);

        assertTrue(slots.tryBalanceCraftingSlot(p[0], p[1], layout),
            "a click on a crafting cell is a balance target");
        assertEquals(2, crafting.getCraftingInputSlot(0).getCount());
        assertEquals(2, crafting.getCraftingInputSlot(1).getCount());
        assertEquals(4, craftingDirtTotal(), "balancing must conserve item count");
    }

    @Test
    void middleClickBalanceSpreadsTheRemainderAcrossCells() {
        // 5 DIRT across two cells: floor(5/2) = 2 each, leaving 1 on the first cell.
        crafting.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 4));
        crafting.setCraftingInputSlot(1, new ItemStack(BlockType.DIRT, 1));
        float[] p = craftingSlotCenter(0);

        slots.tryBalanceCraftingSlot(p[0], p[1], layout);

        assertEquals(3, crafting.getCraftingInputSlot(0).getCount(),
            "the first cell receives the remainder");
        assertEquals(2, crafting.getCraftingInputSlot(1).getCount());
        assertEquals(5, craftingDirtTotal(), "balancing must conserve item count");
    }

    @Test
    void middleClickBalanceLeavesCellsOfADifferentItemUntouched() {
        crafting.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 3));
        crafting.setCraftingInputSlot(1, new ItemStack(BlockType.DIRT, 1));
        crafting.setCraftingInputSlot(2, new ItemStack(BlockType.STONE, 9));
        float[] p = craftingSlotCenter(0);

        slots.tryBalanceCraftingSlot(p[0], p[1], layout);

        assertEquals(2, crafting.getCraftingInputSlot(0).getCount());
        assertEquals(2, crafting.getCraftingInputSlot(1).getCount());
        assertEquals(9, crafting.getCraftingInputSlot(2).getCount(),
            "balancing one item must never touch another item's cells");
    }

    @Test
    void middleClickBalanceOnASingleMatchingCellChangesNothing() {
        crafting.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 5));
        crafting.setCraftingInputSlot(1, new ItemStack(BlockType.STONE, 2));
        float[] p = craftingSlotCenter(0);

        assertTrue(slots.tryBalanceCraftingSlot(p[0], p[1], layout));
        assertEquals(5, crafting.getCraftingInputSlot(0).getCount(),
            "with nothing to share with, a single stack stays put");
    }

    @Test
    void middleClickOnAnEmptyCraftingCellIsHandledButChangesNothing() {
        float[] p = craftingSlotCenter(2);
        assertTrue(slots.tryBalanceCraftingSlot(p[0], p[1], layout),
            "an empty crafting cell is still a crafting-cell hit (no sort)");
        for (int i = 0; i < 4; i++) {
            assertTrue(crafting.getCraftingInputSlot(i).isEmpty());
        }
    }

    @Test
    void middleClickOutsideTheCraftingGridIsNotABalanceTarget() {
        float[] p = mainSlotCenter(0);
        assertFalse(slots.tryBalanceCraftingSlot(p[0], p[1], layout),
            "a click outside the grid must fall through to sorting");
    }

    // ---- single-item deposit -------------------------------------------------------------------

    @Test
    void rightClickDepositsExactlyOneItemIntoAnEmptySlot() {
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, 10);
        drag.dragSource = DragSource.MAIN_INVENTORY;
        float[] p = mainSlotCenter(2);

        assertTrue(slots.tryDropOneToMainInventory(p[0], p[1], layout, drag));

        assertEquals(1, inventory.getMainInventorySlot(2).getCount(), "exactly one item is deposited");
        assertEquals(9, drag.draggedItemStack.getCount(), "the cursor stack loses exactly one");
    }

    @Test
    void repeatedDepositsGrowTheTargetStackOneAtATime() {
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, 10);
        drag.dragSource = DragSource.MAIN_INVENTORY;
        float[] p = mainSlotCenter(2);

        slots.tryDropOneToMainInventory(p[0], p[1], layout, drag);
        slots.tryDropOneToMainInventory(p[0], p[1], layout, drag);
        slots.tryDropOneToMainInventory(p[0], p[1], layout, drag);

        assertEquals(3, inventory.getMainInventorySlot(2).getCount());
        assertEquals(7, drag.draggedItemStack.getCount());
    }

    @Test
    void depositIsRefusedByASlotHoldingADifferentItem() {
        inventory.setMainInventorySlot(2, new ItemStack(BlockType.STONE, 1));
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, 10);
        drag.dragSource = DragSource.MAIN_INVENTORY;
        float[] p = mainSlotCenter(2);

        assertFalse(slots.tryDropOneToMainInventory(p[0], p[1], layout, drag),
            "an incompatible slot must refuse the deposit");
        assertEquals(1, inventory.getMainInventorySlot(2).getCount(), "the occupant is untouched");
        assertEquals(10, drag.draggedItemStack.getCount(), "the cursor stack is untouched");
    }

    @Test
    void depositIsRefusedByAFullStack() {
        ItemStack full = UiTestFixtures.fullStack(BlockType.DIRT);
        inventory.setMainInventorySlot(2, full);
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, 10);
        drag.dragSource = DragSource.MAIN_INVENTORY;
        float[] p = mainSlotCenter(2);

        assertFalse(slots.tryDropOneToMainInventory(p[0], p[1], layout, drag),
            "a stack already at max must refuse another item");
        assertEquals(full.getMaxStackSize(), inventory.getMainInventorySlot(2).getCount());
        assertEquals(10, drag.draggedItemStack.getCount());
    }

    @Test
    void depositingTheLastItemClearsTheDragState() {
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, 1);
        drag.draggedItemOriginalSlotIndex = 5;
        drag.dragSource = DragSource.MAIN_INVENTORY;
        float[] p = mainSlotCenter(2);

        assertTrue(slots.tryDropOneToMainInventory(p[0], p[1], layout, drag));

        assertFalse(drag.isDragging(), "an exhausted cursor stack must end the drag");
        assertEquals(DragSource.NONE, drag.dragSource);
        assertEquals(-1, drag.draggedItemOriginalSlotIndex);
        assertEquals(1, inventory.getMainInventorySlot(2).getCount());
    }

    // ---- right-drag painting -------------------------------------------------------------------

    @Test
    void rightDragDepositsAtMostOncePerSlot() {
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, 10);
        drag.dragSource = DragSource.MAIN_INVENTORY;
        Set<Integer> visited = new HashSet<>();
        float[] p = mainSlotCenter(2);

        assertTrue(slots.tryRightDragDepositToSlot(p[0], p[1], layout, drag, visited),
            "the first pass over a slot deposits");
        assertFalse(slots.tryRightDragDepositToSlot(p[0], p[1], layout, drag, visited),
            "dragging back across the same slot must not deposit again");

        assertEquals(1, inventory.getMainInventorySlot(2).getCount(),
            "a slot the cursor crosses twice must still hold exactly one item");
        assertEquals(9, drag.draggedItemStack.getCount());
    }

    @Test
    void rightDragAcrossSeveralSlotsDepositsOneEach() {
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, 10);
        drag.dragSource = DragSource.MAIN_INVENTORY;
        Set<Integer> visited = new HashSet<>();

        for (int slot : new int[] { 0, 1, 2 }) {
            float[] p = mainSlotCenter(slot);
            assertTrue(slots.tryRightDragDepositToSlot(p[0], p[1], layout, drag, visited),
                "slot " + slot + " should accept one item");
        }

        assertEquals(1, inventory.getMainInventorySlot(0).getCount());
        assertEquals(1, inventory.getMainInventorySlot(1).getCount());
        assertEquals(1, inventory.getMainInventorySlot(2).getCount());
        assertEquals(7, drag.draggedItemStack.getCount());
        assertEquals(3, visited.size());
    }

    @Test
    void rightDragUsesDistinctSlotIdsAcrossMainInventoryAndHotbar() {
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, 10);
        drag.dragSource = DragSource.MAIN_INVENTORY;
        Set<Integer> visited = new HashSet<>();

        // Main slot 0 and hotbar slot 0 must not collide in the visited set, or painting across
        // the boundary would silently skip the second one.
        float[] main = mainSlotCenter(0);
        float[] hot = hotbarSlotCenter(0);
        assertTrue(slots.tryRightDragDepositToSlot(main[0], main[1], layout, drag, visited));
        assertTrue(slots.tryRightDragDepositToSlot(hot[0], hot[1], layout, drag, visited),
            "hotbar slot 0 must have a different visited-id than main slot 0");

        assertEquals(1, inventory.getMainInventorySlot(0).getCount());
        assertEquals(1, inventory.getHotbarSlot(0).getCount());
        assertEquals(2, visited.size());
    }

    @Test
    void rightDragDoesNothingWithoutAnActiveDrag() {
        Set<Integer> visited = new HashSet<>();
        float[] p = mainSlotCenter(0);

        assertFalse(slots.tryRightDragDepositToSlot(p[0], p[1], layout, drag, visited),
            "painting with an empty cursor must be a no-op");
        assertTrue(visited.isEmpty());
    }

    // ---- gather (double-click) -----------------------------------------------------------------

    @Test
    void gatherPullsMatchingStacksUpToTheMaxStackSize() {
        int max = UiTestFixtures.fullStack(BlockType.DIRT).getMaxStackSize();
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 10));
        inventory.setMainInventorySlot(1, new ItemStack(BlockType.DIRT, 10));
        inventory.setHotbarSlot(0, new ItemStack(BlockType.DIRT, 10));
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, 1);
        drag.dragSource = DragSource.MAIN_INVENTORY;

        slots.gatherMatchingItemsToStack(drag);

        int expected = Math.min(max, 31);
        assertEquals(expected, drag.draggedItemStack.getCount(),
            "gather must accumulate matching items but never exceed the max stack size");
        assertTrue(drag.draggedItemStack.getCount() <= max);
    }

    @Test
    void gatherIgnoresItemsOfADifferentType() {
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.STONE, 10));
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, 1);
        drag.dragSource = DragSource.MAIN_INVENTORY;

        slots.gatherMatchingItemsToStack(drag);

        assertEquals(1, drag.draggedItemStack.getCount(), "non-matching items must not be absorbed");
        assertEquals(10, inventory.getMainInventorySlot(0).getCount(), "and must be left in place");
    }

    @Test
    void gatherOnAnAlreadyFullStackChangesNothing() {
        ItemStack full = UiTestFixtures.fullStack(BlockType.DIRT);
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 10));
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, full.getMaxStackSize());
        drag.dragSource = DragSource.MAIN_INVENTORY;

        slots.gatherMatchingItemsToStack(drag);

        assertEquals(full.getMaxStackSize(), drag.draggedItemStack.getCount());
        assertEquals(10, inventory.getMainInventorySlot(0).getCount(),
            "a full cursor stack must not drain the inventory");
    }

    @Test
    void gatherWithNothingHeldIsSafe() {
        assertNotNull(drag);
        slots.gatherMatchingItemsToStack(drag); // must not throw
        assertFalse(drag.isDragging());
    }
}
