package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.fastlod.FastLodChunkData;
import com.stonebreak.world.fastlod.FastLodKey;
import com.stonebreak.world.fastlod.FastLodLevel;
import com.stonebreak.world.fastlod.FastLodSampler;
import com.stonebreak.world.generation.diffusion.DryHillsTileSource;
import com.stonebreak.world.generation.heightmap.Density3D;
import com.stonebreak.world.generation.heightmap.RavineCarver;
import com.stonebreak.world.generation.noise.TerrainNoise;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FastLOD draws one height per column, so that height has to be the top of the column the
 * block loop actually writes — not the raw tile height the column starts from.
 *
 * <p>It used not to be. {@code sampleColumns} returned {@code generateHeight} and no carve
 * mask reached the LOD package at all, so a ravine was drawn beyond the loaded seam as a flat
 * sheet with a dark foundation wall closing the cut, and a 25-60 block trench appeared the
 * instant the chunk loaded. Trees came off the same uncarved height and stood over columns
 * that in reality have no ground block, because the real {@code VegetationGenerator} reads the
 * block at {@code surface - 1} and finds air there.
 *
 * <p>Nothing else in the suite compares LOD output against real generation — the other
 * {@code fastlod} tests all build {@code FastLodChunkData} by hand, and the sampler had no
 * test at all. So this is the only thing standing between the two paths and a silent redrift.
 *
 * <p>Both tests count the columns they actually prove something about and assert a floor on
 * that count. Without it a sweep that happened to land on uncarved terrain would pass while
 * testing nothing, which is exactly how a heights-vs-generator parity test can stay green
 * through a change that breaks it.
 */
public class FastLodCarveParityTest {

    private static final long SEED = 12345L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;

    /** Chunks per side of the general sweep. Each one is a full chunk generation. */
    private static final int SWEEP = 4;

    /**
     * The sweep is small and ravines are rare (one anchor per 450 chunks), so most of its
     * carved columns come from the {@code Density3D} overhang band. That is fine — the point
     * of the floor is only that the comparison is not vacuous; the ravine case gets its own
     * test below.
     */
    private static final int MIN_CARVED_COLUMNS = 50;

    /** Chunks each way around a ravine anchor to look in. */
    private static final int RAVINE_RING = 3;

    @Test
    public void lodHeightIsTheTopOfTheRealColumn() {
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED, new DryHillsTileSource());
        FastLodSampler sampler = new FastLodSampler(terrain);

        int carvedColumns = 0;
        for (int cx = 0; cx < SWEEP; cx++) {
            for (int cz = 0; cz < SWEEP; cz++) {
                Chunk chunk = terrain.generateTerrainOnly(cx, cz).chunk();
                FastLodChunkData data = sampler.sample(new FastLodKey(FastLodLevel.L0, cx, cz));

                for (int lx = 0; lx < CHUNK; lx++) {
                    for (int lz = 0; lz < CHUNK; lz++) {
                        int worldX = cx * CHUNK + lx;
                        int worldZ = cz * CHUNK + lz;
                        int expected = topSolid(chunk, lx, lz) + 1;
                        int actual = data.heightAt(lx, lz);
                        assertEquals(expected, actual, String.format(
                                "LOD draws column (%d,%d) at y=%d but the generated chunk's "
                                        + "highest block there is y=%d — LOD terrain that "
                                        + "disagrees with the chunk is the seam the player "
                                        + "walks into",
                                worldX, worldZ, actual, expected - 1));

                        int raw = terrain.getFinalTerrainHeightAt(worldX, worldZ);
                        if (actual < raw) {
                            carvedColumns++;
                            assertNull(data.treeAt(lx, lz), String.format(
                                    "LOD plants a tree on column (%d,%d), whose top %d blocks "
                                            + "were carved away — the real generator reads the "
                                            + "block at y=%d, finds air, and plants nothing",
                                    worldX, worldZ, raw - actual, raw - 1));
                        }
                    }
                }
            }
        }

