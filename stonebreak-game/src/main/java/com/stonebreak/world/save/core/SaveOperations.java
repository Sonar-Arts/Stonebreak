package com.stonebreak.world.save.core;

import com.stonebreak.world.chunk.Chunk;
import java.util.concurrent.CompletableFuture;

/**
 * Interface defining save operations for world data.
 * Follows Single Responsibility Principle - only handles saving.
 */
public interface SaveOperations {

    /**
     * Saves world metadata (seed, spawn, creation time).
     */
    CompletableFuture<Void> saveWorldMetadata(WorldMetadata metadata);

    /**
     * Saves basic player state (position, inventory, game mode).
     */
    CompletableFuture<Void> savePlayerState(PlayerState playerState);

    /**
     * Saves a single chunk with palette compression.
     */
    CompletableFuture<Void> saveChunk(Chunk chunk);

    /**
     * Saves all dirty chunks in the world.
     */
    CompletableFuture<Void> saveDirtyChunks();

    /**
     * Forces save of all world data.
     */
    CompletableFuture<Void> saveAll();
}