package com.stonebreak.world.fastlod;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeKind;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Round-trip and rejection behavior of the FLOD binary codec. Rejections must
 * return null (never throw) — the store treats null as a cache miss and falls
 * back to live terrain sampling.
 */
class FastLodSerializerTest {

    /** Header layout: magic u32, version u8, level u8, cellsPerAxis u8, stride u8. */
    private static final int HEADER_SIZE = 8;

    private static FastLodChunkData makeData(FastLodLevel level, boolean withTrees) {
        FastLodKey key = FastLodKey.of(level, 3, -7);
        int[] heights = new int[level.heightCount()];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = 40 + (i % 90) - 20;   // varied, in-short-range values incl. < SEA_LEVEL
        }
        BlockType[] surface = new BlockType[level.cellCount()];
        for (int i = 0; i < surface.length; i++) {
            surface[i] = switch (i % 3) {
                case 0 -> BlockType.GRASS;
                case 1 -> BlockType.STONE;
                default -> BlockType.WATER;
            };
        }
        TreeSample[] trees = null;
        if (withTrees) {
            trees = new TreeSample[level.cellCount()];
            trees[0] = new TreeSample(TreeKind.OAK, 5);
            trees[7] = new TreeSample(TreeKind.PINE, 9);
            trees[level.cellCount() - 1] = new TreeSample(TreeKind.ELM, 3);
        }
        return new FastLodChunkData(key, heights, surface, trees);
    }

    @Test
    void roundTripL0WithTrees() {
        FastLodChunkData original = makeData(FastLodLevel.L0, true);
        byte[] blob = FastLodSerializer.serialize(original);
        FastLodChunkData restored = FastLodSerializer.deserialize(original.key(), blob);

        assertNotNull(restored);
        assertEquals(original.key(), restored.key());
        assertArrayEquals(original.rawHeights(), restored.rawHeights());
        assertArrayEquals(original.rawSurface(), restored.rawSurface());

        TreeSample[] origTrees = original.rawTrees();
        TreeSample[] restTrees = restored.rawTrees();
        assertNotNull(restTrees);
        assertEquals(origTrees.length, restTrees.length);
        for (int i = 0; i < origTrees.length; i++) {
            assertEquals(origTrees[i], restTrees[i], "tree mismatch at cell " + i);
        }
    }

    @Test
    void roundTripCoarseLevelWithoutTrees() {
        FastLodChunkData original = makeData(FastLodLevel.L2, false);
        byte[] blob = FastLodSerializer.serialize(original);
        FastLodChunkData restored = FastLodSerializer.deserialize(original.key(), blob);

        assertNotNull(restored);
        assertArrayEquals(original.rawHeights(), restored.rawHeights());
        assertArrayEquals(original.rawSurface(), restored.rawSurface());
        assertNull(restored.rawTrees());
    }

    @Test
    void heightsClampToShortRange() {
        FastLodChunkData data = makeData(FastLodLevel.L4, false);
        data.rawHeights()[0] = Integer.MAX_VALUE;
        data.rawHeights()[1] = Integer.MIN_VALUE;
        FastLodChunkData restored = FastLodSerializer.deserialize(
                data.key(), FastLodSerializer.serialize(data));
        assertNotNull(restored);
        assertEquals(Short.MAX_VALUE, restored.rawHeights()[0]);
        assertEquals(Short.MIN_VALUE, restored.rawHeights()[1]);
    }

    @Test
    void rejectsBadMagic() {
        FastLodChunkData data = makeData(FastLodLevel.L2, false);
        byte[] blob = FastLodSerializer.serialize(data);
        blob[0] ^= 0x5A;
        assertNull(FastLodSerializer.deserialize(data.key(), blob));
    }

    @Test
    void rejectsUnknownVersion() {
        FastLodChunkData data = makeData(FastLodLevel.L2, false);
        byte[] blob = FastLodSerializer.serialize(data);
        blob[4] = (byte) (FastLodSerializer.VERSION + 1);
        assertNull(FastLodSerializer.deserialize(data.key(), blob));
    }

    @Test
    void rejectsLevelMismatchWithKey() {
        FastLodChunkData data = makeData(FastLodLevel.L1, false);
        byte[] blob = FastLodSerializer.serialize(data);
        FastLodKey wrongLevel = FastLodKey.of(FastLodLevel.L2, data.chunkX(), data.chunkZ());
        assertNull(FastLodSerializer.deserialize(wrongLevel, blob));
    }

    @Test
    void rejectsGeometryMismatch() {
        // Patch an L1 blob's level byte to L2: the level now matches the key,
        // but cellsPerAxis/stride no longer do — stale-format defense.
        FastLodChunkData data = makeData(FastLodLevel.L1, false);
        byte[] blob = FastLodSerializer.serialize(data);
        blob[5] = (byte) FastLodLevel.L2.index();
        FastLodKey l2Key = FastLodKey.of(FastLodLevel.L2, data.chunkX(), data.chunkZ());
        assertNull(FastLodSerializer.deserialize(l2Key, blob));
    }

    @Test
    void rejectsTruncationAtEveryRegion() {
        FastLodChunkData data = makeData(FastLodLevel.L0, true);
        byte[] blob = FastLodSerializer.serialize(data);
        FastLodLevel level = data.level();

        int heightsEnd = HEADER_SIZE + level.heightCount() * 2;
        int surfaceEnd = heightsEnd + level.cellCount() * 2;
        int treeFlagEnd = surfaceEnd + 1;
        int[] cuts = {
                0, 4, 7,                    // shorter than the header
                HEADER_SIZE,                // header only
                heightsEnd - 3,             // mid-heights
                surfaceEnd - 1,             // mid-surface
                treeFlagEnd,                // tree flag says trees follow, but they don't
                treeFlagEnd + level.cellCount(),      // kinds present, trunk heights missing
                blob.length - 1             // one byte short
        };
        for (int cut : cuts) {
            byte[] truncated = Arrays.copyOf(blob, cut);
            assertNull(FastLodSerializer.deserialize(data.key(), truncated),
                    "expected rejection at cut=" + cut);
        }
    }

    @Test
    void rejectsInvalidTreeKind() {
        FastLodChunkData data = makeData(FastLodLevel.L0, true);
        byte[] blob = FastLodSerializer.serialize(data);
        int kindsStart = HEADER_SIZE + data.level().heightCount() * 2 + data.level().cellCount() * 2 + 1;
        blob[kindsStart] = (byte) 200;   // ordinal 199 — far past TreeKind.values()
        assertNull(FastLodSerializer.deserialize(data.key(), blob));
    }

    @Test
    void rejectsL0BlobWithoutTrees() {
        // An L0 node must carry a tree layer; a blob claiming otherwise is stale.
        FastLodChunkData data = makeData(FastLodLevel.L0, false);
        byte[] blob = FastLodSerializer.serialize(data);
        assertNull(FastLodSerializer.deserialize(data.key(), blob));
    }

    @Test
    void dropsTreesOnCoarseLevelsInsteadOfRejecting() {
        // Hand-craft an L1 blob with an appended tree section: treePresent → 1,
        // then kind + trunkHeight arrays. The reader keeps the terrain data and
        // silently drops the trees (coarse levels never emit them).
        FastLodChunkData data = makeData(FastLodLevel.L1, false);
        byte[] blob = FastLodSerializer.serialize(data);
        int cells = data.level().cellCount();
        byte[] patched = Arrays.copyOf(blob, blob.length + cells * 2);
        patched[blob.length - 1] = 1;   // treePresent flag is the last byte of a tree-less blob
        patched[blob.length] = 2;       // one arbitrary valid tree kind (ordinal 1)
        FastLodChunkData restored = FastLodSerializer.deserialize(data.key(), patched);
        assertNotNull(restored);
        assertNull(restored.rawTrees());
        assertArrayEquals(data.rawHeights(), restored.rawHeights());
    }
}
