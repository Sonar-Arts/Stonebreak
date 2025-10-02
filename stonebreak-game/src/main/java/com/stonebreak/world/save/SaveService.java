package com.stonebreak.world.save;

import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.repository.SaveRepository;
import com.stonebreak.world.save.repository.FileSaveRepository;
import com.stonebreak.world.save.serialization.*;
import com.stonebreak.world.save.util.StateConverter;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.world.chunk.Chunk;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Main save/load service coordinator.
 * Follows Single Responsibility - coordinates save/load operations.
 * Follows KISS - simple, clear responsibilities.
 * Follows YAGNI - only essential features.
 */
public class SaveService implements AutoCloseable {
    private static final int AUTO_SAVE_INTERVAL_SECONDS = 30;

    private final String worldPath;
    private final SaveRepository repository;
    private final ScheduledExecutorService autoSaveScheduler;
    private ScheduledFuture<?> autoSaveTask;
    private long lastAutoSaveTime;

    // Current state
    private volatile WorldData worldData;
    private volatile Player player;
    private volatile World world;

    public SaveService(String worldPath) {
        this.worldPath = worldPath;

        // Create repository with serializers
        this.repository = new FileSaveRepository(
            worldPath,
            new JsonWorldSerializer(),
            new JsonPlayerSerializer(),
            new BinaryChunkSerializer()
        );

        // Create auto-save scheduler
        this.autoSaveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoSave");
            t.setDaemon(true);
            return t;
        });

        this.lastAutoSaveTime = System.currentTimeMillis();
    }

    /**
     * Initializes the service with game state.
     */
    public void initialize(WorldData worldData, Player player, World world) {
        this.worldData = worldData;
        this.player = player;
        this.world = world;
    }

    /**
     * Starts auto-save.
     */
    public void startAutoSave() {
        if (autoSaveTask != null) {
            return; // Already running
        }

        autoSaveTask = autoSaveScheduler.scheduleAtFixedRate(
            this::performAutoSave,
            AUTO_SAVE_INTERVAL_SECONDS,
            AUTO_SAVE_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );

        System.out.println("[SAVE] Auto-save started - interval: " + AUTO_SAVE_INTERVAL_SECONDS + "s");
    }

    /**
     * Stops auto-save.
     */
    public void stopAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel(false);
            autoSaveTask = null;
            System.out.println("[SAVE] Auto-save stopped");
        }
    }

    /**
     * Forces a blocking flush of outstanding save operations.
     * Used when shutting down or switching worlds so edited chunks persist.
     */
    public void flushSavesBlocking(String reason) {
        CompletableFuture<Void> flushFuture;

        if (worldData != null && player != null && world != null) {
            flushFuture = saveAll();
        } else if (world != null) {
            flushFuture = saveDirtyChunks();
        } else {
            return; // Nothing to flush
        }

        try {
            flushFuture.get(15, TimeUnit.SECONDS);
            System.out.println("[SAVE] Flush completed (" + reason + ")");
        } catch (Exception e) {
            System.err.println("[SAVE] Flush failed (" + reason + "): " + e.getMessage());
        }
    }

    /**
     * Saves complete world state immediately.
     */
    public CompletableFuture<Void> saveAll() {
        if (worldData == null || player == null || world == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Save service not initialized")
            );
        }

        long startTime = System.currentTimeMillis();

        // Update world data with current play time
        long sessionTime = startTime - lastAutoSaveTime;
        WorldData updatedWorld = worldData.withAddedPlayTime(sessionTime);
        this.worldData = updatedWorld;

        // Convert player to data model
        PlayerData playerData = StateConverter.toPlayerData(player, updatedWorld.getWorldName());

        // Save world and player in parallel
        CompletableFuture<Void> worldFuture = repository.saveWorld(updatedWorld);
        CompletableFuture<Void> playerFuture = repository.savePlayer(playerData);

        // Save all dirty chunks
        CompletableFuture<Void> chunksFuture = saveDirtyChunks();

        return CompletableFuture.allOf(worldFuture, playerFuture, chunksFuture)
            .thenRun(() -> {
                long duration = System.currentTimeMillis() - startTime;
                lastAutoSaveTime = startTime;
                System.out.printf("[SAVE] Complete save finished in %dms%n", duration);
            });
    }

    /**
     * Saves only dirty chunks (for auto-save).
     */
    public CompletableFuture<Void> saveDirtyChunks() {
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }

        var dirtyChunks = world.getDirtyChunks();
        if (dirtyChunks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<?>[] futures = dirtyChunks.stream()
            .map(chunk -> {
                ChunkData chunkData = StateConverter.toChunkData(chunk);
                return repository.saveChunk(chunkData)
                    .thenRun(() -> chunk.markClean())
                    .exceptionally(ex -> {
                        System.err.println("[SAVE] Failed to save chunk at " +
                            chunk.getX() + "," + chunk.getZ() + ": " + ex.getMessage());
                        return null;
                    });
            })
            .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures)
            .thenRun(() -> {
                System.out.printf("[SAVE] Saved %d dirty chunks%n", dirtyChunks.size());
            });
    }

    /**
     * Loads complete world state.
     */
    public CompletableFuture<LoadResult> loadWorld() {
        long startTime = System.currentTimeMillis();

        return repository.loadWorld()
            .thenCompose(worldOpt -> {
                if (worldOpt.isEmpty()) {
                    return CompletableFuture.completedFuture(
                        new LoadResult(false, "World metadata not found", null, null)
                    );
                }

                WorldData world = worldOpt.get();

                // Load player data (with default fallback)
                return repository.loadPlayer()
                    .thenApply(playerOpt -> {
                        PlayerData playerData = playerOpt.orElse(
                            PlayerData.createDefault(world.getWorldName())
                        );

                        long duration = System.currentTimeMillis() - startTime;
                        System.out.printf("[LOAD] World loaded in %dms%n", duration);

                        return new LoadResult(true, null, world, playerData);
                    });
            })
            .exceptionally(ex -> {
                long duration = System.currentTimeMillis() - startTime;
                System.err.printf("[LOAD] Failed to load world in %dms: %s%n",
                    duration, ex.getMessage());
                return new LoadResult(false, ex.getMessage(), null, null);
            });
    }

    /**
     * Loads a single chunk.
     */
    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        return repository.loadChunk(chunkX, chunkZ)
            .thenApply(dataOpt -> {
                if (dataOpt.isEmpty()) {
                    return null; // Chunk doesn't exist - will be generated
                }
                return StateConverter.createChunkFromData(dataOpt.get());
            })
            .exceptionally(ex -> {
                System.err.println("[LOAD] Failed to load chunk at " +
                    chunkX + "," + chunkZ + ": " + ex.getMessage());
                return null; // Return null to trigger regeneration
            });
    }

    /**
     * Saves a single chunk immediately.
     */
    public CompletableFuture<Void> saveChunk(Chunk chunk) {
        ChunkData chunkData = StateConverter.toChunkData(chunk);
        return repository.saveChunk(chunkData)
            .thenRun(() -> chunk.markClean());
    }

    /**
     * Checks if chunk exists in storage.
     */
    public CompletableFuture<Boolean> chunkExists(int chunkX, int chunkZ) {
        return repository.chunkExists(chunkX, chunkZ);
    }

    /**
     * Checks if world save exists.
     */
    public CompletableFuture<Boolean> worldExists() {
        return repository.worldExists();
    }

    /**
     * Gets the world path.
     */
    public String getWorldPath() {
        return worldPath;
    }

    /**
     * Gets current world data.
     */
    public WorldData getWorldData() {
        return worldData;
    }

    /**
     * Performs auto-save operation.
     */
    private void performAutoSave() {
        if (worldData == null || player == null || world == null) {
            return;
        }

        long startTime = System.currentTimeMillis();

        // Update world data
        long sessionTime = startTime - lastAutoSaveTime;
        WorldData updatedWorld = worldData.withAddedPlayTime(sessionTime);
        this.worldData = updatedWorld;

        // Convert player to data model
        PlayerData playerData = StateConverter.toPlayerData(player, updatedWorld.getWorldName());

        // Save only essentials in auto-save (world, player, dirty chunks)
        repository.saveWorld(updatedWorld)
            .thenCompose(v -> repository.savePlayer(playerData))
            .thenCompose(v -> saveDirtyChunks())
            .thenRun(() -> {
                long duration = System.currentTimeMillis() - startTime;
                lastAutoSaveTime = startTime;
                System.out.printf("[AUTO-SAVE] Completed in %dms%n", duration);
            })
            .exceptionally(ex -> {
                System.err.println("[AUTO-SAVE] Failed: " + ex.getMessage());
                return null;
            });
    }

    @Override
    public void close() {
        stopAutoSave();
        flushSavesBlocking("service close");
        autoSaveScheduler.shutdown();
        repository.close();
        System.out.println("[SAVE] SaveService closed");
    }

    /**
     * Result of load operation.
     */
    public static class LoadResult {
        private final boolean success;
        private final String error;
        private final WorldData worldData;
        private final PlayerData playerData;

        public LoadResult(boolean success, String error, WorldData worldData, PlayerData playerData) {
            this.success = success;
            this.error = error;
            this.worldData = worldData;
            this.playerData = playerData;
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }
        public WorldData getWorldData() { return worldData; }
        public PlayerData getPlayerData() { return playerData; }
    }
}
