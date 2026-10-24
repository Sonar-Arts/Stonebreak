package com.openmason.engine.net.protocol.codec;

import com.openmason.engine.net.replication.BlockSetter;
import com.openmason.engine.net.replication.IBlockTypeResolver;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.IVoxelChunkData;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #345: chunk snapshots are encoded straight from the paletted sections (uniform sections
 * without touching a cell, indexed sections from their own palette and indices), and the
 * {@link ChunkBlockPayload} a server sends is encoded at most once, from a copy-on-write
 * snapshot that later writes to the live chunk cannot reach.
 */
@Tag("regression")
class VoxelChunkStorageEncodeTest {

    private static final int W = VoxelChunkCodec.CHUNK_W;
    private static final int H = VoxelChunkCodec.CHUNK_H;

    /** Minimal block keyed on id; one instance per id, as palettes compare by identity. */
    private record TestBlock(int id) implements IBlockType {
        public int getId() { return id; }
        public String getName() { return "block" + id; }
        public boolean isSolid() { return id != 0; }
        public boolean isBreakable() { return id != 0; }
        public boolean isTransparent() { return false; }
        public boolean isAir() { return id == 0; }
    }

    private static final TestBlock[] BLOCKS = new TestBlock[1024];
    static {
        for (int i = 0; i < BLOCKS.length; i++) {
            BLOCKS[i] = new TestBlock(i);
        }
    }
    private static final IBlockTypeResolver RESOLVER = id -> BLOCKS[id];

    private static CcoPalettedChunkStorage emptyStorage() {
        return CcoPalettedChunkStorage.createEmpty(W, H, W, BLOCKS[0]);
    }

    private static void fillSection(CcoPalettedChunkStorage s, int sy, java.util.function.IntSupplier id) {
        for (int y = sy * 16; y < sy * 16 + 16; y++)
            for (int z = 0; z < W; z++)
                for (int x = 0; x < W; x++)
                    s.set(x, y, z, BLOCKS[id.getAsInt()]);
    }

    /** One section per storage tier, plus the shapes a live chunk leaves behind. */
    private static CcoPalettedChunkStorage everyTier() {
        CcoPalettedChunkStorage s = emptyStorage();
        Random r = new Random(345L);
        fillSection(s, 0, () -> 7);                         // uniform, non-air
        fillSection(s, 1, () -> 1 + r.nextInt(12));          // nibble tier
        fillSection(s, 2, () -> 1 + r.nextInt(200));         // byte tier
        fillSection(s, 3, () -> 1 + r.nextInt(600));         // > 256 entries: wide tier
        s.set(3, 70, 4, BLOCKS[9]);                          // section 4: one odd cell
        s.set(5, 90, 5, BLOCKS[6]);                          // section 5: a palette entry
        s.set(5, 90, 5, BLOCKS[0]);                          //   no cell uses any more
        fillSection(s, 15, () -> 2);                         // top section uniform, non-air
        return s;
    }

    private static int[] decode(byte[] payload) {
        int[] out = new int[W * H * W];
        BlockSetter sink = (x, y, z, type) -> out[(y * W + z) * W + x] = type.getId();
        VoxelChunkCodec.decodeInto(payload, sink, RESOLVER);
        return out;
    }

    private static int[] cells(CcoPalettedChunkStorage s) {
        int[] out = new int[W * H * W];
        for (int y = 0; y < H; y++)
            for (int z = 0; z < W; z++)
                for (int x = 0; x < W; x++)
                    out[(y * W + z) * W + x] = s.get(x, y, z).getId();
        return out;
    }

    @Test
    void storageEncodeRoundTripsEveryTier() {
        CcoPalettedChunkStorage storage = everyTier();
        assertArrayEquals(cells(storage), decode(VoxelChunkCodec.encode(storage)));
    }

    @Test
    void storageEncodeDecodesLikeTheCellPath() {
        CcoPalettedChunkStorage storage = everyTier();
        IVoxelChunkData cellView = new IVoxelChunkData() {
            public IBlockType getBlock(int x, int y, int z) { return storage.get(x, y, z); }
            public int getChunkX() { return 0; }
            public int getChunkZ() { return 0; }
        };
        assertArrayEquals(decode(VoxelChunkCodec.encode(cellView)), decode(VoxelChunkCodec.encode(storage)));
    }

    @Test
    void uniformSectionsShipInThreeBytes() {
        byte[] payload = VoxelChunkCodec.encode(emptyStorage());
        assertEquals(VoxelChunkCodec.SECTIONS_PER_CHUNK * 3, payload.length);
    }

    @Test
    void payloadEncodesOnceAndOnlyWhenAsked() {
        ChunkBlockPayload payload = ChunkBlockPayload.ofSnapshot(everyTier());
        assertFalse(payload.isEncoded(), "a local receiver never needs the bytes");

        byte[] first = payload.encoded();
        assertTrue(payload.isEncoded());
        assertSame(first, payload.encoded(), "every connection shares one encode");
    }

    @Test
    void snapshotIgnoresLaterWritesToTheLiveStorage() {
        CcoPalettedChunkStorage live = everyTier();
        int[] before = cells(live);
        ChunkBlockPayload payload = ChunkBlockPayload.ofSnapshot(live.copy());

        live.set(1, 20, 1, BLOCKS[500]);   // nibble section
        live.set(0, 0, 0, BLOCKS[3]);      // uniform section
        live.set(2, 40, 2, BLOCKS[777]);   // byte section

        assertArrayEquals(before, cells(payload.snapshot()));
        assertArrayEquals(before, decode(payload.encoded()));
    }

    @Test
    void wirePayloadCarriesNoSnapshot() {
        byte[] bytes = VoxelChunkCodec.encode(emptyStorage());
        ChunkBlockPayload payload = ChunkBlockPayload.ofEncoded(bytes);
        assertNull(payload.snapshot());
        assertSame(bytes, payload.encoded());
    }
}
