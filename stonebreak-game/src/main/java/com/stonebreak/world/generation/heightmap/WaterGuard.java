package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.diffusion.TerrainTile;

/**
 * Keeps cave carvers out of the ground directly beneath standing water.
 *
 * <p>A cavern that intersects a riverbed drains the river into the void — and because
 * worldgen water is a source block by definition ({@code ChunkWaterLayer}: block ==
 * WATER with no entry means level 0), the drain never stops. {@code WaterSim} keeps
 * pushing water down the hole across every chunk that loads. Before Phase 8 the only
 * water was the sea, and each carver guarded it with a whole-column skip keyed on
 * {@code SEA_LEVEL}; those skips are still there and unchanged. This is their per-column
 * counterpart for the inland water the bridge now sends.
 *
 * <p>The guard is measured down from the column's <em>bed</em>, not down from the water
 * surface. Plan section 4.6 proposes "suppress carving for {@code y >= waterLevel - K}",
 * which is right for a river — its bed sits a block or two under its surface — and wrong
 * for a lake: a 40-block-deep lake has its bed 40 blocks below the level, so a band
 * anchored to the surface leaves the bed itself wide open. Anchoring to the bed covers
 * both, since {@code height <= waterLevel} for every wet column.
 *
 * <p>The clearance is each carver's own {@code WATER_CLEARANCE}, derived from its own
 * blob radius, rather than section 4.6's flat {@code K = 6}: the distance that matters is
 * how far a carve can reach up from the y it was aimed at, and that is a property of the
 * carver.
 */
final class WaterGuard {

    private WaterGuard() {
    }

    /**
     * True when carving at {@code y} would come within {@code clearance} blocks of a wet
     * column's bed.
     *
     * @param waterLevels per-column water level indexed {@code x * CHUNK_SIZE + z}, or
     *                    null when the caller has none (tests, and any path that has not
     *                    resolved a tile); a null plane suppresses nothing
     * @param surface     the column's terrain height, i.e. the bed under any water
     */
    static boolean sealsBed(int[] waterLevels, int index, int surface, int y, int clearance) {
        return waterLevels != null
                && waterLevels[index] != TerrainTile.NO_WATER
                && y >= surface - clearance;
    }
}
