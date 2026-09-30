package com.stonebreak.world.generation.heightmap;

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
 * <p>The guard is a precomputed per-chunk plane covering each column's 4-neighbourhood,
 * because a per-column rule leaves the BANKS open: a carve entering the dry column beside
 * a river at water level opens the side wall and drains the river exactly like a bed
 * breach. The fused kernel mirrors this exact rule, so it is pinned exactly here.
 */
class WaterGuardTest {

    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int NO_WATER = WorldConfiguration.NO_WATER;
    private static final int CLEARANCE = 6;

    private static int idx(int x, int z) {
        return x * CHUNK + z;
    }

    /** One wet column at (4,4): bed 180, level 200, on otherwise dry 220 ground. */
    private static int[] plane() {
        int[] heights = new int[CHUNK * CHUNK];
        int[] water = new int[CHUNK * CHUNK];
        Arrays.fill(heights, 220);
        Arrays.fill(water, NO_WATER);
        heights[idx(4, 4)] = 180;
        water[idx(4, 4)] = 200;
        return WaterGuard.guardPlane(heights, water, null, 0, 0);
    }

    @Test
    void suppressesCarvingWithinTheClearanceUnderAWetColumn() {
        int[] guard = plane();
        // Bed at y=180: everything from 174 up is off limits in that column.
        assertTrue(WaterGuard.seals(guard, idx(4, 4), 180, CLEARANCE));
        assertTrue(WaterGuard.seals(guard, idx(4, 4), 174, CLEARANCE));
        assertFalse(WaterGuard.seals(guard, idx(4, 4), 173, CLEARANCE));
    }

    @Test
    void sealsTheBankColumnsBesideAWetColumn() {
        // The regression the plane exists for: the four dry neighbours are the
        // river's side walls, sealed from the WET column's bed.
        int[] guard = plane();
        assertTrue(WaterGuard.seals(guard, idx(3, 4), 180, CLEARANCE));
        assertTrue(WaterGuard.seals(guard, idx(5, 4), 174, CLEARANCE));
        assertTrue(WaterGuard.seals(guard, idx(4, 3), 199, CLEARANCE));
        assertTrue(WaterGuard.seals(guard, idx(4, 5), 180, CLEARANCE));
        assertFalse(WaterGuard.seals(guard, idx(4, 5), 173, CLEARANCE));
        // Diagonals and anything further are outside the 4-neighbourhood: open.
        assertFalse(WaterGuard.seals(guard, idx(5, 5), 180, CLEARANCE));
        assertFalse(WaterGuard.seals(guard, idx(4, 6), 180, CLEARANCE));
    }

    @Test
    void measuresFromTheBedNotFromTheWaterSurface() {
        // A deep lake: surface 200, bed 140. A band anchored to the water level
        // would leave the 60 blocks of bed holding the lake up wide open.
        int[] heights = new int[CHUNK * CHUNK];
        int[] water = new int[CHUNK * CHUNK];
        Arrays.fill(heights, 220);
        Arrays.fill(water, NO_WATER);
        heights[idx(8, 8)] = 140;
        water[idx(8, 8)] = 200;
        int[] guard = WaterGuard.guardPlane(heights, water, null, 0, 0);
        assertTrue(WaterGuard.seals(guard, idx(8, 8), 140, CLEARANCE));
        assertTrue(WaterGuard.seals(guard, idx(8, 8), 135, CLEARANCE));
        assertFalse(WaterGuard.seals(guard, idx(8, 8), 100, CLEARANCE));
    }

    @Test
    void neighborhoodTakesTheLowestBed() {
        // Two wet columns beside one dry column: the guard for the dry column
        // anchors to the DEEPER bed, or a carve under the shallow rule would
        // still clip the deep neighbour's bank.
        int[] heights = new int[CHUNK * CHUNK];
        int[] water = new int[CHUNK * CHUNK];
        Arrays.fill(heights, 220);
        Arrays.fill(water, NO_WATER);
        heights[idx(7, 8)] = 196;
        water[idx(7, 8)] = 200;
        heights[idx(9, 8)] = 150;
        water[idx(9, 8)] = 200;
        int[] guard = WaterGuard.guardPlane(heights, water, null, 0, 0);
        assertEquals(150, guard[idx(8, 8)]);
    }

    @Test
    void suppressesNothingForDryGroundAwayFromWater() {
        int[] guard = plane();
        assertFalse(WaterGuard.seals(guard, idx(0, 0), 180, CLEARANCE));
        assertFalse(WaterGuard.seals(guard, idx(12, 12), 219, CLEARANCE));
    }

    @Test
    void guardsABankAcrossTheChunkBorderThroughTheHeightSource() {
        // Water in the neighbouring chunk, one block past x=0: the border column is its
        // bank. Only the height source can see it, and the Daedalus generator hands in its
        // tile heights for exactly this.
        int[] heights = new int[CHUNK * CHUNK];
        int[] water = new int[CHUNK * CHUNK];
        Arrays.fill(heights, 220);
        Arrays.fill(water, NO_WATER);
        SurfaceHeights outside = new SurfaceHeights() {
            @Override
            public int generateHeight(int x, int z) {
                return x == -1 ? 170 : 220;
            }

            @Override
            public int waterLevel(int x, int z) {
                return x == -1 ? 200 : NO_WATER;
            }
        };
        int[] guard = WaterGuard.guardPlane(heights, water, outside, 0, 0);
        assertEquals(170, guard[idx(0, 5)]);
        assertEquals(WaterGuard.OPEN, guard[idx(1, 5)]);
        // Without it the border column is guarded from inside the chunk only.
        assertEquals(WaterGuard.OPEN, WaterGuard.guardPlane(heights, water, null, 0, 0)[idx(0, 5)]);
    }

    @Test
    void suppressesNothingWhenTheCallerHasNoWaterPlane() {
        int[] heights = new int[CHUNK * CHUNK];
        assertNull(WaterGuard.guardPlane(heights, null, null, 0, 0));
        assertFalse(WaterGuard.seals(null, 0, 180, CLEARANCE));
    }
}
