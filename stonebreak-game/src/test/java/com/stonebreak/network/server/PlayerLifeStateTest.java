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
    /** No level: the spawn is the default (0, 80, 0) and cheats are off. */
    private final ServerWorldContext ctx = new ServerWorldContext(mock(ConnectionRegistry.class));

    private static ServerPlayer reportedPlayer(int id) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), id);
        sp.markHandshakeDone();
        sp.updateState(5f, 64f, 5f, 0f, 0f);
        return sp;
    }

    @Test
    void deathEdgeMarksDeadAtTheCorpse() {
        ServerPlayer sp = reportedPlayer(1);

        handler.handlePlayerLife(sp, new PlayerLifeC2S(true, 8f, 63f, 9f), ctx);

        assertTrue(sp.isDead());
        assertFalse(sp.isLiveInWorld());
        assertEquals(new Vector3f(8f, 63f, 9f), new Vector3f(sp.x(), sp.y(), sp.z()));
    }

    @Test
    void stateStreamNeverClearsDeath() {
        ServerPlayer sp = reportedPlayer(1);
        handler.handlePlayerLife(sp, new PlayerLifeC2S(true, 8f, 63f, 9f), ctx);

        handler.handlePlayerState(sp, new PlayerStateC2S(8f, 63f, 9f, 0f, 0f, (byte) 0), ctx);

        assertTrue(sp.isDead(), "only a respawn edge revives a player");
    }

    @Test
    void deathIsAppliedEvenWhenThePositionIsInvalid() {
        ServerPlayer sp = reportedPlayer(1);

        handler.handlePlayerLife(sp, new PlayerLifeC2S(true, Float.NaN, -500f, 0f), ctx);

        assertTrue(sp.isDead());
        assertEquals(new Vector3f(5f, 64f, 5f), new Vector3f(sp.x(), sp.y(), sp.z()),
                "an invalid position is ignored, not cached");
    }

    @Test
    void respawnEdgeMovesTheBodyOffTheCorpse() {
        ServerPlayer sp = reportedPlayer(1);
        handler.handlePlayerLife(sp, new PlayerLifeC2S(true, 8f, 63f, 9f), ctx);

        handler.handlePlayerLife(sp, new PlayerLifeC2S(false, 2f, 81f, -3f), ctx); // at the spawn

        assertTrue(sp.isLiveInWorld());
        assertEquals(new Vector3f(2f, 81f, -3f), new Vector3f(sp.x(), sp.y(), sp.z()));
    }

    @Test
    void respawnAwayFromTheWorldSpawnIsRefusedAndCorrected() {
        ServerPlayer sp = reportedPlayer(1);
        handler.handlePlayerLife(sp, new PlayerLifeC2S(true, 8f, 63f, 9f), ctx);

        // A respawn is the one teleport a client may report; it must land at the spawn (#336).
        handler.handlePlayerLife(sp, new PlayerLifeC2S(false, 5000f, 80f, 5000f), ctx);

        assertTrue(sp.isLiveInWorld(), "the life state still follows the edge");
        assertEquals(new Vector3f(8f, 63f, 9f), new Vector3f(sp.x(), sp.y(), sp.z()));
        org.mockito.Mockito.verify(sp.connection()).send(
                org.mockito.ArgumentMatchers.isA(com.stonebreak.network.packet.player.PlayerPositionS2C.class),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void deathFarFromThePlayerKeepsTheLastAcceptedPosition() {
        ServerPlayer sp = reportedPlayer(1);

        handler.handlePlayerLife(sp, new PlayerLifeC2S(true, 9000f, 63f, 9000f), ctx);

        assertTrue(sp.isDead());
        assertEquals(new Vector3f(5f, 64f, 5f), new Vector3f(sp.x(), sp.y(), sp.z()),
                "a death is a move like any other: a teleport to die elsewhere is refused");
    }

    @Test
    void redundantAliveEdgeKeepsTheReportedPosition() {
        ServerPlayer sp = reportedPlayer(1);

        // A dead save respawned on load reports "alive" while already alive.
        handler.handlePlayerLife(sp, new PlayerLifeC2S(false, 100f, 80f, 100f), ctx);

        assertTrue(sp.isLiveInWorld());
        assertEquals(new Vector3f(5f, 64f, 5f), new Vector3f(sp.x(), sp.y(), sp.z()));
    }

    @Test
    void deadPlayerAnchorDespawnsButNeverSpawns() {
        ServerPlayer alive = reportedPlayer(1);
        ServerPlayer dead = reportedPlayer(2);
        handler.handlePlayerLife(dead, new PlayerLifeC2S(true, 5f, 64f, 5f), ctx);
        ServerPlayer unreported = new ServerPlayer(mock(ServerConnection.class), 3);
        unreported.markHandshakeDone();

        List<EntitySpawner.SpawnAnchor> anchors =
                IntegratedServer.spawnAnchorsFor(List.of(alive, dead, unreported));

        assertEquals(2, anchors.size(), "the unreported player has no anchor at all");
        assertTrue(anchors.get(0).spawnsMobs());
        assertFalse(anchors.get(1).spawnsMobs());
    }
}
