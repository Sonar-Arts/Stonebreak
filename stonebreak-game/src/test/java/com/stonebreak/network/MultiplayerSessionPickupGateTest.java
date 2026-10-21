package com.stonebreak.network;

import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.network.server.handlers.ServerPlayerHandler;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Dead players must not receive drop pickups (#357): the server caches the client's
 * {@code PlayerStateFlags.DEAD} bit from the state stream and the nearest-player selection
 * in {@code MultiplayerSession.tryServerPickup} skips flagged players, so death drops stay
 * in the world until the player respawns.
 */
class MultiplayerSessionPickupGateTest {

    private static ServerPlayer newPlayer(int id, String name) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), -1);
        sp.setUsername(name);
        sp.assignPlayerId(id);
        sp.markHandshakeDone();
        sp.updateState(0f, 0f, 0f, 0f, 0f); // reported position at the drop
        return sp;
    }

    @Test
    void deadNearestPlayerIsSkipped() {
        ServerPlayer dead = newPlayer(1, "Dead");
        dead.cacheStateFlags((byte) PlayerStateFlags.DEAD);
        ServerPlayer alive = newPlayer(2, "Alive");
        alive.updateState(1f, 0f, 0f, 0f, 0f); // farther, but alive

        assertSame(alive, MultiplayerSession.nearestEligiblePickupCandidate(
                List.of(dead, alive), new Vector3f(0f, 0f, 0f), 1.5f));
    }

    @Test
    void allDeadYieldsNoCandidate() {
        ServerPlayer dead = newPlayer(1, "Dead");
        dead.cacheStateFlags((byte) PlayerStateFlags.DEAD);

        assertNull(MultiplayerSession.nearestEligiblePickupCandidate(
                List.of(dead), new Vector3f(0f, 0f, 0f), 1.5f));
    }

    @Test
    void alivePlayerStillSelected() {
        ServerPlayer a = newPlayer(1, "A");
        a.updateState(1f, 0f, 0f, 0f, 0f);
        ServerPlayer b = newPlayer(2, "B");
        b.updateState(0.5f, 0f, 0f, 0f, 0f); // nearest, no flags

        assertSame(b, MultiplayerSession.nearestEligiblePickupCandidate(
                List.of(a, b), new Vector3f(0f, 0f, 0f), 1.5f));
    }

    @Test
    void handlePlayerStateCachesFlags() {
        ServerPlayer sp = newPlayer(3, "Cached");
        ServerWorldContext ctx = new ServerWorldContext(mock(ConnectionRegistry.class));

        new ServerPlayerHandler().handlePlayerState(sp,
                new PlayerStateC2S(0f, 0f, 0f, 0f, 0f, (byte) PlayerStateFlags.DEAD), ctx);

        assertTrue(PlayerStateFlags.has(sp.stateFlags(), PlayerStateFlags.DEAD),
                "server must cache the DEAD bit from the state report");
        assertFalse(PlayerStateFlags.has(sp.stateFlags(), PlayerStateFlags.SPRINTING));
    }
}
