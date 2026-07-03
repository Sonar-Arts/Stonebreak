package com.stonebreak.network;

import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stable player identity: the id is derived from the username, so the same player keeps the
 * same id across reconnects and server restarts. The roster must survive a session takeover —
 * a stale channel's late disconnect must not evict or de-announce the live session.
 */
class StablePlayerIdTest {

    @Test
    void sameUsernameAlwaysMapsToTheSameId() {
        int a = ServerWorldContext.stablePlayerIdFor("Chace");
        int b = ServerWorldContext.stablePlayerIdFor("Chace");
        assertEquals(a, b);
    }

    @Test
    void idsArePositiveAndDifferNamesDiffer() {
        int a = ServerWorldContext.stablePlayerIdFor("Alice");
        int b = ServerWorldContext.stablePlayerIdFor("Bob");
        assertTrue(a > 0, "stable ids must be positive (negatives are provisional/synthetic)");
        assertTrue(b > 0);
        assertNotEquals(a, b);
        assertTrue(ServerWorldContext.stablePlayerIdFor("") > 0, "empty name still yields a valid id");
    }

    @Test
    void staleSessionCannotEvictTakeoverFromRoster() {
        ServerWorldContext ctx = new ServerWorldContext(
            new com.openmason.engine.net.server.ConnectionRegistry());
        int id = ServerWorldContext.stablePlayerIdFor("Chace");

        ServerPlayer oldSession = new ServerPlayer(null, ctx.allocatePlayerId());
        oldSession.assignPlayerId(id);
        ctx.addPlayer(oldSession);

        // Takeover: the reconnect removes the old session and rosters the new one.
        ctx.removePlayer(oldSession);
        ServerPlayer newSession = new ServerPlayer(null, ctx.allocatePlayerId());
        newSession.assignPlayerId(id);
        ctx.addPlayer(newSession);

        // The stale channel's late DISCONNECT: instance-guarded removal must be a no-op.
        assertNotEquals(ctx.player(id), oldSession);
        ctx.removePlayer(oldSession);
        assertSame(newSession, ctx.player(id),
            "a stale session's disconnect must not evict the live takeover session");
    }

    @Test
    void provisionalConnectionIdsAreNegative() {
        ServerWorldContext ctx = new ServerWorldContext(
            new com.openmason.engine.net.server.ConnectionRegistry());
        assertTrue(ctx.allocatePlayerId() < 0,
            "pre-handshake ids must be negative so they can never collide with stable ids");
    }
}
