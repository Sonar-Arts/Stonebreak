package com.stonebreak.world.save.io;

import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.save.model.ChunkData;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class ChunkCodecCorruptionTest {

    // ── Fixtures ──────────────────────────────────────────────

    private static ChunkData createFixture() {
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
        return ChunkData.builder()
                .chunkX(2).chunkZ(7)
                .blocks(blocks)
                .lastModified(LocalDateTime.of(2024, 1, 1, 12, 0))
                .featuresPopulated(true)
                .hasEntitiesGenerated(false)
                .waterMetadata(new HashMap<>())
                .entities(new ArrayList<>())
                .snowLayers(new HashMap<>())
                .build();
    }

    private static byte[] validPayload() throws IOException {
        return ChunkCodec.encode(createFixture());
    }

    // ── Corruption: magic ─────────────────────────────────────

    @Test
    void corruptedMagicIsRejected() throws IOException {
        byte[] payload = validPayload();
        byte[] corrupted = payload.clone();
        corrupted[0] ^= 0xFF;

        IOException ex = assertThrows(IOException.class, () -> ChunkCodec.decode(corrupted));
        assertTrue(ex.getMessage().contains("missing SBCK header"),
                "Expected 'missing SBCK header' in: " + ex.getMessage());
    }

    // ── Corruption: version ───────────────────────────────────

    @Test
    void unsupportedVersionsAreRejected() throws IOException {
        byte[] payload = validPayload();

        for (short badVersion : new short[]{0, 6}) {
            byte[] corrupted = payload.clone();
            ByteBuffer.wrap(corrupted).putShort(4, badVersion);

            IOException ex = assertThrows(IOException.class, () -> ChunkCodec.decode(corrupted));
            assertTrue(ex.getMessage().contains("Unsupported chunk payload version:"),
                    "Expected 'Unsupported chunk payload version:' in: " + ex.getMessage());
        }
    }

    // ── Corruption: truncated payload ─────────────────────────

    @Test
    void truncatedPayloadIsRejected() throws IOException {
        byte[] payload = validPayload();

        // Severely truncated — only part of the header.
        byte[] short1 = java.util.Arrays.copyOf(payload, 10);
        assertThrows(IOException.class, () -> ChunkCodec.decode(short1));

        // Truncated after header — stops inside the body.
        byte[] short2 = java.util.Arrays.copyOf(payload, 30);
        assertThrows(IOException.class, () -> ChunkCodec.decode(short2));
    }

    // ── Corruption: length field ──────────────────────────────

    @Test
    void corruptedLengthFieldIsRejected() throws IOException {
        byte[] payload = validPayload();
        byte[] corrupted = payload.clone();
        ByteBuffer.wrap(corrupted).putInt(29, 0x7FFFFFFF);

        IOException ex = assertThrows(IOException.class, () -> ChunkCodec.decode(corrupted));
        assertTrue(ex.getMessage().contains("lengths invalid"),
                "Expected 'lengths invalid' in: " + ex.getMessage());
    }

    // ── Corruption: compressed data ───────────────────────────

    @Test
    void corruptedCompressedDataIsRejected() throws IOException {
        byte[] payload = validPayload();
        byte[] corrupted = payload.clone();
        // XOR-flip 8 bytes inside the compressed block (offset 40 is safely inside
        // the compressed region for any realistic payload).
        for (int i = 0; i < 8; i++) {
            corrupted[40 + i] ^= 0xFF;
        }

        IOException ex = assertThrows(IOException.class, () -> ChunkCodec.decode(corrupted));
        String msg = ex.getMessage();
        assertTrue(msg.contains("zstd block decompression failed")
                        || msg.contains("Corrupt deflate block stream"),
                "Expected decompression failure message, got: " + msg);
    }

    // ── Edge shape: all-air chunk ─────────────────────────────

    @Test
    void allAirChunkRoundTripsAndStaysTiny() throws IOException {
        CcoPalettedChunkStorage blocks =
                CcoPalettedChunkStorage.createEmpty(16, 256, 16, BlockType.AIR);
        ChunkData allAir = ChunkData.builder()
                .chunkX(0).chunkZ(0)
                .blocks(blocks)
                .lastModified(LocalDateTime.of(2024, 1, 1, 12, 0))
                .build();
        byte[] payload = ChunkCodec.encode(allAir);

        // Assert rawLen (int at offset 25) is exactly 49.
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            in.readInt(); // magic
            in.readShort(); // version
            in.readInt(); // chunkX
            in.readInt(); // chunkZ
            in.readLong(); // lastModified
            in.readBoolean(); // featuresPopulated
            in.readBoolean(); // hasEntitiesGenerated
            in.readByte(); // compression flag
            int rawLen = in.readInt();
            assertEquals(49, rawLen,
                    "all-air raw section stream must be exactly 49 bytes");
        }

        ChunkData decoded = ChunkCodec.decode(payload);
        // Spot-check ~10 cells are AIR.
        com.openmason.engine.voxel.cco.data.CcoBlockStorage storage = decoded.getBlockStorage();
        int[][] spots = {{0, 0, 0}, {7, 63, 7}, {15, 127, 15}, {3, 128, 3},
                {8, 191, 8}, {1, 192, 1}, {14, 255, 14}, {5, 50, 5},
                {10, 100, 10}, {0, 255, 0}};
        for (int[] s : spots) {
            assertEquals(BlockType.AIR, storage.get(s[0], s[1], s[2]),
                    "Expected AIR at (" + s[0] + "," + s[1] + "," + s[2] + ")");
        }
    }

    // ── Edge: builder rejects bad storage ─────────────────────

    @Test
    void builderRejectsMissingOrWrongSizeStorage() {
        // No blocks supplied.
        IllegalStateException ex1 = assertThrows(IllegalStateException.class, () ->
                ChunkData.builder().chunkX(0).chunkZ(0).build());
        assertTrue(ex1.getMessage().contains("Invalid chunk block storage dimensions"),
                "Expected dimension error, got: " + ex1.getMessage());

        // Wrong height (128 instead of 256).
        CcoPalettedChunkStorage wrongSize =
                CcoPalettedChunkStorage.createEmpty(16, 128, 16, BlockType.AIR);
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, () ->
                ChunkData.builder().chunkX(0).chunkZ(0).blocks(wrongSize).build());
        assertTrue(ex2.getMessage().contains("Invalid chunk block storage dimensions"),
                "Expected dimension error, got: " + ex2.getMessage());
    }

    // ── Edge: malformed water key ─────────────────────────────

    @Test
    void malformedWaterKeyThrowsUnchecked() throws IOException {
        ChunkData chunk = createFixture();
        Map<String, ChunkData.WaterBlockData> badWater = new HashMap<>();
        badWater.put("bogus", new ChunkData.WaterBlockData(3, false));
        ChunkData badChunk = ChunkData.builder()
                .chunkX(chunk.getChunkX()).chunkZ(chunk.getChunkZ())
                .blocks(chunk.getBlockStorage())
                .lastModified(chunk.getLastModified())
                .waterMetadata(badWater)
                .build();

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> ChunkCodec.encode(badChunk));
        assertTrue(ex.getMessage().contains("Invalid water metadata key"),
                "Expected 'Invalid water metadata key' in: " + ex.getMessage());
    }

    // ── Round-trip: block states (including multi-byte UTF-8) ─

    @Test
    void blockStatesRoundTripIncludingUtf8() throws IOException {
        Map<Integer, String> states = new HashMap<>();
        states.put(LocalBlockKey.pack(3, 70, 9), "furnace:lit=true");
        states.put(LocalBlockKey.pack(0, 0, 0), "door:état=öppen");

        ChunkData chunk = ChunkData.builder()
                .chunkX(0).chunkZ(0)
                .blocks(CcoPalettedChunkStorage.createEmpty(16, 256, 16, BlockType.AIR))
                .lastModified(LocalDateTime.of(2024, 1, 1, 12, 0))
                .blockStates(states)
                .build();

        byte[] payload = ChunkCodec.encode(chunk);
        ChunkData decoded = ChunkCodec.decode(payload);
        assertEquals(states, decoded.getBlockStates(),
                "Block states should round-trip including multi-byte UTF-8 values");
    }

    // ── Edge: snow layers clamp to [1, 8] ─────────────────────

    @Test
    void snowLayersClampTo1Through8() throws IOException {
        Map<Integer, Integer> snow = new HashMap<>();
        snow.put(LocalBlockKey.pack(1, 65, 1), 0);   // below min → 1
        snow.put(LocalBlockKey.pack(2, 65, 2), 99);  // above max → 8
        snow.put(LocalBlockKey.pack(3, 65, 3), 4);   // in range → 4

        ChunkData chunk = ChunkData.builder()
                .chunkX(0).chunkZ(0)
                .blocks(CcoPalettedChunkStorage.createEmpty(16, 256, 16, BlockType.AIR))
                .lastModified(LocalDateTime.of(2024, 1, 1, 12, 0))
                .snowLayers(snow)
                .build();

        byte[] payload = ChunkCodec.encode(chunk);
        ChunkData decoded = ChunkCodec.decode(payload);

        assertEquals(1, decoded.getSnowLayers().get(LocalBlockKey.pack(1, 65, 1)),
                "Snow value 0 should clamp to 1");
        assertEquals(8, decoded.getSnowLayers().get(LocalBlockKey.pack(2, 65, 2)),
                "Snow value 99 should clamp to 8");
        assertEquals(4, decoded.getSnowLayers().get(LocalBlockKey.pack(3, 65, 3)),
                "Snow value 4 should remain 4");
    }

    // ── Thread safety: concurrent encode/decode ───────────────

    @Test
    void concurrentEncodeDecodeIsSafe() throws InterruptedException, ExecutionException {
        int threads = 8;
        int iterations = 25;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(pool.submit((Callable<Void>) () -> {
                for (int i = 0; i < iterations; i++) {
                    CcoPalettedChunkStorage blocks =
                            CcoPalettedChunkStorage.createEmpty(16, 256, 16, BlockType.AIR);
                    // Place a few deterministic blocks based on thread + iteration.
                    int x0 = threadId % 16;
                    int y0 = (i * 3 + threadId * 7) % 256;
                    int z0 = (threadId + i) % 16;
                    blocks.set(x0, y0, z0, BlockType.STONE);
                    blocks.set((x0 + 5) % 16, (y0 + 1) % 256, (z0 + 3) % 16, BlockType.DIRT);
                    blocks.set((x0 + 10) % 16, (y0 + 2) % 256, (z0 + 7) % 16, BlockType.GRASS);

                    ChunkData chunk = ChunkData.builder()
                            .chunkX(threadId).chunkZ(i)
                            .blocks(blocks)
                            .lastModified(LocalDateTime.of(2024, 1, 1, 12, 0))
                            .build();

                    byte[] payload = ChunkCodec.encode(chunk);
                    ChunkData decoded = ChunkCodec.decode(payload);

                    // Spot-check ~20 deterministic cells.
                    com.openmason.engine.voxel.cco.data.CcoBlockStorage decodedStorage =
                            decoded.getBlockStorage();
                    for (int cx = 0; cx < 16; cx++) {
                        for (int cy = 0; cy < 64; cy += 32) {
                            for (int cz = 0; cz < 16; cz++) {
                                assertEquals(blocks.get(cx, cy, cz),
                                        decodedStorage.get(cx, cy, cz),
                                        "Mismatch at (" + cx + "," + cy + "," + cz +
                                                ") thread=" + threadId + " iter=" + i);
                            }
                        }
                    }
                }
                return null;
            }));
        }

        for (Future<?> f : futures) {
            f.get(); // propagates any exception from the worker.
        }
        pool.shutdown();
    }
}