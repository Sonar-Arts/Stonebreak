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
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

/**
 * The held item is DERIVED from the server's replica via HotbarSelectC2S (issue #334):
 * selecting an empty slot forces held = air, so the shears check and tool-specific drops
 * cannot be forged by a relayed item id.
 */
class HeldItemDerivedTest {

    @Test
    void hotbarSelectToEmptySlotForcesHeldAir() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 3);
        sp.markHandshakeDone();
        sp.inventory().add(ItemType.SHEARS.getId(), 1); // in hotbar slot 0
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        when(ctx.players()).thenReturn(List.of(sp));
        ServerPlayerHandler handler = new ServerPlayerHandler();

        handler.handleHotbarSelect(sp, new HotbarSelectC2S(0), ctx);
        assertEquals(ItemType.SHEARS.getId(), sp.inventory().heldItemId(),
            "slot 0 already selected: held derives from the replica");
        handler.broadcastHeldItemChanges(ctx);

        // Selecting the EMPTY slot 1: the held id collapses to air — no shears, no tool drops.
        handler.handleHotbarSelect(sp, new HotbarSelectC2S(1), ctx);
        assertEquals(0, sp.inventory().heldItemId());
        handler.broadcastHeldItemChanges(ctx);

        // The tick announces the derived held id once per change (issue #336).
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(ctx, times(2)).broadcast(captor.capture(), anyBoolean());
        PlayerHeldItemS2C first = assertInstanceOf(PlayerHeldItemS2C.class, captor.getAllValues().get(0));
        assertEquals(ItemType.SHEARS.getId(), first.itemId());
        PlayerHeldItemS2C change = assertInstanceOf(PlayerHeldItemS2C.class, captor.getAllValues().get(1));
        assertEquals(0, change.itemId(), "the empty slot cannot hold a forged tool id");
    }

    @Test
    void selectionFloodCostsOneBroadcastPerChangePerTick() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 3);
        sp.markHandshakeDone();
        sp.inventory().add(ItemType.SHEARS.getId(), 1); // in hotbar slot 0
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        when(ctx.players()).thenReturn(List.of(sp));
        ServerPlayerHandler handler = new ServerPlayerHandler();

        for (int i = 0; i < 500; i++) {
            handler.handleHotbarSelect(sp, new HotbarSelectC2S(i % 2), ctx);
        }
        verify(ctx, never()).broadcast(any(), anyBoolean());

        handler.broadcastHeldItemChanges(ctx); // one tick: the latest held item, once
        handler.broadcastHeldItemChanges(ctx); // nothing changed since
        verify(ctx, times(1)).broadcast(any(), anyBoolean());
    }

    @Test
    void playersStillHandshakingAreNotAnnounced() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 3);
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        when(ctx.players()).thenReturn(List.of(sp));

        new ServerPlayerHandler().broadcastHeldItemChanges(ctx);

        verify(ctx, never()).broadcast(any(), anyBoolean());
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
