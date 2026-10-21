package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The add/stack/remove arithmetic every pickup, craft and block break runs through. Only sorting
 * had tests; the actual accounting — stacking before opening new slots, spilling at the stack cap,
 * refusing when full, and the state-match rule that keeps differently-faced stairs (and water vs
 * empty buckets) in separate stacks — was unpinned.
 */
class InventoryTest {

    private static final int STONE = BlockType.STONE.getId();

    private Inventory inventory;
    private int maxStack;
    private int capacity;

    @BeforeEach
    void freshInventory() {
        inventory = new Inventory();
        maxStack = new ItemStack(STONE, 1).getMaxStackSize();
        capacity = Inventory.TOTAL_SLOTS * maxStack;
    }

    @Test
    void aFreshInventoryHoldsNothing() {
        assertEquals(0, inventory.getItemCount(STONE));
        assertFalse(inventory.hasItem(STONE));
    }

    @Test
    void aPickupLandsInTheFirstHotbarSlot() {
        assertTrue(inventory.addItem(STONE, 1));

        assertEquals(1, inventory.getItemCount(STONE));
        assertEquals(STONE, inventory.getHotbarSlot(0).getBlockTypeId());
        assertEquals(1, inventory.getHotbarSlot(0).getCount());
    }

    @Test
    void anOverfullPickupSpillsIntoTheNextSlot() {
        assertTrue(inventory.addItem(STONE, maxStack + 5));

        assertEquals(maxStack, inventory.getHotbarSlot(0).getCount(),
                "the first slot fills to the cap");
        assertEquals(5, inventory.getHotbarSlot(1).getCount(),
                "the remainder opens a second stack");
        assertEquals(maxStack + 5, inventory.getItemCount(STONE));
    }

    @Test
    void newItemsStackWithExistingOnesBeforeOpeningSlots() {
        assertTrue(inventory.addItem(STONE, 10));
        assertTrue(inventory.addItem(STONE, 10));

        assertEquals(20, inventory.getHotbarSlot(0).getCount(),
                "a second pickup merges into the existing stack");
        assertTrue(inventory.getHotbarSlot(1).isEmpty());
    }

    @Test
    void aFullInventoryRefusesAnotherItem() {
        assertTrue(inventory.addItem(STONE, capacity));

        assertFalse(inventory.addItem(STONE, 1));
        assertEquals(capacity, inventory.getItemCount(STONE));
    }

    @Test
    void addItemsAndReturnCountReportsWhatActuallyFit() {
        int added = inventory.addItemsAndReturnCount(STONE, capacity + 10);

        assertEquals(capacity, added, "only what fits is added, and the caller is told");
        assertEquals(capacity, inventory.getItemCount(STONE));
    }

    @Test
    void removalTakesExactlyWhatWasAsked() {
        assertTrue(inventory.addItem(STONE, 10));

        assertTrue(inventory.removeItem(STONE, 4));
        assertEquals(6, inventory.getItemCount(STONE));
    }

    @Test
    void removingMoreThanIsHeldReportsFailure() {
        assertTrue(inventory.addItem(STONE, 6));

        assertFalse(inventory.removeItem(STONE, 100),
                "a removal that cannot be met in full must say so");
    }

