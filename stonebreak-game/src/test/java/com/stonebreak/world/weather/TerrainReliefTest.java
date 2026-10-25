package com.stonebreak.world.weather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relief the clouds meet: cloud piles up on the windward face of a high
 * ridge, light cloud is steered around ground that blocks it while massive
 * cloud rides over, and cloud reaches the ground but not into it.
 */
class TerrainReliefTest {

    /** Ridge crest, in cells of tile (0, 0); the wind blows toward +x. */
    private static final int CREST = 32;

    /** A north-south ridge 200 blocks high, crossing the (mostly eastward) wind. */
    private static float ridge(long ci, long cj) {
        double d = (ci - CREST) / 6.0;
        return (float) (64 + 200 * Math.exp(-d * d));
    }

    @Test
    void cloudPilesUpOnTheWindwardFaceOnly() {
        TerrainRelief.Tile tile = TerrainRelief.derive(0, 0, TerrainReliefTest::ridge);
        int j = 32;
        float windward = tile.at(TerrainRelief.PILEUP, CREST - 5, j);
        float lee = tile.at(TerrainRelief.PILEUP, CREST + 8, j);
        assertTrue(windward > 0.3f, "windward pile-up " + windward);
        assertEquals(0f, lee, 1e-6f, "the lee slope descends: nothing piles up");
    }

    @Test
    void slopePointsUphill() {
        TerrainRelief.Tile tile = TerrainRelief.derive(0, 0, TerrainReliefTest::ridge);
        assertTrue(tile.at(TerrainRelief.GRAD_X, CREST - 6, 10) > 0f);
        assertTrue(tile.at(TerrainRelief.GRAD_X, CREST + 6, 10) < 0f);
        assertEquals(0f, tile.at(TerrainRelief.GRAD_Z, CREST - 6, 10), 1e-4f);
    }

    private static TerrainRelief.Sample sampleOf(TerrainRelief.Tile t, int i, int j) {
        return new TerrainRelief.Sample(t.at(TerrainRelief.HEIGHT, i, j), t.at(TerrainRelief.SMOOTH, i, j),
                t.at(TerrainRelief.GRAD_X, i, j), t.at(TerrainRelief.GRAD_Z, i, j), t.at(TerrainRelief.PILEUP, i, j),
                t.at(TerrainRelief.SHIFT_X, i, j), t.at(TerrainRelief.SHIFT_Z, i, j), t.at(TerrainRelief.STEER, i, j));
    }

    @Test
    void flatGroundLeavesCloudsAlone() {
        TerrainRelief.Tile tile = TerrainRelief.derive(3, -2, (i, j) -> 70f);
        TerrainRelief.Sample s = sampleOf(tile, 5, 5);
        assertEquals(0f, TerrainFlow.pileup(s));
        assertEquals(TerrainFlow.Flow.NONE, TerrainFlow.around(s, 0f));
        assertEquals(1f, TerrainFlow.clip(s, 180f));
    }

    @Test
    void cloudReachesTheGroundButNotIntoIt() {
        TerrainRelief.Sample hill = new TerrainRelief.Sample(170f, 160f, 0f, 0f, 0f, 0f, 0f, 0f);
        assertEquals(1f, TerrainFlow.clip(hill, 170f), "cloud sits right on the surface");
        assertEquals(0f, TerrainFlow.clip(hill, 170f - TerrainFlow.CLIP_SINK));
    }

    @Test
    void lightCloudGoesAroundAndMassiveCloudGoesOver() {
        // Below the ridge's flank, where it rises through the cloud layer to the east (+x).
        TerrainRelief.Tile tile = TerrainRelief.derive(0, 0, TerrainReliefTest::ridge);
        int i = strongestSteer(tile, 0, CREST, 10);
        TerrainRelief.Sample flank = sampleOf(tile, i, 10);
        TerrainFlow.Flow light = TerrainFlow.around(flank, 0.2f);
        assertTrue(light.shiftX() > 0.25f * TerrainFlow.DEFLECT_REACH, "steered around, uphill: " + light);
        assertEquals(0f, light.shiftZ(), 1e-3f);
        assertTrue(light.coverage() > 0f, "squeezed cloud thickens: " + light);
        assertEquals(TerrainFlow.Flow.NONE, TerrainFlow.around(flank, 1f), "a massive cloud holds its course");
        // On the far flank, uphill is west.
        int lee = strongestSteer(tile, CREST, TerrainRelief.TILE_CELLS, 10);
        assertTrue(TerrainFlow.around(sampleOf(tile, lee, 10), 0.2f).shiftX() < 0f);
    }

