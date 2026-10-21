package com.stonebreak.network.client.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.sheep.Sheep;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * Regression tests for the buffered-spawn edges from issue #375: anim/variant packets that
 * arrive while their spawn sits in {@code pendingSpawns} must survive the drain, and a
 * mid-drain world swap must not lose a buffered record or its queued state.
 */
class ClientEntityHandlerTest {

    private static EntitySpawnS2C sheepSpawn(int netId) {
        return new EntitySpawnS2C(netId, EntityType.SHEEP.ordinal(), 1f, 2f, 3f, 0f, "default");
    }

    @Test
    void animAndVariantForBufferedSpawnAreAppliedOnDrain() {
        World world = mock(World.class);
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Game> game = mockStatic(Game.class)) {
            game.when(Game::getWorld).thenReturn(world);
            game.when(Game::getEntityManager).thenReturn(em);
            ClientEntityHandler handler = new ClientEntityHandler();

            game.when(Game::isClientWorldReady).thenReturn(false);
            handler.applySpawn(sheepSpawn(7));
            assertEquals(0, handler.trackedShadowCount());

            // Both packets arrive while the spawn is still buffered — neither may be dropped.
            handler.applyAnim(7, "Grazing");
            handler.applyVariant(7, "Sheared");

            game.when(Game::isClientWorldReady).thenReturn(true);
            handler.tick();
            ArgumentCaptor<Entity> added = ArgumentCaptor.forClass(Entity.class);
            verify(em).addEntity(added.capture());
            Sheep shadow = assertInstanceOf(Sheep.class, added.getValue());
            assertEquals(MobBehaviorState.GRAZING, shadow.getAI().getCurrentState());
            assertEquals("Sheared", shadow.getTextureVariant());
        }
    }

    /**
     * The world-build thread can flip readiness between ANY two reads the drain makes, so
     * every flip point is tried: the drain must never throw, never lose a record, and never
     * lose a record's queued anim — the remainder drains on a later tick.
     */
    @Test
    void aWorldSwapAtAnyPointOfTheDrainLosesNothing() {
        for (int readyReads = 1; readyReads <= 6; readyReads++) {
            World world = mock(World.class);
            EntityManager em = mock(EntityManager.class);
            try (MockedStatic<Game> game = mockStatic(Game.class)) {
                game.when(Game::getWorld).thenReturn(world);
                game.when(Game::getEntityManager).thenReturn(em);
                ClientEntityHandler handler = new ClientEntityHandler();

                game.when(Game::isClientWorldReady).thenReturn(false);
                handler.applySpawn(sheepSpawn(1));
                handler.applySpawn(sheepSpawn(2));
                handler.applySpawn(sheepSpawn(3));
                handler.applyAnim(3, "Grazing");

                // Ready for the first readyReads reads of this tick, then the world swaps.
                int[] reads = {0};
                int limit = readyReads;
                game.when(Game::isClientWorldReady).thenAnswer(inv -> reads[0]++ < limit);
                handler.tick();

                // The swap completes; whatever is still buffered drains with its state.
                game.when(Game::isClientWorldReady).thenReturn(true);
                handler.tick();
                ArgumentCaptor<Entity> added = ArgumentCaptor.forClass(Entity.class);
                verify(em, times(3)).addEntity(added.capture());
                assertEquals(3, handler.trackedShadowCount(), "flip after read " + readyReads);
                Sheep third = assertInstanceOf(Sheep.class, added.getAllValues().get(2));
                assertEquals(MobBehaviorState.GRAZING, third.getAI().getCurrentState(),
                        "flip after read " + readyReads);
            }
        }
    }

    @Test
    void despawnDropsAStillBufferedSpawn() {
        World world = mock(World.class);
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Game> game = mockStatic(Game.class)) {
            game.when(Game::getWorld).thenReturn(world);
            game.when(Game::getEntityManager).thenReturn(em);
            ClientEntityHandler handler = new ClientEntityHandler();

            game.when(Game::isClientWorldReady).thenReturn(false);
            handler.applySpawn(sheepSpawn(5));
            handler.applyDespawn(5);

            game.when(Game::isClientWorldReady).thenReturn(true);
            handler.tick();
            verify(em, never()).addEntity(any());
            assertEquals(0, handler.trackedShadowCount());
        }
    }

    @Test
    void duplicateBufferedSpawnForwardsQueuedStateToTheLiveShadow() {
        World world = mock(World.class);
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Game> game = mockStatic(Game.class)) {
            game.when(Game::getWorld).thenReturn(world);
            game.when(Game::getEntityManager).thenReturn(em);
            ClientEntityHandler handler = new ClientEntityHandler();

            game.when(Game::isClientWorldReady).thenReturn(false);
            handler.applySpawn(sheepSpawn(9));
            handler.applyAnim(9, "Grazing");

            // Before the buffered record drains, a duplicate spawn arrives while the world is
            // ready and creates the shadow directly — the drain must forward the queued anim
            // to it instead of discarding the whole record.
            game.when(Game::isClientWorldReady).thenReturn(true);
            handler.applySpawn(sheepSpawn(9));
            handler.tick();

            verify(em, times(1)).addEntity(any());
            ArgumentCaptor<Entity> added = ArgumentCaptor.forClass(Entity.class);
            verify(em).addEntity(added.capture());
            Sheep shadow = assertInstanceOf(Sheep.class, added.getValue());
            assertEquals(MobBehaviorState.GRAZING, shadow.getAI().getCurrentState());
        }
    }
}
