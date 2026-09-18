package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cave-carver water guard, tested directly on its own terms — it is the thing
 * standing between a retune of the carvers' Y bands and a river that drains into a
 * cavern forever.
 *
 * <p>Since the native water backend the guard is a precomputed per-chunk plane
 * covering each column's 4-neighborhood, because the original per-column rule left
 * the BANKS open: a carve entering the dry column beside a river at water level
 * opens the side wall and drains the river exactly like a bed breach.
 */
class WaterGuardTest {

    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int CLEARANCE = 6;
    /** The widest and deepest the guard's noise may take it past the fixed rule. */
    private static final int MAX_RADIUS = 3;
    private static final int MAX_EXTRA_DEPTH = 3;

    private static int idx(int x, int z) {
        return x * CHUNK + z;
    }

    /** One wet column at (4,4): bed 380, level 400, on otherwise dry 420 ground. */
    private static int[] plane() {
        int[] heights = new int[CHUNK * CHUNK];
        int[] water = new int[CHUNK * CHUNK];
        Arrays.fill(heights, 420);
        Arrays.fill(water, TerrainTile.NO_WATER);
        heights[idx(4, 4)] = 380;
        water[idx(4, 4)] = 400;
        return WaterGuard.guardPlane(heights, water, null, 0, 0);
    }

    @Test
    void suppressesCarvingWithinTheClearanceUnderAWetColumn() {
        int[] guard = plane();
        // Bed at y=380: everything from 374 up is off limits in that column.
        assertTrue(WaterGuard.seals(guard, idx(4, 4), 380, CLEARANCE));
        assertTrue(WaterGuard.seals(guard, idx(4, 4), 374, CLEARANCE));
        // Up to MAX_EXTRA_DEPTH deeper, by noise; never further.
        assertFalse(WaterGuard.seals(guard, idx(4, 4), 373 - MAX_EXTRA_DEPTH, CLEARANCE));
    }

    @Test
    void sealsTheBankColumnsBesideAWetColumn() {
        // The regression the plane exists for: the four dry neighbors are the
        // river's side walls, and a carve entering one at/below the water level
        // drains the river sideways. They are sealed from the WET column's bed.
        int[] guard = plane();
        assertTrue(WaterGuard.seals(guard, idx(3, 4), 380, CLEARANCE));
        assertTrue(WaterGuard.seals(guard, idx(5, 4), 374, CLEARANCE));
        assertTrue(WaterGuard.seals(guard, idx(4, 3), 399, CLEARANCE));
        assertTrue(WaterGuard.seals(guard, idx(4, 5), 380, CLEARANCE));
        assertFalse(WaterGuard.seals(guard, idx(4, 5), 373 - MAX_EXTRA_DEPTH, CLEARANCE));
        // Past the widest the noise can reach, nothing touches the water: open.
        assertFalse(WaterGuard.seals(guard, idx(4, 4 + MAX_RADIUS + 1), 380, CLEARANCE));
        assertFalse(WaterGuard.seals(guard, idx(2, 2), 380, CLEARANCE));
    }

    @Test
    void measuresFromTheBedNotFromTheWaterSurface() {
        // A deep lake: surface 400, bed 340. A band anchored to the water level
        // would leave the 60 blocks of bed holding the lake up wide open.
        int[] heights = new int[CHUNK * CHUNK];
        int[] water = new int[CHUNK * CHUNK];
        Arrays.fill(heights, 420);
        Arrays.fill(water, TerrainTile.NO_WATER);
        heights[idx(8, 8)] = 340;
        water[idx(8, 8)] = 400;
        int[] guard = WaterGuard.guardPlane(heights, water, null, 0, 0);
        assertTrue(WaterGuard.seals(guard, idx(8, 8), 340, CLEARANCE));
        assertTrue(WaterGuard.seals(guard, idx(8, 8), 335, CLEARANCE));
        assertFalse(WaterGuard.seals(guard, idx(8, 8), 300, CLEARANCE));
    }

    @Test
    void neighborhoodTakesTheLowestBed() {
        // Two wet columns beside one dry column: the guard for the dry column
        // anchors to the DEEPER bed, or a carve under the shallow rule would
        // still clip the deep neighbor's bank.
        int[] heights = new int[CHUNK * CHUNK];
        int[] water = new int[CHUNK * CHUNK];
        Arrays.fill(heights, 420);
        Arrays.fill(water, TerrainTile.NO_WATER);
        heights[idx(7, 8)] = 396;
        water[idx(7, 8)] = 400;
        heights[idx(9, 8)] = 350;
        water[idx(9, 8)] = 400;
        int[] guard = WaterGuard.guardPlane(heights, water, null, 0, 0);
        assertTrue(guard[idx(8, 8)] <= 350 && guard[idx(8, 8)] >= 350 - MAX_EXTRA_DEPTH,
                "anchored to the deeper bed, give or take the noise's extra depth");
    }

    @Test
    void suppressesNothingForDryGroundAwayFromWater() {
        int[] guard = plane();
        assertFalse(WaterGuard.seals(guard, idx(0, 0), 380, CLEARANCE));
        assertFalse(WaterGuard.seals(guard, idx(12, 12), 419, CLEARANCE));
    }

