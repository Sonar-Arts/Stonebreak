package com.openmason.engine.wayfind.voxel;

import com.openmason.engine.wayfind.SearchDomain;

/**
 * Movement rules for an agent that walks on surfaces: the {@link SearchDomain} a ground mob is
 * routed through.
 *
 * <p>A node is the cell an agent's <b>feet</b> occupy, which makes surfaces the unit of navigation
 * rather than free space — a 256-tall column collapses to the one or two cells that can actually be
 * stood in, so the search stays a 2.5D surface problem instead of a true 3D voxel one.
 *
 * <p>Each cell has exactly one canonical standing surface, so a surface is never reachable under two
 * different node keys: standing on a partial block (a stair tread, snow) belongs to the cell holding
 * that block, and the cell above it is not standable. Without that rule the search would happily
 * expand the same physical spot twice.
 *
 * <p>Eight horizontal moves per node. For each, the landing height is resolved the way the game's
 * own physics would: walk in flat if possible, otherwise step or jump up to the first surface within
 * the climb limit, otherwise fall to the first surface within the fall limit. Diagonals additionally
 * require both flanking columns to be open, so an agent never squeezes through a corner its collision
 * box would not fit through.
 *
 * <p>Water is a cost, not a wall: shallow enough to keep an agent's head out is wading, anything
 * deeper needs {@link NavProfile#canSwim()}. Hazards are impassable outright — a cost high enough to
 * avoid lava is also a cost a desperate search will eventually pay.
 *
 * <p>One instance serves one search (it holds scratch state); construct it per query, wrapping the
 * volume in a {@link NavCellCache}.
 */
public final class GroundNavDomain implements SearchDomain {

    /** Returned by {@link #snapToSurface} when no standable cell exists in range. */
    public static final long NO_NODE = Long.MIN_VALUE;

    private static final float DIAGONAL_COST = 1.4142135f;
    private static final float EPSILON = 1e-3f;

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private final NavVolume volume;
    private final NavProfile profile;
    private final int goalX;
    private final int goalY;
    private final int goalZ;
    private final float goalRadiusSquared;

    // Out-parameters of resolveLanding — this is a per-search object, so plain fields beat
    // allocating a landing record for every one of the eight directions of every expansion.
    private int landingY;
    private float landingSurcharge;
    private boolean landingWading;
    private boolean landingSubmerged;

    /**
     * @param goalRadius how close a node must get to the goal cell to count as arrival. Zero demands
     *                   the exact cell; anything targeting a moving entity or a spot that might be
     *                   inside a block wants at least 1, or the search can only ever return partials.
     */
    public GroundNavDomain(NavVolume volume, NavProfile profile,
                           int goalX, int goalY, int goalZ, float goalRadius) {
        this.volume = volume;
        this.profile = profile;
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.goalRadiusSquared = goalRadius * goalRadius;
    }

    // ── SearchDomain ─────────────────────────────────────────────────────────

    @Override
    public int successors(long node, long[] outNodes, float[] outCosts) {
        int x = NavNodes.x(node);
        int y = NavNodes.y(node);
        int z = NavNodes.z(node);

        float fromSurface = standSurface(x, y, z);
        if (Float.isNaN(fromSurface)) {
            return 0; // the world changed under the search, or the start was never standable
        }
        boolean fromSubmerged = isLiquid(x, y, z) && isLiquid(x, y + 1, z);

        int count = 0;
        for (int dir = 0; dir < DX.length; dir++) {
            int dx = DX[dir];
            int dz = DZ[dir];
            int nx = x + dx;
            int nz = z + dz;
            if (!NavNodes.inRange(nx, y, nz)) {
                continue;
            }

            boolean diagonal = dx != 0 && dz != 0;
            if (diagonal && !(columnClear(nx, z, fromSurface) && columnClear(x, nz, fromSurface))) {
                continue; // would clip the corner block between the two columns
            }
            if (!resolveLanding(nx, nz, y, fromSurface, fromSubmerged)) {
                continue;
            }

            float multiplier = landingSubmerged ? profile.swimCostMultiplier()
                    : landingWading ? profile.wadeCostMultiplier() : 1.0f;
            outNodes[count] = NavNodes.pack(nx, landingY, nz);
            outCosts[count] = (diagonal ? DIAGONAL_COST : 1.0f) * multiplier + landingSurcharge;
            count++;
        }
        return count;
    }

