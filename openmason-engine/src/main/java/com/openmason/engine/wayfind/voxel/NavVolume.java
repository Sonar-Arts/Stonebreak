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
     * How much of the cell its solid part fills, measured from the cell's bottom: {@code 1.0} for a
     * full block, {@code 0.5} for a half-height stair tread, {@code 0.125} for one snow layer.
     *
     * <p>Only meaningful when {@link NavCell#SOLID} is set; other cells may return anything and
     * callers must not consult it. This is what makes an agent stand at the right height on shaped
     * blocks, and it must agree with whatever the game's collision uses — if the two disagree, mobs
     * plan routes their own physics refuses to walk.
     */
    float topSurface(int x, int y, int z);
}
