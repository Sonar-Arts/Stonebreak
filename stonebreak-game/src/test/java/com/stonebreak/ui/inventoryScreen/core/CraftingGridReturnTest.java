package com.stonebreak.ui.inventoryScreen.core;

import static com.stonebreak.ui.support.UiTestFixtures.countOf;
import static com.stonebreak.ui.support.UiTestFixtures.emptyInventory;
import static com.stonebreak.ui.support.UiTestFixtures.fillHotbar;
import static com.stonebreak.ui.support.UiTestFixtures.fillMainInventory;
import static com.stonebreak.ui.support.UiTestFixtures.fullStack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #307: a crafting grid is UI state, so closing the screen must hand every
 * stack back to the player — into the inventory, with what does not fit returned
 * for dropping — and leave the grid empty, never silently destroying items.
 */
class CraftingGridReturnTest {

    private static InventoryCraftingManager workbenchGrid() {
        return new InventoryCraftingManager(new CraftingManager(),
                InventoryLayoutCalculator.getWorkbenchCraftingGridSize());
    }

    private static void assertGridEmpty(InventoryCraftingManager grid) {
        for (ItemStack slot : grid.getCraftingInputSlots()) {
            assertTrue(slot.isEmpty(), "every grid cell must be emptied");
        }
        assertTrue(grid.getCraftingOutputSlot().isEmpty(), "no stale output after the grid empties");
    }

    @Test
    void everyGridStackMovesIntoTheInventory() {
        Inventory inventory = emptyInventory();
        InventoryCraftingManager grid = workbenchGrid();
        grid.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 3));
        grid.setCraftingInputSlot(4, new ItemStack(BlockType.STONE, 5));
        grid.setCraftingInputSlot(8, new ItemStack(BlockType.DIRT, 2));

        List<ItemStack> overflow = grid.returnInputsTo(inventory);

        assertTrue(overflow.isEmpty(), "an empty inventory takes everything");
        assertEquals(5, countOf(inventory, BlockType.DIRT));
        assertEquals(5, countOf(inventory, BlockType.STONE));
        assertGridEmpty(grid);
    }

    @Test
    void onlyThePartThatDoesNotFitIsReturnedForDropping() {
        Inventory inventory = emptyInventory();
        fillHotbar(inventory, BlockType.STONE);
        fillMainInventory(inventory, BlockType.STONE);
        ItemStack almostFull = fullStack(BlockType.STONE);
        almostFull.setCount(almostFull.getCount() - 2);
        inventory.setHotbarSlot(0, almostFull);
        int before = countOf(inventory, BlockType.STONE);

        InventoryCraftingManager grid = workbenchGrid();
        grid.setCraftingInputSlot(3, new ItemStack(BlockType.STONE, 5));

        List<ItemStack> overflow = grid.returnInputsTo(inventory);

        assertEquals(before + 2, countOf(inventory, BlockType.STONE), "the two free spaces are filled");
        assertEquals(1, overflow.size());
        assertEquals(BlockType.STONE.getId(), overflow.get(0).getBlockTypeId());
        assertEquals(3, overflow.get(0).getCount(), "exactly the remainder is dropped — no duplication");
        assertGridEmpty(grid);
    }

    @Test
    void aFullInventoryGetsEveryStackBackAsOverflow() {
        Inventory inventory = emptyInventory();
        fillHotbar(inventory, BlockType.STONE);
        fillMainInventory(inventory, BlockType.STONE);
        InventoryCraftingManager grid = workbenchGrid();
        grid.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 4));
        grid.setCraftingInputSlot(1, new ItemStack(BlockType.DIRT, 1));

        List<ItemStack> overflow = grid.returnInputsTo(inventory);

        assertEquals(0, countOf(inventory, BlockType.DIRT));
        assertEquals(5, overflow.stream().mapToInt(ItemStack::getCount).sum(), "nothing destroyed");
        assertGridEmpty(grid);
    }

    @Test
    void anEmptyGridReturnsNothing() {
        InventoryCraftingManager grid = new InventoryCraftingManager(new CraftingManager());
        assertTrue(grid.returnInputsTo(emptyInventory()).isEmpty());
        assertGridEmpty(grid);
    }
}
