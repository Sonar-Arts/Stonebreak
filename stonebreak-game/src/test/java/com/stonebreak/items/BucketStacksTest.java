package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bucket conversion rule shared by filling, pouring and stalagmite watering
 * (issue #354): a single bucket swaps state in place; a stack gives up one bucket
 * of the other state — and if that overflow bucket has nowhere to go, the action
 * is refused up front instead of deleting it.
 */
class BucketStacksTest {

    private static final int STONE = BlockType.STONE.getId();

    private Inventory inventory;
    private int bucketMaxStack;
    private int capacity;

    @BeforeEach
    void freshInventory() {
        inventory = new Inventory();
        bucketMaxStack = new ItemStack(ItemType.WOODEN_BUCKET, 1).getMaxStackSize();
        capacity = Inventory.TOTAL_SLOTS * new ItemStack(STONE, 1).getMaxStackSize();
    }

    @Test
    void aSingleBucketConvertsInPlace() {
        inventory.setHotbarSlot(0,
                new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_EMPTY));

        assertTrue(BucketStacks.canConvertHeld(inventory, inventory.getSelectedHotbarSlot(),
                ItemType.BUCKET_STATE_WATER));
        BucketStacks.convertHeld(inventory, inventory.getSelectedHotbarSlot(),
                ItemType.BUCKET_STATE_WATER);

        ItemStack slot = inventory.getHotbarSlot(0);
        assertEquals(1, slot.getCount());
        assertEquals(ItemType.BUCKET_STATE_WATER, slot.getState());
    }

    @Test
    void aStackGivesUpOneBucketOfTheOtherState() {
        inventory.setHotbarSlot(0,
                new ItemStack(ItemType.WOODEN_BUCKET, 2, ItemType.BUCKET_STATE_EMPTY));

        assertTrue(BucketStacks.canConvertHeld(inventory, inventory.getSelectedHotbarSlot(),
                ItemType.BUCKET_STATE_WATER));
        BucketStacks.convertHeld(inventory, inventory.getSelectedHotbarSlot(),
                ItemType.BUCKET_STATE_WATER);

        assertEquals(1, inventory.getHotbarSlot(0).getCount(),
                "the slot keeps one bucket of the old state");
        assertEquals(ItemType.BUCKET_STATE_EMPTY, inventory.getHotbarSlot(0).getState());
        assertEquals(2, inventory.getItemCount(ItemType.WOODEN_BUCKET),
                "the converted bucket lands somewhere else in the inventory");
        assertEquals(2, totalBuckets(), "no bucket is created or destroyed");
    }

    @Test
    void canConvertHeldRefusesWhenTheOverflowBucketCannotFit() {
        inventory.addItem(STONE, capacity);
        inventory.setHotbarSlot(0,
                new ItemStack(ItemType.WOODEN_BUCKET, 2, ItemType.BUCKET_STATE_EMPTY));

        assertFalse(BucketStacks.canConvertHeld(inventory, inventory.getSelectedHotbarSlot(),
                ItemType.BUCKET_STATE_WATER));
        assertEquals(2, inventory.getHotbarSlot(0).getCount(),
                "the check is non-mutating — the stack is untouched");
        assertEquals(2, inventory.getItemCount(ItemType.WOODEN_BUCKET),
                "no converted bucket was added");
    }

    @Test
    void canConvertHeldAllowsWhenARoomySameStateBucketStackExists() {
        inventory.setHotbarSlot(0,
                new ItemStack(ItemType.WOODEN_BUCKET, 2, ItemType.BUCKET_STATE_EMPTY));
        inventory.setHotbarSlot(1,
                new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER));

        assertTrue(BucketStacks.canConvertHeld(inventory, inventory.getSelectedHotbarSlot(),
                ItemType.BUCKET_STATE_WATER));
    }

    @Test
    void aFullStackRefusesEvenWhenADifferentStateBucketStackIsFull() {
        int stoneMax = new ItemStack(STONE, 1).getMaxStackSize();
        inventory.setHotbarSlot(0,
                new ItemStack(ItemType.WOODEN_BUCKET, bucketMaxStack,
                        ItemType.BUCKET_STATE_EMPTY));
        inventory.addItem(new ItemStack(ItemType.WOODEN_BUCKET, bucketMaxStack,
                ItemType.BUCKET_STATE_WATER));
        inventory.addItem(STONE, (Inventory.TOTAL_SLOTS - 2) * stoneMax);

        assertFalse(BucketStacks.canConvertHeld(inventory, inventory.getSelectedHotbarSlot(),
                ItemType.BUCKET_STATE_WATER),
                "a full water stack is not a stacking destination for a water bucket");
    }

    private int totalBuckets() {
        int total = 0;
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            total += bucketsIn(inventory.getHotbarSlot(i));
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
                total += bucketsIn(inventory.getMainInventorySlot(i));
            }
        return total;
    }

    private int bucketsIn(ItemStack stack) {
        return stack.isEmpty() || stack.getItem() != ItemType.WOODEN_BUCKET ? 0 : stack.getCount();
    }
}
