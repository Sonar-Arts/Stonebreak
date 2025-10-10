package com.stonebreak.world.chunk.utils;

import com.stonebreak.world.chunk.api.commonChunkOperations.core.CcoPlayerTracker;
import org.joml.Vector3f;

/**
 * CCO-integrated listener for player chunk boundary crossings.
 * This demonstrates how to use the CcoPlayerTracker to respond to player movement.
 *
 * Example Use Cases:
 * - Trigger chunk loading/unloading based on player movement
 * - Update chunk dirty tracking when player enters/exits chunks
 * - Trigger mesh regeneration for chunks near player
 * - Log player movement for debugging
 *
 * Design Principles:
 * - Observer pattern for decoupled communication
 * - Single Responsibility (only handles chunk notifications)
 * - KISS - simple, focused implementation
 */
public class CcoPlayerChunkListener implements CcoPlayerTracker.PositionChangeListener {

    private final ChunkManager chunkManager;
    private boolean debugLogging = false;

    public CcoPlayerChunkListener(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
    }

    /**
     * Enables or disables debug logging for position changes.
     *
     * @param enabled Whether to enable debug logging
     */
    public void setDebugLogging(boolean enabled) {
        this.debugLogging = enabled;
    }

    @Override
    public void onPositionChanged(Vector3f oldPosition, Vector3f newPosition) {
        if (debugLogging) {
            System.out.printf("[CCO-PLAYER-LISTENER] Position changed: (%.2f, %.2f, %.2f) -> (%.2f, %.2f, %.2f)%n",
                oldPosition.x, oldPosition.y, oldPosition.z,
                newPosition.x, newPosition.y, newPosition.z);
        }

        // Future enhancement: Could trigger proximity-based chunk updates here
    }

    @Override
    public void onChunkBoundaryCrossed(int oldChunkX, int oldChunkZ, int newChunkX, int newChunkZ) {
        System.out.printf("[CCO-PLAYER-LISTENER] Player crossed chunk boundary: (%d, %d) -> (%d, %d)%n",
            oldChunkX, oldChunkZ, newChunkX, newChunkZ);

        // Future enhancements could include:
        // - Triggering automatic chunk loading for new chunks
        // - Marking nearby chunks as dirty for mesh regeneration
        // - Updating chunk priorities based on player proximity
        // - Triggering auto-save when player moves significant distance
    }
}