    @Override
    public float heuristic(long node) {
        // Octile distance over the horizontal plane. Every move in this domain costs at least its
        // horizontal distance, so this never over-estimates however the vertical works out.
        int dx = Math.abs(NavNodes.x(node) - goalX);
        int dz = Math.abs(NavNodes.z(node) - goalZ);
        int diagonalSteps = Math.min(dx, dz);
        return (dx + dz - 2 * diagonalSteps) + DIAGONAL_COST * diagonalSteps;
    }

    @Override
    public boolean isGoal(long node) {
        float dx = NavNodes.x(node) - goalX;
        float dy = NavNodes.y(node) - goalY;
        float dz = NavNodes.z(node) - goalZ;
        return dx * dx + dy * dy + dz * dz <= goalRadiusSquared;
    }

    // ── Public helpers for callers ───────────────────────────────────────────

    /**
     * The world-space Y an agent's feet rest at when standing in {@code node}, or
     * {@link Float#NaN} if it cannot stand there. Path followers need this: a node names a cell,
     * but walking to it means walking to a height.
     */
    public float surfaceOf(long node) {
        return standSurface(NavNodes.x(node), NavNodes.y(node), NavNodes.z(node));
    }

    /**
     * Finds the standable cell nearest {@code y} in the column, preferring the cell itself, then
     * lower ones, then higher. Used to snap a start or goal position — which is a float somewhere in
     * the air or a block interior — onto the surface graph.
     *
     * @return a packed node, or {@link #NO_NODE}
     */
    public long snapToSurface(int x, int y, int z, int searchDown, int searchUp) {
        if (NavNodes.inRange(x, y, z) && !Float.isNaN(standSurface(x, y, z))) {
            return NavNodes.pack(x, y, z);
        }
        for (int offset = 1; offset <= Math.max(searchDown, searchUp); offset++) {
            if (offset <= searchDown) {
                int cy = y - offset;
                if (NavNodes.inRange(x, cy, z) && !Float.isNaN(standSurface(x, cy, z))) {
                    return NavNodes.pack(x, cy, z);
                }
            }
            if (offset <= searchUp) {
                int cy = y + offset;
                if (NavNodes.inRange(x, cy, z) && !Float.isNaN(standSurface(x, cy, z))) {
                    return NavNodes.pack(x, cy, z);
                }
            }
        }
        return NO_NODE;
    }

    // ── Movement rules ───────────────────────────────────────────────────────

    /**
     * Resolves where an agent leaving {@code fromSurface} ends up in the target column, in the order
     * physics would: flat, then up, then down. Writes the landing into the out-parameter fields.
     */
    private boolean resolveLanding(int nx, int nz, int fromY, float fromSurface, boolean fromSubmerged) {
        float surface = standSurface(nx, fromY, nz);
        if (!Float.isNaN(surface) && acceptLanding(nx, fromY, nz, surface, fromSurface, fromSubmerged)) {
            return true;
        }

        // Up: the first standable cell above, if the climb is within reach. A surface inside cell cy
        // is never below cy, so once cy alone is out of reach nothing higher can be either.
        for (int cy = fromY + 1; cy - fromSurface <= profile.maxClimb() + EPSILON; cy++) {
            if (cy > NavNodes.MAX_Y) {
                break;
            }
            surface = standSurface(nx, cy, nz);
            if (!Float.isNaN(surface)) {
                return acceptLanding(nx, cy, nz, surface, fromSurface, fromSubmerged);
            }
        }

        // Down: the first standable cell below, if the drop is survivable. A surface inside cell cy
        // is never above cy + 1.
        for (int cy = fromY - 1; fromSurface - (cy + 1) <= profile.maxFall() + EPSILON; cy--) {
            if (cy < 0) {
                break;
            }
            surface = standSurface(nx, cy, nz);
            if (!Float.isNaN(surface)) {
                return acceptLanding(nx, cy, nz, surface, fromSurface, fromSubmerged);
            }
        }
        return false;
    }

