package com.stonebreak.network.server;

import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.mobs.entities.EntitySpawner;
import com.stonebreak.network.packet.player.PlayerLifeC2S;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.server.handlers.ServerPlayerHandler;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Server half of #357: the dead/alive state comes from the reliable {@code PlayerLifeC2S}
 * edges only, independent of the droppable state stream and of position validation, and
 * every roster loop that acts on a body reads it through {@link ServerPlayer#isLiveInWorld()}.
 */
class PlayerLifeStateTest {

    private final ServerPlayerHandler handler = new ServerPlayerHandler();

    private static ServerPlayer reportedPlayer(int id) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), id);
        sp.markHandshakeDone();
        sp.updateState(5f, 64f, 5f, 0f, 0f);
        return sp;
    }

    @Test
    void deathEdgeMarksDeadAtTheCorpse() {
        ServerPlayer sp = reportedPlayer(1);

        handler.handlePlayerLife(sp, new PlayerLifeC2S(true, 8f, 63f, 9f));

        assertTrue(sp.isDead());
        assertFalse(sp.isLiveInWorld());
        assertEquals(new Vector3f(8f, 63f, 9f), new Vector3f(sp.x(), sp.y(), sp.z()));
    }

    @Test
    void stateStreamNeverClearsDeath() {
        ServerPlayer sp = reportedPlayer(1);
        handler.handlePlayerLife(sp, new PlayerLifeC2S(true, 8f, 63f, 9f));

        handler.handlePlayerState(sp, new PlayerStateC2S(8f, 63f, 9f, 0f, 0f, (byte) 0),
                new ServerWorldContext(mock(ConnectionRegistry.class)));

        assertTrue(sp.isDead(), "only a respawn edge revives a player");
    }

    @Test
    void deathIsAppliedEvenWhenThePositionIsInvalid() {
        ServerPlayer sp = reportedPlayer(1);

        handler.handlePlayerLife(sp, new PlayerLifeC2S(true, Float.NaN, -500f, 0f));

        assertTrue(sp.isDead());
        assertEquals(new Vector3f(5f, 64f, 5f), new Vector3f(sp.x(), sp.y(), sp.z()),
                "an invalid position is ignored, not cached");
    }

    @Test
    void respawnEdgeMovesTheBodyOffTheCorpse() {
        ServerPlayer sp = reportedPlayer(1);
        handler.handlePlayerLife(sp, new PlayerLifeC2S(true, 8f, 63f, 9f));

        handler.handlePlayerLife(sp, new PlayerLifeC2S(false, 100f, 80f, 100f));

        assertTrue(sp.isLiveInWorld());
        assertEquals(new Vector3f(100f, 80f, 100f), new Vector3f(sp.x(), sp.y(), sp.z()));
    }

    @Test
    void redundantAliveEdgeKeepsTheReportedPosition() {
        ServerPlayer sp = reportedPlayer(1);

        // A dead save respawned on load reports "alive" while already alive.
        handler.handlePlayerLife(sp, new PlayerLifeC2S(false, 100f, 80f, 100f));

        assertTrue(sp.isLiveInWorld());
        assertEquals(new Vector3f(5f, 64f, 5f), new Vector3f(sp.x(), sp.y(), sp.z()));
    }

    @Test
    void deadPlayerAnchorDespawnsButNeverSpawns() {
        ServerPlayer alive = reportedPlayer(1);
        ServerPlayer dead = reportedPlayer(2);
        handler.handlePlayerLife(dead, new PlayerLifeC2S(true, 5f, 64f, 5f));
        ServerPlayer unreported = new ServerPlayer(mock(ServerConnection.class), 3);
        unreported.markHandshakeDone();

        List<EntitySpawner.SpawnAnchor> anchors =
                IntegratedServer.spawnAnchorsFor(List.of(alive, dead, unreported));

        assertEquals(2, anchors.size(), "the unreported player has no anchor at all");
        assertTrue(anchors.get(0).spawnsMobs());
        assertFalse(anchors.get(1).spawnsMobs());
    }
}
