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
        assertFalse(WaterGuard.seals(guard, idx(4, 4), 373, CLEARANCE));
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
        assertFalse(WaterGuard.seals(guard, idx(4, 5), 373, CLEARANCE));
        // Diagonal and two-away columns never touch the water: open.
        assertFalse(WaterGuard.seals(guard, idx(3, 3), 380, CLEARANCE));
        assertFalse(WaterGuard.seals(guard, idx(6, 4), 380, CLEARANCE));
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
        assertEquals(350, guard[idx(8, 8)]);
    }

    @Test
    void suppressesNothingForDryGroundAwayFromWater() {
        int[] guard = plane();
        assertFalse(WaterGuard.seals(guard, idx(0, 0), 380, CLEARANCE));
        assertFalse(WaterGuard.seals(guard, idx(12, 12), 419, CLEARANCE));
    }

    @Test
    void suppressesNothingWhenTheCallerHasNoWaterPlane() {
        // The three-argument carver entry points pass null, and every existing
        // caller of those must keep carving exactly as it did before Phase 8.
        int[] heights = new int[CHUNK * CHUNK];
        assertNull(WaterGuard.guardPlane(heights, null, null, 0, 0));
        assertFalse(WaterGuard.seals(null, 0, 380, CLEARANCE));
    }
}