        assertTrue(carvedColumns >= MIN_CARVED_COLUMNS, String.format(
                "only %d of the %d columns swept were carved below their raw height, so this "
                        + "run compared almost nothing — the sweep needs terrain the carvers "
                        + "actually cut into, or it passes whatever the sampler does",
                carvedColumns, SWEEP * SWEEP * CHUNK * CHUNK));
    }

    /**
     * The same parity, pinned to the native noise backend on purpose.
     *
     * <p>{@code buildSurfaceProfile} must descend on {@link Density3D#prepareChunk}'s field,
     * the one {@code generateTerrainOnly}'s block loop reads — not on per-point
     * {@link Density3D#isSolid}. On the native backend those are not two spellings of one
     * thing: {@code isSolid} samples the Java simplex generator, the prepared field is a
     * FastNoise2 SIMD volume fill, and they describe different caves. Descending per-point
     * put the LOD surface up to 16 blocks above the chunk that replaces it on 0.79% of
     * columns — terrain that vanishes, and uncovers whatever it was hiding, the moment the
     * chunk loads.
     *
     * <p>The trap is that per-point looks like the cheaper choice, and the code used to carry
     * a comment saying so. It is cheaper; it is also a different world. The sweep above would
     * catch this too, but only when it runs with the kernels loaded — hence the explicit
     * assumption here, so a run without them reports as skipped rather than as proof.
     */
    @Test
    public void lodHeightMatchesTheChunkOnTheNativeNoiseBackend() {
        assumeTrue(TerrainNoise.backend() == TerrainNoise.Backend.NATIVE,
                "needs the Cenda kernels; the Java backend has only one density path to agree with");

        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED, new DryHillsTileSource());
        FastLodSampler sampler = new FastLodSampler(terrain);

        int compared = 0;
        int carvedColumns = 0;
        for (int cx = 0; cx < SWEEP; cx++) {
            for (int cz = 0; cz < SWEEP; cz++) {
                Chunk chunk = terrain.generateTerrainOnly(cx, cz).chunk();
                FastLodChunkData data = sampler.sample(new FastLodKey(FastLodLevel.L0, cx, cz));
                for (int lx = 0; lx < CHUNK; lx++) {
                    for (int lz = 0; lz < CHUNK; lz++) {
                        int expected = topSolid(chunk, lx, lz) + 1;
                        int actual = data.heightAt(lx, lz);
                        assertEquals(expected, actual, String.format(
                                "column (%d,%d): LOD draws y=%d, chunk's top block is y=%d. "
                                        + "The surface profile is reading a different density "
                                        + "field than the block loop",
                                cx * CHUNK + lx, cz * CHUNK + lz, actual, expected - 1));
                        compared++;
                        if (actual < terrain.getFinalTerrainHeightAt(cx * CHUNK + lx, cz * CHUNK + lz)) {
                            carvedColumns++;
                        }
                    }
                }
            }
        }

        assertEquals(SWEEP * SWEEP * CHUNK * CHUNK, compared);
        assertTrue(carvedColumns >= MIN_CARVED_COLUMNS,
                "sweep hit no carved terrain, so it proved nothing; saw " + carvedColumns);
    }

    /**
     * The case from the bug report: a ravine is the deepest thing that opens onto the surface,
     * and it is what made the flat-LOD seam impossible to miss. Checking the sweep above would
     * not catch a fix that handled only the shallow overhang band.
     */
    @Test
    public void aRavineIsCutIntoLodTerrain() {
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED, new DryHillsTileSource());
        FastLodSampler sampler = new FastLodSampler(terrain);
        // hasRavine is a pure hash of seed and chunk coords, so a throwaway carver answers it
        // for the system under test without needing access to its private one.
        RavineCarver oracle = new RavineCarver(SEED, new DryHillsHeightMap(SEED));

        int deepest = 0;
        int found = 0;
        for (int cx = 0; cx < 400 && found == 0; cx++) {
            for (int cz = 0; cz < 400 && found == 0; cz++) {
                if (!oracle.hasRavine(cx, cz)) continue;
                found++;
                // The anchor sits somewhere in (cx,cz) and the two arms run outward from it,
                // so where the cut actually breaks the surface is several chunks off. Inside
                // the anchor's own chunk this ravine's roof sits ~29 blocks down and changes
                // no surface block at all; a one-chunk ring would prove nothing.
                for (int dcx = -RAVINE_RING; dcx <= RAVINE_RING; dcx++) {
                    for (int dcz = -RAVINE_RING; dcz <= RAVINE_RING; dcz++) {
                        FastLodChunkData data = sampler.sample(
                                new FastLodKey(FastLodLevel.L0, cx + dcx, cz + dcz));
                        for (int lx = 0; lx < CHUNK; lx++) {
                            for (int lz = 0; lz < CHUNK; lz++) {
                                int worldX = (cx + dcx) * CHUNK + lx;
                                int worldZ = (cz + dcz) * CHUNK + lz;
                                deepest = Math.max(deepest,
                                        terrain.getFinalTerrainHeightAt(worldX, worldZ)
                                                - data.heightAt(lx, lz));
                            }
                        }
                    }
                }
            }
        }

        assertTrue(found > 0, "no ravine found in the search window — the test measured nothing");
        // Comfortably past the Density3D overhang band (16 blocks), so only a real ravine or
        // sinkhole cut can produce it.
        assertTrue(deepest >= 25, String.format(
                "the deepest cut LOD shows anywhere around a ravine is %d blocks, which is no "
                        + "more than the overhang band — the ravine itself is still being drawn "
                        + "as flat ground", deepest));
    }

    /** Highest non-air, non-water block in a column, or 0 (bedrock) if there is none. */
    private static int topSolid(Chunk chunk, int lx, int lz) {
        for (int y = WorldConfiguration.WORLD_HEIGHT - 1; y > 0; y--) {
            BlockType block = chunk.getBlock(lx, y, lz);
            if (block != BlockType.AIR && block != BlockType.WATER) {
                return y;
            }
        }
        return 0;
    }
}
