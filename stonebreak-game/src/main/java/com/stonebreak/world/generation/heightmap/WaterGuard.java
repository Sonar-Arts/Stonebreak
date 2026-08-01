package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Keeps cave carvers out of the ground beneath — and BESIDE — standing water.
 *
 * <p>A cavern that intersects a riverbed drains the river into the void, and because
 * worldgen water is a source block by definition ({@code ChunkWaterLayer}: block ==
 * WATER with no entry means level 0), the drain never stops. {@code WaterSim} keeps
 * pushing water down the hole across every chunk that loads.
 *
 * <p>The original guard was per-column: a wet column sealed its own bed. That left the
 * <em>banks</em> open — a carve entering the dry column right next to a river, at or
 * below the water level, opens a hole in the side wall and drains the river sideways
 * exactly like a bed breach. So the guard is now a per-chunk precomputed plane:
 * for each column, the lowest bed among the wet columns in its 4-neighborhood
 * (itself included). Sealing above {@code that bed - clearance} protects beds and
 * bank walls with one test.
 *
 * <p>The plane is measured down from a wet column's <em>bed</em>, not its surface.
 * Plan section 4.6 proposed "suppress carving for {@code y >= waterLevel - K}", which
 * is right for a river — its bed sits a block or two under its surface — and wrong for
 * a lake: a deep lake's bed is far below the level, so a surface-anchored band leaves
 * the bed itself wide open. {@code height <= waterLevel} for every wet column, so the
 * bed anchor covers both.
 *
 * <p>Neighbors outside the 16x16 chunk are resolved through the
 * {@link HeightMapGenerator}'s tile source — the same resolved tiles the chunk's own
 * planes came from — so a river hugging a chunk border is guarded from both sides.
 * The clearance is each carver's own {@code WATER_CLEARANCE}, derived from its blob
 * radius: the distance that matters is how far a carve reaches up from the y it was
 * aimed at, and that is a property of the carver.
 */
final class WaterGuard {

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    /** Sentinel for "no wet column anywhere in this column's neighborhood". */
    static final int OPEN = Integer.MAX_VALUE;

    private WaterGuard() {
    }

    /**
     * Per-column guard plane for one chunk: the lowest wet-column bed in each
     * column's 4-neighborhood (itself included), or {@link #OPEN}. Null in, null
     * out — a caller with no water plane (tests, legacy paths) suppresses nothing.
     *
     * @param targetHeights the chunk's 16x16 final heights, indexed {@code x*16+z}
     * @param waterLevels   the co-located water levels ({@link TerrainTile#NO_WATER}
     *                      for dry), same indexing
     * @param heightMap     resolves the one-block ring outside the chunk; may be
     *                      null, which leaves border columns guarded from inside
     *                      the chunk only
     */
    static int[] guardPlane(int[] targetHeights, int[] waterLevels,
                            HeightMapGenerator heightMap, int chunkX, int chunkZ) {
        if (waterLevels == null) {
            return null;
        }
        int[] plane = new int[CHUNK_SIZE * CHUNK_SIZE];
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int guard = OPEN;
                guard = consider(guard, x, z, targetHeights, waterLevels, heightMap, baseX, baseZ);
                guard = consider(guard, x - 1, z, targetHeights, waterLevels, heightMap, baseX, baseZ);
                guard = consider(guard, x + 1, z, targetHeights, waterLevels, heightMap, baseX, baseZ);
                guard = consider(guard, x, z - 1, targetHeights, waterLevels, heightMap, baseX, baseZ);
                guard = consider(guard, x, z + 1, targetHeights, waterLevels, heightMap, baseX, baseZ);
                plane[x * CHUNK_SIZE + z] = guard;
            }
        }
        return plane;
    }

    private static int consider(int guard, int x, int z, int[] heights, int[] waterLevels,
                                HeightMapGenerator heightMap, int baseX, int baseZ) {
        if (x >= 0 && x < CHUNK_SIZE && z >= 0 && z < CHUNK_SIZE) {
            int idx = x * CHUNK_SIZE + z;
            if (waterLevels[idx] != TerrainTile.NO_WATER) {
                return Math.min(guard, heights[idx]);
            }
            return guard;
        }
        if (heightMap == null) {
            return guard;
        }
        int wx = baseX + x;
        int wz = baseZ + z;
        if (heightMap.waterLevel(wx, wz) != TerrainTile.NO_WATER) {
            return Math.min(guard, heightMap.generateHeight(wx, wz));
        }
        return guard;
    }

    /**
     * True when carving at {@code y} would come within {@code clearance} blocks of
     * a neighboring (or own) wet column's bed. {@code guardPlane} null suppresses
     * nothing.
     */
    static boolean seals(int[] guardPlane, int index, int y, int clearance) {
        return guardPlane != null
                && guardPlane[index] != OPEN
                && y >= guardPlane[index] - clearance;
    }
}
