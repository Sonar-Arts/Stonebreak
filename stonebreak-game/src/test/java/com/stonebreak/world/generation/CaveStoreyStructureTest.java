package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.heightmap.CaveWaterTable;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.TerrainNoise;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Caves must actually cluster into horizontal storeys.
 *
 * <p>The geological structure is the point of {@link CaveWaterTable}: passage shape should
 * tell a player how deep they are, with level galleries at each present-or-former water table
 * and different behaviour above and below. That is delivered by widening the spaghetti
 * threshold with {@link CaveWaterTable#galleryWeight}, which is a subtle enough mechanism
 * that it could be diluted to nothing — by a retuned constant, a rescaled table, or a
 * threshold change — without any other test noticing. Every other cave test would still pass
 * on a perfectly uniform, storey-less mush.
 *
 * <p>The assertion is deliberately statistical and scale-free rather than tied to absolute
 * Y values, which move with terrain: carved blocks should sit closer to a gallery level than
 * rock does. Comparing the mean gallery weight of carved cells against the mean over all
 * sub-surface cells cancels out how much of the column happens to be near a storey.
 */
public class CaveStoreyStructureTest {

    private static final long SEED = 24680L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int REGION = 6;

    /**
     * How much more "in a gallery" carved rock must be than rock in general, per noise backend.
     *
     * <p>The floor has to sit between the ratio with the gallery mechanism switched OFF
     * ({@code Density3D.SPAG_GALLERY_BONUS = 0} and {@code PerlinWormCarver.GALLERY_DAMP = 1}) and
     * the ratio the tuning produces with it ON, so ordinary retuning does not trip it and switching
     * the mechanism off does. Off is not 1.0: carving still prefers some depths for other reasons
     * (worm placement, the epiphreatic pitch zones), and how much differs by backend. The two
     * backends use different simplex implementations, so they need their own floors: native ON is
     * below Java OFF, and one shared floor would either fail native or pass Java with storeys off.
     *
     * <p>Measured 2026-11-15 (after the noise router's altitude chill moved to y 110, 4ea3d20a),
     * seed {@value #SEED}, {@value #REGION}x{@value #REGION} chunks:
     * native ON 1.616 / OFF 1.300; Java ON 2.166 / OFF 1.703 (spaghetti bonus alone off: 1.787).
     * The old single floor 1.65 (from native 1.699 / Java 1.844 in Aug 2026) sat 3% under native.
     */
    private static final double MIN_GALLERY_ENRICHMENT_NATIVE = 1.50;
    private static final double MIN_GALLERY_ENRICHMENT_JAVA = 1.95;

    @Test
    public void carvedVolumeClustersIntoWaterTableStoreys() {
        DryHillsHeightMap src = new DryHillsHeightMap(SEED);
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED, src);
        HeightMapGenerator heightMap = src;
        CaveWaterTable waterTable = new CaveWaterTable(SEED, heightMap);

        int[] heights = new int[CHUNK * CHUNK];
        int[] waterLevels = new int[CHUNK * CHUNK];

        double carvedWeight = 0;
        long carvedCount = 0;
        double allWeight = 0;
        long allCount = 0;

        for (int cx = 0; cx < REGION; cx++) {
            for (int cz = 0; cz < REGION; cz++) {
                Chunk chunk = terrain.generateTerrainOnly(cx, cz).chunk();
                heightMap.populateChunkHeights(cx, cz, heights, waterLevels);
                int[] table = waterTable.tableForChunk(cx, cz, heights, waterLevels);

                for (int lx = 0; lx < CHUNK; lx++) {
                    for (int lz = 0; lz < CHUNK; lz++) {
                        int idx = lx * CHUNK + lz;
                        int surface = heights[idx];
                        int t = table[idx];
                        for (int y = 1; y < surface; y++) {
                            float w = CaveWaterTable.galleryWeight(t, y);
                            allWeight += w;
                            allCount++;
                            if (StandardFrame.block(chunk, lx, y, lz) == BlockType.AIR) {
                                carvedWeight += w;
                                carvedCount++;
                            }
                        }
                    }
                }
            }
        }

        assertTrue(carvedCount > 0, "no carved volume at all — nothing to measure");
        assertTrue(allCount > 0, "no sub-surface volume at all");

        double carvedMean = carvedWeight / carvedCount;
        double baselineMean = allWeight / allCount;
        double enrichment = carvedMean / baselineMean;

        System.out.printf("[caves] gallery enrichment: %.3fx (carved mean %.4f vs baseline "
                        + "%.4f over %d carved / %d sub-surface cells)%n",
                enrichment, carvedMean, baselineMean, carvedCount, allCount);

        TerrainNoise.Backend backend = TerrainNoise.backend();
        double floor = backend == TerrainNoise.Backend.NATIVE
                ? MIN_GALLERY_ENRICHMENT_NATIVE : MIN_GALLERY_ENRICHMENT_JAVA;
        assertTrue(enrichment >= floor, String.format(
                "carved volume is only %.3fx as close to a gallery level as rock in general "
                        + "(need %.2fx on the %s backend) — caves are not forming storeys, they "
                        + "are uniform mush",
                enrichment, floor, backend));
    }
}
