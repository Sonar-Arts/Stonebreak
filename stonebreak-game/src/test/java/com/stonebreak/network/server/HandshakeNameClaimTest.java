package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.ProtocolVersion;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.PlayerNames;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.world.save.serialization.JsonPlayerSerializer;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #335: a remote player joining with a name that is online used to replace that session
 * — the host's included, which then shut its own server down. The host is never replaced; a
 * live session is replaced only by a client presenting its join secret (or once its connection
 * is dead); a connection gets one handshake; names follow {@link PlayerNames}.
 */
class HandshakeNameClaimTest {

    private static ServerConnection connection(boolean local, boolean active) {
        ServerConnection conn = mock(ServerConnection.class);
        Channel channel = local ? mock(LocalChannel.class) : mock(Channel.class);
        when(conn.channel()).thenReturn(channel);
        when(conn.isActive()).thenReturn(active);
        return conn;
    }

    /** A rostered player, as completeHandshake leaves it. */
    private static ServerPlayer rostered(IntegratedServer server, String name, ServerConnection conn,
                                         String secret) {
        ServerPlayer sp = new ServerPlayer(conn, -1);
        sp.setUsername(name);
        sp.assignPlayerId(ServerWorldContext.stablePlayerIdFor(name));
        sp.setClientSecret(secret);
        sp.markHandshakeRequested();
        server.worldContext().addPlayer(sp);
        return sp;
    }

    private static ServerPlayer connecting(ServerConnection conn) {
        return new ServerPlayer(conn, -7);
    }

    private static void handshake(IntegratedServer server, ServerPlayer sp, String name, String secret) {
        server.handleHandshake(sp, new HandshakeC2S(ProtocolVersion.CURRENT, name, secret));
    }

    @Test
    void aRemoteUsingTheHostsNameIsRefusedAndTheHostStays() {
        IntegratedServer server = new IntegratedServer();
        ServerConnection hostConn = connection(true, true);
        ServerPlayer host = rostered(server, "Player", hostConn, null);

        ServerConnection remoteConn = connection(false, true);
        handshake(server, connecting(remoteConn), "Player", "");

        verify(remoteConn).sendAndClose(new KickS2C(NameClaims.HOST_NAME));
        verify(hostConn, never()).sendAndClose(any());
        assertSame(host, server.worldContext().player(host.playerId()), "the host keeps its session");
    }

    @Test
    void aLiveSessionIsNotReplacedWithoutItsSecret() {
        IntegratedServer server = new IntegratedServer();
        ServerConnection bobConn = connection(false, true);
        ServerPlayer bob = rostered(server, "Bob", bobConn, "s3cret");

        ServerConnection otherConn = connection(false, true);
        handshake(server, connecting(otherConn), "Bob", "guess");

        verify(otherConn).sendAndClose(new KickS2C(NameClaims.ALREADY_ONLINE));
        verify(bobConn, never()).sendAndClose(any());
        assertSame(bob, server.worldContext().player(bob.playerId()));
    }

    @Test
    void theSameClientPresentingItsSecretTakesItsSessionOver() {
        IntegratedServer server = new IntegratedServer();
        ServerConnection oldConn = connection(false, true);
        ServerPlayer old = rostered(server, "Bob", oldConn, "s3cret");

        ServerConnection newConn = connection(false, true);
        handshake(server, connecting(newConn), "Bob", "s3cret");

        verify(oldConn).sendAndClose(any(KickS2C.class));
        verify(newConn, never()).sendAndClose(any());
        assertNotEquals(old, server.worldContext().player(old.playerId()), "the stale session left the roster");
    }

    @Test
    void aDeadSessionMayBeTakenOverWithoutTheSecret() {
        IntegratedServer server = new IntegratedServer();
        ServerConnection deadConn = connection(false, false);
        ServerPlayer dead = rostered(server, "Bob", deadConn, "s3cret");

        ServerConnection newConn = connection(false, true);
        handshake(server, connecting(newConn), "Bob", "");

        verify(newConn, never()).sendAndClose(any());
        assertNotEquals(dead, server.worldContext().player(dead.playerId()));
    }

    @Test
    void theHostClaimsItsNameFromARemoteThatHeldIt() {
        IntegratedServer server = new IntegratedServer();
        ServerConnection remoteConn = connection(false, true);
        ServerPlayer remote = rostered(server, "Player", remoteConn, "s3cret");

        ServerConnection hostConn = connection(true, true);
        ServerPlayer host = connecting(hostConn);
        handshake(server, host, "Player", "");

        verify(remoteConn).sendAndClose(new KickS2C(NameClaims.HOST_NAME));
        verify(hostConn, never()).sendAndClose(any());
        assertSame(host, server.worldContext().player(remote.playerId()));
    }

    @Test
    void oneHandshakePerConnection() {
        IntegratedServer server = new IntegratedServer();
        ServerConnection bobConn = connection(false, true);
        rostered(server, "Bob", bobConn, "s3cret");

        ServerConnection conn = connection(false, true);
        ServerPlayer sp = connecting(conn);
        sp.markHandshakeRequested(); // its first handshake is still loading the saved record
        handshake(server, sp, "Bob", "s3cret");

        assertEquals("Player", sp.username(), "a repeated handshake is not processed at all");
        verify(bobConn, never()).sendAndClose(any());
        verify(conn, never()).sendAndClose(any());
    }

    @Test
    void namesOutsideTheRuleAreRefused() {
        IntegratedServer server = new IntegratedServer();
        for (String bad : new String[] {"[SERVER]", "Bob  Smith", " Bob", "", "a".repeat(25), "Bob\u0007"}) {
            ServerConnection conn = connection(false, true);
            handshake(server, connecting(conn), bad, "");
            verify(conn).sendAndClose(new KickS2C("Invalid name. " + PlayerNames.RULE));
        }
    }

    @Test
    void theSecretIsKeptWhenPresentedAndReissuedOtherwise() {
        String stored = NameClaims.issueSecret();
        assertEquals(24, stored.length());
        assertSame(stored, NameClaims.secretFor(stored, stored));
        String rotated = NameClaims.secretFor("", stored);
        assertNotNull(rotated);
        assertNotEquals(stored, rotated, "a client without the secret gets a new one, not the stored one");
        assertNotEquals(rotated, NameClaims.secretFor(null, null), "secrets are random");
        assertTrue(NameClaims.secretMatches("abc", "abc"));
        assertTrue(!NameClaims.secretMatches("", "") && !NameClaims.secretMatches(null, "abc"),
                "an empty secret never matches");
    }

    @Test
    void theSecretIsSavedWithThePlayer() {
        ServerPlayer sp = new ServerPlayer(connection(false, true), 3);
        sp.setClientSecret("kept");
        JsonPlayerSerializer json = new JsonPlayerSerializer();
        byte[] blob = json.serialize(ServerPlayerDataFactory.build(sp, "w"));
        assertEquals("kept", json.deserialize(blob).getClientSecret());
    }

    @Test
    void aDifferentNameHashingToTheSameIdIsRefused() {
        ServerPlayer existing = new ServerPlayer(connection(false, true), 5);
        existing.setUsername("Alice");
        ServerPlayer newcomer = new ServerPlayer(connection(false, true), -1);
        newcomer.setUsername("Mallory");
        String refusal = NameClaims.takeoverRefusal(existing, newcomer);
        assertNotNull(refusal);
        assertTrue(refusal.contains("id collision"), refusal);
        assertNull(NameClaims.takeoverRefusal(existing, new ServerPlayer(connection(true, true), -1)),
                "the host is never refused");
    }
}
