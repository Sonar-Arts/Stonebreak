package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /obtain runs ON THE SERVER (issue #334): the client forwards the line through chat, the
 * SERVER's cheats flag decides, and items enter the server-owned replica — never a client
 * inventory write.
 */
class ObtainCommandServerTest {

    private static ServerPlayer newPlayer() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 5);
        sp.updateState(0f, 64f, 0f, 0f, 0f);
        return sp;
    }

    private List<Packet> sent(ServerPlayer sp) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeastOnce()).send(captor.capture(), anyBoolean());
        return captor.getAllValues();
    }

    @Test
    void obtainRefusedWithoutServerCheats() {
        ServerPlayer sp = newPlayer();
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        ServerLevel level = mock(ServerLevel.class);
        when(ctx.serverLevel()).thenReturn(level);
        when(level.cheatsEnabled()).thenReturn(false);

        boolean handled = new ServerCommandHandler().handleCommand(sp, "/obtain stone 5", ctx);

        assertTrue(handled, "the line was consumed as a command");
        assertEquals(0, sp.inventory().countOf(BlockType.STONE.getId()),
            "no item entered the replica");
        List<Packet> packets = sent(sp);
        assertTrue(packets.stream().anyMatch(p -> p instanceof ChatMessageS2C
                && ((ChatMessageS2C) p).text().contains("Cheats")));
        assertTrue(packets.stream().noneMatch(p -> p instanceof InventorySyncS2C));
    }

    @Test
    void obtainAddsViaServerInventoryAndSyncs() {
        ServerPlayer sp = newPlayer();
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        ServerLevel level = mock(ServerLevel.class);
        when(ctx.serverLevel()).thenReturn(level);
        when(level.cheatsEnabled()).thenReturn(true);

        boolean handled = new ServerCommandHandler().handleCommand(sp, "/obtain stone 5", ctx);

        assertTrue(handled);
        assertEquals(5, sp.inventory().countOf(BlockType.STONE.getId()),
            "the SERVER's replica received the items");
        List<Packet> packets = sent(sp);
        assertTrue(packets.stream().anyMatch(p -> p instanceof InventorySyncS2C),
            "the grant is synced back");
        assertTrue(packets.stream().anyMatch(p -> p instanceof ChatMessageS2C
                && ((ChatMessageS2C) p).text().contains("Gave")),
            "only the sender gets the confirmation");
    }

    @Test
    void unknownItemRefused() {
        ServerPlayer sp = newPlayer();
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        ServerLevel level = mock(ServerLevel.class);
        when(ctx.serverLevel()).thenReturn(level);
        when(level.cheatsEnabled()).thenReturn(true);

        assertTrue(new ServerCommandHandler().handleCommand(sp, "/obtain nonsense_item 1", ctx));
        assertEquals(0, sp.inventory().countOf(BlockType.STONE.getId()));
        assertTrue(sent(sp).stream().anyMatch(p -> p instanceof ChatMessageS2C
                && ((ChatMessageS2C) p).text().contains("Unknown item")));
    }
}
