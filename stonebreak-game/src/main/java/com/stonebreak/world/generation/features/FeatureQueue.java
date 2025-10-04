package com.stonebreak.world.generation.features;

import com.stonebreak.world.World;
import com.stonebreak.world.chunk.utils.ChunkPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages deferred feature placement for cross-chunk features.
 * Features that span multiple chunks are queued until all required chunks exist.
 *
 * Thread-safe: Uses ConcurrentHashMap for concurrent access during chunk loading.
 *
 * Example usage:
 * - Large elm trees that span 2x2 chunks
 * - Villages that span 3x3 chunks (future)
 * - Dungeons of variable size (future)
 */
public class FeatureQueue {
    // Maps chunk position → list of features waiting for that chunk to exist
    private final Map<ChunkPosition, List<QueuedFeature>> pendingFeatures = new ConcurrentHashMap<>();

    /**
     * Queues a feature for deferred placement.
     * The feature will be placed when all required chunks exist.
     *
     * @param feature The feature to queue
     */
    public synchronized void queueFeature(QueuedFeature feature) {
        // Add feature to the queue for each required chunk
        for (ChunkPosition requiredChunk : feature.requiredChunks()) {
            pendingFeatures
                .computeIfAbsent(requiredChunk, k -> new ArrayList<>())
                .add(feature);
        }
    }

    /**
     * Processes queued features when a chunk is loaded/generated.
     * Attempts to place features that were waiting for this chunk.
     *
     * @param world The world to place features in
     * @param chunkPos The chunk that just loaded
     */
    public synchronized void processChunk(World world, ChunkPosition chunkPos) {
        List<QueuedFeature> features = pendingFeatures.remove(chunkPos);
        if (features == null || features.isEmpty()) {
            return;
        }

        // Attempt to place each queued feature
        for (QueuedFeature feature : features) {
            if (areAllChunksLoaded(world, feature)) {
                try {
                    // All required chunks exist - place the feature
                    feature.placer().place(world);

                    // Remove feature from all other chunk queues
                    removeFeatureFromAllQueues(feature);
                } catch (Exception e) {
                    System.err.println("[FEATURE-QUEUE] Failed to place feature '" + feature.name() +
                        "' at " + feature.origin() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Checks if all required chunks for a feature are loaded.
     */
    private boolean areAllChunksLoaded(World world, QueuedFeature feature) {
        for (ChunkPosition requiredChunk : feature.requiredChunks()) {
            if (!world.hasChunkAt(requiredChunk.getX(), requiredChunk.getZ())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes a feature from all chunk queues after it's been placed.
     * This prevents duplicate placement.
     */
    private void removeFeatureFromAllQueues(QueuedFeature placedFeature) {
        for (ChunkPosition chunk : placedFeature.requiredChunks()) {
            List<QueuedFeature> chunkFeatures = pendingFeatures.get(chunk);
            if (chunkFeatures != null) {
                chunkFeatures.removeIf(f -> f == placedFeature); // Reference equality
                if (chunkFeatures.isEmpty()) {
                    pendingFeatures.remove(chunk);
                }
            }
        }
    }

    /**
     * Clears all queued features.
     * Call this when unloading a world.
     */
    public synchronized void clear() {
        pendingFeatures.clear();
    }

    /**
     * Gets the number of pending features waiting for placement.
     */
    public int getPendingFeatureCount() {
        return pendingFeatures.values().stream()
            .mapToInt(List::size)
            .sum();
    }
}
