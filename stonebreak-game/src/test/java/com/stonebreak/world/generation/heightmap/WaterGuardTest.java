package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.diffusion.TerrainTile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cave-carver water guard, tested directly.
 *
 * <p>Directly, because in the world as it currently stands the guard never actually fires:
 * every carver's Y band is a fraction of {@code SEA_LEVEL} while after the Phase 5 curve all
 * land sits at or above {@code SEA_LEVEL}, so no carve comes near a riverbed
 * ({@code TerrainWaterConsistencyTest} records that measurement). A guard that cannot be
 * observed through its callers is exactly the kind that rots, so its rule is pinned here on
 * its own terms — it is the thing standing between a retune of those bands and a river that
 * drains into a cavern forever.
 */
class WaterGuardTest {

    private static final int CLEARANCE = 6;

    @Test
    void suppressesCarvingWithinTheClearanceUnderAWetColumn() {
        int[] water = {400};
        // Bed at y=380 under a surface at 400: everything from 374 up is off limits.
        assertTrue(WaterGuard.sealsBed(water, 0, 380, 380, CLEARANCE));
        assertTrue(WaterGuard.sealsBed(water, 0, 380, 374, CLEARANCE));
        assertFalse(WaterGuard.sealsBed(water, 0, 380, 373, CLEARANCE));
    }

    @Test
    void measuresFromTheBedNotFromTheWaterSurface() {
        // A deep lake: surface 400, bed 340. Plan section 4.6 proposes anchoring the band to
        // the water level, which would leave everything below 394 open — including the 60
        // blocks of bed holding the lake up. Anchoring to the bed is what covers it.
        int[] water = {400};
        assertTrue(WaterGuard.sealsBed(water, 0, 340, 340, CLEARANCE));
        assertTrue(WaterGuard.sealsBed(water, 0, 340, 335, CLEARANCE));
        assertFalse(WaterGuard.sealsBed(water, 0, 340, 300, CLEARANCE));
    }

    @Test
    void suppressesNothingForADryColumn() {
        int[] dry = {TerrainTile.NO_WATER};
        assertFalse(WaterGuard.sealsBed(dry, 0, 380, 380, CLEARANCE));
        assertFalse(WaterGuard.sealsBed(dry, 0, 380, 379, CLEARANCE));
    }

    @Test
    void suppressesNothingWhenTheCallerHasNoWaterPlane() {
        // The three-argument carver entry points pass null, and every existing caller of
        // those must keep carving exactly as it did before Phase 8.
        assertFalse(WaterGuard.sealsBed(null, 0, 380, 380, CLEARANCE));
    }

    @Test
    void readsTheColumnAtTheGivenIndex() {
        // Indexed x * CHUNK_SIZE + z like every other per-column plane; an off-by-one here
        // would guard the wrong column and still look like it was working.
        int[] water = {TerrainTile.NO_WATER, 400, TerrainTile.NO_WATER};
        assertFalse(WaterGuard.sealsBed(water, 0, 380, 380, CLEARANCE));
        assertTrue(WaterGuard.sealsBed(water, 1, 380, 380, CLEARANCE));
        assertFalse(WaterGuard.sealsBed(water, 2, 380, 380, CLEARANCE));
    }
}
