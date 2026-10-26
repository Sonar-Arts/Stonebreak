package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.ProtocolVersion;
import com.openmason.engine.net.protocol.codec.WireCompression;
import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.network.packet.handshake.NeedsCharacterCreationS2C;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.network.packet.player.CharacterCreationC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.player.PlayerHealthS2C;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
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
 * Issue #347: the join flow against a real {@link IntegratedServer} over TCP — the handshake, the
 * version kick, name rules and claims, one handshake per connection, and the character-creation
 * checks. Each test uses its own names, so the one server is shared by the class.
 */
@Tag("integration")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ServerHandshakeIntegrationTest {

    /** Long enough that a refusal would have arrived if one were coming. */
    private static final Duration QUIET = Duration.ofMillis(500);

    @TempDir
    static Path home;
    private static ServerHarness server;

    @BeforeAll
    static void boot() throws Exception {
        assumeTrue(ServerHarness.isolatedHomeAvailable(), "worlds would land in XDG_DATA_HOME");
        server = ServerHarness.boot(home, "handshake-347");
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (server != null) {
            server.close();
            assertNull(server.tickFailure(), "every tick completed");
        }
    }

    private static HandshakeC2S handshake(int version, String name) {
        return new HandshakeC2S(version, name, "", WireCompression.localDecodeMask());
    }

    @Test
    void aFreshPlayerCreatesACharacterAndIsWelcomedIntoTheWorld() throws Exception {
        try (HarnessClient c = HarnessClient.connect(server)) {
            c.send(handshake(ProtocolVersion.CURRENT, "Ava"));

            NeedsCharacterCreationS2C ask = c.await(NeedsCharacterCreationS2C.class, HarnessClient.PROMPT)
                    .orElseThrow();
            assertEquals(ServerWorldContext.stablePlayerIdFor("Ava"), ask.playerId());
            assertTrue(c.received(WelcomeS2C.class).isEmpty(), "no welcome before the character exists");

            c.send(new CharacterCreationC2S(HarnessClient.creationJson()));

            WelcomeS2C welcome = c.await(WelcomeS2C.class, HarnessClient.PROMPT).orElseThrow();
            assertEquals(ask.playerId(), welcome.playerId());
            assertFalse(welcome.clientSecret().isEmpty(), "a join secret is issued (#335)");
            assertTrue(c.await(InventorySyncS2C.class, HarnessClient.PROMPT).isPresent(), "the inventory follows");
            assertTrue(c.await(PlayerHealthS2C.class, h -> h.playerId() == welcome.playerId(),
                    HarnessClient.PROMPT).isPresent(), "and the server-owned health (#343)");
            ServerPlayer sp = server.player("Ava");
            assertNotNull(sp);
            assertTrue(server.onTick(sp::handshakeDone));
        }
    }

    @Test
    void aClientOnAnotherProtocolVersionIsKickedAndClosed() throws Exception {
        try (HarnessClient c = HarnessClient.connect(server)) {
            c.send(handshake(ProtocolVersion.CURRENT - 1, "OldClient"));

            KickS2C kick = c.await(KickS2C.class, HarnessClient.PROMPT).orElseThrow();
            assertTrue(kick.reason().contains("Protocol mismatch"), kick.reason());
            assertTrue(c.awaitDisconnect(HarnessClient.PROMPT), "the connection is closed");
            assertNull(server.player("OldClient"));
        }
    }

    @Test
    void aNameOutsideTheRuleIsKicked() throws Exception {
        try (HarnessClient c = HarnessClient.connect(server)) {
            c.send(handshake(ProtocolVersion.CURRENT, "no spaces!"));

            assertTrue(c.await(KickS2C.class, HarnessClient.PROMPT).isPresent());
            assertTrue(c.awaitDisconnect(HarnessClient.PROMPT));
            assertNull(server.player("no spaces!"));
        }
    }

    @Test
    void aSecondSessionUnderALiveNameIsRefusedAndTheFirstStays() throws Exception {
        try (HarnessClient first = HarnessClient.join(server, "Bea");
             HarnessClient second = HarnessClient.connect(server)) {
            second.send(handshake(ProtocolVersion.CURRENT, "Bea")); // no secret

            KickS2C kick = second.await(KickS2C.class, HarnessClient.PROMPT).orElseThrow();
            assertEquals(NameClaims.ALREADY_ONLINE, kick.reason());
            assertTrue(second.awaitDisconnect(HarnessClient.PROMPT));
            assertTrue(first.isConnected(), "the live session is untouched");
            assertTrue(first.received(KickS2C.class).isEmpty());
        }
    }

    @Test
    void aSecondHandshakeOnTheSameConnectionIsIgnored() throws Exception {
        try (HarnessClient c = HarnessClient.join(server, "Cy")) {
            c.send(handshake(ProtocolVersion.CURRENT, "Dee"));

            Thread.sleep(QUIET.toMillis());
            assertEquals(1, c.received(WelcomeS2C.class).size(), "one welcome");
            assertTrue(c.received(KickS2C.class).isEmpty());
            assertTrue(c.isConnected());
            assertNull(server.player("Dee"), "the connection was not rostered a second time");
            assertNotNull(server.player("Cy"));
        }
    }

    @Test
    void aForgedCharacterIsRefusedBeforeItEntersTheWorld() throws Exception {
        try (HarnessClient c = HarnessClient.connect(server)) {
            c.send(handshake(ProtocolVersion.CURRENT, "Eve"));
            c.await(NeedsCharacterCreationS2C.class, HarnessClient.PROMPT).orElseThrow();
            String forged = new String(HarnessClient.creationJson(), StandardCharsets.UTF_8)
                    .replace("\"remainingAp\":0", "\"remainingAp\":3"); // 3 ability points over budget

            c.send(new CharacterCreationC2S(forged.getBytes(StandardCharsets.UTF_8)));

            assertTrue(c.await(KickS2C.class, HarnessClient.PROMPT).isPresent());
            assertTrue(c.awaitDisconnect(HarnessClient.PROMPT));
            assertTrue(c.received(WelcomeS2C.class).isEmpty(), "never welcomed");
        }
    }

    @Test
    void aCharacterNobodyAskedForIsIgnored() throws Exception {
        try (HarnessClient c = HarnessClient.join(server, "Finn")) {
            c.send(new CharacterCreationC2S(HarnessClient.creationJson()));

            Thread.sleep(QUIET.toMillis());
            assertEquals(1, c.received(WelcomeS2C.class).size(), "no second welcome");
            assertTrue(c.isConnected());
        }
    }

    @Test
    void packetsBeforeTheHandshakeReachNoHandler() throws Exception {
        try (HarnessClient listener = HarnessClient.join(server, "Gus");
             HarnessClient stranger = HarnessClient.connect(server)) {
            stranger.send(new ChatMessageC2S("hello before the handshake"));
            stranger.send(handshake(ProtocolVersion.CURRENT, "Hal"));
            stranger.await(NeedsCharacterCreationS2C.class, HarnessClient.PROMPT).orElseThrow();

            Thread.sleep(QUIET.toMillis());
            assertTrue(listener.received(ChatMessageS2C.class).stream()
                    .noneMatch(m -> m.text().contains("before the handshake")), "the early chat was dropped");
        }
    }
}