    private boolean acceptLanding(int x, int y, int z, float surface, float fromSurface, boolean fromSubmerged) {
        float delta = surface - fromSurface;

        if (delta > profile.maxStepUp() + EPSILON) {
            if (fromSubmerged) {
                return false; // a swimming agent has nothing to push off
            }
            if (delta > profile.maxClimb() + EPSILON) {
                return false;
            }
        }
        if (-delta > profile.maxFall() + EPSILON) {
            return false;
        }

        float surcharge = 0.0f;
        if (delta > EPSILON) {
            surcharge = delta > profile.maxStepUp() + EPSILON ? profile.jumpCost() : profile.stepCost();
        } else if (delta < -EPSILON) {
            surcharge = profile.fallCostPerBlock() * -delta;
        }

        landingY = y;
        landingSurcharge = surcharge;
        landingWading = isLiquid(x, y, z);
        landingSubmerged = landingWading && isLiquid(x, y + 1, z);
        return true;
    }

    /**
     * The height an agent's feet rest at when standing in this cell, or {@link Float#NaN} when it
     * cannot stand there. Encodes the canonical-surface rule: a cell holding a partial block is
     * where you stand on that block, and a cell whose support is partial is not standable at all
     * (its surface belongs to the cell below).
     */
    private float standSurface(int x, int y, int z) {
        int flags = volume.flags(x, y, z);
        if (NavCell.isUnknown(flags) || NavCell.isHazard(flags)) {
            return Float.NaN;
        }

        float surface;
        if (NavCell.isSolid(flags)) {
            float top = volume.topSurface(x, y, z);
            if (top >= 1.0f - EPSILON) {
                return Float.NaN; // filled cell
            }
            surface = y + top;
        } else if (NavCell.isLiquid(flags)) {
            boolean submerged = isLiquid(x, y + 1, z);
            if (submerged) {
                if (!profile.canSwim()) {
                    return Float.NaN;
                }
            } else if (!profile.canSwim() && !hasFullSupportBelow(x, y, z)) {
                return Float.NaN; // wading needs a bed underfoot
            }
            surface = y;
        } else {
            if (!hasFullSupportBelow(x, y, z)) {
                return Float.NaN;
            }
            surface = y;
        }

        return columnsClear(x, z, surface, profile.columnRadius()) ? surface : Float.NaN;
    }

    /** True only for a full block: a partial one puts the real surface in the cell below. */
    private boolean hasFullSupportBelow(int x, int y, int z) {
        int below = volume.flags(x, y - 1, z);
        return NavCell.isSolid(below) && volume.topSurface(x, y - 1, z) >= 1.0f - EPSILON;
    }

    private boolean columnClear(int x, int z, float surface) {
        return columnsClear(x, z, surface, 0);
    }

    /** Whether the agent's body fits standing at {@code surface}, across the profile's footprint. */
    private boolean columnsClear(int x, int z, float surface, int radius) {
        int lowest = (int) Math.floor(surface + EPSILON);
        int highest = (int) Math.floor(surface + profile.height() - EPSILON);
        for (int cx = x - radius; cx <= x + radius; cx++) {
            for (int cz = z - radius; cz <= z + radius; cz++) {
                for (int cy = lowest; cy <= highest; cy++) {
                    if (blocksBody(cx, cy, cz, surface)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Whether a cell obstructs a body whose feet are at {@code surface}. The block being stood on
     * is not an obstruction — its top is exactly the surface — which is what lets partial blocks be
     * both floor and free space.
     */
    private boolean blocksBody(int x, int y, int z, float surface) {
        int flags = volume.flags(x, y, z);
        if (NavCell.isUnknown(flags) || NavCell.isHazard(flags)) {
            return true;
        }
        if (!NavCell.isSolid(flags)) {
            return false;
        }
        return y + volume.topSurface(x, y, z) > surface + EPSILON;
    }

    private boolean isLiquid(int x, int y, int z) {
        return NavCell.isLiquid(volume.flags(x, y, z));
    }
}
