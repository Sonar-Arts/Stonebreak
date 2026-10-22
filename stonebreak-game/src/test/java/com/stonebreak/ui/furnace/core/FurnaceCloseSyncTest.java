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

import com.stonebreak.network.packet.player.ContainerActionC2S;

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

    private final List<ContainerActionC2S> sent = new ArrayList<>();
    private FurnaceController controller;
    private FurnaceInputManager input;
    private FurnaceState furnace;

    @BeforeEach
    void setUp() {
        Inventory inventory = new Inventory();
        controller = new FurnaceController(null, null, null);
        input = new FurnaceInputManager(null, inventory, controller);
        controller.setInputManager(input);
        furnace = new FurnaceState(new BlockPos(0, 64, 0));
        furnace.setFuel(new ItemStack(STONE, 5));
        controller.bind(furnace);
        controller.actions().setCapture(sent::add);
    }

    @Test
    void closingWhileCarryingAFurnaceStackSendsTheRestoredSlot() {
        pickUpFuel(); // helper mirrors the pickup's local effect; the real click path also
                      // emits TAKE_ALL through the bound sink (FurnaceSlotAddressTest covers it)
        assertTrue(sent.isEmpty());

        controller.close();

        assertEquals(5, furnace.getFuel().getCount(), "the stack goes back to its slot");
        assertEquals(1, sent.size(), "the restore must be sent before the UI lets go of the state");
        assertEquals(ContainerActionC2S.Action.PUT_ALL, sent.get(0).action());
        assertEquals(ContainerActionC2S.Region.FURNACE_FUEL, sent.get(0).region());
        assertFalse(controller.isVisible());
    }

    @Test
    void closingInTheSameFrameAsThePickupSendsNothingRedundant() {
        pickUpFuel();
        controller.close(); // restored before any update
        assertEquals(1, sent.size());
        assertEquals(ContainerActionC2S.Action.PUT_ALL, sent.get(0).action(),
            "the restore is the one atomic action the server needs");
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
