package com.stonebreak.network;

import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.packet.player.PlayerLifeC2S;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.handlers.ServerPlayerHandler;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Dead players must not receive drop pickups (#357): the server learns deaths from the
 * reliable {@code PlayerLifeC2S} edge and the nearest-player selection in
 * {@code MultiplayerSession.tryServerPickup} skips dead players, so death drops stay in the
 * world until the player respawns (away from them).
 */
class MultiplayerSessionPickupGateTest {

    private static final Vector3f DROP = new Vector3f(0f, 0f, 0f);

    private static ServerPlayer newPlayer(int id, String name, float x) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), -1);
        sp.setUsername(name);
        sp.assignPlayerId(id);
        sp.markHandshakeDone();
        sp.updateState(x, 0f, 0f, 0f, 0f);
        return sp;
    }

    private static void kill(ServerPlayer sp) {
        new ServerPlayerHandler().handlePlayerLife(sp, new PlayerLifeC2S(true, sp.x(), sp.y(), sp.z()));
    }

    @Test
    void deadNearestPlayerIsSkipped() {
        ServerPlayer dead = newPlayer(1, "Dead", 0f);
        kill(dead);
        ServerPlayer alive = newPlayer(2, "Alive", 1f); // farther, but alive

        assertSame(alive, MultiplayerSession.nearestEligiblePickupCandidate(
                List.of(dead, alive), DROP, 1.5f));
    }

    @Test
    void allDeadYieldsNoCandidate() {
        ServerPlayer dead = newPlayer(1, "Dead", 0f);
        kill(dead);

        assertNull(MultiplayerSession.nearestEligiblePickupCandidate(List.of(dead), DROP, 1.5f));
    }

    @Test
    void respawnedPlayerIsEligibleAgainAtItsNewPosition() {
        ServerPlayer sp = newPlayer(1, "Back", 0f);
        kill(sp);
        new ServerPlayerHandler().handlePlayerLife(sp, new PlayerLifeC2S(false, 50f, 0f, 0f));

        assertNull(MultiplayerSession.nearestEligiblePickupCandidate(List.of(sp), DROP, 1.5f),
                "respawned away from the corpse, so the death drop is out of range");
        assertSame(sp, MultiplayerSession.nearestEligiblePickupCandidate(
                List.of(sp), new Vector3f(50f, 0f, 0f), 1.5f));
    }

    @Test
    void nearestAlivePlayerStillSelected() {
        ServerPlayer a = newPlayer(1, "A", 1f);
        ServerPlayer b = newPlayer(2, "B", 0.5f); // nearest

        assertSame(b, MultiplayerSession.nearestEligiblePickupCandidate(List.of(a, b), DROP, 1.5f));
    }

    @Test
    void unreportedPlayerNeverWinsAtTheDefaultOrigin() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 1);
        sp.markHandshakeDone(); // no PlayerStateC2S yet: sits at (0,0,0) by default

        assertNull(MultiplayerSession.nearestEligiblePickupCandidate(List.of(sp), DROP, 1.5f));
    }
}
