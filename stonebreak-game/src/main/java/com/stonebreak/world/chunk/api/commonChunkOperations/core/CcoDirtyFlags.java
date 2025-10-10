package com.stonebreak.world.chunk.api.commonChunkOperations.core;

/**
 * CCO Dirty Flags Interface - Track chunk modification state
 *
 * Tracks whether chunk needs mesh regeneration or data saving.
 * Implementations must be thread-safe for concurrent flag checks/sets.
 *
 * Design: Interface for dependency inversion
 * Performance: Lock-free implementations preferred
 */
public interface CcoDirtyFlags {

    /**
     * Check if mesh needs regeneration
     *
     * @return true if mesh dirty
     *
     * Thread-safety: Must be thread-safe
     */
    boolean isMeshDirty();

    /**
     * Check if data needs saving
     *
     * @return true if data dirty
     *
     * Thread-safety: Must be thread-safe
     */
    boolean isDataDirty();

    /**
     * Mark mesh as needing regeneration
     *
     * Thread-safety: Must be thread-safe
     * Performance: < 100ns for lock-free implementations
     */
    void markMeshDirty();

    /**
     * Mark data as needing save
     *
     * Thread-safety: Must be thread-safe
     * Performance: < 100ns for lock-free implementations
     */
    void markDataDirty();

    /**
     * Clear mesh dirty flag
     *
     * Thread-safety: Must be thread-safe
     */
    void clearMeshDirty();

    /**
     * Clear data dirty flag
     *
     * Thread-safety: Must be thread-safe
     */
    void clearDataDirty();

    /**
     * Clear all dirty flags
     *
     * Thread-safety: Must be thread-safe
     */
    void clearAll();

    /**
     * Check if any dirty flags are set
     *
     * @return true if mesh or data dirty
     */
    default boolean isAnyDirty() {
        return isMeshDirty() || isDataDirty();
    }
}
