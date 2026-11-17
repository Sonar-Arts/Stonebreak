package com.stonebreak.util;

import com.stonebreak.world.physics.WorldPhysics;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Resolves passable spawn and escape cells for item and block drops (issues #225
 * and #265).
 *
 * <p>A drop must never rest inside a collidable block. When a block is broken the
 * drop spawns into the nearest adjacent passable cell (air or non-collidable, e.g.
 * flowers, water or placed torches) — preferably the one nearest the breaker —
 * instead of blindly offsetting into whatever happens to be above. When a drop ends
 * up embedded inside a collidable cell anyway (e.g. it rose into a tree trunk), it
 * escapes out the nearest side instead of the legacy surfacing behavior that climbed
 * block-by-block to the canopy.</p>
 *
 * <p>One solidity rule with the drops' landing pass (issue #265): passability is
 * backed by the engine physics' drop mask ({@link WorldPhysics#DROP_MASK}) — a cell is passable exactly where its
 * collision height is 0 — so the two can never drift: non-collidable cells (flowers,
 * wildgrass, placed torches, water) are passable AND never act as ground, and
 * collidable partial-height cells (snow layers, stairs) never host a spawn.</p>
 */
public final class DropSpawnResolver {

    private DropSpawnResolver() {
    }

    /**
     * A drop can occupy air or a non-collidable cell (flowers, water, placed torches);
     * never a collidable one. One solidity rule with the drops' landing pass (issue
     * #265), backed by {@code BlockShape.collisionHeight} — the whole-cell form, because
     * the drop would occupy the whole cell; a null block (out of bounds) answers nothing.
     */
    public static boolean isPassable(World world, int x, int y, int z) {
        return world == null || com.stonebreak.world.physics.WorldPhysics.of(world).isOpen(x, y, z, WorldPhysics.DROP_MASK);
    }

    /** True when the drop's own cell collides — the drop is embedded inside a block
     *  (trunk, snow, stairs; a snow or stair cell collides at partial height). */
    public static boolean isEmbedded(World world, int x, int y, int z) {
        return world != null && !com.stonebreak.world.physics.WorldPhysics.of(world).isOpen(x, y, z, WorldPhysics.DROP_MASK);
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
        int best;
        if (world == null) {
            best = 0;
        } else {
            int[] flat = new int[candidates.length * 3];
            for (int i = 0; i < candidates.length; i++) {
                System.arraycopy(candidates[i], 0, flat, i * 3, 3);
            }
            best = preferencePoint == null
                ? com.stonebreak.world.physics.WorldPhysics.of(world).engine().nearestOpenCell(flat, candidates.length, false, 0, 0, 0, WorldPhysics.DROP_MASK)
                : com.stonebreak.world.physics.WorldPhysics.of(world).engine().nearestOpenCell(flat, candidates.length, true,
                    preferencePoint.x, preferencePoint.y, preferencePoint.z, WorldPhysics.DROP_MASK);
        }
        return best < 0 ? null
                : new Vector3f(candidates[best][0] + 0.5f, candidates[best][1] + 0.5f, candidates[best][2] + 0.5f);
    }
}
