package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.items.ItemType;
import com.stonebreak.network.packet.player.HotbarSelectC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The held item is DERIVED from the server's replica via HotbarSelectC2S (issue #334):
 * selecting an empty slot forces held = air, so the shears check and tool-specific drops
 * cannot be forged by a relayed item id.
 */
class HeldItemDerivedTest {

    @SuppressWarnings("unchecked")
    @Test
    void hotbarSelectToEmptySlotForcesHeldAir() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 3);
        sp.inventory().add(ItemType.SHEARS.getId(), 1); // in hotbar slot 0
        ServerWorldContext ctx = mock(ServerWorldContext.class);

        new ServerPlayerHandler().handleHotbarSelect(sp, new HotbarSelectC2S(0), ctx);
        assertEquals(ItemType.SHEARS.getId(), sp.inventory().heldItemId(),
            "slot 0 already selected: held derives from the replica");

        // Selecting the EMPTY slot 1: the held id collapses to air — no shears, no tool drops.
        new ServerPlayerHandler().handleHotbarSelect(sp, new HotbarSelectC2S(1), ctx);
        assertEquals(0, sp.inventory().heldItemId());

        // Every accepted selection announces the derived held id — including a re-select of
        // the SAME slot, because picking an item INTO the held slot changes it too.
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(ctx, times(2)).broadcast(captor.capture(), anyBoolean());
        PlayerHeldItemS2C first = assertInstanceOf(PlayerHeldItemS2C.class, captor.getAllValues().get(0));
        assertEquals(ItemType.SHEARS.getId(), first.itemId());
        PlayerHeldItemS2C change = assertInstanceOf(PlayerHeldItemS2C.class, captor.getAllValues().get(1));
        assertEquals(0, change.itemId(), "the empty slot cannot hold a forged tool id");
    }

    @Test
    void outOfRangeSlotRefusedWithSync() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 3);
        ServerWorldContext ctx = mock(ServerWorldContext.class);

        new ServerPlayerHandler().handleHotbarSelect(sp, new HotbarSelectC2S(42), ctx);

        assertEquals(0, sp.inventory().selectedHotbarSlot(), "the bogus selection was dropped");
        verify(ctx, times(0)).broadcast(any(), anyBoolean());
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection()).send(captor.capture(), anyBoolean());
        assertInstanceOf(InventorySyncS2C.class, captor.getValue());
    }
}
