package com.stonebreak.network.server;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #422: a placement debits the server-owned replica with the same depletion auto-switch
 * {@code Inventory.removeItem} predicts on the client, so the next {@code InventorySyncS2C}
 * neither empties the selected slot again nor moves the switched-in stack back. A refunded
 * placement goes back onto the held stack.
 */
class PlacementAutoSwitchTest {

    private static final int STONE = BlockType.STONE.getId();

    @Test
    void placingTheLastPullsTheNextHotbarStackIn() {
        ServerInventory inv = new ServerInventory();
        inv.setSlot(0, new ItemStack(BlockType.STONE, 1));
        inv.setSlot(4, new ItemStack(BlockType.STONE, 20));

        assertNotNull(inv.debitOneWithAutoSwitch(STONE));

        assertEquals(STONE, inv.slot(0).getBlockTypeId());
        assertEquals(20, inv.slot(0).getCount());
        assertTrue(inv.slot(4).isEmpty());
    }

    @Test
    void placingTheLastPullsFromMainWhenNoHotbarMatch() {
        ServerInventory inv = new ServerInventory();
        inv.setSlot(0, new ItemStack(BlockType.STONE, 1));
        inv.setSlot(ServerInventory.HOTBAR_SIZE + 6, new ItemStack(BlockType.STONE, 7));

        assertNotNull(inv.debitOneWithAutoSwitch(STONE));

        assertEquals(7, inv.slot(0).getCount());
        assertTrue(inv.slot(ServerInventory.HOTBAR_SIZE + 6).isEmpty());
    }

    @Test
    void aStackOfAnotherStateIsNotSwitchedIn() {
        ServerInventory inv = new ServerInventory();
        inv.setSlot(0, new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER));
        inv.setSlot(1, new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_EMPTY));

        assertNotNull(inv.debitOneWithAutoSwitch(ItemType.WOODEN_BUCKET.getId()));

        assertTrue(inv.slot(0).isEmpty(), "an empty bucket is not the water bucket that ran out");
        assertEquals(1, inv.slot(1).getCount());
    }

    @Test
    void placingFromElsewhereLeavesTheSelectedSlotAlone() {
        ServerInventory inv = new ServerInventory();
        inv.setSlot(0, new ItemStack(BlockType.DIRT, 3));
        inv.setSlot(2, new ItemStack(BlockType.STONE, 1));
        inv.setSlot(5, new ItemStack(BlockType.STONE, 4));

        assertNotNull(inv.debitOneWithAutoSwitch(STONE));

        assertEquals(BlockType.DIRT.getId(), inv.slot(0).getBlockTypeId());
        assertTrue(inv.slot(2).isEmpty());
        assertEquals(4, inv.slot(5).getCount(), "no switch: the selected slot did not run out");
    }

    @Test
    void nothingHeldIsRefusedWithoutChange() {
        ServerInventory inv = new ServerInventory();
        inv.setSlot(0, new ItemStack(BlockType.DIRT, 3));

        assertNull(inv.debitOneWithAutoSwitch(STONE));
        assertEquals(3, inv.slot(0).getCount());
    }

    @Test
    void replicaConvergesWithTheClientPrediction() {
        Inventory client = new Inventory();
        ServerInventory server = new ServerInventory();
        int selected = 2;
        client.setSelectedHotbarSlotIndex(selected);
        server.selectHotbarSlot(selected);
        clearClient(client);
        put(client, server, selected, new ItemStack(BlockType.STONE, 1));
        put(client, server, 6, new ItemStack(BlockType.STONE, 2));
        put(client, server, ServerInventory.HOTBAR_SIZE + 1, new ItemStack(BlockType.STONE, 3));
        put(client, server, 0, new ItemStack(BlockType.DIRT, 5));

        for (int placed = 0; placed < 6; placed++) {
            client.removeItem(STONE, 1);
            assertNotNull(server.debitOneWithAutoSwitch(STONE), "placement " + placed);
            for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
                ItemStack c = i < Inventory.HOTBAR_SIZE ? client.getHotbarSlot(i)
                        : client.getMainInventorySlot(i - Inventory.HOTBAR_SIZE);
                ItemStack s = server.slot(i);
                assertEquals(c.isEmpty() ? 0 : c.getCount(), s.getCount(), "placement " + placed + ", slot " + i);
                if (!c.isEmpty()) {
                    assertEquals(c.getBlockTypeId(), s.getBlockTypeId(), "placement " + placed + ", slot " + i);
                }
            }
        }
    }

    @Test
    void aRefundGoesBackOntoTheHeldStack() {
        ServerInventory inv = new ServerInventory();
        inv.selectHotbarSlot(3);
        inv.setSlot(0, new ItemStack(BlockType.STONE, 10));
        inv.setSlot(3, new ItemStack(BlockType.STONE, 5));

        assertEquals(1, inv.addToSelectedFirst(STONE, null));

        assertEquals(6, inv.slot(3).getCount(), "back into the placing hand");
        assertEquals(10, inv.slot(0).getCount());
    }

    @Test
    void aRefundOfAnotherKindUsesTheNormalStackingRule() {
        ServerInventory inv = new ServerInventory();
        inv.selectHotbarSlot(3);
        inv.setSlot(0, new ItemStack(BlockType.STONE, 10));
        inv.setSlot(3, new ItemStack(BlockType.DIRT, 5));

        assertEquals(1, inv.addToSelectedFirst(STONE, null));

        assertEquals(11, inv.slot(0).getCount());
        assertEquals(5, inv.slot(3).getCount());
    }

    private static void clearClient(Inventory client) {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            client.setHotbarSlot(i, new ItemStack(BlockType.AIR.getId(), 0));
        }
        for (int i = 0; i < ServerInventory.MAIN_SIZE; i++) {
            client.setMainInventorySlot(i, new ItemStack(BlockType.AIR.getId(), 0));
        }
    }

    private static void put(Inventory client, ServerInventory server, int slot, ItemStack stack) {
        if (slot < Inventory.HOTBAR_SIZE) {
            client.setHotbarSlot(slot, stack.copy());
        } else {
            client.setMainInventorySlot(slot - Inventory.HOTBAR_SIZE, stack.copy());
        }
        server.setSlot(slot, stack.copy());
    }
}
