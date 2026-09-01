package com.stonebreak.world.fastlod;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeKind;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Compact binary codec for {@link FastLodChunkData} blobs persisted in the
 * {@link FastLodStore}. The format is self-describing enough to survive
 * level-structure refactors: a version byte plus the cell geometry fields
 * let a reader reject blobs whose layout no longer matches.
 *
 * Wire layout (little-endian):
 * <pre>
 *   magic        u32  'FLOD' (0x444F4C46)
 *   version      u8   current = 3 (v3: heights are the carved surface — ravines and
 *                     sinkholes are cut into LOD terrain rather than drawn flat — and
 *                     coarse cells carry a per-cell cave-mouth channel drawn as a recessed
 *                     notch where the height's single representative probe missed the
 *                     opening. Pre-v3 blobs hold the uncarved height and no opening data,
 *                     so they must be resampled or the distance rings keep showing flat
 *                     ground and ravines only.
 *                     v2: submerged cells store the real seabed block instead of WATER;
 *                     submergence is height-derived)
 *   level        u8   0..FastLodLevel.count-1
 *   cellsPerAxis u8
 *   stride       u8
 *   heights      i16[stride*stride]       world Y (clamped to short range)
 *   surface      u16[cellsPerAxis²]       BlockType.getId()
 *   openPresent  u8   0 = opening channel omitted (L0), 1 = the two arrays follow
 *   openFloor?   i16[cellsPerAxis²]       world Y, or OPEN_NONE (Short.MIN_VALUE)
 *   openCover?   u8[cellsPerAxis²]        carved share of the cell, 0..255
 *   treePresent  u8   0 = trees omitted, 1 = trees follow
 *   trees?       u8[cellsPerAxis²] kind   0 = none, else TreeKind.ordinal()+1
 *   trunkH?      u8[cellsPerAxis²]        0 if no tree
 * </pre>
 */
public final class FastLodSerializer {

    public static final int VERSION = 3;
    private static final int MAGIC  = 0x444F4C46; // 'FLOD' little-endian
    /**
     * Wire sentinel for "this cell has no cave mouth". The in-memory sentinel
     * ({@link TerrainGenerationSystem#NO_OPENING}) is an int and the field is i16, so it
     * needs its own value; no real floor can reach Short.MIN_VALUE.
     */
    private static final short OPEN_NONE = Short.MIN_VALUE;

    private FastLodSerializer() {}

    public static byte[] serialize(FastLodChunkData data) {
        FastLodLevel level = data.level();
        int heightsLen = level.heightCount();
        int cellsLen   = level.cellCount();
        TreeSample[] trees = data.rawTrees();
        boolean hasTrees = trees != null;
        int[] openFloor = data.rawOpeningFloor();
        byte[] openCover = data.rawOpeningCoverage();
        boolean hasOpenings = openFloor != null;

        int size = 4 + 1 + 1 + 1 + 1
                 + heightsLen * 2
                 + cellsLen   * 2
                 + 1
                 + (hasOpenings ? cellsLen * 3 : 0)
                 + 1
                 + (hasTrees ? cellsLen * 2 : 0);

        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(MAGIC);
        buf.put((byte) VERSION);
        buf.put((byte) level.index());
        buf.put((byte) level.cellsPerAxis());
        buf.put((byte) level.stride());

        int[] heights = data.rawHeights();
        for (int i = 0; i < heightsLen; i++) {
            buf.putShort((short) clampShort(heights[i]));
        }

        BlockType[] surface = data.rawSurface();
        for (int i = 0; i < cellsLen; i++) {
            BlockType b = surface[i];
            int id = (b == null) ? 0 : b.getId();
            buf.putShort((short) id);
        }

        if (hasOpenings) {
            buf.put((byte) 1);
            for (int i = 0; i < cellsLen; i++) {
                int v = openFloor[i];
                buf.putShort((short) (v == TerrainGenerationSystem.NO_OPENING
                        ? OPEN_NONE : clampShort(v)));
            }
            buf.put(openCover, 0, cellsLen);
        } else {
            buf.put((byte) 0);
        }

        if (hasTrees) {
            buf.put((byte) 1);
            TreeKind[] kinds = TreeKind.values();
            for (int i = 0; i < cellsLen; i++) {
                TreeSample t = trees[i];
                buf.put((byte) (t == null ? 0 : (t.kind().ordinal() + 1)));
                // Confirm ordinal stays in range at compile time via assert.
                assert t == null || t.kind().ordinal() < kinds.length;
            }
            for (int i = 0; i < cellsLen; i++) {
                TreeSample t = trees[i];
                buf.put((byte) (t == null ? 0 : clampUnsignedByte(t.trunkHeight())));
            }
        } else {
            buf.put((byte) 0);
        }

        return buf.array();
    }

