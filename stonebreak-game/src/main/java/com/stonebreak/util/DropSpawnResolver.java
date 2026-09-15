package com.stonebreak.util;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Resolves passable spawn and escape cells for item and block drops (issue #225).
 *
 * <p>A drop must never rest inside a solid block. When a block is broken the drop
 * spawns into the nearest adjacent passable cell (air or non-collidable, e.g. flowers
 * or water) — preferably the one nearest the breaker — instead of blindly offsetting
 * into whatever happens to be above. When a drop ends up embedded inside a solid cell
 * anyway (e.g. it rose into a tree trunk), it escapes out the nearest side instead of
 * the legacy surfacing behavior that climbed block-by-block to the canopy.</p>
 */
public final class DropSpawnResolver {

    private DropSpawnResolver() {
    }

    /** A drop can occupy air or a non-collidable cell (flowers, water); never a solid block. */
    public static boolean isPassable(World world, int x, int y, int z) {
        if (world == null) {
            return true;
        }
        BlockType block = world.getBlockAt(x, y, z);
        return block == null || block == BlockType.AIR || !block.isSolid();
    }

    /** True when the drop's own cell is solid — the drop is embedded inside a block. */
    public static boolean isEmbedded(World world, int x, int y, int z) {
        if (world == null) {
            return false;
        }
        BlockType block = world.getBlockAt(x, y, z);
        return block != null && block != BlockType.AIR && block.isSolid();
    }

    /**
     * Finds the passable cell nearest to {@code preferencePoint} (usually the breaker's
     * position) among the broken cell and its six face-adjacent neighbours. Returns the
     * chosen cell's centre, or null when no candidate is passable — the caller then falls
     * back to the legacy spawn offset (surfacing).
     *
     * <p>Ties in distance resolve in declaration order: the broken cell itself, the four
     * horizontal neighbours, down, then up (surfacing is the last resort per the issue).</p>
     */
    public static Vector3f resolveSpawn(World world, int cellX, int cellY, int cellZ, Vector3f preferencePoint) {
        int[][] candidates = {
            {cellX, cellY, cellZ},
            {cellX + 1, cellY, cellZ}, {cellX - 1, cellY, cellZ},
            {cellX, cellY, cellZ + 1}, {cellX, cellY, cellZ - 1},
            {cellX, cellY - 1, cellZ}, {cellX, cellY + 1, cellZ},
        };
        return nearestPassable(world, candidates, preferencePoint);
    }

    /**
     * Escape cell for a drop embedded inside the solid cell {@code (cellX, cellY, cellZ)}.
     * The four horizontal neighbours and down are distance-ordered against
     * {@code preferencePoint} (usually the drop's pre-embedment position), so the drop
     * escapes out the open side nearest where it came from — a drop rising into a trunk
     * from straight below escapes back down, not out an arbitrary side (all four sides
     * tie in distance from a point straight below). Up (surfacing) is tried only when no
     * side or down escape exists — e.g. a fully enclosed cell, which CAN happen (a drop
     * embedded in flat ground below the surface has no side or down escape). Returns the
     * chosen cell's centre, or null when the drop is fully enclosed above too.
     */
    public static Vector3f resolveEscape(World world, int cellX, int cellY, int cellZ, Vector3f preferencePoint) {
        int[][] candidates = {
            {cellX + 1, cellY, cellZ}, {cellX - 1, cellY, cellZ},
            {cellX, cellY, cellZ + 1}, {cellX, cellY, cellZ - 1},
            {cellX, cellY - 1, cellZ},
        };
        Vector3f escape = nearestPassable(world, candidates, preferencePoint);
        if (escape != null) {
            return escape;
        }
        return nearestPassable(world, new int[][]{{cellX, cellY + 1, cellZ}}, preferencePoint);
    }

    /**
     * First passable candidate when {@code preferencePoint} is null (declaration order),
     * otherwise the passable candidate whose centre is nearest the preference point.
     */
    private static Vector3f nearestPassable(World world, int[][] candidates, Vector3f preferencePoint) {
        int best = -1;
        float bestDistSq = Float.MAX_VALUE;
        for (int i = 0; i < candidates.length; i++) {
            int[] c = candidates[i];
            if (!isPassable(world, c[0], c[1], c[2])) {
                continue;
            }
            if (preferencePoint == null) {
                best = i;
                break;
            }
            float dx = c[0] + 0.5f - preferencePoint.x;
            float dy = c[1] + 0.5f - preferencePoint.y;
            float dz = c[2] + 0.5f - preferencePoint.z;
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = i;
            }
        }
        return best < 0 ? null
                : new Vector3f(candidates[best][0] + 0.5f, candidates[best][1] + 0.5f, candidates[best][2] + 0.5f);
    }
}
