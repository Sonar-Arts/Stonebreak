package com.stonebreak.world.generation;

/**
 * Interface for reporting loading progress during terrain generation.
 *
 * Decouples terrain generation from the loading screen implementation,
 * following the Dependency Inversion Principle.
 *
 * Implementations can:
 * - Update UI loading screens
 * - Log progress to console
 * - Track metrics for debugging
 * - Do nothing (NullObject pattern for testing)
 *
 * Benefits:
 * - TerrainGenerationSystem no longer depends on Game singleton
 * - Easy to mock for unit testing
 * - Can swap implementations without modifying generation code
 */
public interface LoadingProgressReporter {

    /**
     * Reports progress for a specific generation stage.
     *
     * @param stageName Name of the current generation stage
     *                  (e.g., "Generating Base Terrain Shape", "Determining Biomes & Blending")
     */
    void updateProgress(String stageName);

    /**
     * No-op implementation for situations where progress reporting is not needed.
     * Follows the Null Object pattern.
     */
    LoadingProgressReporter NULL = stageName -> {
        // Do nothing
    };
}
