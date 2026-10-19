package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.fastlod.FastLodChunkData;
import com.stonebreak.world.fastlod.FastLodKey;
import com.stonebreak.world.fastlod.FastLodLevel;
import com.stonebreak.world.fastlod.FastLodSampler;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeKind;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Swamps in FastLOD look like the swamps the chunks hold: pools of water over a dirt bed,
 * cypress on the swampy grass and in shallow pools, and the same cypress past the finest
 * band (they were missing there: the coarse tree probe only looked at grass and snowy dirt).
 */
class FastLodSwampTest {

    private static final long SEED = 1234L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;

    private final TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED);
    private final FastLodSampler sampler = new FastLodSampler(terrain);

    /** Chunks whose every probed column is swamp. */
    private List<int[]> swampChunks(int want) {
        List<int[]> found = new ArrayList<>();
        for (int cx = -400; cx < 400 && found.size() < want; cx += 3) {
            for (int cz = -400; cz < 400 && found.size() < want; cz += 3) {
                boolean all = true;
                for (int p = 0; p < CHUNK && all; p += 5) {
                    for (int q = 0; q < CHUNK && all; q += 5) {
                        all = terrain.getBiomeAt(cx * CHUNK + p, cz * CHUNK + q) == BiomeType.SWAMP;
                    }
                }
                if (all) found.add(new int[]{cx, cz});
            }
        }
        assertTrue(found.size() == want, "seed " + SEED + " has only " + found.size() + " swamp chunks in range");
        return found;
    }

    @Test
    void finestLevelShowsPoolsAndCypressStandingInThem() {
        int pools = 0, cores = 0, cypress = 0, cypressInPools = 0, columns = 0;
        for (int[] c : swampChunks(12)) {
            FastLodChunkData d = sampler.sample(FastLodKey.of(FastLodLevel.L0, c[0], c[1]));
            for (int x = 0; x < CHUNK; x++) {
                for (int z = 0; z < CHUNK; z++) {
                    columns++;
                    int depth = d.waterLevelAt(x, z) - d.heightAt(x, z);
                    boolean pool = depth > 0 && d.surfaceAt(x, z) == BlockType.DIRT;
                    if (pool) {
                        assertTrue(depth <= 2, "a pool is one or two blocks deep, got " + depth);
                        pools++;
                        if (depth == 2) cores++;
                    }
                    TreeSample tree = d.treeAt(x, z);
                    if (tree != null && tree.kind() == TreeKind.CYPRESS) {
                        cypress++;
                        if (pool) {
                            cypressInPools++;
                            assertTrue(depth == 1, "a cypress never stands in a pool core");
                        }
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "[swamp-lod] %d columns: %d pool (%d core), %d cypress (%d in pools)%n",
            columns, pools, cores, cypress, cypressInPools);
        assertTrue(pools > columns / 10, "pools " + pools + " of " + columns);
        assertTrue(cores > 0 && cypress > 0 && cypressInPools > 0,
            "cores " + cores + ", cypress " + cypress + ", in pools " + cypressInPools);
    }

    @Test
    void coarseLevelsDrawTheFinestLevelsCypress() {
        for (FastLodLevel level : new FastLodLevel[]{FastLodLevel.L1, FastLodLevel.L2}) {
            int fine = 0, coarse = 0, shared = 0;
            for (int[] c : swampChunks(12)) {
                FastLodChunkData l0 = sampler.sample(FastLodKey.of(FastLodLevel.L0, c[0], c[1]));
                Set<Integer> fineCypress = new HashSet<>();
                for (int x = 0; x < CHUNK; x++) {
                    for (int z = 0; z < CHUNK; z++) {
                        TreeSample t = l0.treeAt(x, z);
                        if (t != null && t.kind() == TreeKind.CYPRESS) fineCypress.add(x * CHUNK + z);
                    }
                }
                fine += fineCypress.size();
                FastLodChunkData d = sampler.sample(FastLodKey.of(level, c[0], c[1], true, true));
                for (int spot : d.treeSpots()) {
                    if (FastLodChunkData.spotKind(spot) != TreeKind.CYPRESS) continue;
                    coarse++;
                    if (fineCypress.contains(FastLodChunkData.spotX(spot) * CHUNK + FastLodChunkData.spotZ(spot))) {
                        shared++;
                    }
                }
            }
            double recall = fine == 0 ? 0 : (double) shared / fine;
            double precision = coarse == 0 ? 0 : (double) shared / coarse;
            String msg = String.format(Locale.ROOT, "%s: L0 %d cypress, coarse %d, shared %d (recall %.3f, precision %.3f)",
                level, fine, coarse, shared, recall, precision);
            System.out.println("[swamp-lod] " + msg);
            assertTrue(fine >= 20, "too few cypress to prove anything: " + msg);
            assertTrue(recall >= 0.8 && precision >= 0.8, msg);
        }
    }
}
