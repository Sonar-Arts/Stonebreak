package com.stonebreak.mobs.entities;

import com.stonebreak.core.Game;
import com.stonebreak.core.services.GameServices;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Projectiles and zones find their targets in their OWN world's entity manager, and nowhere else
 * (#340). They used to fall back to {@code Game.getEntityManager()} when their world had none
 * bound — the client's render manager, read from the server tick thread, so a server-spawned
 * caltrop could trip over a client shadow. A world with no manager now means no scan at all.
 */
class OwningEntityManagerTest {

    private final TestWorld world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
    private final StubMob victim = new StubMob(EntityType.COW, new Vector3f(0, 64, 0), world);

    /** Stands in for the client's manager behind the Game singleton. */
    private final EntityManager gameManager = mock(EntityManager.class);
    private EntityManager previousGameManager;

    @BeforeEach
    void installGameManager() throws ReflectiveOperationException {
        GameServices services = gameServices();
        previousGameManager = services.entityManager();
        when(gameManager.getLivingEntities()).thenReturn(List.of(victim));
        when(gameManager.getEntitiesInRange(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyFloat())).thenReturn(List.of(victim));
        services.setEntityManager(gameManager);
    }

    @AfterEach
    void restoreGameManager() throws ReflectiveOperationException {
        gameServices().setEntityManager(previousGameManager);
    }

    private static GameServices gameServices() throws ReflectiveOperationException {
        Field field = Game.class.getDeclaredField("services");
        field.setAccessible(true);
        return (GameServices) field.get(Game.getInstance());
    }

    @Test
    void aWorldWithNoManagerIsNotPatchedWithTheGameSingletons() {
        assertNull(world.getEntityManager(), "precondition: nothing bound to this world");
        CaltropCluster caltrop = new CaltropCluster(world, new Vector3f(0, 64, 0), 10f);

        caltrop.update(0.05f);

        verifyNoInteractions(gameManager);
        assertTrue(caltrop.isAlive(), "nothing in its own world stepped on it");
        assertFalse(victim.hasStatusEffect(StatusEffectType.FLAT_FOOTED),
                "the Game singleton's mob was never touched");
    }

    @Test
    void aLeylineZoneInAnUnboundWorldLeavesTheGameSingletonAlone() {
        LeylineBreachZone zone = new LeylineBreachZone(world, new Vector3f(0, 64, 0),
                4f, 1f, 1f, 10f, false);

        zone.update(1.0f);

        verifyNoInteractions(gameManager);
    }

    /** Control: the same caltrop does work once its world knows its manager. */
    @Test
    void theOwningWorldsManagerIsScanned() {
        EntityManager owning = mock(EntityManager.class);
        when(owning.getLivingEntities()).thenReturn(List.of(victim));
        world.setEntityManager(owning);
        CaltropCluster caltrop = new CaltropCluster(world, new Vector3f(0, 64, 0), 10f);

        caltrop.update(0.05f);

        assertFalse(caltrop.isAlive(), "consumed by the mob standing on it");
        assertTrue(victim.hasStatusEffect(StatusEffectType.FLAT_FOOTED));
        verifyNoInteractions(gameManager);
    }
}
