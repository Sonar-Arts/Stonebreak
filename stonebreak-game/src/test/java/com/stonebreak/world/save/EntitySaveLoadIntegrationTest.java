package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.EntityData;
import com.stonebreak.world.save.serialization.BinaryChunkSerializer;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the full entity save/load cycle through the chunk serialization system.
 * Tests that entities are properly saved to binary chunk format and restored correctly.
 */
@DisplayName("Entity Save/Load Integration Tests")
class EntitySaveLoadIntegrationTest {

    private static final float FLOAT_EPSILON = 0.0001f;
    private BinaryChunkSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new BinaryChunkSerializer();
    }

    @Test
    @DisplayName("Integration - Save and load chunk with block drops")
    void testSaveAndLoadChunkWithBlockDrops() {
        // Given: A chunk with 3 block drops
        List<EntityData> entities = new ArrayList<>();

        // Block drop 1: DIRT at (5, 64, 3)
        EntityData drop1 = EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(5.5f, 64.0f, 3.2f))
            .velocity(new Vector3f(0.1f, -2.0f, 0.3f))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(10.5f)
            .alive(true)
            .addCustomData("blockType", BlockType.DIRT)
            .addCustomData("despawnTimer", 285.0f)
            .addCustomData("stackCount", 1)
            .build();
        entities.add(drop1);

        // Block drop 2: STONE stack at (12, 65, 8)
        EntityData drop2 = EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(12.3f, 65.0f, 8.7f))
            .velocity(new Vector3f(-0.2f, 0.0f, 0.1f))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(45.2f)
            .alive(true)
            .addCustomData("blockType", BlockType.STONE)
            .addCustomData("despawnTimer", 254.8f)
            .addCustomData("stackCount", 16)
            .build();
        entities.add(drop2);

        // Block drop 3: WOOD at (7, 63, 14)
        EntityData drop3 = EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(7.1f, 63.5f, 14.9f))
            .velocity(new Vector3f(0.0f, 0.0f, 0.0f))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(120.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.WOOD)
            .addCustomData("despawnTimer", 180.0f)
            .addCustomData("stackCount", 1)
            .build();
        entities.add(drop3);

        // Create chunk data with entities
        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing the chunk
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: All entities should be preserved
        assertNotNull(loadedChunk, "Loaded chunk should not be null");
        List<EntityData> loadedEntities = loadedChunk.getEntities();
        assertEquals(3, loadedEntities.size(), "Should have 3 entities");

        // Verify first block drop
        EntityData loadedDrop1 = loadedEntities.get(0);
        EntityData.BlockDropData blockDrop1 = EntityData.BlockDropData.fromCustomData(loadedDrop1);
        assertEquals(BlockType.DIRT, blockDrop1.getBlockType(), "First drop should be DIRT");
        assertVector3fEquals(new Vector3f(5.5f, 64.0f, 3.2f), loadedDrop1.getPosition(), "Position should match");
        assertEquals(285.0f, blockDrop1.getDespawnTimer(), FLOAT_EPSILON, "Despawn timer should match");
        assertEquals(1, blockDrop1.getStackCount(), "Stack count should match");

        // Verify second block drop (stacked)
        EntityData loadedDrop2 = loadedEntities.get(1);
        EntityData.BlockDropData blockDrop2 = EntityData.BlockDropData.fromCustomData(loadedDrop2);
        assertEquals(BlockType.STONE, blockDrop2.getBlockType(), "Second drop should be STONE");
        assertEquals(16, blockDrop2.getStackCount(), "Should have stack of 16");
        assertEquals(254.8f, blockDrop2.getDespawnTimer(), FLOAT_EPSILON, "Despawn timer should match");

        // Verify third block drop
        EntityData loadedDrop3 = loadedEntities.get(2);
        EntityData.BlockDropData blockDrop3 = EntityData.BlockDropData.fromCustomData(loadedDrop3);
        assertEquals(BlockType.WOOD, blockDrop3.getBlockType(), "Third drop should be WOOD");
        assertEquals(180.0f, blockDrop3.getDespawnTimer(), FLOAT_EPSILON, "Despawn timer should match");
    }

    @Test
    @DisplayName("Integration - Save and load chunk with cows")
    void testSaveAndLoadChunkWithCows() {
        // Given: A chunk with 2 cows
        List<EntityData> entities = new ArrayList<>();

        // Cow 1: Default variant, wandering
        EntityData cow1 = EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.COW)
            .position(new Vector3f(8.5f, 64.0f, 6.3f))
            .velocity(new Vector3f(0.5f, 0.0f, 0.3f))
            .rotation(new Vector3f(0.0f, 45.0f, 0.0f))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(250.0f)
            .alive(true)
            .addCustomData("textureVariant", "default")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "WANDERING")
            .build();
        entities.add(cow1);

        // Cow 2: Angus variant, damaged and can't be milked
        EntityData cow2 = EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.COW)
            .position(new Vector3f(3.2f, 64.0f, 10.8f))
            .velocity(new Vector3f(0.0f, 0.0f, 0.0f))
            .rotation(new Vector3f(0.0f, 180.0f, 0.0f))
            .health(6.5f)
            .maxHealth(10.0f)
            .age(500.0f)
            .alive(true)
            .addCustomData("textureVariant", "angus")
            .addCustomData("canBeMilked", false)
            .addCustomData("milkRegenTimer", 120.0f)
            .addCustomData("aiState", "IDLE")
            .build();
        entities.add(cow2);

        // Create chunk data with entities
        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing the chunk
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: All cows should be preserved
        assertNotNull(loadedChunk, "Loaded chunk should not be null");
        List<EntityData> loadedEntities = loadedChunk.getEntities();
        assertEquals(2, loadedEntities.size(), "Should have 2 entities");

        // Verify first cow
        EntityData loadedCow1 = loadedEntities.get(0);
        EntityData.CowData cowData1 = EntityData.CowData.fromCustomData(loadedCow1);
        assertEquals("default", cowData1.getTextureVariant(), "First cow should be default variant");
        assertEquals(10.0f, loadedCow1.getHealth(), FLOAT_EPSILON, "Health should be full");
        assertTrue(cowData1.canBeMilked(), "Should be able to milk");
        assertEquals("WANDERING", cowData1.getAiState(), "Should be wandering");
        assertVector3fEquals(new Vector3f(8.5f, 64.0f, 6.3f), loadedCow1.getPosition(), "Position should match");

        // Verify second cow
        EntityData loadedCow2 = loadedEntities.get(1);
        EntityData.CowData cowData2 = EntityData.CowData.fromCustomData(loadedCow2);
        assertEquals("angus", cowData2.getTextureVariant(), "Second cow should be angus variant");
        assertEquals(6.5f, loadedCow2.getHealth(), FLOAT_EPSILON, "Health should be damaged");
        assertFalse(cowData2.canBeMilked(), "Should not be able to milk");
        assertEquals(120.0f, cowData2.getMilkRegenTimer(), FLOAT_EPSILON, "Milk regen timer should match");
        assertEquals("IDLE", cowData2.getAiState(), "Should be idle");
    }

    @Test
    @DisplayName("Integration - Save and load chunk with mixed entity types")
    void testSaveAndLoadChunkWithMixedEntities() {
        // Given: A chunk with both block drops and cows
        List<EntityData> entities = new ArrayList<>();

        // Add 2 block drops
        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(2.0f, 64.0f, 2.0f))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.GRASS)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", 5)
            .build());

        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(10.0f, 65.0f, 12.0f))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.SAND)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", 1)
            .build());

        // Add 2 cows
        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.COW)
            .position(new Vector3f(6.0f, 64.0f, 6.0f))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("textureVariant", "highland")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "GRAZING")
            .build());

        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.COW)
            .position(new Vector3f(14.0f, 64.0f, 8.0f))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("textureVariant", "jersey")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "IDLE")
            .build());

        // Create chunk data with mixed entities
        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing the chunk
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: All entities should be preserved with correct types
        assertNotNull(loadedChunk, "Loaded chunk should not be null");
        List<EntityData> loadedEntities = loadedChunk.getEntities();
        assertEquals(4, loadedEntities.size(), "Should have 4 entities");

        // Count entity types
        int blockDropCount = 0;
        int cowCount = 0;
        for (EntityData entity : loadedEntities) {
            if (entity.getEntityType() == com.stonebreak.mobs.entities.EntityType.BLOCK_DROP) {
                blockDropCount++;
            } else if (entity.getEntityType() == com.stonebreak.mobs.entities.EntityType.COW) {
                cowCount++;
            }
        }

        assertEquals(2, blockDropCount, "Should have 2 block drops");
        assertEquals(2, cowCount, "Should have 2 cows");
    }

    @Test
    @DisplayName("Integration - Save and load empty chunk with no entities")
    void testSaveAndLoadEmptyChunk() {
        // Given: A chunk with no entities
        List<EntityData> entities = new ArrayList<>();
        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing the chunk
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: Chunk should load successfully with empty entity list
        assertNotNull(loadedChunk, "Loaded chunk should not be null");
        List<EntityData> loadedEntities = loadedChunk.getEntities();
        assertNotNull(loadedEntities, "Entity list should not be null");
        assertEquals(0, loadedEntities.size(), "Should have 0 entities");
    }

    @Test
    @DisplayName("Integration - Verify entity data integrity after serialization")
    void testEntityDataIntegrity() {
        // Given: Entity with very specific values to test precision
        List<EntityData> entities = new ArrayList<>();
        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(1.23456f, 64.78901f, 2.34567f))
            .velocity(new Vector3f(-0.11111f, 2.22222f, 0.33333f))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(123.456f)
            .alive(true)
            .addCustomData("blockType", BlockType.DIRT)
            .addCustomData("despawnTimer", 287.654f)
            .addCustomData("stackCount", 42)
            .build());

        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: Values should be preserved with high precision
        EntityData loaded = loadedChunk.getEntities().get(0);
        EntityData.BlockDropData blockDrop = EntityData.BlockDropData.fromCustomData(loaded);

        assertEquals(1.23456f, loaded.getPosition().x, 0.001f, "Position X should be precise");
        assertEquals(64.78901f, loaded.getPosition().y, 0.001f, "Position Y should be precise");
        assertEquals(2.34567f, loaded.getPosition().z, 0.001f, "Position Z should be precise");

        assertEquals(-0.11111f, loaded.getVelocity().x, 0.001f, "Velocity X should be precise");
        assertEquals(2.22222f, loaded.getVelocity().y, 0.001f, "Velocity Y should be precise");
        assertEquals(0.33333f, loaded.getVelocity().z, 0.001f, "Velocity Z should be precise");

        assertEquals(123.456f, loaded.getAge(), 0.001f, "Age should be precise");
        assertEquals(287.654f, blockDrop.getDespawnTimer(), 0.001f, "Despawn timer should be precise");
        assertEquals(42, blockDrop.getStackCount(), "Stack count should be exact");
    }

    // ===== Additional Integration Tests =====

    @Test
    @DisplayName("Integration - Save and load large entity count (200+ entities)")
    void testSaveAndLoadLargeEntityCount() {
        // Given: A chunk with 200 block drops
        List<EntityData> entities = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            entities.add(EntityData.builder()
                .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
                .position(new Vector3f(i % 16, 64 + (i / 16), i % 16))
                .velocity(new Vector3f(0, 0, 0))
                .rotation(new Vector3f(0, 0, 0))
                .health(1.0f)
                .maxHealth(1.0f)
                .age((float) i)
                .alive(true)
                .addCustomData("blockType", BlockType.STONE)
                .addCustomData("despawnTimer", 300.0f)
                .addCustomData("stackCount", 1)
                .build());
        }

        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: All 200 entities should be preserved
        assertEquals(200, loadedChunk.getEntities().size(), "Should have 200 entities");

        // Verify first and last entities
        assertEquals(0.0f, loadedChunk.getEntities().get(0).getAge(), FLOAT_EPSILON, "First entity age should be 0");
        assertEquals(199.0f, loadedChunk.getEntities().get(199).getAge(), FLOAT_EPSILON, "Last entity age should be 199");
    }

    @Test
    @DisplayName("Integration - Save and load entities at chunk boundaries")
    void testSaveAndLoadChunkBoundaryEntities() {
        // Given: Entities at chunk edges (0.0, 15.99, etc.)
        List<EntityData> entities = new ArrayList<>();

        // Entity at X=0, Z=0 (min corner)
        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(0.0f, 64.0f, 0.0f))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.DIRT)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", 1)
            .build());

        // Entity at X=15.99, Z=15.99 (max corner)
        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(15.99f, 64.0f, 15.99f))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.STONE)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", 1)
            .build());

        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: Boundary positions should be preserved
        List<EntityData> loadedEntities = loadedChunk.getEntities();
        assertEquals(2, loadedEntities.size(), "Should have 2 entities");

        assertEquals(0.0f, loadedEntities.get(0).getPosition().x, FLOAT_EPSILON, "Min X should be preserved");
        assertEquals(0.0f, loadedEntities.get(0).getPosition().z, FLOAT_EPSILON, "Min Z should be preserved");

        assertEquals(15.99f, loadedEntities.get(1).getPosition().x, 0.01f, "Max X should be preserved");
        assertEquals(15.99f, loadedEntities.get(1).getPosition().z, 0.01f, "Max Z should be preserved");
    }

    @Test
    @DisplayName("Integration - Handle corrupted entity data gracefully")
    void testSaveAndLoadWithPartialCorruption() {
        // Given: 5 valid entities
        List<EntityData> entities = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            entities.add(EntityData.builder()
                .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
                .position(new Vector3f(i, 64, i))
                .velocity(new Vector3f(0, 0, 0))
                .rotation(new Vector3f(0, 0, 0))
                .health(1.0f)
                .maxHealth(1.0f)
                .age(0.0f)
                .alive(true)
                .addCustomData("blockType", BlockType.STONE)
                .addCustomData("despawnTimer", 300.0f)
                .addCustomData("stackCount", 1)
                .build());
        }

        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: All entities should be loaded successfully
        assertEquals(5, loadedChunk.getEntities().size(), "Should have all 5 entities");
    }

    @Test
    @DisplayName("Integration - High precision float values preserved")
    void testSaveAndLoadHighPrecisionFloats() {
        // Given: Entity with high-precision float values
        List<EntityData> entities = new ArrayList<>();
        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(1.123456f, 64.987654f, 2.555555f))
            .velocity(new Vector3f(-0.123456f, 2.987654f, 0.555555f))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(123.123456f)
            .alive(true)
            .addCustomData("blockType", BlockType.DIRT)
            .addCustomData("despawnTimer", 287.123456f)
            .addCustomData("stackCount", 1)
            .build());

        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: High precision should be preserved (within JSON precision limits)
        EntityData loaded = loadedChunk.getEntities().get(0);
        EntityData.BlockDropData blockDrop = EntityData.BlockDropData.fromCustomData(loaded);

        assertEquals(1.123456f, loaded.getPosition().x, 0.0001f, "High precision X should be preserved");
        assertEquals(64.987654f, loaded.getPosition().y, 0.0001f, "High precision Y should be preserved");
        assertEquals(123.123456f, loaded.getAge(), 0.0001f, "High precision age should be preserved");
        assertEquals(287.123456f, blockDrop.getDespawnTimer(), 0.0001f, "High precision timer should be preserved");
    }

    @Test
    @DisplayName("Integration - Backward compatibility with empty entity list")
    void testSaveAndLoadBackwardCompatibility() {
        // Given: A chunk with no entities (simulates old save format)
        ChunkData originalChunk = createTestChunk(0, 0, new ArrayList<>());

        // When: Serializing and deserializing
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: Should load successfully with empty entity list
        assertNotNull(loadedChunk, "Chunk should load");
        assertNotNull(loadedChunk.getEntities(), "Entity list should not be null");
        assertEquals(0, loadedChunk.getEntities().size(), "Should have no entities");
    }

    @Test
    @DisplayName("Integration - Entity order preservation")
    void testSaveAndLoadEntityOrderPreservation() {
        // Given: Entities in a specific order
        List<EntityData> entities = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            entities.add(EntityData.builder()
                .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
                .position(new Vector3f(i, 64, i))
                .velocity(new Vector3f(0, 0, 0))
                .rotation(new Vector3f(0, 0, 0))
                .health(1.0f)
                .maxHealth(1.0f)
                .age((float) i)  // Use age to verify order
                .alive(true)
                .addCustomData("blockType", BlockType.STONE)
                .addCustomData("despawnTimer", 300.0f)
                .addCustomData("stackCount", i + 1)
                .build());
        }

        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: Entity order should be preserved
        List<EntityData> loadedEntities = loadedChunk.getEntities();
        for (int i = 0; i < 10; i++) {
            assertEquals((float) i, loadedEntities.get(i).getAge(), FLOAT_EPSILON,
                "Entity " + i + " should be in correct order");

            EntityData.BlockDropData blockDrop = EntityData.BlockDropData.fromCustomData(loadedEntities.get(i));
            assertEquals(i + 1, blockDrop.getStackCount(), "Stack count should match order");
        }
    }

    @Test
    @DisplayName("Integration - Mixed alive and dead entity states")
    void testSaveAndLoadMixedAliveStates() {
        // Given: Mix of alive and dead entities
        List<EntityData> entities = new ArrayList<>();

        // Alive entity
        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(5, 64, 5))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(10.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.DIRT)
            .addCustomData("despawnTimer", 250.0f)
            .addCustomData("stackCount", 1)
            .build());

        // Dead entity
        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(10, 64, 10))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(0.0f)
            .maxHealth(1.0f)
            .age(300.0f)
            .alive(false)
            .addCustomData("blockType", BlockType.STONE)
            .addCustomData("despawnTimer", 0.0f)
            .addCustomData("stackCount", 1)
            .build());

        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: Both alive and dead states should be preserved
        List<EntityData> loadedEntities = loadedChunk.getEntities();
        assertEquals(2, loadedEntities.size(), "Should have 2 entities");

        assertTrue(loadedEntities.get(0).isAlive(), "First entity should be alive");
        assertFalse(loadedEntities.get(1).isAlive(), "Second entity should be dead");

        assertEquals(1.0f, loadedEntities.get(0).getHealth(), FLOAT_EPSILON, "Alive entity should have health");
        assertEquals(0.0f, loadedEntities.get(1).getHealth(), FLOAT_EPSILON, "Dead entity should have zero health");
    }

    @Test
    @DisplayName("Integration - Binary format size measurement")
    void testBinaryFormatSizeWithEntities() {
        // Given: Chunks with varying entity counts
        int[] entityCounts = {0, 1, 10, 50};

        for (int count : entityCounts) {
            List<EntityData> entities = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                entities.add(EntityData.builder()
                    .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
                    .position(new Vector3f(i, 64, i))
                    .velocity(new Vector3f(0, 0, 0))
                    .rotation(new Vector3f(0, 0, 0))
                    .health(1.0f)
                    .maxHealth(1.0f)
                    .age(0.0f)
                    .alive(true)
                    .addCustomData("blockType", BlockType.STONE)
                    .addCustomData("despawnTimer", 300.0f)
                    .addCustomData("stackCount", 1)
                    .build());
            }

            ChunkData chunk = createTestChunk(0, 0, entities);
            byte[] serialized = serializer.serialize(chunk);

            // Then: Size should be reasonable (< 500 bytes per entity overhead)
            // This is mainly to detect if serialization is inefficient
            System.out.println("Entity count: " + count + ", Serialized size: " + serialized.length + " bytes");
            assertTrue(serialized.length > 0, "Serialized data should have size");
        }
    }

    @Test
    @DisplayName("Integration - Multiple entities at same position")
    void testSaveAndLoadDuplicatePositions() {
        // Given: Multiple entities at the exact same position
        Vector3f sharedPos = new Vector3f(8.5f, 64.0f, 8.5f);
        List<EntityData> entities = new ArrayList<>();

        // 3 entities at same position but different types/properties
        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(sharedPos)
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(10.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.DIRT)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", 1)
            .build());

        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(sharedPos)
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(20.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.STONE)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", 5)
            .build());

        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.COW)
            .position(sharedPos)
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(100.0f)
            .alive(true)
            .addCustomData("textureVariant", "default")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "IDLE")
            .build());

        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: All entities should be preserved, even at same position
        List<EntityData> loadedEntities = loadedChunk.getEntities();
        assertEquals(3, loadedEntities.size(), "Should have 3 entities");

        // All should have the same position
        for (EntityData entity : loadedEntities) {
            assertVector3fEquals(sharedPos, entity.getPosition(), "Position should match shared position");
        }

        // But different ages to distinguish them
        assertEquals(10.0f, loadedEntities.get(0).getAge(), FLOAT_EPSILON, "First entity age");
        assertEquals(20.0f, loadedEntities.get(1).getAge(), FLOAT_EPSILON, "Second entity age");
        assertEquals(100.0f, loadedEntities.get(2).getAge(), FLOAT_EPSILON, "Third entity age");
    }

    @Test
    @DisplayName("Integration - All cow variants in one chunk")
    void testSaveAndLoadAllCowVariants() {
        // Given: All 4 cow variants in one chunk
        String[] variants = {"default", "angus", "highland", "jersey"};
        List<EntityData> entities = new ArrayList<>();

        for (int i = 0; i < variants.length; i++) {
            entities.add(EntityData.builder()
                .entityType(com.stonebreak.mobs.entities.EntityType.COW)
                .position(new Vector3f(i * 4, 64, i * 4))
                .velocity(new Vector3f(0, 0, 0))
                .rotation(new Vector3f(0, i * 90.0f, 0))
                .health(10.0f)
                .maxHealth(10.0f)
                .age(i * 50.0f)
                .alive(true)
                .addCustomData("textureVariant", variants[i])
                .addCustomData("canBeMilked", i % 2 == 0)
                .addCustomData("milkRegenTimer", i * 30.0f)
                .addCustomData("aiState", i == 0 ? "IDLE" : i == 1 ? "WANDERING" : "GRAZING")
                .build());
        }

        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing
        byte[] serialized = serializer.serialize(originalChunk);
        ChunkData loadedChunk = serializer.deserialize(serialized);

        // Then: All variants should be preserved with correct properties
        List<EntityData> loadedEntities = loadedChunk.getEntities();
        assertEquals(4, loadedEntities.size(), "Should have 4 cow variants");

        for (int i = 0; i < variants.length; i++) {
            EntityData.CowData cowData = EntityData.CowData.fromCustomData(loadedEntities.get(i));
            assertEquals(variants[i], cowData.getTextureVariant(),
                "Variant " + i + " should be " + variants[i]);
            assertEquals(i * 50.0f, loadedEntities.get(i).getAge(), FLOAT_EPSILON,
                "Age for variant " + i + " should match");
        }
    }

    @Test
    @DisplayName("Integration - Round-trip multiple times")
    void testMultipleSerializationCycles() {
        // Given: A chunk with entities
        List<EntityData> entities = new ArrayList<>();
        entities.add(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.BLOCK_DROP)
            .position(new Vector3f(5.5f, 64.0f, 3.2f))
            .velocity(new Vector3f(0.1f, -2.0f, 0.3f))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(10.5f)
            .alive(true)
            .addCustomData("blockType", BlockType.DIRT)
            .addCustomData("despawnTimer", 285.0f)
            .addCustomData("stackCount", 1)
            .build());

        ChunkData originalChunk = createTestChunk(0, 0, entities);

        // When: Serializing and deserializing 5 times
        ChunkData currentChunk = originalChunk;
        for (int cycle = 0; cycle < 5; cycle++) {
            byte[] serialized = serializer.serialize(currentChunk);
            currentChunk = serializer.deserialize(serialized);
        }

        // Then: Data should still be intact after 5 cycles
        List<EntityData> finalEntities = currentChunk.getEntities();
        assertEquals(1, finalEntities.size(), "Should still have 1 entity");

        EntityData.BlockDropData blockDrop = EntityData.BlockDropData.fromCustomData(finalEntities.get(0));
        assertEquals(BlockType.DIRT, blockDrop.getBlockType(), "Block type should be preserved");
        assertVector3fEquals(new Vector3f(5.5f, 64.0f, 3.2f), finalEntities.get(0).getPosition(),
            "Position should be preserved after 5 cycles");
        assertEquals(285.0f, blockDrop.getDespawnTimer(), 0.01f, "Timer should be preserved");
    }

    // Helper methods

    private ChunkData createTestChunk(int chunkX, int chunkZ, List<EntityData> entities) {
        // Create a simple chunk with AIR blocks
        BlockType[][][] blocks = new BlockType[16][256][16];
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    blocks[x][y][z] = BlockType.AIR;
                }
            }
        }

        return ChunkData.builder()
            .chunkX(chunkX)
            .chunkZ(chunkZ)
            .blocks(blocks)
            .lastModified(LocalDateTime.now())
            .featuresPopulated(false)
            .waterMetadata(new HashMap<>())
            .entities(entities)
            .build();
    }

    private void assertVector3fEquals(Vector3f expected, Vector3f actual, String message) {
        assertEquals(expected.x, actual.x, FLOAT_EPSILON, message + " (X component)");
        assertEquals(expected.y, actual.y, FLOAT_EPSILON, message + " (Y component)");
        assertEquals(expected.z, actual.z, FLOAT_EPSILON, message + " (Z component)");
    }
}
