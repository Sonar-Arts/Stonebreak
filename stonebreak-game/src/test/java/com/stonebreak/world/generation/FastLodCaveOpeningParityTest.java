package com.stonebreak.world.generation;

import com.stonebreak.world.fastlod.FastLodChunkData;
import com.stonebreak.world.fastlod.FastLodKey;
import com.stonebreak.world.fastlod.FastLodLevel;
import com.stonebreak.world.fastlod.FastLodSampler;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end, against real generated terrain: does a coarse LOD node actually represent the
 * cave openings inside it?
 *
 * <p>Heights are one representative probe per cell, so before the opening channel a cave mouth
 * survived coarsening only by being hit. Ravines are long and wide and got hit; a worm mouth
 * or a sinkhole a few blocks across did not, which is why the distance rings showed ravines
 * and effectively nothing else. Measured hit rates by the probe alone: 79% at L1, 54% at L2,
 * 33% at L3, 15% at L4.
 *
 * <p>Each test below compares the probe-only hit rate against the rate once the opening
 * channel is counted, on the same terrain in the same run. Asserting only the second number
 * would pass on terrain that never needed the fix, so both are asserted — the point is the
 * gap between them.
 */
public class FastLodCaveOpeningParityTest {

    private static final long SEED = 12345L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    /**
     * Chunks per side. Sized by what L4 needs, not by runtime: L4 is one cell per chunk, so a
     * 12-chunk sweep left 39 cells carrying a carve and a single unlucky cell moved the rate
     * by 2.6 points — 89.7% against a 90% floor, which measured the sample and not the
     * channel. At 24 the coarsest level sees 171 such cells (L1 sees 2365) and the rates
     * settle at 97.4 / 95.7 / 91.2%.
     */
    private static final int SWEEP = 24;
    /** Below this the sweep found too little carved terrain to be evidence of anything. */
    private static final int MIN_CELLS_WITH_CARVE = 8;

    private record Coverage(int cellsWithCarve, int probeOnly, int withOpenings) {}

    private static Coverage measure(FastLodLevel level) {
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED, new DryHillsHeightMap(SEED));
        FastLodSampler sampler = new FastLodSampler(terrain);
        int cells = level.cellsPerAxis();
        int cellSize = level.cellSize();

        int withCarve = 0, probeOnly = 0, withOpenings = 0;
        for (int cx = 0; cx < SWEEP; cx++) {
            for (int cz = 0; cz < SWEEP; cz++) {
                FastLodChunkData data = sampler.sample(FastLodKey.of(level, cx, cz));
                for (int ix = 0; ix < cells; ix++) {
                    for (int iz = 0; iz < cells; iz++) {
                        boolean carveInFootprint = false;
                        for (int dx = 0; dx < cellSize && !carveInFootprint; dx++) {
                            for (int dz = 0; dz < cellSize && !carveInFootprint; dz++) {
                                int wx = cx * CHUNK + ix * cellSize + dx;
                                int wz = cz * CHUNK + iz * cellSize + dz;
                                carveInFootprint = terrain.carvedSurfaceHeight(wx, wz)
                                        < terrain.getFinalTerrainHeightAt(wx, wz);
                            }
                        }
                        if (!carveInFootprint) continue;
                        withCarve++;

                        int shown = data.heightAt(ix, iz);
                        int rep = terrain.getFinalTerrainHeightAt(
                                cx * CHUNK + ix * cellSize + (cellSize == 1 ? 0 : cellSize / 2),
                                cz * CHUNK + iz * cellSize + (cellSize == 1 ? 0 : cellSize / 2));
                        boolean probeSaw = shown < rep;
                        int floor = data.openingFloorAt(ix, iz);
                        boolean notch = floor != TerrainGenerationSystem.NO_OPENING && floor < shown;

                        if (probeSaw) probeOnly++;
                        if (probeSaw || notch) withOpenings++;
                    }
                }
            }
        }
        return new Coverage(withCarve, probeOnly, withOpenings);
    }

    private static void assertCoarseLevelShowsItsOpenings(FastLodLevel level, int minGainPercent) {
        Coverage c = measure(level);
        assertTrue(c.cellsWithCarve() >= MIN_CELLS_WITH_CARVE, String.format(
                "%s: only %d cells in the sweep contained a carve, so this proved nothing",
                level, c.cellsWithCarve()));

        double probePct = 100.0 * c.probeOnly() / c.cellsWithCarve();
        double fullPct = 100.0 * c.withOpenings() / c.cellsWithCarve();
        assertTrue(fullPct >= 90.0, String.format(
                "%s: only %.1f%% of cells containing a cave mouth draw one (%d of %d)",
                level, fullPct, c.withOpenings(), c.cellsWithCarve()));
        assertTrue(fullPct - probePct >= minGainPercent, String.format(
                "%s: the opening channel added only %.1f points over the height probe alone "
                        + "(%.1f%% -> %.1f%%); either it stopped working or the sweep no longer "
                        + "contains openings small enough to be probed away",
                level, fullPct - probePct, probePct, fullPct));
    }

    @Test
    public void l1ShowsOpeningsItsHeightProbeMisses() {
        assertCoarseLevelShowsItsOpenings(FastLodLevel.L1, 10);
    }

    @Test
    public void l2ShowsOpeningsItsHeightProbeMisses() {
        assertCoarseLevelShowsItsOpenings(FastLodLevel.L2, 25);
    }

    @Test
    public void theCoarsestLevelShowsOpeningsItsHeightProbeMisses() {
        // L4 is one 16-block cell per chunk — the probe hits roughly one opening in seven.
        assertCoarseLevelShowsItsOpenings(FastLodLevel.L4, 50);
    }

    /**
     * The finest level must be untouched. Its cells are single columns, so the carve is
     * already the height and there is nothing for a notch to add; the LOD band nearest the
     * player has to draw exactly what it drew before this channel existed.
     */
    @Test
    public void theFinestLevelIsUnchanged() {
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED, new DryHillsHeightMap(SEED));
        FastLodSampler sampler = new FastLodSampler(terrain);
        int carved = 0;
        for (int cx = 0; cx < 4; cx++) {
            for (int cz = 0; cz < 4; cz++) {
                FastLodChunkData data = sampler.sample(FastLodKey.of(FastLodLevel.L0, cx, cz));
                assertEquals(false, data.hasOpenings(), "L0 carries no opening channel");
                for (int ix = 0; ix < CHUNK; ix++) {
                    for (int iz = 0; iz < CHUNK; iz++) {
                        int wx = cx * CHUNK + ix, wz = cz * CHUNK + iz;
                        assertEquals(terrain.carvedSurfaceHeight(wx, wz), data.heightAt(ix, iz),
                                "L0 height is still the carved surface, exactly");
                        if (data.heightAt(ix, iz) < terrain.getFinalTerrainHeightAt(wx, wz)) carved++;
                    }
                }
            }
        }
        assertTrue(carved > 0, "sweep contained no carved columns, so it compared nothing");
    }
}
