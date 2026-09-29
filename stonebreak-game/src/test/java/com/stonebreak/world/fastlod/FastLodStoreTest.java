package com.stonebreak.world.fastlod;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The store only ever serves nodes sampled from the terrain it was opened for. A
 * DaedalusTGM-Exp world's terrain changes whenever the model is retrained, and the store is
 * consulted before the sampler, so a node kept across that change would stand where the
 * chunk under it no longer does — forever.
 */
class FastLodStoreTest {

    private static final FastLodKey KEY = FastLodKey.of(FastLodLevel.L4, 3, -2);

    private static FastLodChunkData node() {
        int[] heights = new int[FastLodLevel.L4.heightCount()];
        Arrays.fill(heights, 90);
        return new FastLodChunkData(KEY, heights, new int[] {-1}, new BlockType[] {BlockType.GRASS}, null);
    }

    private static void write(Path db, String tag) {
        FastLodStore store = FastLodStore.open(db, tag);
        assertNotNull(store);
        store.saveAsync(node());
        store.close(); // drains the write
    }

    private static FastLodChunkData read(Path db, String tag) {
        FastLodStore store = FastLodStore.open(db, tag);
        assertNotNull(store);
        try {
            return store.tryLoad(KEY);
        } finally {
            store.close();
        }
    }

    @Test
    void servesNodesSampledFromTheSameTerrain(@TempDir Path dir) {
        Path db = dir.resolve("fastlod/cache.sqlite");
        write(db, "DaedalusTGM-Exp:aaaa");
        FastLodChunkData loaded = read(db, "DaedalusTGM-Exp:aaaa");
        assertNotNull(loaded);
        assertArrayEquals(node().rawHeights(), loaded.rawHeights());
    }

    @Test
    void discardsNodesSampledFromOtherTerrain(@TempDir Path dir) {
        Path db = dir.resolve("fastlod/cache.sqlite");
        write(db, "DaedalusTGM-Exp:aaaa");
        assertNull(read(db, "DaedalusTGM-Exp:bbbb"), "a retrained model's world must resample");
        assertNull(read(db, "DaedalusTGM-Exp:aaaa"), "and the old nodes are gone, not hidden");
    }
}
