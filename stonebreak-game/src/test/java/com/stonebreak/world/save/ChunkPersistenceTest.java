package com.stonebreak.world.save;

import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.save.io.ChunkCodec;
import com.stonebreak.world.save.io.ChunkStorage;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.EntityData;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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

    @Test
    void palettedSectionStreamStaysCompact() throws IOException {
        // v5 pins: version 5, and the uncompressed section stream must be the
        // paletted form — a realistic chunk (terrain below y=64, air above)
        // stays far below the old fixed 128 KB dense stream.
        CcoPalettedChunkStorage blocks =
            CcoPalettedChunkStorage.createEmpty(16, 256, 16, BlockType.AIR);
        Random random = new Random(99L);
        BlockType[] palette = {BlockType.STONE, BlockType.DIRT, BlockType.GRASS};
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 64; y++) {
                    blocks.set(x, y, z, palette[random.nextInt(palette.length)]);
                }
            }
        }
        ChunkData chunk = ChunkData.builder()
            .chunkX(2).chunkZ(7)
            .blocks(blocks)
            .lastModified(LocalDateTime.of(2024, 1, 1, 12, 0))
            .featuresPopulated(true)
            .hasEntitiesGenerated(false)
            .waterMetadata(new HashMap<>())
            .entities(new ArrayList<>())
            .snowLayers(new HashMap<>())
            .build();
        byte[] payload = ChunkCodec.encode(chunk);

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            in.readInt();              // magic
            assertEquals(5, in.readUnsignedShort(), "writer emits v5");
            in.readInt();              // chunkX
            in.readInt();              // chunkZ
            in.readLong();             // lastModified
            in.readBoolean();          // featuresPopulated
            in.readBoolean();          // hasEntitiesGenerated
            in.readByte();             // compression flag
            int rawLength = in.readInt();
            // 4 mixed sections (~4.1 KB each) + 12 uniform air sections (3 B each).
            assertTrue(rawLength < 20_000,
                "paletted stream must stay compact, was " + rawLength + " bytes");
        }

        assertChunksEqual(chunk, ChunkCodec.decode(payload));
    }

    @Test
    void legacyV3PayloadStillDecodes() throws IOException {
        // Hand-built v3 payload (deflated dense big-endian short ids in
        // y,z,x order) — pins that pre-palette worlds keep loading.
        ChunkData expected = createSampleChunk(-4, 9);
        CcoBlockStorage storage = expected.getBlockStorage();

        java.io.ByteArrayOutputStream rawBlocks = new java.io.ByteArrayOutputStream(65536 * 2);
        try (java.io.DataOutputStream rawOut = new java.io.DataOutputStream(rawBlocks)) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        rawOut.writeShort(((BlockType) storage.get(x, y, z)).getId());
                    }
                }
            }
        }
        java.io.ByteArrayOutputStream deflated = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream deflater =
                 new java.util.zip.DeflaterOutputStream(deflated)) {
            deflater.write(rawBlocks.toByteArray());
        }
        byte[] compressed = deflated.toByteArray();

        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(payload)) {
            out.writeInt(0x5342434B);           // magic
            out.writeShort(3);                   // version
            out.writeInt(expected.getChunkX());
            out.writeInt(expected.getChunkZ());
            out.writeLong(0L);                   // lastModified
            out.writeBoolean(expected.isFeaturesPopulated());
            out.writeBoolean(expected.hasEntitiesGenerated());
            out.writeInt(compressed.length);
            out.write(compressed);
            out.writeInt(0);                     // waterCount
            out.writeInt(0);                     // entityCount
            out.writeInt(0);                     // blockStateCount (v2+)
            out.writeInt(0);                     // snowCount (v3+)
        }

        ChunkData decoded = ChunkCodec.decode(payload.toByteArray());
        assertEquals(expected.getChunkX(), decoded.getChunkX());
        assertEquals(expected.getChunkZ(), decoded.getChunkZ());
        CcoBlockStorage actualBlocks = decoded.getBlockStorage();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    assertEquals(storage.get(x, y, z), actualBlocks.get(x, y, z),
                        "Block mismatch at (" + x + "," + y + "," + z + ")");
                }
            }
        }
    }

    private static ChunkData createSampleChunk(int chunkX, int chunkZ) {
        CcoPalettedChunkStorage blocks =
            CcoPalettedChunkStorage.createEmpty(16, 256, 16, BlockType.AIR);
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
                    blocks.set(x, y, z, palette[random.nextInt(palette.length)]);
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

        java.util.Map<Integer, Integer> snowLayers = new java.util.HashMap<>();
        snowLayers.put(com.stonebreak.world.chunk.utils.LocalBlockKey.pack(0, 64, 0), 1);
        snowLayers.put(com.stonebreak.world.chunk.utils.LocalBlockKey.pack(15, 200, 15), 8);
        snowLayers.put(com.stonebreak.world.chunk.utils.LocalBlockKey.pack(4, 70, 11), 5);

        return ChunkData.builder()
            .chunkX(chunkX)
            .chunkZ(chunkZ)
            .blocks(blocks)
            .lastModified(LocalDateTime.of(2024, 1, 1, 12, 0))
            .featuresPopulated(true)
            .hasEntitiesGenerated(true)
            .waterMetadata(water)
            .entities(entities)
            .snowLayers(snowLayers)
            .build();
    }

    private static void assertChunksEqual(ChunkData expected, ChunkData actual) {
        assertEquals(expected.getChunkX(), actual.getChunkX(), "Chunk X mismatch");
        assertEquals(expected.getChunkZ(), actual.getChunkZ(), "Chunk Z mismatch");
        assertEquals(expected.isFeaturesPopulated(), actual.isFeaturesPopulated(), "Features flag mismatch");
        assertEquals(expected.hasEntitiesGenerated(), actual.hasEntitiesGenerated(), "Entity generation flag mismatch");
        assertEquals(expected.getWaterMetadata(), actual.getWaterMetadata(), "Water metadata mismatch");
        assertEquals(expected.getSnowLayers(), actual.getSnowLayers(), "Snow layers mismatch (v3)");
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

        CcoBlockStorage expectedBlocks = expected.getBlockStorage();
        CcoBlockStorage actualBlocks = actual.getBlockStorage();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    assertEquals(expectedBlocks.get(x, y, z), actualBlocks.get(x, y, z),
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
