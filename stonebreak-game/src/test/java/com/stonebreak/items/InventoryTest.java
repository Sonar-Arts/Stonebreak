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
}
