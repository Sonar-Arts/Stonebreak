package com.stonebreak.world.save.managers;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.core.LoadOperations;
import com.stonebreak.world.save.core.WorldMetadata;
import com.stonebreak.world.save.core.PlayerState;
import com.stonebreak.world.save.storage.providers.TerrainDataProvider;
import com.stonebreak.world.save.storage.providers.PlayerDataProvider;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Coordinates all world load operations using clean architecture principles.
 * Single responsibility: orchestrate load operations via providers.
 * Implements LoadOperations interface for dependency inversion.
 */
public class WorldLoadManager implements LoadOperations, AutoCloseable {

    private final TerrainDataProvider terrainProvider;
    private final PlayerDataProvider playerProvider;
    private final String worldPath;

    public WorldLoadManager(String worldPath) {
        this.worldPath = worldPath;
        this.terrainProvider = new TerrainDataProvider(worldPath);
        this.playerProvider = new PlayerDataProvider(worldPath);
    }

    @Override
    public CompletableFuture<WorldMetadata> loadWorldMetadata() {
        return terrainProvider.loadWorldMetadata()
            .thenApply(metadata -> {
                if (metadata != null) {
                    System.out.println("Loaded world metadata for: " + metadata.getWorldName());
                }
                return metadata;
            })
            .exceptionally(ex -> {
                System.err.println("Failed to load world metadata: " + ex.getMessage());
                throw new CompletionException(ex);
            });
    }

    @Override
    public CompletableFuture<PlayerState> loadPlayerState() {
        return playerProvider.loadPlayerState()
            .thenApply(playerState -> {
                if (playerState != null) {
                    System.out.println("Loaded player state");
                } else {
                    System.out.println("No saved player state found - using defaults");
                }
                return playerState;
            })
            .exceptionally(ex -> {
                System.err.println("Failed to load player state: " + ex.getMessage());
                throw new CompletionException(ex);
            });
    }

