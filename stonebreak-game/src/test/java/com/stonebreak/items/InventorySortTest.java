package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enum declaration order for ItemCategory is BLOCKS, TOOLS, MATERIALS,
 * FOOD, DECORATIVE — that's the primary sort key order these tests expect.
 */
class InventorySortTest {

    @Test
    void sortsMainInventoryByCategoryThenName() {
        Inventory inv = new Inventory();
        inv.setMainInventorySlot(0, new ItemStack(ItemType.WOODEN_PICKAXE, 1)); // TOOLS
        inv.setMainInventorySlot(1, new ItemStack(BlockType.STONE, 10));        // BLOCKS "Stone"
        inv.setMainInventorySlot(2, new ItemStack(BlockType.DIRT, 5));          // BLOCKS "Dirt"
        inv.setMainInventorySlot(3, new ItemStack(ItemType.STICK, 3));          // MATERIALS

        inv.sortMainInventory();

        // BLOCKS first (Dirt before Stone alphabetically), then TOOLS, then MATERIALS.
        assertEquals(BlockType.DIRT.getId(), inv.getMainInventorySlot(0).getBlockTypeId());
        assertEquals(BlockType.STONE.getId(), inv.getMainInventorySlot(1).getBlockTypeId());
        assertEquals(ItemType.WOODEN_PICKAXE.getId(), inv.getMainInventorySlot(2).getBlockTypeId());
        assertEquals(ItemType.STICK.getId(), inv.getMainInventorySlot(3).getBlockTypeId());
    }

    @Test
    void emptySlotsAreCompactedToTheEnd() {
        Inventory inv = new Inventory();
        inv.setMainInventorySlot(5, new ItemStack(BlockType.STONE, 1));
        inv.setMainInventorySlot(20, new ItemStack(BlockType.DIRT, 1));

        inv.sortMainInventory();

        assertFalse(inv.getMainInventorySlot(0).isEmpty());
        assertFalse(inv.getMainInventorySlot(1).isEmpty());
        for (int i = 2; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            assertTrue(inv.getMainInventorySlot(i).isEmpty(), "slot " + i + " should be empty");
        }
    }

    @Test
    void hotbarAndMainInventoryAreSortedIndependently() {
        Inventory inv = new Inventory();
        inv.setHotbarSlot(0, new ItemStack(ItemType.WOODEN_PICKAXE, 1));
        inv.setHotbarSlot(1, new ItemStack(BlockType.STONE, 1));
        inv.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 1));

        inv.sortInventory();

        boolean stoneStillInHotbar = false;
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            if (inv.getHotbarSlot(i).getBlockTypeId() == BlockType.STONE.getId()) {
                stoneStillInHotbar = true;
            }
            assertNotEquals(BlockType.DIRT.getId(), inv.getHotbarSlot(i).getBlockTypeId());
        }
        assertTrue(stoneStillInHotbar);
        assertFalse(inv.getMainInventorySlot(0).isEmpty());
    }

    @Test
    void partialStacksAreMergedIntoOne() {
        Inventory inv = new Inventory();
        inv.setMainInventorySlot(0, new ItemStack(BlockType.STONE, 5));
        inv.setMainInventorySlot(10, new ItemStack(BlockType.STONE, 3));

        inv.sortMainInventory();

        assertEquals(8, inv.getMainInventorySlot(0).getCount());
        for (int i = 1; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            assertTrue(inv.getMainInventorySlot(i).isEmpty());
        }
    }

    @Test
    void mergingRespectsMaxStackSize() {
        Inventory inv = new Inventory();
        int maxStack = new ItemStack(BlockType.STONE, 1).getMaxStackSize(); // 64
        inv.setMainInventorySlot(0, new ItemStack(BlockType.STONE, maxStack - 10));
        inv.setMainInventorySlot(1, new ItemStack(BlockType.STONE, 20));

        inv.sortMainInventory();

        assertEquals(maxStack, inv.getMainInventorySlot(0).getCount());
        assertEquals(10, inv.getMainInventorySlot(1).getCount());
        for (int i = 2; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            assertTrue(inv.getMainInventorySlot(i).isEmpty());
        }
    }

    @Test
    void selectedHotbarItemStaysSelectedAfterMergeSort() {
        Inventory inv = new Inventory();
        inv.setHotbarSlot(0, new ItemStack(ItemType.WOODEN_PICKAXE, 1));
        inv.setHotbarSlot(1, new ItemStack(BlockType.STONE, 30));
        inv.setHotbarSlot(2, new ItemStack(BlockType.STONE, 20)); // will merge with slot 1
        inv.setSelectedHotbarSlotIndex(2);

        inv.sortHotbar();

        ItemStack selectedAfter = inv.getSelectedHotbarSlot();
        assertFalse(selectedAfter.isEmpty());
        assertEquals(BlockType.STONE.getId(), selectedAfter.getBlockTypeId());
        assertEquals(50, selectedAfter.getCount());
    }

    @Test
    void selectedEmptySlotIndexIsLeftUnchanged() {
        Inventory inv = new Inventory();
        inv.setHotbarSlot(3, new ItemStack(BlockType.STONE, 1));
        inv.setSelectedHotbarSlotIndex(8); // an empty slot

        inv.sortHotbar();

        assertEquals(8, inv.getSelectedHotbarSlotIndex());
    }

    @Test
    void stateDifferentiatedItemsAreNotMerged() {
        Inventory inv = new Inventory();
        inv.setMainInventorySlot(0,
            new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER));
        inv.setMainInventorySlot(1,
            new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_EMPTY));

        inv.sortMainInventory();

        // Both buckets must survive as separate 1-count stacks, not merge into a count of 2.
        int nonEmptyCount = 0;
        boolean sawEmptyState = false;
        boolean sawWaterState = false;
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getMainInventorySlot(i);
            if (stack.isEmpty()) continue;
            nonEmptyCount++;
            assertEquals(1, stack.getCount());
            assertEquals(ItemType.WOODEN_BUCKET.getId(), stack.getBlockTypeId());
            if (ItemType.BUCKET_STATE_EMPTY.equals(stack.getState())) sawEmptyState = true;
            if (ItemType.BUCKET_STATE_WATER.equals(stack.getState())) sawWaterState = true;
        }
        assertEquals(2, nonEmptyCount);
        assertTrue(sawEmptyState);
        assertTrue(sawWaterState);
    }

    @Test
    void sortIsIdempotent() {
        Inventory inv = new Inventory();
        inv.setMainInventorySlot(0, new ItemStack(ItemType.WOODEN_PICKAXE, 1));
        inv.setMainInventorySlot(1, new ItemStack(BlockType.STONE, 1));

        inv.sortMainInventory();
        ItemStack[] first = inv.getMainInventorySlots();
        inv.sortMainInventory();
        ItemStack[] second = inv.getMainInventorySlots();

        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], second[i]);
        }
    }
}
