package com.stonebreak.world.generation.features;

import com.stonebreak.util.BlockPos;
import com.stonebreak.world.chunk.utils.ChunkPosition;

import java.util.Set;

/**
 * Represents a feature that has been queued for placement when required chunks exist.
 *
 * @param name Feature name for debugging (e.g., "elm_tree", "village")
 * @param origin World position where feature originates
 * @param requiredChunks Set of chunks required for safe placement
 * @param placer Lambda that places the feature when chunks are ready
 */
public record QueuedFeature(
    String name,
    BlockPos origin,
    Set<ChunkPosition> requiredChunks,
    FeaturePlacer placer
) {
    /**
     * Validates that required chunks is not empty.
     */
    public QueuedFeature {
        if (requiredChunks == null || requiredChunks.isEmpty()) {
            throw new IllegalArgumentException("QueuedFeature must require at least one chunk");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("QueuedFeature must have a non-empty name");
        }
        if (origin == null) {
            throw new IllegalArgumentException("QueuedFeature must have an origin position");
        }
        if (placer == null) {
            throw new IllegalArgumentException("QueuedFeature must have a placer function");
        }
    }
}
