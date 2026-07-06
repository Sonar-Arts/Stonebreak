package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.packet.player.PlayerStateS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Player-roster reliability across a re-host: a player's FIRST state report of a session
 * re-announces its join (non-droppable, real position) — peers who rostered it while it had
 * no reported position (host mid client-world rebuild) hold a figure parked at spawn and may
 * have lost the droppable state stream to their own join chunk burst. A manual resync
 * ({@code EntityResyncC2S}) re-sends the whole roster the same way.
 */
class PlayerRosterAnnounceTest {

    private final ServerPlayerHandler handler = new ServerPlayerHandler();

    private static ServerPlayer newPlayer(ServerConnection conn, int id, String name) {
        ServerPlayer sp = new ServerPlayer(conn, -1);
        sp.setUsername(name);
        sp.assignPlayerId(id);
        sp.markHandshakeDone();
        return sp;
    }

    @Test
    void firstStateReportReAnnouncesJoinNonDroppableThenStops() {
        ConnectionRegistry registry = mock(ConnectionRegistry.class);
        ServerWorldContext ctx = new ServerWorldContext(registry);
        ServerConnection hostConn = mock(ServerConnection.class);
        ServerPlayer host = newPlayer(hostConn, 42, "Host");
        ctx.addPlayer(host);

        handler.handlePlayerState(host, new PlayerStateC2S(100f, 70f, -5f, 90f, 0f, (byte) 0), ctx);

        ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
        ArgumentCaptor<Boolean> droppable = ArgumentCaptor.forClass(Boolean.class);
        verify(registry, times(2)).broadcastExcept(eq(hostConn), packets.capture(), droppable.capture());

        List<Packet> sent = packets.getAllValues();
        PlayerJoinS2C join = assertInstanceOf(PlayerJoinS2C.class, sent.get(0));
        assertEquals(42, join.playerId());
        assertEquals("Host", join.username());
        assertEquals(100f, join.x());
        assertEquals(70f, join.y());
        assertEquals(-5f, join.z());
        assertEquals(false, droppable.getAllValues().get(0), "re-announce join must be non-droppable");

        assertInstanceOf(PlayerStateS2C.class, sent.get(1));
        assertEquals(true, droppable.getAllValues().get(1), "state relay stays droppable");

        // Subsequent reports must NOT re-announce — only the droppable state relay.
        clearInvocations(registry);
        handler.handlePlayerState(host, new PlayerStateC2S(101f, 70f, -5f, 90f, 0f, (byte) 0), ctx);
        ArgumentCaptor<Packet> second = ArgumentCaptor.forClass(Packet.class);
        verify(registry, times(1)).broadcastExcept(eq(hostConn), second.capture(), anyBoolean());
        assertInstanceOf(PlayerStateS2C.class, second.getValue());
    }

    @Test
    void invalidFirstStateAnnouncesNothing() {
        ConnectionRegistry registry = mock(ConnectionRegistry.class);
        ServerWorldContext ctx = new ServerWorldContext(registry);
        ServerPlayer sp = newPlayer(mock(ServerConnection.class), 7, "Bad");
        ctx.addPlayer(sp);

        handler.handlePlayerState(sp, new PlayerStateC2S(0f, -9999f, 0f, 0f, 0f, (byte) 0), ctx);

        verify(registry, never()).broadcastExcept(any(), any(), anyBoolean());
        assertEquals(0L, sp.lastStateNs(), "rejected state must not mark the player as reported");
    }

    @Test
    void peerResyncResendsRosterWithSpawnFallbackForUnreportedPlayers() {
        ConnectionRegistry registry = mock(ConnectionRegistry.class);
        ServerWorldContext ctx = new ServerWorldContext(registry); // no ServerLevel → default spawn (0,80,0)

        ServerConnection requesterConn = mock(ServerConnection.class);
        ServerPlayer requester = newPlayer(requesterConn, 1, "Rejoiner");
        ServerPlayer reported = newPlayer(mock(ServerConnection.class), 2, "Host");
        reported.updateState(100f, 65f, -3f, 0f, 0f);
        reported.setHeldItemId(5);
        ServerPlayer unreported = newPlayer(mock(ServerConnection.class), 3, "Fresh");
        ctx.addPlayer(requester);
        ctx.addPlayer(reported);
        ctx.addPlayer(unreported);

        handler.onPeerResync(requester, ctx);

        // 2 joins + 2 held items, all non-droppable, none about the requester itself.
        ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
        verify(requesterConn, times(4)).send(packets.capture(), eq(false));

        List<PlayerJoinS2C> joins = packets.getAllValues().stream()
            .filter(PlayerJoinS2C.class::isInstance).map(PlayerJoinS2C.class::cast).toList();
        assertEquals(2, joins.size());
        assertTrue(joins.stream().noneMatch(j -> j.playerId() == 1), "requester must not receive itself");

        PlayerJoinS2C hostJoin = joins.stream().filter(j -> j.playerId() == 2).findFirst().orElseThrow();
        assertEquals(100f, hostJoin.x());
        assertEquals(65f, hostJoin.y());
        assertEquals(-3f, hostJoin.z());

        PlayerJoinS2C freshJoin = joins.stream().filter(j -> j.playerId() == 3).findFirst().orElseThrow();
        assertEquals(0f, freshJoin.x());
        assertEquals(80f, freshJoin.y(), "unreported players roster at the world spawn, not (0,0,0)");
        assertEquals(0f, freshJoin.z());

        List<PlayerHeldItemS2C> held = packets.getAllValues().stream()
            .filter(PlayerHeldItemS2C.class::isInstance).map(PlayerHeldItemS2C.class::cast).toList();
        assertEquals(2, held.size());
        assertEquals(5, held.stream().filter(h -> h.playerId() == 2).findFirst().orElseThrow().itemId());
    }
}
