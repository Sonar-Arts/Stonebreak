package com.stonebreak.world.chunk.api.commonChunkOperations.core;

import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoSerializableSnapshot;

/**
 * CCO Serializable Interface - Chunk serialization capability
 *
 * Provides snapshot creation for save system integration.
 * Implementations should use lazy evaluation for performance.
 *
 * Design: Interface for save/load system integration
 * Performance: Lazy snapshots - no cost until accessed
 */
public interface CcoSerializable {

    /**
     * Create serializable snapshot of chunk
     *
     * Returns lazy snapshot - actual serialization deferred until accessed.
     *
     * @return Immutable snapshot for save system
     *
     * Thread-safety: Must be thread-safe
     * Performance: < 1μs for lazy snapshot creation
     */
    CcoSerializableSnapshot createSnapshot();

    /**
     * Check if chunk has unsaved changes
     *
     * @return true if data dirty (needs save)
     */
    boolean needsSave();

    /**
     * Mark chunk as saved (clear data dirty flag)
     *
     * Thread-safety: Must be thread-safe
     */
    void markSaved();
}
