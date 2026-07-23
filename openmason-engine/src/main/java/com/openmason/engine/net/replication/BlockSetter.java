package com.openmason.engine.net.replication;

import com.openmason.engine.voxel.IBlockType;

/**
 * Write sink for decoded block data. The game module implements this over its chunk /
 * world so engine codecs can apply blocks without importing game classes.
 *
 * <p>Coordinates match {@link com.openmason.engine.voxel.IVoxelChunkData}: {@code x} and
 * {@code z} are chunk-local (0..chunkSize-1) and {@code y} is world height
 * (0..worldHeight-1).
 *
 * <p><b>Later seam:</b> an interest-management / area-of-interest layer can wrap a
 * {@code BlockSetter} to gate which writes are applied per client. Not used this milestone.
 */
@FunctionalInterface
public interface BlockSetter {

    void setBlock(int x, int y, int z, IBlockType type);

    /**
     * Bulk fast path: replace section {@code sectionY} (16 tall) entirely with
     * one block. Return false to make the codec fall back to per-cell
     * {@link #setBlock} calls.
     */
    default boolean setSectionUniform(int sectionY, IBlockType block) {
        return false;
    }

    /**
     * Bulk fast path: replace section {@code sectionY} with paletted data.
     * {@code cellIndices} (4096 entries, section cell order
     * {@code ((y & 15)*16 + z)*16 + x}, values masked {@code & 0xFF}) index into
     * {@code palette}. The caller hands over ownership of both arrays.
     * Return false to fall back to per-cell {@link #setBlock} calls.
     */
    default boolean setSectionPaletted(int sectionY, IBlockType[] palette, byte[] cellIndices) {
        return false;
    }
}
