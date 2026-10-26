package com.stonebreak.network.server;

import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.packet.handshake.KeepAliveC2S;
import com.stonebreak.network.packet.player.PlayerLeaveS2C;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #347: abuse at the real server's edge. A packet flood is cut off by the inbound limit
 * (the flooder kicked, everyone else unaffected), and a client that stops reading is closed once
 * the server has queued more for it than the outbound cap — the server never buffers without bound.
 */
@Tag("integration")
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ServerLoadIntegrationTest {

    @TempDir
    Path home;

    @BeforeEach
    void isolated() {
        assumeTrue(ServerHarness.isolatedHomeAvailable(), "worlds would land in XDG_DATA_HOME");
    }

    @Test
    void aPacketFloodIsCutOffAndOnlyTheFlooderIsDropped() throws Exception {
        try (ServerHarness server = ServerHarness.boot(home, "flood-347");
             HarnessClient bystander = HarnessClient.join(server, "Calm");
             HarnessClient flooder = HarnessClient.join(server, "Flood")) {
            for (int i = 0; i < 20_000 && flooder.isConnected(); i++) {
                flooder.send(new KeepAliveC2S(i)); // far past 600 packets/s with a 2400 burst
            }

            // Only the close is asserted: the server sends a KickS2C first, but it closes with the
            // flood still unread in its receive buffer, so TCP resets the connection — and a reset
            // discards what the flooder had not read yet, the kick included.
            assertTrue(flooder.awaitDisconnect(HarnessClient.PROMPT), "the flooder was closed");
            assertTrue(bystander.await(PlayerLeaveS2C.class,
                    l -> l.playerId() == ServerWorldContext.stablePlayerIdFor("Flood"), HarnessClient.PROMPT)
                    .isPresent());
            assertTrue(bystander.isConnected(), "the flood cost the bystander nothing");
            assertNull(server.player("Flood"));
            assertNull(server.tickFailure());
        }
    }

    @Test
    void aClientThatNeverReadsIsClosedPastTheOutboundCap() throws Exception {
        long cap = 64 * 1024;
        try (ServerHarness server = ServerHarness.boot(home, "noread-347",
                ServerHarness.limits(5_000, cap), IntegratedServer.INBOUND_SILENCE_TIMEOUT_NS,
                IntegratedServer.KEEPALIVE_INTERVAL_TICKS);
             HarnessClient stuck = HarnessClient.join(server, "Stuck")) {
            stuck.net().connection().channel().config().setAutoRead(false); // stops reading, keeps the socket
            ServerPlayer sp = server.player("Stuck");
            String line = "x".repeat(200);

            // Far more than the socket buffers on both ends can hold: what is left queues on the
            // server until the cap closes the connection.
            for (int round = 0; round < 40 && server.player("Stuck") != null; round++) {
                server.onTick(() -> {
                    for (int i = 0; i < 2_000; i++) {
                        sp.send(new ChatMessageS2C(0, "Server", line), false);
                    }
                    return null;
                });
            }

            assertTrue(ServerHarness.await(() -> !sp.connection().isActive(), HarnessClient.PROMPT),
                    "the connection was closed");
            assertTrue(ServerHarness.await(() -> playerGone(server, "Stuck"), HarnessClient.PROMPT),
                    "and left the roster like any disconnect");
            assertNull(server.tickFailure());
        }
    }

    private static boolean playerGone(ServerHarness server, String name) {
        try {
            return server.player(name) == null;
        } catch (Exception e) {
            return false;
        }
    }
}
