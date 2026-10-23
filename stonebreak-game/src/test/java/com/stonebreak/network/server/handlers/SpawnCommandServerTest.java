package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /spawn runs ON THE SERVER (issue #341): the client forwards the line through chat, the
 * SERVER's cheats flag decides, and the mob is spawned into the server world — where it runs
 * its AI, replicates to every player and is saved — never into a client world.
 */
class SpawnCommandServerTest {

    private ServerPlayer sp;
    private ServerWorldContext ctx;
    private ServerLevel level;
    private EntityManager em;

    @BeforeEach
    void playerInAWorld() {
        sp = new ServerPlayer(mock(ServerConnection.class), 5);
        sp.updateState(0f, 64f, 0f, 0f, 0f); // looking along +X
        ctx = mock(ServerWorldContext.class);
        level = mock(ServerLevel.class);
        em = mock(EntityManager.class);
        when(ctx.serverLevel()).thenReturn(level);
        when(ctx.entityManager()).thenReturn(em);
    }

    private List<Packet> sent() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeastOnce()).send(captor.capture(), anyBoolean());
        return captor.getAllValues();
    }

    private boolean replied(String fragment) {
        return sent().stream().anyMatch(p -> p instanceof ChatMessageS2C m && m.text().contains(fragment));
    }

    @Test
    void spawnIsRefusedWithoutServerCheats() {
        when(level.cheatsEnabled()).thenReturn(false);

        boolean handled = new ServerCommandHandler().handleCommand(sp, "/spawn cow 3", ctx);

        assertTrue(handled, "the line was consumed as a command, not relayed as chat");
        verify(em, never()).spawnEntity(any(), any());
        assertTrue(replied("Cheats"));
    }

    @Test
    void spawnWithCheatsSpawnsIntoTheServerWorldInFrontOfThePlayer() {
        when(level.cheatsEnabled()).thenReturn(true);
        Entity cow = mock(Entity.class);
        when(em.spawnEntity(eq(EntityType.COW), any())).thenReturn(cow);

        new ServerCommandHandler().handleCommand(sp, "/spawn cow", ctx);

        ArgumentCaptor<Vector3f> at = ArgumentCaptor.forClass(Vector3f.class);
        verify(em).spawnEntity(eq(EntityType.COW), at.capture());
        assertEquals(3f, at.getValue().x, 1e-4f, "three blocks along the player's facing");
        assertEquals(0f, at.getValue().z, 1e-4f);
        verify(cow).setCommandSpawned(true);
        assertTrue(replied("Spawned 1x"));
    }

    @Test
    void spawnHonoursTheCountAndRejectsUnknownMobs() {
        when(level.cheatsEnabled()).thenReturn(true);
        when(em.spawnEntity(eq(EntityType.SHEEP), any())).thenReturn(mock(Entity.class));

        ServerCommandHandler commands = new ServerCommandHandler();
        commands.handleCommand(sp, "/spawn Sheep 4", ctx);
        commands.handleCommand(sp, "/spawn dragon", ctx);
        commands.handleCommand(sp, "/spawn sheep 51", ctx);

        verify(em, times(4)).spawnEntity(eq(EntityType.SHEEP), any());
        assertTrue(replied("Unknown mob: dragon"));
        assertTrue(replied("Count too large"));
    }
}
