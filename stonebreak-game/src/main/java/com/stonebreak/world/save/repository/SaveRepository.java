package com.stonebreak.world.save.repository;

import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.ChunkData;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Repository interface for save/load operations.
 * Follows Dependency Inversion - high-level code depends on this abstraction.
 * Follows Interface Segregation - minimal, focused interface.
 */
public interface SaveRepository {
    /**
     * Saves world metadata.
     */
    CompletableFuture<Void> saveWorld(WorldData world);

    /**
     * Loads world metadata.
     */
    CompletableFuture<Optional<WorldData>> loadWorld();

    /**
     * Saves player data.
     */
    CompletableFuture<Void> savePlayer(PlayerData player);

    /**
     * Loads player data.
     */
    CompletableFuture<Optional<PlayerData>> loadPlayer();

    /**
     * Saves a chunk.
     */
    CompletableFuture<Void> saveChunk(ChunkData chunk);

    /**
     * Loads a chunk.
     */
    CompletableFuture<Optional<ChunkData>> loadChunk(int chunkX, int chunkZ);

    /**
     * Checks if a chunk exists.
     */
    CompletableFuture<Boolean> chunkExists(int chunkX, int chunkZ);

    /**
     * Checks if the world save exists.
     */
    CompletableFuture<Boolean> worldExists();

    /**
     * Closes resources.
     */
    void close();
}
