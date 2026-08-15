package com.openmason.engine.wayfind.voxel;

/**
 * The world, as navigation sees it. The single seam between the engine's movement rules and a
 * game's blocks — the engine never learns what a block type is.
 *
 * <p>Implementations are read-only and are queried heavily during a search (a few thousand cells
 * for a typical mob route), so they should be cheap; wrap one in a {@link NavCellCache} to collapse
 * the repeat probes a search inevitably makes on shared columns.
 *
 * <p><b>Out of bounds and unloaded terrain must report {@link NavCell#UNKNOWN}</b> rather than air.
 * Reporting air would let mobs plan routes through terrain that does not exist, and — worse — a
 * volume that generates the chunk to answer would turn pathfinding into a world-gen trigger.
 */
public interface NavVolume {

    /** The {@link NavCell} flag set for one cell. */
    int flags(int x, int y, int z);

    /**
     * How much of the cell is filled from its bottom: {@code 1.0} for a full block, {@code 0.5} for
     * a half-height stair tread, {@code 0.125} for one snow layer — and for a {@link NavCell#LIQUID}
     * cell, the height of the liquid's own surface (a flowing block is shorter than a source).
     *
     * <p>Meaningful for {@link NavCell#SOLID} and {@link NavCell#LIQUID}; other cells may return
     * anything and callers must not consult it.
     *
     * <p>For solids this is what makes an agent stand at the right height on shaped blocks, and it
     * must agree with whatever the game's collision uses — if the two disagree, mobs plan routes
     * their own physics refuses to walk. For liquids it is what puts a swimmer's node at the
     * waterline rather than on the bed a metre below, which is the difference between a shore
     * reading as a step and reading as a wall.
     */
    float topSurface(int x, int y, int z);
}
