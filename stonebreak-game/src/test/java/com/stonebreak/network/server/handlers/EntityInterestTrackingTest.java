package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.network.packet.entity.EntityDespawnS2C;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #346: interest is only re-judged when a chunk distance changes — a player entering
 * another chunk, or an entity whose broadcast base did — and still spawns/despawns exactly as
 * the full every-tick scan did.
 */
class EntityInterestTrackingTest {

    private static final int VIEW = 4;
    private static final float CHUNK = 16f;

    private final List<ServerPlayer> roster = new ArrayList<>();
    private final ServerWorldContext ctx = mock(ServerWorldContext.class);
    private final ServerEntityHandler handler = new ServerEntityHandler();

    @BeforeEach
    void setUp() {
        when(ctx.players()).thenReturn(roster);
    }

    private ServerPlayer join(int id, float x, float z) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), id);
        sp.markHandshakeDone();
        sp.setViewDistanceChunks(VIEW);
        sp.updateState(x, 64f, z, 0f, 0f);
        roster.add(sp);
        handler.onPeerJoined(sp, ctx);
        return sp;
    }

    private static long count(ServerPlayer sp, Class<? extends Packet> type, int networkId) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeast(0)).send(captor.capture(), anyBoolean());
        return captor.getAllValues().stream().filter(p -> type.isInstance(p) && switch (p) {
            case EntitySpawnS2C s -> s.networkId() == networkId;
            case EntityDespawnS2C d -> d.networkId() == networkId;
            default -> false;
        }).count();
    }

    private Cow cowAt(float x, float z) {
        Cow cow = new Cow(mock(World.class), new Vector3f(x, 64f, z));
        handler.onEntitySpawned(cow, ctx);
        return cow;
    }

    private void walk(Cow cow, float toX, int ticks) {
        float from = cow.getX();
        for (int i = 1; i <= ticks; i++) {
            cow.setPosition(from + (toX - from) * i / ticks, 64f, cow.getZ());
            handler.tick(ctx);
        }
    }

    @Test
    void anEntityWalkingIntoViewIsSpawnedForAStillPlayer() {
        ServerPlayer sp = join(1, 8f, 8f);
        Cow cow = cowAt((VIEW + 3) * CHUNK + 8f, 8f);
        handler.tick(ctx);
        assertEquals(0, count(sp, EntitySpawnS2C.class, cow.getNetworkId()));

        walk(cow, (VIEW - 1) * CHUNK + 8f, 120);
        handler.tick(ctx); // the crossing is judged on the next pass
        assertEquals(1, count(sp, EntitySpawnS2C.class, cow.getNetworkId()));

        walk(cow, (VIEW + 4) * CHUNK + 8f, 160); // past the exit margin
        handler.tick(ctx);
        assertEquals(1, count(sp, EntityDespawnS2C.class, cow.getNetworkId()));
    }

    @Test
    void aPlayerLeavingAndReturningDespawnsAndRespawns() {
        ServerPlayer sp = join(1, 8f, 8f);
        Cow cow = cowAt(24f, 8f);
        assertEquals(1, count(sp, EntitySpawnS2C.class, cow.getNetworkId()));

        sp.updateState(8f + (VIEW + 2) * CHUNK, 64f, 8f, 0f, 0f); // inside the exit margin
        handler.tick(ctx);
        assertEquals(0, count(sp, EntityDespawnS2C.class, cow.getNetworkId()));

        sp.updateState(8f + (VIEW + 4) * CHUNK, 64f, 8f, 0f, 0f);
        handler.tick(ctx);
        assertEquals(1, count(sp, EntityDespawnS2C.class, cow.getNetworkId()));

        sp.updateState(8f, 64f, 8f, 0f, 0f);
        handler.tick(ctx);
        assertEquals(2, count(sp, EntitySpawnS2C.class, cow.getNetworkId()));
    }

    @Test
    void aStillEntityAndStillPlayerAreNotReSent() {
        ServerPlayer sp = join(1, 8f, 8f);
        Cow cow = cowAt(24f, 8f);
        for (int i = 0; i < 50; i++) {
            handler.tick(ctx);
        }
        assertEquals(1, count(sp, EntitySpawnS2C.class, cow.getNetworkId()));
        assertEquals(0, count(sp, EntityDespawnS2C.class, cow.getNetworkId()));
    }

    @Test
    void eachPlayerIsJudgedFromItsOwnPosition() {
        ServerPlayer near = join(1, 8f, 8f);
        ServerPlayer far = join(2, 8f + 20 * CHUNK, 8f);
        Cow cow = cowAt(24f, 8f);
        assertEquals(1, count(near, EntitySpawnS2C.class, cow.getNetworkId()));
        assertEquals(0, count(far, EntitySpawnS2C.class, cow.getNetworkId()));

        walk(cow, 8f + 18 * CHUNK, 400);
        handler.tick(ctx);
        assertEquals(1, count(near, EntityDespawnS2C.class, cow.getNetworkId()));
        assertEquals(1, count(far, EntitySpawnS2C.class, cow.getNetworkId()));
    }

    @Test
    void aRejoiningSessionStartsFresh() {
        ServerPlayer old = join(7, 8f, 8f);
        Cow cow = cowAt(24f, 8f);
        assertEquals(1, count(old, EntitySpawnS2C.class, cow.getNetworkId()));

        roster.remove(old);
        ServerPlayer fresh = join(7, 8f, 8f); // same stable id, new connection
        assertEquals(1, count(fresh, EntitySpawnS2C.class, cow.getNetworkId()));

        walk(cow, 30f, 10);
        // Moves go to the session that holds the id now, not the retired one.
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(old.connection(), atLeast(0)).send(captor.capture(), anyBoolean());
        long oldMoves = captor.getAllValues().stream()
            .filter(p -> p instanceof com.stonebreak.network.packet.entity.EntityMoveS2C).count();
        assertEquals(0, oldMoves);
    }

    @Test
    void aDespawnedEntityInTheCrossingListIsNotSpawned() {
        ServerPlayer sp = join(1, 8f, 8f);
        Cow cow = cowAt((VIEW + 3) * CHUNK + 8f, 8f);
        cow.setPosition(24f, 64f, 8f);
        handler.tick(ctx); // the teleport into view lands it in the crossing list
        assertEquals(0, count(sp, EntitySpawnS2C.class, cow.getNetworkId()));

        handler.onEntityDespawned(cow, ctx);
        handler.tick(ctx);
        assertEquals(0, count(sp, EntitySpawnS2C.class, cow.getNetworkId()));
    }
}
