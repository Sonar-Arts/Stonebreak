package com.openmason.engine.voxel.cco.data;

import com.openmason.engine.voxel.IBlockType;

/**
 * Abstraction over per-chunk block storage for the CCO API.
 *
 * <p>Implementations decide the in-memory representation (dense arrays,
 * paletted sections, ...). Callers interact purely through block-typed
 * get/set so the representation can change without touching them.
 *
 * <p>Concurrency contract (must be honored by all implementations):
 * <ul>
 *   <li>Reads are lock-free and safe from any thread. A racing read returns
 *       either the old or the new block for the cell — never a torn or
 *       out-of-range value.</li>
 *   <li>Writes may synchronize internally; callers do not need external
 *       locking for single-cell writes.</li>
 * </ul>
 */
public interface CcoBlockStorage {

    /**
     * Gets the block at the given local coordinates.
     *
     * @return Block type (may be null if a null block was stored), or null if out of bounds
     */
    IBlockType get(int x, int y, int z);

    /**
     * Sets the block at the given local coordinates.
     *
     * @return true if the cell changed, false if out of bounds or unchanged
     */
    boolean set(int x, int y, int z, IBlockType block);

    /** Checks whether the coordinates are within this storage's bounds. */
    boolean isInBounds(int x, int y, int z);

    int getSizeX();

    int getSizeY();

    int getSizeZ();

    /** Number of cells holding a non-air block. */
    int countNonAirBlocks();

    /**
     * Highest Y that contains a non-air block anywhere in the chunk,
     * or -1 if the chunk is entirely air. Used by meshers to skip
     * empty upper air space.
     */
    int getHighestNonAirY();

    /**
     * Creates an independent snapshot copy of this storage.
     * Cheap for paletted implementations (copies palettes + index arrays,
     * uniform sections cost almost nothing).
     */
    CcoBlockStorage copy();

    /**
     * Replaces this storage's contents with a copy of another storage's
     * contents. Both storages must have identical dimensions.
     */
    void copyFrom(CcoBlockStorage other);
}
