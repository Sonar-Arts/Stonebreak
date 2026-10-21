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

    @Test
    void midDrainWorldSwapKeepsRemainingSpawnAndItsQueuedAnim() {
        World world = mock(World.class);
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Game> game = mockStatic(Game.class)) {
            game.when(Game::getWorld).thenReturn(world);
            game.when(Game::getEntityManager).thenReturn(em);
            ClientEntityHandler handler = new ClientEntityHandler();

            game.when(Game::isClientWorldReady).thenReturn(false);
            handler.applySpawn(sheepSpawn(1));
            handler.applySpawn(sheepSpawn(2));
            handler.applyAnim(2, "Grazing");

            // Ready on tick entry and for the first record, then the world swaps mid-drain:
            // the drain must stop before the second record can be re-buffered without its anim.
            game.when(Game::isClientWorldReady).thenReturn(true, true, true, false);
            handler.tick();
            verify(em, times(1)).addEntity(any());

            // The swap completes; the still-buffered record keeps its queued anim and drains.
            game.when(Game::isClientWorldReady).thenReturn(true);
            handler.tick();
            ArgumentCaptor<Entity> added = ArgumentCaptor.forClass(Entity.class);
            verify(em, times(2)).addEntity(added.capture());
            Sheep second = assertInstanceOf(Sheep.class, added.getAllValues().get(1));
            assertEquals(MobBehaviorState.GRAZING, second.getAI().getCurrentState());
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
