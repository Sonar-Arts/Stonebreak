package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.save.io.ChunkCodec;
import com.stonebreak.world.save.io.ChunkStorage;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.EntityData;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPersistenceTest {

    @Test
    void codecRoundTripPreservesData() throws IOException {
        ChunkData original = createSampleChunk(0, 0);
        byte[] payload = ChunkCodec.encode(original);
        ChunkData decoded = ChunkCodec.decode(payload);
        assertChunksEqual(original, decoded);
    }

    @Test
    void storageRoundTripUsesFilesystemSafely() throws IOException {
        Path tempDir = Files.createTempDirectory("chunk-storage-test");
        try {
            ChunkStorage storage = new ChunkStorage(tempDir);
            ChunkData original = createSampleChunk(5, -3);
            storage.saveChunk(original);

            assertTrue(storage.chunkExists(5, -3));
            ChunkData loaded = storage.loadChunk(5, -3).orElseThrow();
            assertChunksEqual(original, loaded);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static ChunkData createSampleChunk(int chunkX, int chunkZ) {
        BlockType[][][] blocks = new BlockType[16][256][16];
        BlockType[] palette = {
            BlockType.STONE,
            BlockType.GRASS,
            BlockType.DIRT,
            BlockType.SAND,
            BlockType.WATER
        };
        Random random = new Random(12345L + chunkX * 31L + chunkZ * 17L);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    blocks[x][y][z] = palette[random.nextInt(palette.length)];
                }
            }
        }

        Map<String, ChunkData.WaterBlockData> water = new HashMap<>();
        water.put("3,62,3", new ChunkData.WaterBlockData(4, true));
        water.put("7,63,7", new ChunkData.WaterBlockData(2, false));

        List<EntityData> entities = new ArrayList<>();
        entities.add(EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(chunkX * 16 + 2, 65, chunkZ * 16 + 2))
            .velocity(new Vector3f(0.1f, 0.2f, 0.0f))
            .rotation(new Vector3f(0, 180, 0))
            .health(5.0f)
            .maxHealth(5.0f)
            .age(20.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.GRASS)
            .addCustomData("despawnTimer", 120f)
            .addCustomData("stackCount", 12)
            .build());

        entities.add(EntityData.builder()
            .entityType(EntityType.COW)
            .position(new Vector3f(chunkX * 16 + 8, 64, chunkZ * 16 + 8))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 90, 0))
            .health(10.0f)
            .maxHealth(20.0f)
            .age(200.0f)
            .alive(true)
            .addCustomData("textureVariant", "highland")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 12.5f)
            .addCustomData("aiState", "GRAZING")
            .build());

        return ChunkData.builder()
            .chunkX(chunkX)
            .chunkZ(chunkZ)
            .blocks(blocks)
            .lastModified(LocalDateTime.of(2024, 1, 1, 12, 0))
            .featuresPopulated(true)
            .hasEntitiesGenerated(true)
            .waterMetadata(water)
            .entities(entities)
            .build();
    }

    private static void assertChunksEqual(ChunkData expected, ChunkData actual) {
        assertEquals(expected.getChunkX(), actual.getChunkX(), "Chunk X mismatch");
        assertEquals(expected.getChunkZ(), actual.getChunkZ(), "Chunk Z mismatch");
        assertEquals(expected.isFeaturesPopulated(), actual.isFeaturesPopulated(), "Features flag mismatch");
        assertEquals(expected.hasEntitiesGenerated(), actual.hasEntitiesGenerated(), "Entity generation flag mismatch");
        assertEquals(expected.getWaterMetadata(), actual.getWaterMetadata(), "Water metadata mismatch");
        assertEquals(expected.getEntities().size(), actual.getEntities().size(), "Entity count mismatch");

        for (int i = 0; i < expected.getEntities().size(); i++) {
            EntityData a = expected.getEntities().get(i);
            EntityData b = actual.getEntities().get(i);
            assertEquals(a.getEntityType(), b.getEntityType(), "Entity type mismatch at index " + i);
            assertEquals(a.getPosition(), b.getPosition(), "Entity position mismatch at index " + i);
            assertEquals(a.getVelocity(), b.getVelocity(), "Entity velocity mismatch at index " + i);
            assertEquals(a.getRotation(), b.getRotation(), "Entity rotation mismatch at index " + i);
            assertEquals(a.getHealth(), b.getHealth(), "Entity health mismatch at index " + i);
            assertEquals(a.getMaxHealth(), b.getMaxHealth(), "Entity max health mismatch at index " + i);
            assertEquals(a.getAge(), b.getAge(), "Entity age mismatch at index " + i);
            assertEquals(a.isAlive(), b.isAlive(), "Entity alive flag mismatch at index " + i);
            assertEquals(a.getCustomData(), b.getCustomData(), "Entity custom data mismatch at index " + i);
        }

        BlockType[][][] expectedBlocks = expected.getBlocks();
        BlockType[][][] actualBlocks = actual.getBlocks();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    assertEquals(expectedBlocks[x][y][z], actualBlocks[x][y][z],
                        "Block mismatch at (" + x + "," + y + "," + z + ")");
                }
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.compareTo(a)) // delete children before parents
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException ignored) {
                    }
                });
        }
    }
}
