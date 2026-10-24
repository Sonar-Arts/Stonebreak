package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.packet.player.PlayerPositionS2C;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.server.handlers.ServerChatHandler;
import com.stonebreak.network.server.handlers.ServerPlayerHandler;
import io.netty.channel.local.LocalChannel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #336, game side: a remote player cannot teleport its server position around (the
 * movement limit), flood the chat relay, or receive world broadcasts before its handshake.
 */
class AbuseLimitsServerTest {

    private static final long TICK = 50_000_000L;

    private final ServerPlayerHandler handler = new ServerPlayerHandler();
    /** No level: cheats are off. */
    private final ServerWorldContext ctx = new ServerWorldContext(mock(ConnectionRegistry.class));

    private static ServerPlayer remotePlayer() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 1);
        sp.markHandshakeDone();
        sp.updateState(5f, 64f, 5f, 0f, 0f);
        return sp;
    }

    private static PlayerStateC2S at(float x, float z) {
        return new PlayerStateC2S(x, 64f, z, 0f, 0f, (byte) 0);
    }

    private static List<Packet> sent(ServerPlayer sp) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), org.mockito.Mockito.atLeast(0)).send(captor.capture(), anyBoolean());
        return captor.getAllValues();
    }

    // ─── Movement ──────────────────────────────────────────────────────────────

    @Test
    void teleportReportIsRefusedAndTheClientPutBack() {
        ServerPlayer sp = remotePlayer();
        long t = System.nanoTime();

        handler.handlePlayerState(sp, at(5000f, 5000f), ctx, t);

        assertEquals(5f, sp.x());
        assertEquals(5f, sp.z());
        PlayerPositionS2C back = assertInstanceOf(PlayerPositionS2C.class, sent(sp).get(0));
        assertEquals(new PlayerPositionS2C(5f, 64f, 5f), back);
    }

    @Test
    void correctionsAreRateLimitedWhileReportsInFlightKeepFailing() {
        ServerPlayer sp = remotePlayer();
        long t = System.nanoTime();

        for (int i = 0; i < 5; i++) {
            handler.handlePlayerState(sp, at(5000f + i, 5000f), ctx, t + i * TICK);
        }

        assertEquals(1, sent(sp).stream().filter(p -> p instanceof PlayerPositionS2C).count());
        assertEquals(5, sp.movement().refusals());
    }

    @Test
    void anyLegitimateSpeedIsAccepted() {
        ServerPlayer sp = remotePlayer();
        long t = System.nanoTime();
        float x = 5f;

        // 38 blocks/s for 10 s — faster than sprinting, swimming or dashing.
        for (int i = 1; i <= 200; i++) {
            x += 1.9f;
            handler.handlePlayerState(sp, at(x, 5f), ctx, t + i * TICK);
        }

        assertEquals(0, sp.movement().refusals());
        assertEquals(x, sp.x(), 1e-3);
        verify(sp.connection(), never()).send(isA(PlayerPositionS2C.class), anyBoolean());
    }

    @Test
    void sustainedSpeedPastTheLimitIsCaught() {
        ServerPlayer sp = remotePlayer();
        long t = System.nanoTime();
        float x = 5f;

        for (int i = 1; i <= 200; i++) {
            x += 4f; // 80 blocks/s
            handler.handlePlayerState(sp, at(x, 5f), ctx, t + i * TICK);
        }

        assertTrue(sp.movement().refusals() > 0);
        assertTrue(sp.x() < x - 100f, "the server position stayed far behind the claimed one");
    }

    @Test
    void shortAbilityTeleportFitsTheBurst() {
        ServerPlayer sp = remotePlayer();

        // Shadow Step: up to 12 blocks in one report.
        handler.handlePlayerState(sp, at(17f, 5f), ctx, System.nanoTime());

        assertEquals(17f, sp.x());
    }

    @Test
    void verticalMovementIsNotLimited() {
        ServerPlayer sp = remotePlayer();

        handler.handlePlayerState(sp, new PlayerStateC2S(5f, 200f, 5f, 0f, 0f, (byte) 0), ctx,
                System.nanoTime());

        assertEquals(200f, sp.y());
    }

    @Test
    void cheatWorldsAllowTeleports() {
        ServerPlayer sp = remotePlayer();
        ServerWorldContext cheats = mock(ServerWorldContext.class);
        when(cheats.cheatsEnabled()).thenReturn(true);

        handler.handlePlayerState(sp, at(5000f, 5000f), cheats, System.nanoTime());

        assertEquals(5000f, sp.x(), "/teleport is a cheat command");
    }

    @Test
    void theHostIsNeverLimited() {
        ServerConnection conn = mock(ServerConnection.class);
        when(conn.channel()).thenReturn(mock(LocalChannel.class));
        ServerPlayer host = new ServerPlayer(conn, 1);
        host.markHandshakeDone();
        host.updateState(5f, 64f, 5f, 0f, 0f);

        handler.handlePlayerState(host, at(5000f, 5000f), ctx, System.nanoTime());

        assertEquals(5000f, host.x());
    }

    @Test
    void firstReportMustBeNearTheSeededPosition() {
        ServerPlayer near = new ServerPlayer(mock(ServerConnection.class), 1);
        near.markHandshakeDone();
        near.seedTransform(100f, 70f, 100f, 0f, 0f); // its saved position
        handler.handlePlayerState(near, new PlayerStateC2S(110f, 90f, 95f, 0f, 0f, (byte) 0), ctx,
                System.nanoTime());
        assertEquals(110f, near.x(), "the client settling at its saved spot is accepted");

        ServerPlayer far = new ServerPlayer(mock(ServerConnection.class), 2);
        far.markHandshakeDone();
        far.seedTransform(100f, 70f, 100f, 0f, 0f);
        handler.handlePlayerState(far, at(90_000f, 100f), ctx, System.nanoTime());
        assertEquals(0L, far.lastStateNs(), "a first report elsewhere is not a position");
        assertEquals(new PlayerPositionS2C(100f, 70f, 100f), sent(far).get(0));
    }

    // ─── Chat ──────────────────────────────────────────────────────────────────

    @Test
    void chatFloodIsCutOffAndOnlyTheSenderIsTold() {
        ServerPlayer sp = remotePlayer();
        ServerWorldContext chatCtx = mock(ServerWorldContext.class);
        ServerChatHandler chat = new ServerChatHandler();

        for (int i = 0; i < 20; i++) {
            chat.handleChat(sp, new ChatMessageC2S("spam " + i), chatCtx);
        }

        verify(chatCtx, times(8)).broadcast(isA(ChatMessageS2C.class), anyBoolean());
        assertTrue(sent(sp).stream().anyMatch(p -> p instanceof ChatMessageS2C m
                && m.text().contains("too fast")));
    }

    // ─── Broadcast filter ──────────────────────────────────────────────────────

    @Test
    void connectionsWithoutAPlayerOrHandshakeGetNoBroadcasts() {
        ServerConnection stranger = new ServerConnection(new io.netty.channel.embedded.EmbeddedChannel());
        assertFalse(IntegratedServer.inWorld(stranger), "not attached yet: not in the world");

        ServerPlayer handshaking = new ServerPlayer(stranger, 1);
        stranger.setAttachment(handshaking);
        assertFalse(IntegratedServer.inWorld(stranger));

        handshaking.markHandshakeDone();
        assertTrue(IntegratedServer.inWorld(stranger));
    }
}
