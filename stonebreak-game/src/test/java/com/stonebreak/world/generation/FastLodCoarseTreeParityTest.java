package com.stonebreak.world.generation;

import com.stonebreak.world.fastlod.FastLodChunkData;
import com.stonebreak.world.fastlod.FastLodKey;
import com.stonebreak.world.fastlod.FastLodLevel;
import com.stonebreak.world.fastlod.FastLodSampler;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coarse FastLOD levels draw trees past the finest band (preset-dependent, see
 * {@code FastLodQuality.drawsTrees}). They must be the SAME trees: each coarse cell probes
 * all of its columns with the generator's own placement, but against the cell's
 * representative biome and surface rather than each column's own, so a few trees on
 * biome or carve edges may differ. Measured against L0 — which probes every column
 * exactly — on a forested snowy seed. Measured 2026-10-03 (16x16 chunks): recall/precision
 * L1 0.98/0.92, L2 1.00/0.91, uncarved L3 0.93/0.85; floors leave room for the sweep.
 */
class FastLodCoarseTreeParityTest {

    private static final long SEED = 20260820L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int SWEEP = 24;

    private record Agreement(int fine, int coarse, int shared) {
        double recall() { return fine == 0 ? 1 : (double) shared / fine; }
        double precision() { return coarse == 0 ? 1 : (double) shared / coarse; }
    }

    private static Agreement measure(FastLodLevel level, boolean carved) {
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED);
        FastLodSampler sampler = new FastLodSampler(terrain);
        int fine = 0, coarse = 0, shared = 0;
        for (int cx = -SWEEP / 2; cx < SWEEP / 2; cx++) {
            for (int cz = -SWEEP / 2; cz < SWEEP / 2; cz++) {
                FastLodChunkData l0 = sampler.sample(FastLodKey.of(FastLodLevel.L0, cx, cz));
                Set<Integer> fineTrees = new HashSet<>();
                for (int x = 0; x < CHUNK; x++) {
                    for (int z = 0; z < CHUNK; z++) {
                        if (l0.treeAt(x, z) != null) fineTrees.add(x * CHUNK + z);
                    }
                }
                FastLodChunkData c = sampler.sample(FastLodKey.of(level, cx, cz, carved, true));
                fine += fineTrees.size();
                for (int spot : c.treeSpots()) {
                    coarse++;
                    if (fineTrees.contains(FastLodChunkData.spotX(spot) * CHUNK + FastLodChunkData.spotZ(spot))) {
                        shared++;
                    }
                }
            }
        }
        return new Agreement(fine, coarse, shared);
    }

    private static void assertAgrees(FastLodLevel level, boolean carved, double floor) {
        Agreement a = measure(level, carved);
        String msg = String.format(Locale.ROOT, "%s carved=%s: L0 %d trees, coarse %d, shared %d (recall %.3f, precision %.3f)",
            level, carved, a.fine(), a.coarse(), a.shared(), a.recall(), a.precision());
        System.out.println("[coarse-trees] " + msg);
        assertTrue(a.fine() >= 50, "sweep too sparse to prove anything: " + msg);
        assertTrue(a.recall() >= floor && a.precision() >= floor, msg);
    }

    @Test
    void l1DrawsTheTreesTheFinestLevelDraws() {
        assertAgrees(FastLodLevel.L1, true, 0.85);
    }

    @Test
    void l2DrawsTheTreesTheFinestLevelDraws() {
        assertAgrees(FastLodLevel.L2, true, 0.80);
    }

    @Test
    void uncarvedL3DrawsTheTreesTheFinestLevelDraws() {
        assertAgrees(FastLodLevel.L3, false, 0.75);
    }
}
