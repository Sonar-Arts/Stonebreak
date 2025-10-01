package com.stonebreak.world.save.managers;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.world.save.core.SaveOperations;
import com.stonebreak.world.save.core.WorldMetadata;
import com.stonebreak.world.save.core.PlayerState;
import com.stonebreak.world.save.storage.providers.TerrainDataProvider;
import com.stonebreak.world.save.storage.providers.PlayerDataProvider;
import java.util.concurrent.CompletableFuture;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates all world save operations using clean architecture principles.
 * Single responsibility: orchestrate save operations via providers.
 * Implements SaveOperations interface for dependency inversion.
 */
public class WorldSaveManager implements SaveOperations, AutoCloseable {

    private final TerrainDataProvider terrainProvider;
    private final PlayerDataProvider playerProvider;
    private final String worldPath;

    public WorldSaveManager(String worldPath) {
        this.worldPath = worldPath;
        this.terrainProvider = new TerrainDataProvider(worldPath);
        this.playerProvider = new PlayerDataProvider(worldPath);
    }

    @Override
    public CompletableFuture<Void> saveWorldMetadata(WorldMetadata metadata) {
        return terrainProvider.saveWorldMetadata(metadata)
            .thenRun(() -> {
                System.out.println("Saved world metadata for: " + metadata.getWorldName());
            })
            .exceptionally(ex -> {
                System.err.println("Failed to save world metadata: " + ex.getMessage());
                throw new RuntimeException(ex);
            });
    }

    @Override
    public CompletableFuture<Void> savePlayerState(PlayerState playerState) {
        return playerProvider.savePlayerState(playerState)
            .thenRun(() -> {
                System.out.println("Saved player state");
            })
            .exceptionally(ex -> {
                System.err.println("Failed to save player state: " + ex.getMessage());
                throw new RuntimeException(ex);
            });
    }

    @Override
    public CompletableFuture<Void> saveChunk(Chunk chunk) {
        return terrainProvider.saveChunk(chunk)
            .thenRun(() -> {
                System.out.println("Saved chunk at " + chunk.getX() + "," + chunk.getZ());
            })
            .exceptionally(ex -> {
                System.err.println("Failed to save chunk at " + chunk.getX() + "," + chunk.getZ() + ": " + ex.getMessage());
                throw new RuntimeException(ex);
            });
    }

    @Override
    public CompletableFuture<Void> saveDirtyChunks() {
        // This method requires a World instance - use saveDirtyChunks(Collection<Chunk>) instead
        throw new UnsupportedOperationException("Use saveDirtyChunks(Collection<Chunk> chunks) instead");
    }

    /**
     * Saves dirty chunks from a collection.
     */
    public CompletableFuture<Void> saveDirtyChunks(Collection<Chunk> chunks) {
        List<Chunk> dirtyChunks = new ArrayList<>();

        for (Chunk chunk : chunks) {
            if (chunk.isDirty()) {
                dirtyChunks.add(chunk);
            }
        }

        if (dirtyChunks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return terrainProvider.saveDirtyChunks(dirtyChunks)
            .thenRun(() -> {
                System.out.println("Saved " + dirtyChunks.size() + " dirty chunks");
            })
            .exceptionally(ex -> {
                System.err.println("Failed to save dirty chunks: " + ex.getMessage());
                throw new RuntimeException(ex);
            });
    }

    @Override
    public CompletableFuture<Void> saveAll() {
        // This method requires World and Player instances - use saveCompleteWorldState instead
        throw new UnsupportedOperationException("Use saveCompleteWorldState(WorldMetadata, PlayerState, Collection<Chunk>) instead");
    }

    /**
     * Saves complete world state (metadata, player, and chunks).
     */
    public CompletableFuture<Void> saveCompleteWorldState(WorldMetadata metadata, PlayerState playerState, Collection<Chunk> chunks) {
        return CompletableFuture.allOf(
            saveWorldMetadata(metadata),
            savePlayerState(playerState),
            saveDirtyChunks(chunks)
        ).thenRun(() -> {
            System.out.println("Successfully saved complete world state");
        }).exceptionally(ex -> {
            System.err.println("Failed to save complete world state: " + ex.getMessage());
            throw new RuntimeException(ex);
        });
    }

    /**
     * Performs emergency save of critical data only.
     */
    public CompletableFuture<Void> emergencySave(WorldMetadata metadata, PlayerState playerState) {
        return CompletableFuture.allOf(
            saveWorldMetadata(metadata),
            savePlayerState(playerState)
        ).thenRun(() -> {
            System.out.println("Emergency save completed");
        }).exceptionally(ex -> {
            System.err.println("Emergency save failed: " + ex.getMessage());
            throw new RuntimeException(ex);
        });
    }

    /**
     * Creates a backup before performing save operations.
     */
    public CompletableFuture<Void> saveWithBackup(WorldMetadata metadata, PlayerState playerState, Collection<Chunk> chunks) {
        return createBackup()
            .thenCompose(v -> saveCompleteWorldState(metadata, playerState, chunks))
            .thenRun(() -> {
                System.out.println("Save with backup completed successfully");
            })
            .exceptionally(ex -> {
                System.err.println("Save with backup failed: " + ex.getMessage());
                throw new RuntimeException(ex);
            });
    }

    /**
     * Creates backup copies of critical world data.
     */
    public CompletableFuture<Void> createBackup() {
        return playerProvider.backupPlayerData()
            .thenRun(() -> {
                System.out.println("Backup created successfully");
            })
            .exceptionally(ex -> {
                System.err.println("Backup creation failed: " + ex.getMessage());
                return null; // Don't fail save operation if backup fails
            });
    }

    /**
     * Gets statistics about the save operation performance.
     */
    public SaveStats getSaveStats() {
        SaveStats stats = new SaveStats();

        // Get terrain storage stats
        var regionStats = terrainProvider.getStorageStats();
        stats.totalRegions = regionStats.totalRegions;
        stats.totalChunks = regionStats.totalChunks;
        stats.totalTerrainSize = regionStats.totalSize;

        // Get player data size
        try {
            stats.playerDataSize = playerProvider.getPlayerDataSize().get();
        } catch (Exception e) {
            stats.playerDataSize = 0;
        }

        return stats;
    }

    /**
     * Gets the world path this manager is associated with.
     */
    public String getWorldPath() {
        return worldPath;
    }

    /**
     * Gets terrain data provider for direct access if needed.
     */
    public TerrainDataProvider getTerrainProvider() {
        return terrainProvider;
    }

    /**
     * Gets player data provider for direct access if needed.
     */
    public PlayerDataProvider getPlayerProvider() {
        return playerProvider;
    }

    @Override
    public void close() {
        try {
            terrainProvider.close();
            System.out.println("WorldSaveManager closed successfully");
        } catch (Exception e) {
            System.err.println("Error closing WorldSaveManager: " + e.getMessage());
        }
    }

    /**
     * Statistics about save operations.
     */
    public static class SaveStats {
        public int totalRegions = 0;
        public int totalChunks = 0;
        public long totalTerrainSize = 0;
        public long playerDataSize = 0;

        public long getTotalSize() {
            return totalTerrainSize + playerDataSize;
        }

        @Override
        public String toString() {
            return String.format("Regions: %d, Chunks: %d, Terrain: %.2f MB, Player: %.2f KB, Total: %.2f MB",
                totalRegions, totalChunks,
                totalTerrainSize / (1024.0 * 1024.0),
                playerDataSize / 1024.0,
                getTotalSize() / (1024.0 * 1024.0));
        }
    }
}