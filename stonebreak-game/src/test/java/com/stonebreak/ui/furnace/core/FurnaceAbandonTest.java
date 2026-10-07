package com.stonebreak.ui.furnace.core;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.stonebreak.ui.support.UiTestFixtures.emptyInventory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #298: a furnace whose block is gone while its screen is open is abandoned (no stale slot edit or
 * snapshot can reach it), and closing never conjures items: a carried stack that only partly fits
 * the inventory puts in what fits and drops only the rest.
 */
@Tag("regression")
class FurnaceAbandonTest {

    private static final int STONE = BlockType.STONE.getId();
    private static final int DIRT = BlockType.DIRT.getId();

    private final List<String> sent = new ArrayList<>();
    private Inventory inventory;
    private FurnaceController controller;
    private FurnaceInputManager input;
    private FurnaceState furnace;

    @BeforeEach
    void setUp() {
        inventory = emptyInventory();
        controller = new FurnaceController(null, inventory, null, null, null);
        input = new FurnaceInputManager(null, inventory, controller);
        controller.setInputManager(input);
        controller.setSlotSink((pos, slots) -> sent.add(slots));
        controller.setFurnaceAt(p -> true);
        furnace = new FurnaceState(new BlockPos(0, 64, 0));
        furnace.setFuel(new ItemStack(STONE, 5));
        controller.bind(furnace);
    }

    @Test
    void aBrokenFurnaceIsDetachedAndTheCarriedStackGoesToTheInventory() {
        pickUpFuel();
        controller.setFurnaceAt(p -> false); // another player broke it

        controller.update(0f);

        assertFalse(controller.isVisible(), "the screen closes");
        assertTrue(furnace.getFuel().isEmpty(), "nothing is put back into the dead furnace");
        assertEquals(5, count(STONE), "the carried stack is the player's again");
        assertFalse(input.getDragState().isDragging());
        assertTrue(sent.isEmpty(), "no snapshot reaches the broken furnace's position");

        controller.update(0f);
        assertTrue(sent.isEmpty(), "nor later");
    }

    @Test
    void aStandingFurnaceIsKept() {
        controller.update(0f);
        assertTrue(controller.isVisible());
    }

    @Test
    void closingWithAStackThatPartlyFitsAddsOnlyWhatFits() {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            inventory.setHotbarSlot(i, new ItemStack(DIRT, 64));
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            inventory.setMainInventorySlot(i, new ItemStack(DIRT, 64));
        }
        inventory.setMainInventorySlot(0, new ItemStack(STONE, 54)); // room for 10 stone
        furnace.setFuel(new ItemStack(STONE, 64));
        pickUpFuel();
        furnace.setFuel(new ItemStack(DIRT, 1)); // the origin slot refilled: the stack cannot go back
        int[] dropped = {0};
        input.setWorldDrop(d -> {
            dropped[0] += d.draggedItemStack.getCount();
            d.clear();
        });

        controller.close();

        assertEquals(64, count(STONE), "exactly the 10 that fit went in");
        assertEquals(54, dropped[0], "only the rest is dropped: 10 + 54 = the 64 carried, never 10 + 64");
        assertFalse(input.getDragState().isDragging());
    }

    private int count(int id) {
        int n = 0;
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            ItemStack s = inventory.getHotbarSlot(i);
            n += s.getBlockTypeId() == id ? s.getCount() : 0;
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            ItemStack s = inventory.getMainInventorySlot(i);
            n += s.getBlockTypeId() == id ? s.getCount() : 0;
        }
        return n;
    }

    /** Mirrors FurnaceInputManager's left-click pickup from a furnace slot. */
    private void pickUpFuel() {
        InventoryDragDropHandler.DragState drag = input.getDragState();
        drag.draggedItemStack = furnace.getFuel().copy();
        drag.draggedItemOriginalSlotIndex = 3000 + FurnaceController.SLOT_FUEL;
        drag.dragSource = InventoryDragDropHandler.DragSource.NONE;
        furnace.setFuel(new ItemStack(0, 0));
    }
}
