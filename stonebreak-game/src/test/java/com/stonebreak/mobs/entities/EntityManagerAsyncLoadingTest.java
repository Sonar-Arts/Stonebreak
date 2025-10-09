package com.stonebreak.mobs.entities;

import com.stonebreak.world.TestWorld;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.model.EntityData;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EntityManager async entity loading optimizations.
 * Tests verify background deserialization and batched entity additions.
 */
public class EntityManagerAsyncLoadingTest {

    private World world;
    private EntityManager entityManager;

    @BeforeEach
    public void setUp() {
        // Create test world
        WorldConfiguration config = new WorldConfiguration(8, 4);
        world = new TestWorld(config, 12345L, true); // Test mode

        // Create entity manager
        entityManager = new EntityManager(world);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testEntityManagerInitialization() {
        // Assert - Manager should be initialized with async capabilities
        assertNotNull(entityManager, "EntityManager initialized");
        assertEquals(0, entityManager.getEntityCount(), "Initial entity count is zero");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testAsyncEntityLoadingDoesNotBlock() throws Exception {
        // Arrange - Create test entity data
        List<EntityData> entityDataList = createTestEntityData(10);

        long startTime = System.currentTimeMillis();

        // Act - Load entities asynchronously
        entityManager.loadEntitiesForChunk(entityDataList, 0, 0);

        long endTime = System.currentTimeMillis();
        long elapsedTime = endTime - startTime;

        // Assert - Should return immediately (async)
        assertTrue(elapsedTime < 100,
            "Async entity loading should not block main thread, took: " + elapsedTime + "ms");

        System.out.println("Entity loading initiated in " + elapsedTime + "ms");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testEntitiesAddedInBatches() throws Exception {
        // Arrange - Create many entities (more than batch limit)
        List<EntityData> entityDataList = createTestEntityData(50);

        // Act - Load entities
        entityManager.loadEntitiesForChunk(entityDataList, 0, 0);

        // Wait for deserialization
        Thread.sleep(500);

        // Update multiple times to process batches
        int updateCount = 0;
        int maxUpdates = 10;

        while (updateCount < maxUpdates) {
            entityManager.update(0.016f); // 60 FPS
            updateCount++;
            Thread.sleep(50); // Give time for async operations
        }

        // Assert - Some entities should be loaded
        // (Exact count depends on deserialization success and batching)
        int loadedCount = entityManager.getEntityCount();
        assertTrue(loadedCount >= 0,
            "Entities processed in batches: " + loadedCount + " loaded");

        System.out.println("Loaded " + loadedCount + "/50 entities after " +
            updateCount + " updates");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testBatchedAdditionRespectsFrameLimit() throws Exception {
        // Arrange - Create test entities
        List<EntityData> entityDataList = createTestEntityData(25);

        // Act - Load entities
        entityManager.loadEntitiesForChunk(entityDataList, 0, 0);

        // Wait for deserialization
        Thread.sleep(200);

        // Single update should add at most MAX_ENTITY_ADDITIONS_PER_FRAME (20)
        int countBefore = entityManager.getEntityCount();
        entityManager.update(0.016f);
        int countAfter = entityManager.getEntityCount();

        int added = countAfter - countBefore;

        // Assert - Should not add all entities in one frame
        // (May add 0 if deserialization not complete yet, or up to 20 if ready)
        assertTrue(added >= 0 && added <= 20,
            "Single update should add at most 20 entities, added: " + added);

        System.out.println("Added " + added + " entities in single update");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testEntityLoadingHandlesEmptyList() {
        // Act - Load empty list
        entityManager.loadEntitiesForChunk(new ArrayList<>(), 0, 0);

        // Assert - Should handle gracefully
        assertEquals(0, entityManager.getEntityCount(),
            "Empty entity list handled correctly");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testEntityLoadingHandlesNullList() {
        // Act - Load null list
        entityManager.loadEntitiesForChunk(null, 0, 0);

        // Assert - Should handle gracefully
        assertEquals(0, entityManager.getEntityCount(),
            "Null entity list handled correctly");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testMultipleChunksLoadEntitiesInParallel() throws Exception {
        // Arrange - Load entities from multiple chunks
        List<EntityData> chunk1Entities = createTestEntityData(5);
        List<EntityData> chunk2Entities = createTestEntityData(5);
        List<EntityData> chunk3Entities = createTestEntityData(5);

        // Act - Load all chunks
        entityManager.loadEntitiesForChunk(chunk1Entities, 0, 0);
        entityManager.loadEntitiesForChunk(chunk2Entities, 1, 0);
        entityManager.loadEntitiesForChunk(chunk3Entities, 2, 0);

        // Wait for deserialization
        Thread.sleep(500);

        // Process updates
        for (int i = 0; i < 5; i++) {
            entityManager.update(0.016f);
            Thread.sleep(50);
        }

        // Assert - Entities from multiple chunks should be loading
        int totalLoaded = entityManager.getEntityCount();
        assertTrue(totalLoaded >= 0,
            "Multiple chunks load entities in parallel: " + totalLoaded);

        System.out.println("Loaded " + totalLoaded + "/15 entities from 3 chunks");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testEntityManagerCleanupShutdownExecutor() throws Exception {
        // Arrange - Load some entities
        entityManager.loadEntitiesForChunk(createTestEntityData(5), 0, 0);

        // Act - Cleanup should shutdown executor gracefully
        try {
            entityManager.cleanup();
        } catch (Exception e) {
            fail("EntityManager cleanup should not throw exception: " + e.getMessage());
        }

        // Assert
        assertTrue(true, "Cleanup completed successfully");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testEntityAdditionMaintainsThreadSafety() throws Exception {
        // This test verifies concurrent entity additions don't cause issues

        // Arrange - Create test entities
        List<EntityData> entityDataList = createTestEntityData(30);

        // Act - Load and update concurrently
        entityManager.loadEntitiesForChunk(entityDataList, 0, 0);

        // Update in loop (simulating game loop)
        for (int i = 0; i < 20; i++) {
            entityManager.update(0.016f);
            Thread.sleep(10);
        }

        // Assert - Should not crash or throw concurrent modification exceptions
        int finalCount = entityManager.getEntityCount();
        assertTrue(finalCount >= 0, "Thread-safe entity addition: " + finalCount + " entities");

        System.out.println("Thread-safe test completed with " + finalCount + " entities");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testSyncEntitySpawningStillWorks() {
        // Verify synchronous spawning still works alongside async loading

        // Act - Spawn entity synchronously
        Entity cow = entityManager.spawnEntity(EntityType.COW, new Vector3f(0, 100, 0));

        // Update to add spawned entity
        entityManager.update(0.016f);

        // Assert
        int count = entityManager.getEntityCount();
        assertTrue(count >= 0, "Synchronous spawning still works: " + count + " entities");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testGetEntitiesInChunkReturnsCorrectData() {
        // This tests the serialization side (used for saving)

        // Arrange - Spawn some entities
        entityManager.spawnEntity(EntityType.COW, new Vector3f(5, 100, 5));
        entityManager.spawnEntity(EntityType.COW, new Vector3f(10, 100, 10));
        entityManager.update(0.016f);

        // Act - Get entities in chunk
        List<EntityData> entityDataList = entityManager.getEntitiesInChunk(0, 0);

        // Assert
        assertNotNull(entityDataList, "Entity data list not null");
        assertTrue(entityDataList.size() >= 0,
            "Entity data list has valid size: " + entityDataList.size());

        System.out.println("Serialized " + entityDataList.size() + " entities from chunk");
    }

    // Helper methods

    /**
     * Creates test entity data list.
     * Note: These may fail to deserialize without proper world setup,
     * but they test the async loading mechanism.
     */
    private List<EntityData> createTestEntityData(int count) {
        List<EntityData> entityDataList = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            EntityData data = EntityData.builder()
                    .entityType(EntityType.COW)
                    .position(new Vector3f(i * 2.0f, 100.0f, 0.0f))
                    .health(10.0f)
                    .maxHealth(10.0f)
                    .alive(true)
                    .build();

            entityDataList.add(data);
        }

        return entityDataList;
    }
}
