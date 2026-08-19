package com.openmason.engine.voxel.lighting;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sky-shadow heightmap every chunk's lighting stands on. The full rebuild is easy to get
 * right; the incremental {@link ChunkHeightMap#onBlockChanged} cases are where a stale height
 * hides — a column that stays dark after its roof is mined, or lights up under a block that was
 * just placed, is exactly this bookkeeping gone wrong, and it only shows on screen as a chunk
 * that looks subtly off until remeshed.
 */
class ChunkHeightMapTest {

    private static final int W = 4, H = 32, D = 4;

    /** Mutable opaque-block set standing in for the chunk's block array. */
    private static final class FakeBlocks implements ColumnOpacityProbe {
        final Set<Integer> opaque = new HashSet<>();

        static int key(int lx, int ly, int lz) {
            return (ly * D + lz) * W + lx;
        }

        void place(int lx, int ly, int lz) {
            opaque.add(key(lx, ly, lz));
        }

        void remove(int lx, int ly, int lz) {
            opaque.remove(key(lx, ly, lz));
        }

        @Override
        public boolean isOpaqueAt(int lx, int ly, int lz) {
            return opaque.contains(key(lx, ly, lz));
        }
    }

    private final FakeBlocks blocks = new FakeBlocks();
    private final ChunkHeightMap map = new ChunkHeightMap(W, H, D);

    @Test
    void recomputeFindsEachColumnsTopAndMarksPopulated() {
        blocks.place(0, 5, 0);
        blocks.place(0, 2, 0);  // shaded by the block above — must not win
        blocks.place(1, 20, 3);
        assertFalse(map.isPopulated());

        map.recomputeAll(blocks);

        assertTrue(map.isPopulated());
        assertEquals(6, map.getHeight(0, 0), "sky begins one above the topmost opaque block");
        assertEquals(21, map.getHeight(1, 3));
        assertEquals(ChunkHeightMap.SKY_ALL_THE_WAY_DOWN, map.getHeight(2, 2),
                "an empty column is sky all the way down");
    }

    @Test
    void placingAtOrAboveTheTopRaisesTheColumn() {
        blocks.place(1, 5, 1);
        map.recomputeAll(blocks);

        blocks.place(1, 9, 1);
        map.onBlockChanged(1, 9, 1, true, false, blocks);

        assertEquals(10, map.getHeight(1, 1));
    }

    @Test
    void placingBelowTheTopChangesNothing() {
        blocks.place(1, 9, 1);
        map.recomputeAll(blocks);

        blocks.place(1, 3, 1);
        map.onBlockChanged(1, 3, 1, true, false, blocks);

        assertEquals(10, map.getHeight(1, 1));
    }

    @Test
    void miningTheRoofRescansDownToTheNextOpaqueBlock() {
        blocks.place(2, 9, 2);
        blocks.place(2, 4, 2);
        map.recomputeAll(blocks);
        assertEquals(10, map.getHeight(2, 2));

        blocks.remove(2, 9, 2);
        map.onBlockChanged(2, 9, 2, false, true, blocks);

        assertEquals(5, map.getHeight(2, 2), "sky must reach the block that is now the top");
    }

    @Test
    void miningTheOnlyBlockOpensTheColumnToSky() {
        blocks.place(2, 9, 2);
        map.recomputeAll(blocks);

        blocks.remove(2, 9, 2);
        map.onBlockChanged(2, 9, 2, false, true, blocks);

        assertEquals(ChunkHeightMap.SKY_ALL_THE_WAY_DOWN, map.getHeight(2, 2));
    }

    @Test
    void miningBelowTheTopChangesNothing() {
        blocks.place(2, 9, 2);
        blocks.place(2, 4, 2);
        map.recomputeAll(blocks);

        blocks.remove(2, 4, 2);
        map.onBlockChanged(2, 4, 2, false, true, blocks);

        assertEquals(10, map.getHeight(2, 2));
    }

    @Test
    void aChangeBeforePopulationTriggersTheLazyFullRebuild() {
        blocks.place(0, 5, 0);
        blocks.place(3, 12, 3);

        map.onBlockChanged(0, 5, 0, true, false, blocks); // never recomputed — must self-heal

        assertTrue(map.isPopulated());
        assertEquals(6, map.getHeight(0, 0));
        assertEquals(13, map.getHeight(3, 3), "the lazy rebuild covers every column, not just the changed one");
    }

    @Test
    void externallyComputedHeightsInstallVerbatim() {
        int[] heights = new int[W * D];
        heights[2 * W + 1] = 17; // (lx=1, lz=2), same layout as the map's own scan

        map.populate(heights);

        assertTrue(map.isPopulated());
        assertEquals(17, map.getHeight(1, 2));
        assertEquals(0, map.getHeight(0, 0));
    }

    @Test
    void aWrongSizedHeightArrayIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> map.populate(new int[W * D - 1]));
    }

    @Test
    void impossibleDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkHeightMap(0, H, D));
        assertThrows(IllegalArgumentException.class, () -> new ChunkHeightMap(W, 40000, D),
                "heights are stored as shorts; a taller world must be caught at construction");
    }
}
