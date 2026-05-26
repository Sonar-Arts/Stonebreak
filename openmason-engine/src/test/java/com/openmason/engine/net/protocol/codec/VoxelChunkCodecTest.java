package com.openmason.engine.net.protocol.codec;

import com.openmason.engine.net.replication.BlockSetter;
import com.openmason.engine.net.replication.IBlockTypeResolver;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.IVoxelChunkData;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for {@link VoxelChunkCodec}: every section-encoding strategy
 * (SINGLE, PALETTED, DIRECT) plus a mixed chunk, asserting block-by-block id equality
 * through a fake {@link IVoxelChunkData} source and a capturing {@link BlockSetter} sink.
 */
class VoxelChunkCodecTest {

    private static final int W = VoxelChunkCodec.CHUNK_W;   // 16
    private static final int H = VoxelChunkCodec.CHUNK_H;   // 256
    private static final int SECTION_H = VoxelChunkCodec.SECTION_H;

    // ─── Test doubles ─────────────────────────────────────────────────────────

    /** Minimal {@link IBlockType} keyed only on id; air is id 0. */
    private record TestBlock(int id) implements IBlockType {
        public int getId() { return id; }
        public String getName() { return "block" + id; }
        public boolean isSolid() { return id != 0; }
        public boolean isBreakable() { return id != 0; }
        public boolean isTransparent() { return false; }
        public boolean isAir() { return id == 0; }
    }

    private static final IBlockTypeResolver RESOLVER = TestBlock::new;

    /** Chunk source backed by an id grid [x][y][z]. */
    private static final class FakeChunk implements IVoxelChunkData {
        final int[][][] ids = new int[W][H][W];
        public IBlockType getBlock(int x, int y, int z) { return new TestBlock(ids[x][y][z]); }
        public int getChunkX() { return 0; }
        public int getChunkZ() { return 0; }
    }

    // ─── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void allAirRoundTripsAsSingle() {
        FakeChunk chunk = new FakeChunk(); // all zeros
        byte[] payload = VoxelChunkCodec.encode(chunk);
        // 16 sections, each SINGLE = 1 tag byte + 2 id bytes.
        assertTrue(payload.length <= VoxelChunkCodec.SECTIONS_PER_CHUNK * 3,
            "all-air chunk should encode as SINGLE sections, got " + payload.length + " bytes");
        assertRoundTrip(chunk);
    }

    @Test
    void twoColorRoundTripsAsPaletted() {
        FakeChunk chunk = new FakeChunk();
        for (int x = 0; x < W; x++)
            for (int y = 0; y < H; y++)
                for (int z = 0; z < W; z++)
                    chunk.ids[x][y][z] = ((x + y + z) & 1) == 0 ? 1 : 2;
        assertRoundTrip(chunk);
    }

    @Test
    void over256DistinctRoundTripsAsDirect() {
        FakeChunk chunk = new FakeChunk();
        // Fill the bottom section (y 0..15) with 4096 distinct ids → forces DIRECT.
        int id = 1;
        for (int y = 0; y < SECTION_H; y++)
            for (int z = 0; z < W; z++)
                for (int x = 0; x < W; x++)
                    chunk.ids[x][y][z] = id++; // 1..4096, all distinct
        assertRoundTrip(chunk);
    }

    @Test
    void mixedRoundTrips() {
        FakeChunk chunk = new FakeChunk();
        int[] palette = {0, 1, 2, 3, 7, 42, 255};
        Random r = new Random(98765L);
        for (int x = 0; x < W; x++)
            for (int y = 0; y < H; y++)
                for (int z = 0; z < W; z++)
                    chunk.ids[x][y][z] = palette[r.nextInt(palette.length)];
        assertRoundTrip(chunk);
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private static void assertRoundTrip(FakeChunk chunk) {
        byte[] payload = VoxelChunkCodec.encode(chunk);
        int[][][] out = new int[W][H][W];
        BlockSetter sink = (x, y, z, type) -> out[x][y][z] = type.getId();
        VoxelChunkCodec.decodeInto(payload, sink, RESOLVER);
        for (int x = 0; x < W; x++)
            for (int y = 0; y < H; y++)
                assertArrayEquals(chunk.ids[x][y], out[x][y],
                    "mismatch at column x=" + x + " y=" + y);
    }
}
