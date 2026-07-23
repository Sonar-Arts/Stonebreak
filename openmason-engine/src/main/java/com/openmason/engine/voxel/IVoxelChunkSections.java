package com.openmason.engine.voxel;

/**
 * Optional bulk-access extension of {@link IVoxelChunkData}: exposes a chunk's
 * blocks one 16-tall section at a time as a flat id array, in section cell
 * order {@code ((y & 15)*16 + z)*16 + x} (x fastest — the same order the
 * network codec walks). Codecs prefer this over 65k per-cell
 * {@code getBlock} calls when the implementation supports it.
 */
public interface IVoxelChunkSections {

    /**
     * Copies section {@code sectionY}'s 4096 block ids into {@code dst}
     * (length ≥ 4096). Returns false when bulk access is unavailable —
     * callers must then fall back to per-cell reads.
     */
    boolean copySectionBlockIds(int sectionY, short[] dst);
}
