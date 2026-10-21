package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing the inventory screen returns the 2×2 grid THROUGH the server (issue #334): one
 * QUICK_MOVE per non-empty grid cell plus DEPOSIT_CARRIED, sent before the local
 * prediction empties the grid — otherwise the next InventorySyncS2C would overwrite the
 * mirror without those items and the ephemeral server grid would strand them.
 */
@Tag("regression")
class InventoryScreenCloseSyncTest {

    private static final int STICK = ItemType.STICK.getId();

    private Inventory inventory;
    private InventoryCraftingManager crafting;
    private InventorySlotManager slots;
    private InventoryController controller;
    private final List<ContainerActionC2S> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        inventory = new Inventory();
        crafting = new InventoryCraftingManager(new CraftingManager());
        slots = new InventorySlotManager(inventory, crafting);
        InventoryInputManager input = new InventoryInputManager(null, inventory, slots, crafting);
        controller = new InventoryController(inventory, input, crafting);
        slots.actions.setCapture(sent::add);
    }

    @Test
    void closeSendsAQuickMovePerNonEmptyGridCellThenDepositCarried() {
        crafting.getCraftingInputSlots()[1] = new ItemStack(ItemType.STICK, 3);
        crafting.getCraftingInputSlots()[2] = new ItemStack(ItemType.STICK, 2);

        controller.returnHeldItemsToPlayer();

        assertEquals(3, sent.size(), "two grid moves + the carried deposit");
        assertEquals(ContainerActionC2S.Action.QUICK_MOVE, sent.get(0).action());
        assertEquals(ContainerActionC2S.Region.INV_CRAFT_GRID, sent.get(0).region());
        assertEquals(1, sent.get(0).slot());
        assertEquals(ContainerActionC2S.Action.QUICK_MOVE, sent.get(1).action());
        assertEquals(2, sent.get(1).slot());
        assertEquals(ContainerActionC2S.Action.DEPOSIT_CARRIED, sent.get(2).action());
    }

    @Test
    void closeEmptiesTheGridIntoTheLocalPrediction() {
        crafting.getCraftingInputSlots()[0] = new ItemStack(ItemType.STICK, 4);

        controller.returnHeldItemsToPlayer();

        assertTrue(crafting.getCraftingInputSlots()[0].isEmpty(),
            "the local grid is emptied so the sync converges on an already-drained server grid");
        int total = 0;
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            ItemStack s = inventory.getHotbarSlot(i);
            if (s != null && !s.isEmpty()) {
                total += s.getCount();
            }
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            ItemStack s = inventory.getMainInventorySlot(i);
            if (s != null && !s.isEmpty()) {
                total += s.getCount();
            }
        }
        assertEquals(4, total, "the prediction landed in the mirror");
    }

    @Test
    void closeWithAnEmptyGridStillDepositsTheServerCursor() {
        controller.returnHeldItemsToPlayer();

        assertEquals(1, sent.size(), "only the DEPOSIT_CARRIED safety net is sent");
        assertEquals(ContainerActionC2S.Action.DEPOSIT_CARRIED, sent.get(0).action());
    }
}