    /** Column {@code i} in [from, to) of row {@code j} where the steering is strongest. */
    private static int strongestSteer(TerrainRelief.Tile t, int from, int to, int j) {
        int best = from;
        for (int i = from; i < to; i++)
            if (t.at(TerrainRelief.STEER, i, j) > t.at(TerrainRelief.STEER, best, j)) best = i;
        return best;
    }

    /** A round mountain 260 blocks high and only ~70 across: the steepest steering the field can be asked for. */
    private static float peak(long ci, long cj) {
        double di = (ci - 30) / 3.0, dj = (cj - 34) / 3.0;
        return (float) (64 + 260 * Math.exp(-(di * di + dj * dj)));
    }

    /**
     * The terrain bends the cloud map but never folds it: neighbouring air
     * keeps its order (the map x -> x + shift keeps a positive Jacobian), or
     * the same cloud is drawn twice with a smear between. And it still steers.
     */
    @Test
    void steeringNeverFoldsTheCloudMap() {
        for (TerrainRelief.CellHeights terrain : new TerrainRelief.CellHeights[]{
                TerrainReliefTest::ridge, TerrainReliefTest::peak}) {
            TerrainRelief.Tile t = TerrainRelief.derive(0, 0, terrain);
            float c = TerrainRelief.CELL, minDet = Float.MAX_VALUE, maxShift = 0f;
            for (int j = 1; j < TerrainRelief.TILE_CELLS - 1; j++) {
                for (int i = 1; i < TerrainRelief.TILE_CELLS - 1; i++) {
                    float dxx = (t.at(TerrainRelief.SHIFT_X, i + 1, j) - t.at(TerrainRelief.SHIFT_X, i - 1, j)) / (2 * c);
                    float dxz = (t.at(TerrainRelief.SHIFT_X, i, j + 1) - t.at(TerrainRelief.SHIFT_X, i, j - 1)) / (2 * c);
                    float dzx = (t.at(TerrainRelief.SHIFT_Z, i + 1, j) - t.at(TerrainRelief.SHIFT_Z, i - 1, j)) / (2 * c);
                    float dzz = (t.at(TerrainRelief.SHIFT_Z, i, j + 1) - t.at(TerrainRelief.SHIFT_Z, i, j - 1)) / (2 * c);
                    minDet = Math.min(minDet, (1 + dxx) * (1 + dzz) - dxz * dzx);
                    maxShift = Math.max(maxShift, (float) Math.hypot(t.at(TerrainRelief.SHIFT_X, i, j),
                            t.at(TerrainRelief.SHIFT_Z, i, j)));
                }
            }
            assertTrue(minDet > 0.25f, "the cloud map folds: Jacobian " + minDet);
            assertTrue(maxShift > 0.1f * TerrainFlow.DEFLECT_REACH, "steering is still live: " + maxShift);
        }
    }

    @Test
    void reliefSamplesBilinearlyBetweenCellCentres() throws Exception {
        try (TerrainRelief relief = new TerrainRelief((x0, z0, count, stride, out) -> {
            for (int ix = 0; ix < count; ix++)
                for (int iz = 0; iz < count; iz++)
                    out[ix * count + iz] = Math.floorDiv(x0 + ix * stride, TerrainRelief.CELL) % 2 == 0 ? 100 : 120;
        })) {
            relief.focus(10, 10);
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (relief.tile(0, 0) == null && System.nanoTime() < deadline) Thread.sleep(10);
            TerrainRelief.Tile t = relief.tile(0, 0);
            assertTrue(t != null, "the focus tile derives");
            int c = TerrainRelief.CELL;
            // Cell 2 holds 100, cell 3 holds 120; centres at 2.5 and 3.5 cells.
            assertEquals(100f, relief.sample(2.5 * c, 2.5 * c).height(), 1e-3f);
            assertEquals(110f, relief.sample(3.0 * c, 2.5 * c).height(), 1e-3f);
            assertEquals(TerrainRelief.Sample.FLAT, relief.sample(1e7, 1e7));
        }
    }
}
