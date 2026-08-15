package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.diffusion.DryHillsTileSource;
import com.stonebreak.world.generation.heightmap.CaveWaterTable;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
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
     * How much more "in a gallery" carved rock must be than rock in general.
     *
     * <p>1.0 would mean carving ignores the water table entirely — the storey-less mush this
     * test exists to catch. Kept well above that but well below the raw ratio the tuning
     * currently produces, so ordinary retuning does not trip it and switching the mechanism
     * off does.
     */
    private static final double MIN_GALLERY_ENRICHMENT = 1.15;

    @Test
    public void carvedVolumeClustersIntoWaterTableStoreys() {
        DryHillsTileSource src = new DryHillsTileSource();
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED, src);
        HeightMapGenerator heightMap = new HeightMapGenerator(src);
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
                            if (chunk.getBlock(lx, y, lz) == BlockType.AIR) {
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

        assertTrue(enrichment >= MIN_GALLERY_ENRICHMENT, String.format(
                "carved volume is only %.3fx as close to a gallery level as rock in general "
                        + "(need %.2fx) — caves are not forming storeys, they are uniform mush",
                enrichment, MIN_GALLERY_ENRICHMENT));
    }
}
