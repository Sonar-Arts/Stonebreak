package com.stonebreak.ui.inventoryScreen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.ui.support.UiTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #307 for the inventory's own 2×2 crafting grid: the grid belongs to no block and is
 * never saved, so closing the inventory must hand its items back to the player rather than
 * leave them where a world exit deletes them.
 *
 * <p>The overflow drop goes through {@code Game.getPlayer()} (null under test), so these
 * tests assert on the inventory side: exactly what fits is added — never more.
 */
class InventoryControllerTest {

    private Inventory inventory;
    private InventoryCraftingManager crafting;
    private InventoryController controller;

    @BeforeEach
    void setUp() {
        inventory = UiTestFixtures.emptyInventory();
        crafting = new InventoryCraftingManager(new CraftingManager());
        controller = new InventoryController(inventory, null, crafting, null);
    }

    private void open() {
        controller.toggleVisibility();
        assertTrue(controller.isVisible());
    }

    @Test
    void closingReturnsTheGridToTheInventory() {
        open();
        crafting.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 5));
        crafting.setCraftingInputSlot(3, new ItemStack(ItemType.STICK, 2));

        controller.toggleVisibility();

        assertFalse(controller.isVisible());
        for (ItemStack slot : crafting.getCraftingInputSlots()) {
            assertTrue(slot.isEmpty(), "nothing is left in the unsaved grid");
        }
        assertEquals(5, inventory.getItemCount(BlockType.DIRT));
        assertEquals(2, inventory.getItemCount(ItemType.STICK));
    }

    @Test
    void openingNeverTouchesTheGrid() {
        crafting.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 1));
        open();
        assertEquals(1, crafting.getCraftingInputSlot(0).getCount());
        assertEquals(0, inventory.getItemCount(BlockType.DIRT));
    }

    @Test
    void aFullInventoryTakesOnlyWhatFits() {
        // Every slot full of stone except one slot with room for 3 more dirt.
        int maxDirt = new ItemStack(BlockType.DIRT, 1).getMaxStackSize();
        for (int i = 0; i < Inventory.TOTAL_SLOTS - 1; i++) {
            inventory.addItem(new ItemStack(BlockType.STONE, new ItemStack(BlockType.STONE, 1).getMaxStackSize()));
        }
        inventory.addItem(new ItemStack(BlockType.DIRT, maxDirt - 3));

        open();
        crafting.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 10));
        controller.toggleVisibility();

        assertEquals(maxDirt, inventory.getItemCount(BlockType.DIRT),
            "the 3 that fit are added; the other 7 are dropped, not duplicated");
        assertTrue(crafting.getCraftingInputSlot(0).isEmpty());
    }

    @Test
    void theSafetyNetIsANoOpOnAnEmptyGrid() {
        controller.returnCraftingItemsToPlayer();
        assertEquals(0, inventory.getItemCount(BlockType.DIRT));
    }
}
