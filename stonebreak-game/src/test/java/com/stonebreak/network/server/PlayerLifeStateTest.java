package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.mobs.entities.EntitySpawner;
import com.stonebreak.network.packet.player.PlayerHealthS2C;
import com.stonebreak.network.packet.player.PlayerLifeC2S;
import com.stonebreak.network.packet.player.PlayerPositionS2C;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.server.handlers.ServerHealthHandler;
import com.stonebreak.network.server.handlers.ServerPlayerHandler;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Life and death are the server's (#343, superseding #357's client-reported edges): a blow that
 * empties the server-owned health kills, only a respawn revives — at the spot the server picks,
 * not the one the client names — and every roster loop that acts on a body reads the state
 * through {@link ServerPlayer#isLiveInWorld()}.
 */
class PlayerLifeStateTest {

    private final ServerPlayerHandler players = new ServerPlayerHandler();
    private final ServerHealthHandler health = new ServerHealthHandler();
    /** No level: the spawn is the default (0, 80, 0) and cheats are off. */
    private final ServerWorldContext ctx = new ServerWorldContext(mock(ConnectionRegistry.class));

    private static ServerPlayer reportedPlayer(int id) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), id);
        sp.markHandshakeDone();
        sp.updateState(5f, 64f, 5f, 0f, 0f);
        return sp;
    }

    private void kill(ServerPlayer sp) {
        health.hurt(sp, sp.health(), PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx);
    }

    private static List<Packet> sent(ServerPlayer sp) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeast(0)).send(captor.capture(), anyBoolean());
        return captor.getAllValues();
    }

    @Test
    void aBlowThatEmptiesTheHealthKillsWhereThePlayerStands() {
        ServerPlayer sp = reportedPlayer(1);

        kill(sp);

        assertTrue(sp.isDead());
        assertFalse(sp.isLiveInWorld());
        assertEquals(0f, sp.health());
        assertEquals(new Vector3f(5f, 64f, 5f), new Vector3f(sp.x(), sp.y(), sp.z()));
    }

    @Test
    void aBlowThatLeavesHealthLeavesThePlayerAlive() {
        ServerPlayer sp = reportedPlayer(1);

        float taken = health.hurt(sp, 3f, PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx);

        assertEquals(3f, taken);
        assertEquals(17f, sp.health());
        assertTrue(sp.isLiveInWorld());
    }

    @Test
    void theStateStreamNeverClearsDeath() {
        ServerPlayer sp = reportedPlayer(1);
        kill(sp);

        players.handlePlayerState(sp, new PlayerStateC2S(8f, 63f, 9f, 0f, 0f, (short) 0), ctx);

        assertTrue(sp.isDead(), "only a respawn revives a player");
    }

    @Test
    void theDeadTakeNoFurtherHits() {
        ServerPlayer sp = reportedPlayer(1);
        kill(sp);

        assertEquals(0f, health.hurt(sp, 5f, PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx));
    }

    @Test
    void aRespawnLandsAtTheWorldSpawnWithFullHealthWhateverTheRequestSays() {
        ServerPlayer sp = reportedPlayer(1);
        kill(sp);

        health.handleLife(sp, new PlayerLifeC2S(false, 5000f, 80f, 5000f), ctx, System.nanoTime());

        assertTrue(sp.isLiveInWorld());
        assertEquals(20f, sp.health());
        assertEquals(new Vector3f(0f, 80f, 0f), new Vector3f(sp.x(), sp.y(), sp.z()));
        assertTrue(sent(sp).contains(new PlayerPositionS2C(0f, 80f, 0f)), "the client is put there too");
    }

    @Test
    void aRespawnRequestFromALivingPlayerIsIgnored() {
        ServerPlayer sp = reportedPlayer(1);
        health.hurt(sp, 4f, PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx);

        health.handleLife(sp, new PlayerLifeC2S(false, 0f, 80f, 0f), ctx, System.nanoTime());

        assertEquals(new Vector3f(5f, 64f, 5f), new Vector3f(sp.x(), sp.y(), sp.z()), "no free teleport");
        assertEquals(16f, sp.health(), "and no free heal");
    }

    @Test
    void aDeadPlayerAnchorDespawnsButNeverSpawns() {
        ServerPlayer alive = reportedPlayer(1);
        ServerPlayer dead = reportedPlayer(2);
        kill(dead);
        ServerPlayer unreported = new ServerPlayer(mock(ServerConnection.class), 3);
        unreported.markHandshakeDone();

        List<EntitySpawner.SpawnAnchor> anchors =
                IntegratedServer.spawnAnchorsFor(List.of(alive, dead, unreported));

        assertEquals(2, anchors.size(), "the unreported player has no anchor at all");
        assertTrue(anchors.get(0).spawnsMobs());
        assertFalse(anchors.get(1).spawnsMobs());
    }
}
