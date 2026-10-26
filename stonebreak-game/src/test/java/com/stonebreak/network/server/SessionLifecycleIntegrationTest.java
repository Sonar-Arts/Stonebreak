package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.ProtocolVersion;
import com.openmason.engine.net.protocol.codec.WireCompression;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KeepAliveC2S;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.network.packet.handshake.NeedsCharacterCreationS2C;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
import com.stonebreak.network.packet.player.PlayerLeaveS2C;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #347: two real TCP clients against a real {@link IntegratedServer} through the session's
 * edges — a disconnect while chunks stream, the host quitting, a rejoin over a live session, the
 * silence timeout and a socket that never handshakes. Every test boots its own server.
 */
@Tag("integration")
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class SessionLifecycleIntegrationTest {

    @TempDir
    Path home;

    @BeforeEach
    void isolated() {
        assumeTrue(ServerHarness.isolatedHomeAvailable(), "worlds would land in XDG_DATA_HOME");
    }

    private static int idOf(String name) {
        return ServerWorldContext.stablePlayerIdFor(name);
    }

    @Test
    void twoPlayersSeeEachOtherJoin() throws Exception {
        try (ServerHarness server = ServerHarness.boot(home, "two-347");
             HarnessClient ann = HarnessClient.join(server, "Ann");
             HarnessClient ben = HarnessClient.join(server, "Ben")) {
            assertTrue(ann.await(PlayerJoinS2C.class, j -> j.playerId() == idOf("Ben"), HarnessClient.PROMPT)
                    .isPresent(), "the one already in hears of the newcomer");
            assertTrue(ben.await(PlayerJoinS2C.class, j -> j.playerId() == idOf("Ann"), HarnessClient.PROMPT)
                    .isPresent(), "the newcomer gets the roster");
            assertEquals(2, server.rosterSize());
        }
    }

    @Test
    void aDisconnectWhileChunksStreamLeavesTheServerHealthyAndThePlayerSaved() throws Exception {
        try (ServerHarness server = ServerHarness.boot(home, "stream-347");
             HarnessClient ben = HarnessClient.join(server, "Ben")) {
            HarnessClient ann = HarnessClient.join(server, "Ann");
            ann.reportAtSpawn();
            assertTrue(ann.await(ChunkDataS2C.class, HarnessClient.PROMPT).isPresent(), "streaming started");

            ann.close(); // drops the socket mid-stream, no DisconnectC2S

            assertTrue(ben.await(PlayerLeaveS2C.class, l -> l.playerId() == idOf("Ann"), HarnessClient.PROMPT)
                    .isPresent(), "the others are told");
            assertTrue(ServerHarness.await(() -> rosterSize(server) == 1, HarnessClient.PROMPT));
            assertTrue(ServerHarness.await(() -> saved(server, "Ann"), HarnessClient.PROMPT),
                    "the leaver's record was persisted");
            assertNull(server.tickFailure(), "no tick failed while the stream was cut");
            assertTrue(ben.isConnected());
        }
    }

    @Test
    void theHostQuittingClosesEveryClientAndPersistsTheirPlayers() throws Exception {
        HarnessClient ann;
        HarnessClient ben;
        try (ServerHarness server = ServerHarness.boot(home, "quit-347")) {
            ann = HarnessClient.join(server, "Ann");
            ben = HarnessClient.join(server, "Ben");
        } // the host quits: shutdown persists the connected players and closes the listener
        try {
            assertTrue(ann.awaitDisconnect(HarnessClient.PROMPT));
            assertTrue(ben.awaitDisconnect(HarnessClient.PROMPT));
        } finally {
            ann.close();
            ben.close();
        }

        try (ServerHarness again = ServerHarness.boot(home, "quit-347");
             HarnessClient back = HarnessClient.connect(again)) {
            back.send(new HandshakeC2S(ProtocolVersion.CURRENT, "Ann", "", WireCompression.localDecodeMask()));
            assertTrue(back.await(WelcomeS2C.class, HarnessClient.PROMPT).isPresent());
            assertTrue(back.received(NeedsCharacterCreationS2C.class).isEmpty(),
                    "the saved character came back — no new one was asked for");
        }
    }

    @Test
    void aRejoinPresentingTheSecretTakesTheLiveSessionOver() throws Exception {
        try (ServerHarness server = ServerHarness.boot(home, "rejoin-347");
             HarnessClient watcher = HarnessClient.join(server, "Ben");
             HarnessClient old = HarnessClient.join(server, "Ann")) {
            String secret = old.await(WelcomeS2C.class, HarnessClient.PROMPT).orElseThrow().clientSecret();

            try (HarnessClient fresh = HarnessClient.join(server, "Ann", secret)) {
                KickS2C kick = old.await(KickS2C.class, HarnessClient.PROMPT).orElseThrow();
                assertEquals("You logged in from another location.", kick.reason());
                assertTrue(old.awaitDisconnect(HarnessClient.PROMPT));
                assertTrue(fresh.isConnected());
                assertTrue(fresh.received(NeedsCharacterCreationS2C.class).isEmpty(),
                        "the new session continues the live record");
                assertEquals(2, server.rosterSize(), "one Ann, one Ben");
                assertTrue(watcher.await(PlayerLeaveS2C.class, l -> l.playerId() == idOf("Ann"),
                        HarnessClient.PROMPT).isPresent(), "the others drop the stale figure");
            }
        }
    }

    @Test
    @Tag("slow")
    void aSilentClientIsKickedWhileAChattyOneStays() throws Exception {
        long silence = Duration.ofSeconds(1).toNanos();
        try (ServerHarness server = ServerHarness.boot(home, "silence-347",
                ServerHarness.limits(5_000, 16L << 20), silence, 20);
             HarnessClient quiet = HarnessClient.join(server, "Quiet");
             HarnessClient chatty = HarnessClient.join(server, "Chatty")) {
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < until && quiet.isConnected()) {
                chatty.send(new KeepAliveC2S(0L)); // any inbound packet counts as a sign of life
                Thread.sleep(200);
            }

            assertTrue(quiet.awaitDisconnect(Duration.ofSeconds(5)), "the silent peer timed out");
            assertTrue(chatty.isConnected(), "the live peer stays");
            assertNull(server.player("Quiet"));
            assertNotNull(server.player("Chatty"));
        }
    }

    @Test
    @Tag("slow")
    void aSocketThatNeverHandshakesIsClosed() throws Exception {
        try (ServerHarness server = ServerHarness.boot(home, "admission-347",
                ServerHarness.limits(500, 16L << 20), IntegratedServer.INBOUND_SILENCE_TIMEOUT_NS,
                IntegratedServer.KEEPALIVE_INTERVAL_TICKS);
             HarnessClient stranger = HarnessClient.connect(server)) {
            assertTrue(stranger.awaitDisconnect(Duration.ofSeconds(5)), "closed at the admission deadline");
            assertEquals(0, server.rosterSize());
            assertFalse(stranger.isConnected());
        }
    }

    private static int rosterSize(ServerHarness server) {
        try {
            return server.rosterSize();
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean saved(ServerHarness server, String name) {
        try {
            return server.savedPlayer(name) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