    @Test
    void guardsATunnelledRiverFromItsFloorRatherThanItsHilltop() {
        // A river passing under standing ground keeps the ground: the column's
        // height is the hill (420) while the river runs at 384 with its bed at
        // 381. Measuring from the height would seal only the top few blocks and
        // leave the passage itself open for a worm to hole into and drain.
        int[] heights = new int[CHUNK * CHUNK];
        int[] water = new int[CHUNK * CHUNK];
        int[] floors = new int[CHUNK * CHUNK];
        Arrays.fill(heights, 420);
        Arrays.fill(water, TerrainTile.NO_WATER);
        Arrays.fill(floors, TerrainTile.NO_TUNNEL);
        water[idx(4, 4)] = 384;
        floors[idx(4, 4)] = 381;

        int[] guard = WaterGuard.guardPlane(heights, water, floors, null, 0, 0);
        assertTrue(guard[idx(4, 4)] <= 381 && guard[idx(4, 4)] >= 381 - MAX_EXTRA_DEPTH,
                "the tunnel floor is the bed, not the hilltop");
        assertTrue(WaterGuard.seals(guard, idx(4, 4), 384, CLEARANCE), "the passage is sealed");
        assertTrue(WaterGuard.seals(guard, idx(3, 4), 381, CLEARANCE), "and so are its walls");

        // Without the floors plane the same column is guarded from 420, which
        // leaves the whole passage carveable — the bug this parameter exists for.
        int[] blind = WaterGuard.guardPlane(heights, water, null, 0, 0);
        assertFalse(WaterGuard.seals(blind, idx(4, 4), 384, CLEARANCE));
    }

    @Test
    void suppressesNothingWhenTheCallerHasNoWaterPlane() {
        // The three-argument carver entry points pass null, and every existing
        // caller of those must keep carving exactly as it did before Phase 8.
        int[] heights = new int[CHUNK * CHUNK];
        assertNull(WaterGuard.guardPlane(heights, null, null, 0, 0));
        assertFalse(WaterGuard.seals(null, 0, 380, CLEARANCE));
    }

    /**
     * The noisy guard against the fixed rule it replaced, over four chunks read
     * through a real {@link HeightMapGenerator}, so the chunk borders are in it.
     *
     * <p>Superset: every column seals at least from the lowest bed in its
     * 4-neighbourhood, exactly as before — the bank argument depends on nothing
     * else. Bounded: no deeper than {@link #MAX_EXTRA_DEPTH} past the lowest bed
     * within {@link #MAX_RADIUS}, and open where there is none. And actually rough:
     * both the footprint and the depth vary, or the cave faces are as flat as ever.
     */
    @Test
    void theNoisyGuardIsASupersetOfTheFixedRuleAndBoundedByIt() {
        int lo = -64;
        int size = 128;
        short[] heights = new short[size * size];
        short[] water = new short[size * size];
        Arrays.fill(heights, (short) 420);
        Arrays.fill(water, TerrainTile.NO_WATER);
        // A river crossing the chunk borders at x=0 and z=0, with a wandering bed.
        for (int x = -40; x < 40; x++) {
            for (int z = -3 + (x & 3) / 2; z <= 2; z++) {
                int i = (x - lo) * size + (z - lo);
                water[i] = 400;
                heights[i] = (short) (380 + Math.floorMod(x * 7 + z * 3, 5));
            }
        }
        TerrainTile tile = new TerrainTile(0, 0, lo, lo, lo + size, lo + size, size, size,
                heights, new short[size * size], water);
        HeightMapGenerator heightMap = new HeightMapGenerator((x, z) -> tile);

        int widened = 0;
        java.util.Set<Integer> depths = new java.util.HashSet<>();
        for (int cx = -1; cx <= 0; cx++) {
            for (int cz = -1; cz <= 0; cz++) {
                int[] h = new int[CHUNK * CHUNK];
                int[] w = new int[CHUNK * CHUNK];
                for (int x = 0; x < CHUNK; x++) {
                    for (int z = 0; z < CHUNK; z++) {
                        h[idx(x, z)] = heightMap.generateHeight(cx * CHUNK + x, cz * CHUNK + z);
                        w[idx(x, z)] = heightMap.waterLevel(cx * CHUNK + x, cz * CHUNK + z);
                    }
                }
                int[] guard = WaterGuard.guardPlane(h, w, heightMap, cx, cz);
                for (int x = 0; x < CHUNK; x++) {
                    for (int z = 0; z < CHUNK; z++) {
                        int wx = cx * CHUNK + x;
                        int wz = cz * CHUNK + z;
                        int fixed = lowestBed(heightMap, wx, wz, 1);
                        int widest = lowestBed(heightMap, wx, wz, MAX_RADIUS);
                        int g = guard[idx(x, z)];
                        if (fixed != WaterGuard.OPEN) {
                            assertTrue(g <= fixed, "column (" + wx + "," + wz
                                    + ") seals less than the 4-neighbour rule");
                            depths.add(fixed - g);
                        } else if (g != WaterGuard.OPEN) {
                            widened++;
                        }
                        if (widest == WaterGuard.OPEN) {
                            assertEquals(WaterGuard.OPEN, g, "sealed with no water in reach");
                        } else {
                            assertTrue(g >= widest - MAX_EXTRA_DEPTH, "sealed deeper than the noise allows");
                        }
                    }
                }
            }
        }
        assertTrue(widened > 0, "the footprint reaches past the 4-neighbourhood somewhere");
        assertTrue(depths.size() > 1, "and the depth under the bed varies");
    }

    private static int lowestBed(HeightMapGenerator heightMap, int wx, int wz, int radius) {
        int bed = WaterGuard.OPEN;
        for (int dx = -radius; dx <= radius; dx++) {
            int span = radius - Math.abs(dx);
            for (int dz = -span; dz <= span; dz++) {
                if (heightMap.waterLevel(wx + dx, wz + dz) != TerrainTile.NO_WATER) {
                    bed = Math.min(bed, heightMap.generateHeight(wx + dx, wz + dz));
                }
            }
        }
        return bed;
    }
}