    /**
     * @return deserialized data, or {@code null} if the blob is malformed,
     *         uses an unknown version, or doesn't match the requested key's
     *         level geometry (stale format after a LOD refactor).
     */
    public static FastLodChunkData deserialize(FastLodKey expected, byte[] bytes) {
        if (bytes == null || bytes.length < 8) return null;
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        if (buf.getInt() != MAGIC) return null;
        int version = buf.get() & 0xFF;
        if (version != VERSION) return null;
        int levelIdx = buf.get() & 0xFF;
        if (levelIdx != expected.level().index()) return null;

        FastLodLevel level = expected.level();
        int cellsPerAxis = buf.get() & 0xFF;
        int stride       = buf.get() & 0xFF;
        if (cellsPerAxis != level.cellsPerAxis() || stride != level.stride()) return null;

        int heightsLen = level.heightCount();
        int cellsLen   = level.cellCount();
        int needed = heightsLen * 2 + cellsLen * 2 + 1;
        if (buf.remaining() < needed) return null;

        int[] heights = new int[heightsLen];
        for (int i = 0; i < heightsLen; i++) {
            heights[i] = buf.getShort();
        }

        BlockType[] surface = new BlockType[cellsLen];
        for (int i = 0; i < cellsLen; i++) {
            int id = buf.getShort() & 0xFFFF;
            BlockType b = BlockType.getById(id);
            surface[i] = (b != null) ? b : BlockType.AIR;
        }

        int hasOpenings = buf.get() & 0xFF;
        int[] openFloor = null;
        byte[] openCover = null;
        if (hasOpenings == 1) {
            if (buf.remaining() < cellsLen * 3) return null;
            openFloor = new int[cellsLen];
            for (int i = 0; i < cellsLen; i++) {
                short v = buf.getShort();
                openFloor[i] = (v == OPEN_NONE)
                        ? TerrainGenerationSystem.NO_OPENING : v;
            }
            openCover = new byte[cellsLen];
            buf.get(openCover);
        }

        if (buf.remaining() < 1) return null;
        int hasTrees = buf.get() & 0xFF;
        TreeSample[] trees = null;
        if (hasTrees == 1) {
            if (buf.remaining() < cellsLen * 2) return null;
            trees = new TreeSample[cellsLen];
            byte[] kindBytes = new byte[cellsLen];
            byte[] heightBytes = new byte[cellsLen];
            buf.get(kindBytes);
            buf.get(heightBytes);
            TreeKind[] kinds = TreeKind.values();
            for (int i = 0; i < cellsLen; i++) {
                int k = kindBytes[i] & 0xFF;
                if (k == 0) continue;
                int ord = k - 1;
                if (ord >= kinds.length) return null;
                int trunkH = heightBytes[i] & 0xFF;
                trees[i] = new TreeSample(kinds[ord], trunkH);
            }
        }

        if (level.emitsTrees() && trees == null) return null;
        if (!level.emitsTrees() && trees != null) trees = null;
        // L0 cells are single columns, so their carve is already the height and the
        // sampler writes no opening channel. A blob that disagrees is from a different
        // layout than this reader expects.
        if (level.cellSize() == 1 && openFloor != null) return null;

        return new FastLodChunkData(expected, heights, surface, trees,
                openFloor, openCover);
    }

    private static int clampShort(int v) {
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        return v;
    }

    private static int clampUnsignedByte(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }
}
