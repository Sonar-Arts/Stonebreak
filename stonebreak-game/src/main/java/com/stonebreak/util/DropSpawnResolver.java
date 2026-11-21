package com.stonebreak.util;

import com.stonebreak.world.physics.WorldPhysics;
import com.stonebreak.world.World;
import com.openmason.engine.physics.PhysicsWorld;
import org.joml.Vector3d;
import org.joml.Vector3dc;

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
    public static boolean isPassable(World world, long x, long y, long z) {
        return world == null || WorldPhysics.of(world).isOpen(x, y, z, WorldPhysics.DROP_MASK);
    }

    /** True when the drop's own cell collides — the drop is embedded inside a block
     *  (trunk, snow, stairs; a snow or stair cell collides at partial height). */
    public static boolean isEmbedded(World world, long x, long y, long z) {
        return world != null && !WorldPhysics.of(world).isOpen(x, y, z, WorldPhysics.DROP_MASK);
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
    public static Vector3d resolveSpawn(World world, long cellX, long cellY, long cellZ, Vector3dc preferencePoint) {
        long[][] candidates = {
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
    public static Vector3d resolveEscape(World world, long cellX, long cellY, long cellZ, Vector3dc preferencePoint) {
        long[][] candidates = {
            {cellX + 1, cellY, cellZ}, {cellX - 1, cellY, cellZ},
            {cellX, cellY, cellZ + 1}, {cellX, cellY, cellZ - 1},
            {cellX, cellY - 1, cellZ},
        };
        Vector3d escape = nearestPassable(world, candidates, preferencePoint);
        if (escape != null) {
            return escape;
        }
        return nearestPassable(world, new long[][]{{cellX, cellY + 1, cellZ}}, preferencePoint);
    }

    /**
     * First passable candidate when {@code preferencePoint} is null (declaration order),
     * otherwise the passable candidate whose centre is nearest the preference point.
     */
    private static Vector3d nearestPassable(World world, long[][] candidates, Vector3dc preferencePoint) {
        int best;
        if (world == null) {
            best = 0;
        } else {
            long[] flat = new long[candidates.length * 3];
            for (int i = 0; i < candidates.length; i++) {
                for (int k = 0; k < 3; k++) flat[i * 3 + k] = candidates[i][k];
            }
            PhysicsWorld physics = WorldPhysics.of(world).engine();
            // Cells are absolute; the preference point is a frame position (relative to the origin).
            best = preferencePoint == null
                ? physics.nearestOpenCell(flat, candidates.length, false, 0, 0, 0, WorldPhysics.DROP_MASK)
                : physics.nearestOpenCell(flat, candidates.length, true,
                    preferencePoint.x() - physics.originX(), preferencePoint.y() - physics.originY(),
                    preferencePoint.z() - physics.originZ(), WorldPhysics.DROP_MASK);
        }
        return best < 0 ? null
                : new Vector3d(candidates[best][0] + 0.5, candidates[best][1] + 0.5, candidates[best][2] + 0.5);
    }
}
