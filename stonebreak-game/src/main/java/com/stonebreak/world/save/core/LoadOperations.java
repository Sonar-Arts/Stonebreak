package com.stonebreak.world.save.core;

import com.stonebreak.world.chunk.Chunk;
import java.util.concurrent.CompletableFuture;

/**
 * Interface defining load operations for world data.
 * Follows Single Responsibility Principle - only handles loading.
 */
public interface LoadOperations {

    /**
     * Loads world metadata (seed, spawn, creation time).
     */
    CompletableFuture<WorldMetadata> loadWorldMetadata();

    /**
     * Loads basic player state (position, inventory, game mode).
     */
    CompletableFuture<PlayerState> loadPlayerState();

    /**
     * Loads a single chunk from storage.
     */
    CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ);

    /**
     * Checks if a chunk exists in storage.
     */
    CompletableFuture<Boolean> chunkExists(int chunkX, int chunkZ);

    /**
     * Loads all chunks within the specified radius of a center point.
     */
    CompletableFuture<Void> loadChunksInRadius(int centerX, int centerZ, int radius);

    /**
     * Validates that world data exists and is readable.
     */
    CompletableFuture<Boolean> validateWorldExists();
}