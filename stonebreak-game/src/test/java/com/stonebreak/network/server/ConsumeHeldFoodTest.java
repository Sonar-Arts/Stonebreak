package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.network.packet.player.ConsumeHeldC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.server.handlers.ServerPlayerHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Eating is debited from the server-owned inventory (issue #355, on top of #334): the
 * replica drops the eaten item and applies the same depletion auto-switch the client
 * predicts, so the next {@code InventorySyncS2C} neither restores the food nor reverts the
 * switched-in stack.
 */
class ConsumeHeldFoodTest {

    private static final int BANANA = ItemType.BANANA.getId();

    @Test
    void eatingTheLastPullsTheNextHotbarStackIn() {
        ServerInventory inv = new ServerInventory();
        inv.setSlot(0, new ItemStack(ItemType.BANANA, 1));
        inv.setSlot(2, new ItemStack(ItemType.BANANA, 5));

        assertTrue(inv.consumeHeldFood(BANANA));

        assertEquals(5, inv.slot(0).getCount());
        assertTrue(inv.slot(2).isEmpty());
    }

    @Test
    void eatingTheLastPullsFromMainWhenNoHotbarMatch() {
        ServerInventory inv = new ServerInventory();
        inv.setSlot(0, new ItemStack(ItemType.BANANA, 1));
        inv.setSlot(ServerInventory.HOTBAR_SIZE + 4, new ItemStack(ItemType.BANANA, 3));

        assertTrue(inv.consumeHeldFood(BANANA));

        assertEquals(3, inv.slot(0).getCount());
        assertTrue(inv.slot(ServerInventory.HOTBAR_SIZE + 4).isEmpty());
    }

    @Test
    void eatingOneOfManyLeavesOtherStacksAlone() {
        ServerInventory inv = new ServerInventory();
        inv.setSlot(0, new ItemStack(ItemType.BANANA, 5));
        inv.setSlot(2, new ItemStack(ItemType.BANANA, 3));

        assertTrue(inv.consumeHeldFood(BANANA));

        assertEquals(4, inv.slot(0).getCount());
        assertEquals(3, inv.slot(2).getCount());
    }

    @Test
    void refusesWhatTheSelectedSlotDoesNotHold() {
        ServerInventory inv = new ServerInventory();
        inv.setSlot(2, new ItemStack(ItemType.BANANA, 5)); // held slot 0 is empty
        inv.setSlot(1, new ItemStack(ItemType.STICK, 4));

        assertFalse(inv.consumeHeldFood(BANANA), "food elsewhere is not the held stack");
        inv.selectHotbarSlot(1);
        assertFalse(inv.consumeHeldFood(ItemType.STICK.getId()), "a stick is not food");

        assertEquals(5, inv.slot(2).getCount());
        assertEquals(4, inv.slot(1).getCount());
    }

    @Test
    void replicaConvergesWithTheClientPrediction() {
        Inventory client = new Inventory();
        ServerInventory server = new ServerInventory();
        int selected = 3;
        client.setSelectedHotbarSlotIndex(selected);
        server.selectHotbarSlot(selected);
        put(client, server, selected, new ItemStack(ItemType.BANANA, 1));
        put(client, server, 7, new ItemStack(ItemType.BANANA, 2));
        put(client, server, ServerInventory.HOTBAR_SIZE, new ItemStack(ItemType.BANANA, 9));

        for (int bite = 0; bite < 4; bite++) {
            client.removeItem(ItemType.BANANA.getId(), 1);
            server.consumeHeldFood(BANANA);
            for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
                ItemStack c = i < Inventory.HOTBAR_SIZE ? client.getHotbarSlot(i)
                        : client.getMainInventorySlot(i - Inventory.HOTBAR_SIZE);
                assertEquals(c.isEmpty() ? 0 : c.getCount(), server.slot(i).getCount(),
                        "bite " + bite + ", slot " + i);
            }
        }
    }

    @Test
    void handlerAnswersWithAnAcknowledgingSync() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 7);
        sp.inventory().setSlot(0, new ItemStack(ItemType.BANANA, 2));

        new ServerPlayerHandler().handleConsumeHeld(sp, new ConsumeHeldC2S(BANANA, 41));

        assertEquals(1, sp.inventory().slot(0).getCount());
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection()).send(sent.capture(), anyBoolean());
        InventorySyncS2C sync = (InventorySyncS2C) sent.getValue();
        assertEquals(41, sync.ack(), "the reply acknowledges the eat, so it may replace the prediction");
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
