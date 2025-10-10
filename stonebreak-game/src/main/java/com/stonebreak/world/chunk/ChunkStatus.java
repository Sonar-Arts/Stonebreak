package com.stonebreak.world.chunk;

/**
 * Represents the generation status of a chunk, following Minecraft's multi-stage generation pipeline.
 * Chunks progress through these stages to ensure complete generation before becoming visible.
 *
 * Status progression:
 * EMPTY → TERRAIN_GENERATED → FEATURES_PENDING → FEATURES_POPULATED → FULL
 *
 * This prevents the "pop-in" effect where terrain appears before features like trees and flowers.
 */
public enum ChunkStatus {
    /**
     * Chunk has not been generated yet. No terrain or features exist.
     */
    EMPTY,

    /**
     * Base terrain has been generated (stone, dirt, grass, water, etc.) but no features.
     * This is the "bald" chunk state - terrain shape is complete but no decorations.
     * Chunk is NOT visible at this stage.
     */
    TERRAIN_GENERATED,

    /**
     * Terrain is complete and chunk is waiting for required neighbor chunks to exist
     * before features can be safely placed (prevents cross-chunk feature corruption).
     * Chunk is NOT visible at this stage.
     */
    FEATURES_PENDING,

    /**
     * All features have been placed (ores, trees, flowers, etc.).
     * Chunk is ready for mesh generation but mesh may not be uploaded yet.
     * Chunk is NOT visible at this stage.
     */
    FEATURES_POPULATED,

    /**
     * Chunk is fully generated with terrain, features, and mesh uploaded to GPU.
     * Chunk is now VISIBLE and renderable to the player.
     */
    FULL;

    /**
     * Checks if this status allows the chunk to be rendered.
     * Only FULL chunks should be visible to prevent pop-in effects.
     */
    public boolean isRenderable() {
        return this == FULL;
    }

    /**
     * Checks if terrain has been generated (not empty or pending).
     */
    public boolean hasTerrainGenerated() {
        return this != EMPTY;
    }

    /**
     * Checks if features have been populated.
     */
    public boolean hasFeaturesPopulated() {
        return this == FEATURES_POPULATED || this == FULL;
    }
}