    @Override
    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        return terrainProvider.loadChunk(chunkX, chunkZ)
            .thenApply(chunk -> {
                if (chunk != null) {
                    System.out.println("Loaded chunk at " + chunkX + "," + chunkZ);
                }
                return chunk;
            })
            .exceptionally(ex -> {
                System.err.println("Failed to load chunk at " + chunkX + "," + chunkZ + ": " + ex.getMessage());
                return null; // Return null instead of throwing for individual chunk failures
            });
    }

    @Override
    public CompletableFuture<Boolean> chunkExists(int chunkX, int chunkZ) {
        return terrainProvider.chunkExists(chunkX, chunkZ)
            .exceptionally(ex -> {
                System.err.println("Error checking chunk existence at " + chunkX + "," + chunkZ + ": " + ex.getMessage());
                return false;
            });
    }

    @Override
    public CompletableFuture<Void> loadChunksInRadius(int centerX, int centerZ, int radius) {
        return terrainProvider.loadChunksInRadius(centerX, centerZ, radius)
            .thenRun(() -> {
                System.out.println("Loaded chunks in radius " + radius + " around " + centerX + "," + centerZ);
            })
            .exceptionally(ex -> {
                System.err.println("Failed to load chunks in radius: " + ex.getMessage());
                return null; // Don't fail completely if some chunks fail to load
            });
    }

    @Override
    public CompletableFuture<Boolean> validateWorldExists() {
        return terrainProvider.validateWorldExists()
            .thenApply(exists -> {
                if (exists) {
                    System.out.println("World validation passed");
                } else {
                    System.out.println("World validation failed - world does not exist");
                }
                return exists;
            })
            .exceptionally(ex -> {
                System.err.println("World validation error: " + ex.getMessage());
                return false;
            });
    }

    /**
     * Loads complete world state (metadata and player state).
     * Chunks are loaded on-demand as needed.
     */
    public CompletableFuture<WorldLoadResult> loadCompleteWorldState() {
        CompletableFuture<WorldMetadata> metadataFuture = loadWorldMetadata();
        CompletableFuture<PlayerState> playerFuture = loadPlayerState();

        return CompletableFuture.allOf(metadataFuture, playerFuture)
            .thenApply(v -> {
                try {
                    WorldMetadata metadata = metadataFuture.get();
                    PlayerState playerState = playerFuture.get();

                    WorldLoadResult result = new WorldLoadResult();
                    result.metadata = metadata;
                    result.playerState = playerState;
                    result.success = (metadata != null);

                    if (result.success) {
                        System.out.println("Successfully loaded complete world state");
                    } else {
                        System.out.println("World state loading completed with missing components");
                    }

                    return result;

                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            })
            .exceptionally(ex -> {
                System.err.println("Failed to load complete world state: " + ex.getMessage());

                WorldLoadResult result = new WorldLoadResult();
                result.success = false;
                result.error = ex.getMessage();
                return result;
            });
    }

    /**
     * Loads world metadata with validation.
     */
    public CompletableFuture<WorldMetadata> loadValidatedWorldMetadata() {
        return validateWorldExists()
            .thenCompose(exists -> {
                if (!exists) {
                    throw new RuntimeException("World does not exist or is not readable");
                }
                return loadWorldMetadata();
            })
            .thenApply(metadata -> {
                if (metadata == null) {
                    throw new RuntimeException("World metadata is corrupted or missing");
                }
                return metadata;
            });
    }

    /**
     * Loads player state with fallback to defaults.
     */
    public CompletableFuture<PlayerState> loadPlayerStateWithDefaults() {
        return loadPlayerState()
            .thenApply(playerState -> {
                if (playerState == null) {
                    System.out.println("Creating default player state");
                    return new PlayerState(); // Return default state if none exists
                }
                return playerState;
            });
    }

    /**
     * Loads chunks around a spawn point for initial world loading.
     */
    public CompletableFuture<Integer> loadSpawnChunks(WorldMetadata metadata, int renderDistance) {
        if (metadata == null || metadata.getSpawnPosition() == null) {
            return CompletableFuture.completedFuture(0);
        }

        int spawnChunkX = (int) Math.floor(metadata.getSpawnPosition().x / 16);
        int spawnChunkZ = (int) Math.floor(metadata.getSpawnPosition().z / 16);

        return CompletableFuture.supplyAsync(() -> {
            int loadedCount = 0;
            int radius = Math.min(renderDistance, 8); // Limit initial load radius

            for (int x = spawnChunkX - radius; x <= spawnChunkX + radius; x++) {
                for (int z = spawnChunkZ - radius; z <= spawnChunkZ + radius; z++) {
                    try {
                        Chunk chunk = loadChunk(x, z).get();
                        if (chunk != null) {
                            loadedCount++;
                        }
                    } catch (Exception e) {
                        // Continue loading other chunks
                    }
                }
            }

            System.out.println("Loaded " + loadedCount + " chunks around spawn point");
            return loadedCount;
        });
    }

    /**
     * Gets loading statistics and performance metrics.
     */
    public LoadStats getLoadStats() {
        LoadStats stats = new LoadStats();

        // Get terrain storage stats
        var regionStats = terrainProvider.getStorageStats();
        stats.totalRegions = regionStats.totalRegions;
        stats.totalChunks = regionStats.totalChunks;
        stats.totalTerrainSize = regionStats.totalSize;

        // Check if player data exists
        try {
            stats.playerDataExists = playerProvider.playerDataExists().get();
            if (stats.playerDataExists) {
                stats.playerDataSize = playerProvider.getPlayerDataSize().get();
            }
        } catch (Exception e) {
            stats.playerDataExists = false;
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
            System.out.println("WorldLoadManager closed successfully");
        } catch (Exception e) {
            System.err.println("Error closing WorldLoadManager: " + e.getMessage());
        }
    }

    /**
     * Result of loading complete world state.
     */
    public static class WorldLoadResult {
        public WorldMetadata metadata;
        public PlayerState playerState;
        public boolean success;
        public String error;

        public boolean isValid() {
            return success && metadata != null;
        }
    }

    /**
     * Statistics about load operations.
     */
    public static class LoadStats {
        public int totalRegions = 0;
        public int totalChunks = 0;
        public long totalTerrainSize = 0;
        public boolean playerDataExists = false;
        public long playerDataSize = 0;

        public long getTotalSize() {
            return totalTerrainSize + (playerDataExists ? playerDataSize : 0);
        }

        @Override
        public String toString() {
            return String.format("Regions: %d, Chunks: %d, Terrain: %.2f MB, Player: %s, Total: %.2f MB",
                totalRegions, totalChunks,
                totalTerrainSize / (1024.0 * 1024.0),
                playerDataExists ? String.format("%.2f KB", playerDataSize / 1024.0) : "Not Found",
                getTotalSize() / (1024.0 * 1024.0));
        }
    }
}