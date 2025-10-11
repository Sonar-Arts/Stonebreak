package com.stonebreak.world.save;

import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.repository.FileSaveRepository;
import com.stonebreak.world.save.util.StateConverter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates save/load operations. All disk operations run on a single I/O thread to
 * avoid concurrent writes while snapshots are taken on the calling thread to remain
 * consistent with CCO expectations.
 */
public class SaveService implements AutoCloseable {

    private static final int AUTO_SAVE_INTERVAL_SECONDS = 30;

    private final String worldPath;
    private final FileSaveRepository repository;
    private final ExecutorService ioExecutor;
    private final ScheduledExecutorService autoSaveScheduler;
    private final AtomicBoolean autoSaveInProgress = new AtomicBoolean(false);

    private ScheduledFuture<?> autoSaveTask;
    private volatile WorldData worldData;
    private volatile Player player;
    private volatile World world;
    private volatile long lastAutoSaveTime;

    public SaveService(String worldPath) {
        this.worldPath = Objects.requireNonNull(worldPath, "worldPath");
        this.repository = new FileSaveRepository(worldPath);
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Save-IO");
            t.setDaemon(true);
            return t;
        });
        this.autoSaveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoSave");
            t.setDaemon(true);
            return t;
        });
        this.lastAutoSaveTime = System.currentTimeMillis();

        try {
            repository.ensureWorldDirectory();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create world directory: " + worldPath, e);
        }
    }

    public void initialize(WorldData worldData, Player player, World world) {
        this.worldData = worldData;
        this.player = player;
        this.world = world;
    }

    public void startAutoSave() {
        if (autoSaveTask != null) {
            return;
        }
        autoSaveTask = autoSaveScheduler.scheduleAtFixedRate(
            this::performAutoSave,
            AUTO_SAVE_INTERVAL_SECONDS,
            AUTO_SAVE_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        System.out.println("[SAVE] Auto-save started - interval: " + AUTO_SAVE_INTERVAL_SECONDS + "s");
    }

    public void stopAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel(false);
            autoSaveTask = null;
            System.out.println("[SAVE] Auto-save stopped");
        }
    }

    public void flushSavesBlocking(String reason) {
        CompletableFuture<Void> future;
        if (worldData != null && player != null && world != null) {
            future = saveAll();
        } else if (world != null) {
            future = saveDirtyChunks();
        } else {
            return;
        }

        try {
            future.get(15, TimeUnit.SECONDS);
            System.out.println("[SAVE] Flush completed (" + reason + ")");
        } catch (Exception e) {
            System.err.println("[SAVE] Flush failed (" + reason + "): " + e.getMessage());
        }
    }

    public CompletableFuture<Void> saveAll() {
        if (worldData == null || player == null || world == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Save service not initialized")
            );
        }

        long now = System.currentTimeMillis();
        long sessionMillis = now - lastAutoSaveTime;
        WorldData updatedWorld = worldData.withAddedPlayTime(sessionMillis);
        PlayerData playerData = StateConverter.toPlayerData(player, updatedWorld.getWorldName());
        List<ChunkSaveTask> chunkTasks = collectDirtyChunkTasks();

        SaveWork work = new SaveWork(updatedWorld, playerData, chunkTasks, "manual/full");
        return submitSave(work)
            .whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    lastAutoSaveTime = now;
                    System.out.printf("[SAVE] Complete save finished in %dms%n", System.currentTimeMillis() - now);
                }
            });
    }

    public CompletableFuture<Void> saveDirtyChunks() {
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        List<ChunkSaveTask> chunkTasks = collectDirtyChunkTasks();
        if (chunkTasks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return submitSave(new SaveWork(null, null, chunkTasks, "chunks-only"))
            .thenRun(() -> System.out.printf("[SAVE] Saved %d dirty chunks%n", chunkTasks.size()));
    }

    public CompletableFuture<LoadResult> loadWorld() {
        long start = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> {
            try {
                var worldOpt = repository.loadWorld();
                if (worldOpt.isEmpty()) {
                    return new LoadResult(false, "World metadata not found", null, null);
                }
                WorldData data = worldOpt.get();
                var playerOpt = repository.loadPlayer();
                PlayerData playerData = playerOpt.orElse(PlayerData.createDefault(data.getWorldName()));
                System.out.printf("[LOAD] World metadata loaded in %dms%n", System.currentTimeMillis() - start);
                return new LoadResult(true, null, data, playerData);
            } catch (IOException e) {
                System.err.printf("[LOAD] Failed in %dms: %s%n", System.currentTimeMillis() - start, e.getMessage());
                return new LoadResult(false, e.getMessage(), null, null);
            }
        }, ioExecutor);
    }

    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var dataOpt = repository.loadChunk(chunkX, chunkZ);
                if (dataOpt.isEmpty()) {
                    return null;
                }
                if (world == null) {
                    throw new IllegalStateException("World not initialized");
                }
                return StateConverter.createChunkFromData(dataOpt.get(), world);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load chunk (" + chunkX + "," + chunkZ + ")", e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<Void> saveChunk(Chunk chunk) {
        if (!chunk.getCcoDirtyTracker().checkAndClearDataDirty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("World not initialized"));
        }
        ChunkData data = StateConverter.toChunkData(chunk, world);
        List<ChunkSaveTask> tasks = List.of(new ChunkSaveTask(chunk, data));
        return submitSave(new SaveWork(null, null, tasks, "single-chunk"));
    }

    public CompletableFuture<Boolean> chunkExists(int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> repository.chunkExists(chunkX, chunkZ), ioExecutor);
    }

    public String getWorldPath() {
        return worldPath;
    }

    public WorldData getWorldData() {
        return worldData;
    }

    @Override
    public void close() {
        stopAutoSave();
        flushSavesBlocking("service close");
        autoSaveScheduler.shutdown();
        ioExecutor.shutdown();
        repository.close();
        System.out.println("[SAVE] SaveService closed");
    }

    private List<ChunkSaveTask> collectDirtyChunkTasks() {
        if (world == null) {
            return List.of();
        }
        List<Chunk> dirtyChunks = world.getDirtyChunks();
        if (dirtyChunks.isEmpty()) {
            return List.of();
        }
        List<ChunkSaveTask> tasks = new ArrayList<>(dirtyChunks.size());
        for (Chunk chunk : dirtyChunks) {
            if (!chunk.getCcoDirtyTracker().checkAndClearDataDirty()) {
                continue;
            }
            ChunkData data = StateConverter.toChunkData(chunk, world);
            tasks.add(new ChunkSaveTask(chunk, data));
        }
        return List.copyOf(tasks);
    }

    private CompletableFuture<Void> submitSave(SaveWork work) {
        List<ChunkSaveTask> chunkTasks = work.chunks();
        List<ChunkData> payloads = chunkTasks.stream().map(ChunkSaveTask::data).toList();

        return CompletableFuture.runAsync(() -> {
            try {
                if (work.worldData() != null) {
                    repository.saveWorld(work.worldData());
                }
                if (work.playerData() != null) {
                    repository.savePlayer(work.playerData());
                }
                if (!payloads.isEmpty()) {
                    repository.saveChunks(payloads);
                }
            } catch (IOException e) {
                throw new RuntimeException("Save batch failed (" + work.reason() + ")", e);
            }
        }, ioExecutor).whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                if (work.worldData() != null) {
                    worldData = work.worldData();
                }
            } else {
                chunkTasks.forEach(task -> task.chunk().getCcoDirtyTracker().markDataDirtyOnly());
                System.err.println("[SAVE] Batch failed (" + work.reason() + "): " + throwable.getMessage());
            }
        });
    }

    private void performAutoSave() {
        if (worldData == null || player == null || world == null) {
            return;
        }
        if (!autoSaveInProgress.compareAndSet(false, true)) {
            return;
        }

        long started = System.currentTimeMillis();
        long sessionMillis = started - lastAutoSaveTime;
        WorldData updatedWorld = worldData.withAddedPlayTime(sessionMillis);
        PlayerData playerData = StateConverter.toPlayerData(player, updatedWorld.getWorldName());
        List<ChunkSaveTask> chunkTasks = collectDirtyChunkTasks();

        submitSave(new SaveWork(updatedWorld, playerData, chunkTasks, "auto"))
            .whenComplete((ignored, throwable) -> {
                autoSaveInProgress.set(false);
                if (throwable == null) {
                    lastAutoSaveTime = started;
                    long duration = System.currentTimeMillis() - started;
                    System.out.printf("[AUTO-SAVE] Completed in %dms (%d chunks)%n", duration, chunkTasks.size());
                    if (duration > 5000) {
                        System.err.printf("[AUTO-SAVE] WARNING: Save took %dms%n", duration);
                    }
                }
            });
    }

    private record ChunkSaveTask(Chunk chunk, ChunkData data) { }

    private record SaveWork(WorldData worldData,
                            PlayerData playerData,
                            List<ChunkSaveTask> chunks,
                            String reason) { }

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

        public boolean isSuccess() {
            return success;
        }

        public String getError() {
            return error;
        }

        public WorldData getWorldData() {
            return worldData;
        }

        public PlayerData getPlayerData() {
            return playerData;
        }
    }
}
