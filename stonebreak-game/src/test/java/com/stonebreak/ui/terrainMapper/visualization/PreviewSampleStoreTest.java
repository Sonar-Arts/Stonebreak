package com.stonebreak.ui.terrainMapper.visualization;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store is what lets the mapper look back over ground already seen without terrain work.
 * Each read of {@link #columns} stands for a tile lookup that may reach the GPU, so the tests
 * count them.
 */
class PreviewSampleStoreTest {

    private static final long BIG_BUDGET = 64L * 1024 * 1024;

    private final AtomicInteger reads = new AtomicInteger();

    private final TerrainColumns columns = (x, z, out) -> {
        reads.incrementAndGet();
        out[PreviewChannel.HEIGHT.ordinal()] = x * 1000f + z;
        out[PreviewChannel.WATER.ordinal()] = -x;
        out[PreviewChannel.BIOME.ordinal()] = 3f;
    };

    @Test
    void aColumnIsReadOnceForEveryChannel() {
        PreviewSampleStore store = new PreviewSampleStore(BIG_BUDGET);

        assertEquals(5003f, store.valueAt(1L, 1, 5, 3, PreviewChannel.HEIGHT, columns), 0f);
        assertEquals(-5f, store.valueAt(1L, 1, 5, 3, PreviewChannel.WATER, columns), 0f);
        assertEquals(3f, store.valueAt(1L, 1, 5, 3, PreviewChannel.BIOME, columns), 0f);
        assertEquals(1, reads.get(), "switching mode must not go back to the terrain");
    }

    @Test
    void negativeCoordinatesDoNotCollide() {
        PreviewSampleStore store = new PreviewSampleStore(BIG_BUDGET);
        for (int x = -70; x <= 70; x += 7) {
            for (int z = -70; z <= 70; z += 5) {
                store.valueAt(1L, 1, x, z, PreviewChannel.HEIGHT, columns);
            }
        }
        for (int x = -70; x <= 70; x += 7) {
            for (int z = -70; z <= 70; z += 5) {
                assertEquals(x * 1000f + z, store.valueAt(1L, 1, x, z, PreviewChannel.HEIGHT, columns), 0f,
                        "(" + x + "," + z + ")");
            }
        }
    }

    @Test
    void groundSeenUpCloseIsAlreadyCachedWhenZoomedOut() {
        PreviewSampleStore store = new PreviewSampleStore(BIG_BUDGET);
        for (int x = -64; x < 64; x++) {
            store.valueAt(1L, 1, x, 16, PreviewChannel.HEIGHT, columns);
        }
        int afterCloseUp = reads.get();

        for (int x = -64; x < 64; x += 16) {
            store.valueAt(1L, 16, x, 16, PreviewChannel.HEIGHT, columns);
        }
        assertEquals(afterCloseUp, reads.get(), "every coarse point lies on the fine lattice already read");
    }

    @Test
    void seedsAreKeptApart() {
        PreviewSampleStore store = new PreviewSampleStore(BIG_BUDGET);
        store.valueAt(1L, 1, 0, 0, PreviewChannel.HEIGHT, columns);
        TerrainColumns otherWorld = (x, z, out) -> java.util.Arrays.fill(out, 42f);

        assertEquals(42f, store.valueAt(2L, 1, 0, 0, PreviewChannel.HEIGHT, otherWorld), 0f);
        assertEquals(0f, store.valueAt(1L, 1, 0, 0, PreviewChannel.HEIGHT, columns), 0f,
                "switching back to a seed shows its own terrain, not the last one's");
    }

    @Test
    void clearForgetsEverything() {
        PreviewSampleStore store = new PreviewSampleStore(BIG_BUDGET);
        store.valueAt(1L, 1, 0, 0, PreviewChannel.HEIGHT, columns);
        store.clear();
        assertEquals(0, store.chunkCount());
        store.valueAt(1L, 1, 0, 0, PreviewChannel.HEIGHT, columns);
        assertEquals(2, reads.get());
    }

    @Test
    void theBudgetEvictsTheLeastRecentlyUsedDetail() {
        // Room for ten chunks. Full-detail chunks are 32 blocks a side; x = 1 keeps every point off the
        // coarser lattices, so each point allocates exactly one chunk.
        PreviewSampleStore store = new PreviewSampleStore(10 * PreviewSampleStore.CHUNK_BYTES);
        store.beginPass();
        store.valueAt(1L, 1, 1, 0, PreviewChannel.HEIGHT, columns);   // the chunk we keep using
        for (int i = 1; i <= 30; i++) {
            store.beginPass();
            store.valueAt(1L, 1, 1, 0, PreviewChannel.HEIGHT, columns);
            store.valueAt(1L, 1, 1, i * 32, PreviewChannel.HEIGHT, columns);
        }
        assertTrue(store.chunkCount() <= 10, "held " + store.chunkCount());

        int before = reads.get();
        store.valueAt(1L, 1, 1, 0, PreviewChannel.HEIGHT, columns);
        assertEquals(before, reads.get(), "the chunk in constant use must survive eviction");
    }

    @Test
    void rejectsASpacingOffTheLattice() {
        PreviewSampleStore store = new PreviewSampleStore(BIG_BUDGET);
        assertThrows(IllegalArgumentException.class,
                () -> store.valueAt(1L, 3, 0, 0, PreviewChannel.HEIGHT, columns));
        assertThrows(IllegalArgumentException.class,
                () -> store.valueAt(1L, 128, 0, 0, PreviewChannel.HEIGHT, columns));
    }
}
