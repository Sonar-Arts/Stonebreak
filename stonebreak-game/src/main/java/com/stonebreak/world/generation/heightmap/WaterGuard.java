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
 * <p>That rule, applied exactly, leaves a cave that runs into water ending on a
 * vertical face one column out and a flat ceiling at a fixed depth — the shape of
 * the rule rather than of rock. So the neighbourhood is a diamond of noisy radius
 * 1 to 3 and the plane sits a noisy 0 to 3 blocks deeper ({@link ShellNoise}). Both
 * only widen and deepen the seal; the 4-neighbourhood at exactly the bed is still
 * always inside it.
 *
 * <p>The plane is measured down from a wet column's <em>bed</em>, not its surface.
 * Plan section 4.6 proposed "suppress carving for {@code y >= waterLevel - K}", which
 * is right for a river — its bed sits a block or two under its surface — and wrong for
 * a lake: a deep lake's bed is far below the level, so a surface-anchored band leaves
 * the bed itself wide open. {@code height <= waterLevel} for every wet column, so the
 * bed anchor covers both.
 *
 * <p>A river that passes under standing ground is TUNNELLED rather than stamped, and
 * such a column's bed is its {@code riverFloors} entry, not its height — the height
 * there is the hill the river runs beneath. Measuring from the height would seal only
 * the few blocks under the hilltop and leave the passage itself wide open, which drains
 * it exactly like a breached riverbed. So the bed of a wet column is its tunnel floor
 * where it has one and its height otherwise.
 *
 * <p>Neighbors outside the 16x16 chunk (up to three columns out) are resolved through the
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

    /*
     * The guard's footprint and depth wander, so a cave cut off by water ends on a
     * rough face rather than a vertical wall one column out and a flat ceiling at a
     * fixed depth. Both only ever ADD rock: the radius is never under 1 (the
     * 4-neighbourhood the bank argument needs) and the extra depth is never
     * negative.
     */
    /** Columns past the 4-neighbourhood the guard may reach, at most. */
    private static final int EXTRA_RADIUS = 2;
    /** Blocks deeper than {@code bed - clearance} the seal may reach, at most. */
    private static final int EXTRA_DEPTH = 3;
    private static final float RADIUS_WAVE = 6f;
    private static final float DEPTH_WAVE = 5f;
    private static final long SALT_RADIUS = 0x5747524144495553L; // "WGRADIUS"
    private static final long SALT_DEPTH = 0x5747444550544800L;  // "WGDEPTH"
    private static final int PAD = 1 + EXTRA_RADIUS;
    private static final int PADDED = CHUNK_SIZE + 2 * PAD;

    private WaterGuard() {
    }

    /**
     * Per-column guard plane for one chunk: the lowest wet-column bed in each
     * column's noisy diamond neighborhood (itself included, radius 1 to 3), lowered
     * by a noisy 0 to 3 blocks, or {@link #OPEN}. Null in, null
     * out — a caller with no water plane (tests, legacy paths) suppresses nothing.
     *
     * @param targetHeights the chunk's 16x16 final heights, indexed {@code x*16+z}
     * @param waterLevels   the co-located water levels ({@link TerrainTile#NO_WATER}
     *                      for dry), same indexing
     * @param riverFloors   the co-located river-tunnel floors
     *                      ({@link TerrainTile#NO_TUNNEL} where there is none), same
     *                      indexing; may be null, which guards tunnels from their
     *                      hilltops and so barely at all
     * @param heightMap     resolves the three-block ring outside the chunk; may be
     *                      null, which leaves border columns guarded from inside
     *                      the chunk only
     */
    static int[] guardPlane(int[] targetHeights, int[] waterLevels,
                            HeightMapGenerator heightMap, int chunkX, int chunkZ) {
        return guardPlane(targetHeights, waterLevels, null, heightMap, chunkX, chunkZ);
    }

    /** @see #guardPlane(int[], int[], HeightMapGenerator, int, int) */
    static int[] guardPlane(int[] targetHeights, int[] waterLevels, int[] riverFloors,
                            HeightMapGenerator heightMap, int chunkX, int chunkZ) {
        return plane(targetHeights, waterLevels, riverFloors, true, heightMap, chunkX, chunkZ);
    }

    /**
     * As {@link #guardPlane}, but a tunnelled column's bed is its SURFACE, not its
     * tunnel floor: the guard for {@link Density3D}, which seals the passage itself
     * with its own band and must leave the hill above a tunnel its caves. What this
     * adds is the part that band cannot see — surface riverbeds and the bank walls
     * the water kernel raised beside them, which the overhang band would otherwise
     * carve straight through, opening the river sideways.
     */
    static int[] surfaceGuardPlane(int[] targetHeights, int[] waterLevels,
                                   HeightMapGenerator heightMap, int chunkX, int chunkZ) {
        return plane(targetHeights, waterLevels, null, false, heightMap, chunkX, chunkZ);
    }

    private static int[] plane(int[] targetHeights, int[] waterLevels, int[] riverFloors,
                               boolean tunnelBeds, HeightMapGenerator heightMap,
                               int chunkX, int chunkZ) {
        if (waterLevels == null) {
            return null;
        }
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        int[] beds = padBeds(targetHeights, waterLevels, riverFloors, tunnelBeds,
                heightMap, baseX, baseZ);
        int[] plane = new int[CHUNK_SIZE * CHUNK_SIZE];
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int wx = baseX + x;
                int wz = baseZ + z;
                int radius = 1 + Math.round(EXTRA_RADIUS * ShellNoise.stretched(wx, wz, RADIUS_WAVE, SALT_RADIUS));
                int guard = OPEN;
                for (int dx = -radius; dx <= radius; dx++) {
                    int span = radius - Math.abs(dx);
                    for (int dz = -span; dz <= span; dz++) {
                        guard = Math.min(guard, beds[(x + dx + PAD) * PADDED + (z + dz + PAD)]);
                    }
                }
                if (guard != OPEN) {
                    guard -= Math.round(EXTRA_DEPTH * ShellNoise.stretched(wx, wz, DEPTH_WAVE, SALT_DEPTH));
                }
                plane[x * CHUNK_SIZE + z] = guard;
            }
        }
        return plane;
    }

    /**
     * The bed of every wet column in the chunk and a {@link #PAD}-wide ring around
     * it, {@link #OPEN} for dry, indexed {@code (x + PAD) * PADDED + (z + PAD)}.
     * Built once so the widest neighbourhood costs one tile lookup per ring column
     * rather than one per column that can see it.
     */
    private static int[] padBeds(int[] heights, int[] waterLevels, int[] riverFloors,
                                 boolean tunnelBeds, HeightMapGenerator heightMap,
                                 int baseX, int baseZ) {
        int[] beds = new int[PADDED * PADDED];
        for (int px = 0; px < PADDED; px++) {
            for (int pz = 0; pz < PADDED; pz++) {
                int x = px - PAD;
                int z = pz - PAD;
                int bed = OPEN;
                if (x >= 0 && x < CHUNK_SIZE && z >= 0 && z < CHUNK_SIZE) {
                    int idx = x * CHUNK_SIZE + z;
                    if (waterLevels[idx] != TerrainTile.NO_WATER) {
                        bed = bed(heights[idx],
                                riverFloors == null ? TerrainTile.NO_TUNNEL : riverFloors[idx]);
                    }
                } else if (heightMap != null) {
                    int wx = baseX + x;
                    int wz = baseZ + z;
                    if (heightMap.waterLevel(wx, wz) != TerrainTile.NO_WATER) {
                        bed = bed(heightMap.generateHeight(wx, wz),
                                tunnelBeds ? heightMap.riverFloor(wx, wz) : TerrainTile.NO_TUNNEL);
                    }
                }
                beds[px * PADDED + pz] = bed;
            }
        }
        return beds;
    }

    /** The lowest water in a wet column: its tunnel floor, or its own bed. */
    private static int bed(int height, int riverFloor) {
        return riverFloor == TerrainTile.NO_TUNNEL ? height : Math.min(height, riverFloor);
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