    @Test
    void addingAirIsANoOp() {
        assertTrue(inventory.addItem(BlockType.AIR.getId(), 5));

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            assertTrue(inventory.getHotbarSlot(i).isEmpty());
        }
    }

    /**
     * The state-match rule: stacks merge only when item AND state agree, so two stairs facing
     * different ways — or a water bucket and an empty one — never collapse into one stack.
     */
    @Test
    void stacksMergeOnlyWhenTheirStatesMatch() {
        String north = "stairs:facing=NORTH";
        String east = "stairs:facing=EAST";

        assertTrue(inventory.addItem(new ItemStack(BlockType.OAK_STAIRS, 1, north)));
        assertTrue(inventory.addItem(new ItemStack(BlockType.OAK_STAIRS, 1, north)));
        assertTrue(inventory.addItem(new ItemStack(BlockType.OAK_STAIRS, 1, east)));

        assertEquals(2, inventory.getHotbarSlot(0).getCount(),
                "same state merges into one stack");
        assertEquals(north, inventory.getHotbarSlot(0).getState());
        assertEquals(1, inventory.getHotbarSlot(1).getCount(),
                "a different state opens its own stack");
        assertEquals(east, inventory.getHotbarSlot(1).getState());
    }

    /**
     * Issue #350: an id-only pickup carries no state, so it is a default-state (empty) bucket and
     * must not merge into a water bucket — it used to, turning the empty bucket into water.
     */
    @Test
    void anIdOnlyBucketPickupNeverMergesIntoWaterBuckets() {
        inventory.setHotbarSlot(0, new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER));
        inventory.setHotbarSlot(1, new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_EMPTY));

        assertEquals(1, inventory.addItemsAndReturnCount(ItemType.WOODEN_BUCKET.getId(), 1));

        assertEquals(1, inventory.getHotbarSlot(0).getCount(), "the water bucket stack is untouched");
        assertEquals(ItemType.BUCKET_STATE_WATER, inventory.getHotbarSlot(0).getState());
        assertEquals(2, inventory.getHotbarSlot(1).getCount(), "it joins the empty buckets");
        assertEquals(ItemType.BUCKET_STATE_EMPTY, inventory.getHotbarSlot(1).getState());
    }

    @Test
    void anIdOnlyBucketPickupOpensItsOwnSlotBesideWaterBuckets() {
        inventory.setHotbarSlot(0, new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER));

        assertTrue(inventory.addItem(ItemType.WOODEN_BUCKET.getId(), 1));

        assertEquals(1, inventory.getHotbarSlot(0).getCount());
        assertEquals(1, inventory.getHotbarSlot(1).getCount());
        assertEquals(ItemType.BUCKET_STATE_EMPTY, inventory.getHotbarSlot(1).getEffectiveState());
    }

    /** Issue #350: a crafted bucket (no state) and an emptied one (the default state) are one kind. */
    @Test
    void craftedAndEmptiedBucketsStack() {
        assertTrue(inventory.addItem(new ItemStack(ItemType.WOODEN_BUCKET, 1)));
        assertTrue(inventory.addItem(new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_EMPTY)));

        assertEquals(2, inventory.getHotbarSlot(0).getCount());
        assertTrue(inventory.getHotbarSlot(1).isEmpty());

        ItemStack crafted = new ItemStack(ItemType.WOODEN_BUCKET, 1);
        assertTrue(crafted.canStackWith(new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_EMPTY)));
        assertFalse(crafted.canStackWith(new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER)));
    }

    /**
     * Issue #354: the non-mutating capacity check that bucket conversions run before
     * touching the world. It must mirror {@code addItem}'s stacking rules without
     * changing anything.
     */
    @Test
    void canAddItemSeesRoomInARoomyStateMatchedStack() {
        inventory.setHotbarSlot(0, new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER));

        assertTrue(inventory.canAddItem(
                new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER)));
        assertEquals(1, inventory.getHotbarSlot(0).getCount(), "the check is non-mutating");
    }

    @Test
    void canAddItemSeesRoomInAnEmptySlot() {
        assertTrue(inventory.canAddItem(new ItemStack(STONE, 1)));
        assertTrue(inventory.getHotbarSlot(0).isEmpty(), "the check is non-mutating");
    }

    @Test
    void canAddItemRefusesACompletelyFullInventory() {
        inventory.addItem(STONE, capacity);

        assertFalse(inventory.canAddItem(new ItemStack(STONE, 1)));
    }

    @Test
    void canAddItemRefusesWhenOnlyMismatchedOrFullStacksExist() {
        int bucketMaxStack = new ItemStack(ItemType.WOODEN_BUCKET, 1).getMaxStackSize();
        inventory.setHotbarSlot(0,
                new ItemStack(ItemType.WOODEN_BUCKET, bucketMaxStack, ItemType.BUCKET_STATE_WATER));
        inventory.addItem(new ItemStack(ItemType.WOODEN_BUCKET, bucketMaxStack,
                ItemType.BUCKET_STATE_EMPTY));
        inventory.addItem(STONE, (Inventory.TOTAL_SLOTS - 2) * maxStack);

        assertFalse(inventory.canAddItem(
                new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER)),
                "a full water stack is not a destination; no empty slot exists either");
    }
}
