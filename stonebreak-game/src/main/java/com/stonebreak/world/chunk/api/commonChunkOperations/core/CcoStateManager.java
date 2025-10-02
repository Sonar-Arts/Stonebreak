package com.stonebreak.world.chunk.api.commonChunkOperations.core;

import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;

/**
 * CCO State Manager Interface - Chunk lifecycle state management
 *
 * Manages chunk state transitions with validation.
 * Implementations must be thread-safe and atomic.
 *
 * Design: Interface for dependency inversion
 * Performance: Lock-free implementations preferred
 */
public interface CcoStateManager {

    /**
     * Get current chunk state
     *
     * @return Current state
     *
     * Thread-safety: Must be thread-safe
     */
    CcoChunkState getState();

    /**
     * Attempt to transition to new state
     *
     * @param newState Target state
     * @return true if transition succeeded
     *
     * Thread-safety: Must be atomic
     * Performance: < 200ns for lock-free implementations
     */
    boolean transitionTo(CcoChunkState newState);

    /**
     * Check if transition is valid
     *
     * @param from Source state
     * @param to Target state
     * @return true if transition allowed
     */
    boolean isTransitionValid(CcoChunkState from, CcoChunkState to);

    /**
     * Force state change (bypass validation)
     *
     * Use with caution - for recovery scenarios only
     *
     * @param newState Target state
     */
    void forceState(CcoChunkState newState);

    /**
     * Check if chunk is in terminal state
     *
     * @return true if UNLOADED
     */
    default boolean isTerminal() {
        return getState() == CcoChunkState.UNLOADED;
    }

    /**
     * Check if chunk is ready for rendering
     *
     * @return true if READY or ACTIVE
     */
    default boolean isRenderable() {
        CcoChunkState state = getState();
        return state == CcoChunkState.READY || state == CcoChunkState.ACTIVE;
    }
}
