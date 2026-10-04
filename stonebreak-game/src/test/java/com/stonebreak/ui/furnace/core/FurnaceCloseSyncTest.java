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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #320: closing the furnace while carrying a stack taken from a furnace slot
 * returns it to that slot AND tells the server, so the next echo can't wipe it.
 */
@Tag("regression")
class FurnaceCloseSyncTest {

    private static final int STONE = BlockType.STONE.getId();

    private final List<String> sent = new ArrayList<>();
    private FurnaceController controller;
    private FurnaceInputManager input;
    private FurnaceState furnace;

    @BeforeEach
    void setUp() {
        Inventory inventory = new Inventory();
        controller = new FurnaceController(null, inventory, null, null, null);
        input = new FurnaceInputManager(null, inventory, controller);
        controller.setInputManager(input);
        controller.setSlotSink((pos, slots) -> sent.add(slots));
        furnace = new FurnaceState(new BlockPos(0, 64, 0));
        furnace.setFuel(new ItemStack(STONE, 5));
        controller.bind(furnace);
    }

    @Test
    void closingWhileCarryingAFurnaceStackSendsTheRestoredSlot() {
        String withFuel = furnace.encodeSlots();
        pickUpFuel();
        controller.update(0f); // the pickup reaches the server: fuel slot empty
        assertEquals(1, sent.size());
        assertFalse(sent.get(0).equals(withFuel));

        controller.close();

        assertEquals(5, furnace.getFuel().getCount(), "the stack goes back to its slot");
        assertEquals(2, sent.size(), "the restore must be sent before the UI lets go of the state");
        assertEquals(withFuel, sent.get(1));
        assertFalse(controller.isVisible());
    }

    @Test
    void closingInTheSameFrameAsThePickupSendsNothingRedundant() {
        pickUpFuel();
        controller.close(); // restored before any update: the server never saw the pickup
        assertTrue(sent.isEmpty(), "slots match what the server already has");
        assertEquals(5, furnace.getFuel().getCount());
    }

    @Test
    void closingWithoutCarryingSendsNothing() {
        controller.close();
        assertTrue(sent.isEmpty());
    }

    /** Mirrors FurnaceInputManager's left-click pickup from a furnace slot. */
    private void pickUpFuel() {
        InventoryDragDropHandler.DragState drag = input.getDragState();
        drag.draggedItemStack = furnace.getFuel().copy();
        drag.draggedItemOriginalSlotIndex = 3000 + FurnaceController.SLOT_FUEL;
        furnace.setFuel(new ItemStack(0, 0));
    }
}
