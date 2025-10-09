package com.stonebreak.world.save.serialization;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.EntityData;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for optimized chunk serialization including:
 * - Binary entity serialization
 * - Packed water metadata
 * - Palette compression
 * - LZ4 compression
 */
class OptimizedChunkSerializationTest {

    private BinaryChunkSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new BinaryChunkSerializer();
    }

    @Test
    void testChunkWithWaterMetadata() {
        // Create chunk with some water blocks
        BlockType[][][] blocks = createSimpleChunk();

        // Add water metadata with various coordinates
        Map<String, ChunkData.WaterBlockData> waterMetadata = new HashMap<>();
        waterMetadata.put("0,64,0", new ChunkData.WaterBlockData(1, false));
        waterMetadata.put("5,70,10", new ChunkData.WaterBlockData(3, true));
        waterMetadata.put("15,128,15", new ChunkData.WaterBlockData(7, false));
        waterMetadata.put("8,200,12", new ChunkData.WaterBlockData(2, true));

        ChunkData original = ChunkData.builder()
            .chunkX(5)
            .chunkZ(-3)
            .blocks(blocks)
            .lastModified(LocalDateTime.now())
            .featuresPopulated(true)
            .waterMetadata(waterMetadata)
            .entities(new ArrayList<>())
            .build();

        // Serialize and deserialize
        byte[] data = serializer.serialize(original);
        ChunkData deserialized = serializer.deserialize(data);

        // Verify chunk coordinates
        assertEquals(original.getChunkX(), deserialized.getChunkX());
        assertEquals(original.getChunkZ(), deserialized.getChunkZ());
        assertEquals(original.isFeaturesPopulated(), deserialized.isFeaturesPopulated());

        // Verify water metadata
        Map<String, ChunkData.WaterBlockData> deserializedWater = deserialized.getWaterMetadata();
        assertEquals(waterMetadata.size(), deserializedWater.size());

        for (Map.Entry<String, ChunkData.WaterBlockData> entry : waterMetadata.entrySet()) {
            assertTrue(deserializedWater.containsKey(entry.getKey()),
                "Missing water metadata key: " + entry.getKey());

            ChunkData.WaterBlockData originalWater = entry.getValue();
            ChunkData.WaterBlockData deserializedWaterData = deserializedWater.get(entry.getKey());

            assertEquals(originalWater.level(), deserializedWaterData.level(),
                "Water level mismatch for key: " + entry.getKey());
            assertEquals(originalWater.falling(), deserializedWaterData.falling(),
                "Water falling flag mismatch for key: " + entry.getKey());
        }

        System.out.printf("[WATER] Serialized chunk with %d water blocks: %d bytes%n",
            waterMetadata.size(), data.length);
    }

    @Test
    void testChunkWithEntities() {
        // Create chunk with various entities
        BlockType[][][] blocks = createSimpleChunk();

        List<EntityData> entities = new ArrayList<>();

        // Add block drop
        Map<String, Object> blockDropData = new HashMap<>();
        blockDropData.put("blockType", BlockType.STONE);
        blockDropData.put("despawnTimer", 295.0f);
        blockDropData.put("stackCount", 1);

        entities.add(EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(10.5f, 64.0f, 5.3f))
            .velocity(new Vector3f(0.0f, -0.5f, 0.0f))
            .rotation(new Vector3f(0.0f, 0.0f, 0.0f))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(5.0f)
            .alive(true)
            .customData(blockDropData)
            .build());

        // Add cow
        Map<String, Object> cowData = new HashMap<>();
        cowData.put("textureVariant", "default_cow");
        cowData.put("canBeMilked", true);
        cowData.put("milkRegenTimer", 120.0f);
        cowData.put("aiState", "IDLE");

        entities.add(EntityData.builder()
            .entityType(EntityType.COW)
            .position(new Vector3f(8.0f, 64.0f, 8.0f))
            .velocity(new Vector3f(0.0f, 0.0f, 0.0f))
            .rotation(new Vector3f(0.0f, 90.0f, 0.0f))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(500.0f)
            .alive(true)
            .customData(cowData)
            .build());

        ChunkData original = ChunkData.builder()
            .chunkX(0)
            .chunkZ(0)
            .blocks(blocks)
            .lastModified(LocalDateTime.now())
            .featuresPopulated(true)
            .waterMetadata(new HashMap<>())
            .entities(entities)
            .build();

        // Serialize and deserialize
        byte[] data = serializer.serialize(original);
        ChunkData deserialized = serializer.deserialize(data);

        // Verify entities
        List<EntityData> deserializedEntities = deserialized.getEntities();
        assertEquals(entities.size(), deserializedEntities.size());

        // Check block drop
        EntityData blockDrop = deserializedEntities.stream()
            .filter(e -> e.getEntityType() == EntityType.BLOCK_DROP)
            .findFirst()
            .orElseThrow();

        assertEquals(BlockType.STONE, blockDrop.getCustomData().get("blockType"));
        assertVector3fEquals(new Vector3f(10.5f, 64.0f, 5.3f), blockDrop.getPosition(), 0.001f);

        // Check cow
        EntityData cow = deserializedEntities.stream()
            .filter(e -> e.getEntityType() == EntityType.COW)
            .findFirst()
            .orElseThrow();

        assertEquals("default_cow", cow.getCustomData().get("textureVariant"));
        assertEquals(true, cow.getCustomData().get("canBeMilked"));
        assertVector3fEquals(new Vector3f(8.0f, 64.0f, 8.0f), cow.getPosition(), 0.001f);

        System.out.printf("[ENTITIES] Serialized chunk with %d entities: %d bytes%n",
            entities.size(), data.length);
    }

    @Test
    void testChunkWithEverything() {
        // Create chunk with blocks, water metadata, and entities
        BlockType[][][] blocks = createComplexChunk();

        // Water metadata (avoid level 0 since those are source blocks and get filtered out)
        Map<String, ChunkData.WaterBlockData> waterMetadata = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            int x = i % 16;
            int z = i / 16;
            // Use levels 1-7 (skip 0 which is source block and gets filtered)
            int level = (i % 7) + 1; // levels 1-7
            waterMetadata.put(x + ",65," + z, new ChunkData.WaterBlockData(level, i % 2 == 0));
        }

        // Entities
        List<EntityData> entities = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> data = new HashMap<>();
            data.put("blockType", BlockType.DIRT);
            data.put("despawnTimer", 300.0f - i * 10);
            data.put("stackCount", i + 1);

            entities.add(EntityData.builder()
                .entityType(EntityType.BLOCK_DROP)
                .position(new Vector3f(i * 2.0f, 65.0f, i * 2.0f))
                .velocity(new Vector3f(0.0f, 0.0f, 0.0f))
                .rotation(new Vector3f(0.0f, 0.0f, 0.0f))
                .health(1.0f)
                .maxHealth(1.0f)
                .age((float) i)
                .alive(true)
                .customData(data)
                .build());
        }

        ChunkData original = ChunkData.builder()
            .chunkX(10)
            .chunkZ(-5)
            .blocks(blocks)
            .lastModified(LocalDateTime.now())
            .featuresPopulated(true)
            .waterMetadata(waterMetadata)
            .entities(entities)
            .build();

        // Serialize and deserialize
        byte[] data = serializer.serialize(original);
        ChunkData deserialized = serializer.deserialize(data);

        // Verify everything
        assertEquals(original.getChunkX(), deserialized.getChunkX());
        assertEquals(original.getChunkZ(), deserialized.getChunkZ());
        assertEquals(waterMetadata.size(), deserialized.getWaterMetadata().size());
        assertEquals(entities.size(), deserialized.getEntities().size());

        // Verify blocks match
        BlockType[][][] originalBlocks = original.getBlocks();
        BlockType[][][] deserializedBlocks = deserialized.getBlocks();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    assertEquals(originalBlocks[x][y][z], deserializedBlocks[x][y][z],
                        String.format("Block mismatch at (%d,%d,%d)", x, y, z));
                }
            }
        }

        System.out.printf("[COMPLETE] Serialized full chunk: %d bytes | %d water | %d entities%n",
            data.length, waterMetadata.size(), entities.size());
    }

    @Test
    void testSizeComparison() {
        // Create realistic chunk data
        BlockType[][][] blocks = createComplexChunk();

        Map<String, ChunkData.WaterBlockData> waterMetadata = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            // Use levels 1-7 (skip 0 which is source block and gets filtered)
            int level = (i % 7) + 1;
            waterMetadata.put((i % 16) + "," + (64 + i) + "," + (i / 16),
                new ChunkData.WaterBlockData(level, i % 3 == 0));
        }

        List<EntityData> entities = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> data = new HashMap<>();
            data.put("blockType", BlockType.values()[i % BlockType.values().length]);
            data.put("despawnTimer", 300.0f);
            data.put("stackCount", 1);

            entities.add(EntityData.builder()
                .entityType(EntityType.BLOCK_DROP)
                .position(new Vector3f(i, 65, i))
                .velocity(new Vector3f(0, 0, 0))
                .rotation(new Vector3f(0, 0, 0))
                .health(1.0f)
                .maxHealth(1.0f)
                .age(0.0f)
                .alive(true)
                .customData(data)
                .build());
        }

        ChunkData chunk = ChunkData.builder()
            .chunkX(0)
            .chunkZ(0)
            .blocks(blocks)
            .lastModified(LocalDateTime.now())
            .featuresPopulated(true)
            .waterMetadata(waterMetadata)
            .entities(entities)
            .build();

        byte[] data = serializer.serialize(chunk);

        // Estimate uncompressed size
        int blockDataSize = 16 * 256 * 16 * 4; // 4 bytes per block (uncompressed)
        int waterOldFormat = waterMetadata.size() * 5; // Old format: 5 bytes per entry
        int waterNewFormat = waterMetadata.size() * 4; // New format: 4 bytes per entry
        int entityJsonApprox = entities.size() * 250; // Approximate JSON size per entity

        System.out.println("\n=== SIZE COMPARISON ===");
        System.out.printf("Serialized chunk size: %d bytes%n", data.length);
        System.out.printf("Estimated uncompressed blocks: %d bytes%n", blockDataSize);
        System.out.printf("Water metadata (old): %d bytes | (new): %d bytes | Saved: %d bytes%n",
            waterOldFormat, waterNewFormat, waterOldFormat - waterNewFormat);
        System.out.printf("Entities (JSON approx): %d bytes | (binary): ~%d bytes%n",
            entityJsonApprox, entities.size() * 60);
        System.out.printf("Compression ratio: %.1f%%%n",
            (1.0 - (double) data.length / blockDataSize) * 100);
    }

    private BlockType[][][] createSimpleChunk() {
        BlockType[][][] blocks = new BlockType[16][256][16];
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    if (y < 60) {
                        blocks[x][y][z] = BlockType.STONE;
                    } else if (y < 63) {
                        blocks[x][y][z] = BlockType.DIRT;
                    } else if (y == 63) {
                        blocks[x][y][z] = BlockType.GRASS;
                    } else {
                        blocks[x][y][z] = BlockType.AIR;
                    }
                }
            }
        }
        return blocks;
    }

    private BlockType[][][] createComplexChunk() {
        BlockType[][][] blocks = new BlockType[16][256][16];
        BlockType[] types = {BlockType.STONE, BlockType.DIRT, BlockType.GRASS, BlockType.SAND, BlockType.WOOD};

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    if (y < 60) {
                        blocks[x][y][z] = BlockType.STONE;
                    } else if (y < 63) {
                        blocks[x][y][z] = types[(x + z) % types.length];
                    } else {
                        blocks[x][y][z] = BlockType.AIR;
                    }
                }
            }
        }
        return blocks;
    }

    private void assertVector3fEquals(Vector3f expected, Vector3f actual, float delta) {
        assertEquals(expected.x, actual.x, delta);
        assertEquals(expected.y, actual.y, delta);
        assertEquals(expected.z, actual.z, delta);
    }
}
