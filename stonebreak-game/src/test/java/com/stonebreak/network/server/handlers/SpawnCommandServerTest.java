package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * /spawn runs ON THE SERVER: a mob spawned into the client world alone was invisible to
 * everything the server simulates — the player's arrows (server entities) flew through it.
 */
class SpawnCommandServerTest {

    private record Setup(ServerPlayer sp, ServerWorldContext ctx, EntityManager em) {}

    private static Setup setup(boolean cheats) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 5);
        sp.updateState(0f, 64f, 0f, 0f, 0f); // yaw 0, level: looking along +X
        World world = mock(World.class); // getBlockAt → null: open air
        Chunk chunk = mock(Chunk.class);
        when(chunk.areFeaturesPopulated()).thenReturn(true);
        when(world.getChunkIfLoaded(anyInt(), anyInt())).thenReturn(chunk);
        EntityManager em = new EntityManager(world);
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        ServerLevel level = mock(ServerLevel.class);
        when(ctx.serverLevel()).thenReturn(level);
        when(ctx.entityManager()).thenReturn(em);
        when(level.cheatsEnabled()).thenReturn(cheats);
        return new Setup(sp, ctx, em);
    }

    @Test
    void spawnPutsTheMobInTheServersWorldInFrontOfThePlayer() {
        Setup s = setup(true);

        assertTrue(new ServerCommandHandler().handleCommand(s.sp(), "/spawn goblin", s.ctx()));
        s.em().update(0.05f);

        assertEquals(1, s.em().getLivingEntities().size());
        Entity goblin = s.em().getLivingEntities().get(0);
        assertEquals(EntityType.GOBLIN, goblin.getType());
        assertTrue(goblin.isCommandSpawned());
        assertEquals(3f, goblin.getPosition().x, 0.5f, "three blocks along the look direction");
        s.em().cleanup();
    }

    @Test
    void spawnRefusedWithoutServerCheats() {
        Setup s = setup(false);

        assertTrue(new ServerCommandHandler().handleCommand(s.sp(), "/spawn goblin", s.ctx()));
        s.em().update(0.05f);

        assertEquals(0, s.em().getLivingEntities().size());
        s.em().cleanup();
    }
}
