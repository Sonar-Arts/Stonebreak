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
 * Coordinates save/load operations. Disk I/O runs on two small thread pools
 * (SSD-friendly — the old single Save-IO thread serialized every chunk-load
 * probe behind multi-second auto-save batches):
 * <ul>
 *   <li><b>Load pool</b> — chunk loads / existence probes. Never queues behind
 *       saves, so exploration keeps streaming during an auto-save.</li>
 *   <li><b>Save pool</b> — per-chunk save tasks (encode + atomic write are both
 *       parallel-safe: per-chunk files, temp+move, stateless codec) plus the
 *       rare metadata/player writes.</li>
 * </ul>
 * The only ordering that matters — operations on the SAME chunk — is enforced
 * by {@link #pendingChunkSaves}: a save of chunk K chains behind K's previous
 * in-flight save, and a load of K chains behind K's in-flight save (the
 * unload-save → immediate-reload race). Snapshots are still taken on the
 * calling thread to remain consistent with CCO expectations.
 */
public class SaveService implements AutoCloseable {

    private static final int AUTO_SAVE_INTERVAL_SECONDS = 30;
    private static final int SAVE_THREADS = 4;
    private static final int LOAD_THREADS = 4;

    private final String worldPath;
    private final FileSaveRepository repository;
    private final ExecutorService savePool;
    private final ExecutorService loadPool;
    private final ScheduledExecutorService autoSaveScheduler;
    private final AtomicBoolean autoSaveInProgress = new AtomicBoolean(false);

    /** In-flight save future per chunk key ((cx<<32)|cz) — the same-chunk ordering gate. */
    private final java.util.concurrent.ConcurrentHashMap<Long, CompletableFuture<Void>> pendingChunkSaves =
        new java.util.concurrent.ConcurrentHashMap<>();

    private ScheduledFuture<?> autoSaveTask;
    private volatile WorldData worldData;
    private volatile Player player;
    private volatile World world;
    private volatile long lastAutoSaveTime;

    // World-time source for save snapshots. In the two-world model the authoritative clock is
    // the server's TimeOfDay (a render-only client's clock is frozen), so it is injected here.
    // Null = fall back to the Game singleton's clock (the pre-two-world / co-located behavior).
    private volatile com.stonebreak.world.TimeOfDay worldTimeSource;

    public SaveService(String worldPath) {
        this.worldPath = Objects.requireNonNull(worldPath, "worldPath");
        this.repository = new FileSaveRepository(worldPath);
        java.util.concurrent.atomic.AtomicInteger saveId = new java.util.concurrent.atomic.AtomicInteger();
        this.savePool = Executors.newFixedThreadPool(SAVE_THREADS, r -> {
            Thread t = new Thread(r, "Save-IO-" + saveId.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        java.util.concurrent.atomic.AtomicInteger loadId = new java.util.concurrent.atomic.AtomicInteger();
        this.loadPool = Executors.newFixedThreadPool(LOAD_THREADS, r -> {
            Thread t = new Thread(r, "Load-IO-" + loadId.getAndIncrement());
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
        // Bind this service to the world so its chunk store persists through it, instead of
        // the chunk store reaching into the Game singleton's save service.
        if (world != null) {
            world.setSaveService(this);
        }
    }

    /**
     * Inject the authoritative world clock used when stamping the world-time into saves. The
     * server's {@code ServerLevel} sets this so persistence reflects server time rather than a
     * render-only client's frozen clock. Pass {@code null} to revert to the Game-singleton clock.
     */
    public void setWorldTimeSource(com.stonebreak.world.TimeOfDay timeOfDay) {
        this.worldTimeSource = timeOfDay;
    }

    /** Injected clock if present, else the Game singleton's (may be null during early bootstrap). */
    private com.stonebreak.world.TimeOfDay resolveWorldTimeSource() {
        com.stonebreak.world.TimeOfDay injected = worldTimeSource;
        if (injected != null) {
            return injected;
        }
        com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
        return game != null ? game.getTimeOfDay() : null;
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

        // Update world data with current play time and world time
        long sessionTime = now - lastAutoSaveTime;
        WorldData updatedWorld = worldData.withAddedPlayTime(sessionTime);

        // Capture current world time from the authoritative clock (server-owned in the two-world
        // model; falls back to the Game singleton's clock when none is injected).
        com.stonebreak.world.TimeOfDay timeSource = resolveWorldTimeSource();
        if (timeSource != null) {
            updatedWorld = updatedWorld.withWorldTime(timeSource.getTicks());
        }

        this.worldData = updatedWorld;

        // Convert player to data model
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
                PlayerData playerData = playerOpt.orElse(null);
                System.out.printf("[LOAD] World metadata loaded in %dms%n", System.currentTimeMillis() - start);
                return new LoadResult(true, null, data, playerData);
            } catch (IOException e) {
                System.err.printf("[LOAD] Failed in %dms: %s%n", System.currentTimeMillis() - start, e.getMessage());
                return new LoadResult(false, e.getMessage(), null, null);
            }
        }, loadPool);
    }

    /** Completed future when no save of that chunk is in flight; else the save to chain after. */
    private CompletableFuture<Void> saveGate(int chunkX, int chunkZ) {
        CompletableFuture<Void> pending = pendingChunkSaves.get(chunkKey(chunkX, chunkZ));
        return pending == null ? CompletableFuture.completedFuture(null)
            : pending.exceptionally(t -> null);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        return saveGate(chunkX, chunkZ).thenApplyAsync(ignored -> {
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
        }, loadPool);
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
        return saveGate(chunkX, chunkZ)
            .thenApplyAsync(ignored -> repository.chunkExists(chunkX, chunkZ), loadPool);
    }

    /**
     * Persist a remote player's serialized PlayerData blob under players/&lt;username&gt;.json.
     * Opaque to the save service — the bytes come straight from the client. Runs on the IO thread.
     */
    public CompletableFuture<Void> saveNamedPlayer(String username, byte[] json) {
        if (username == null || json == null || json.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                repository.saveNamedPlayerBytes(username, json);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save player '" + username + "'", e);
            }
        }, savePool).exceptionally(t -> {
            System.err.println("[SAVE] Failed to save player '" + username + "': " + t.getMessage());
            return null;
        });
    }

    /** Load a remote player's serialized PlayerData blob, or null if none saved. IO thread. */
    public CompletableFuture<byte[]> loadNamedPlayer(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return repository.loadNamedPlayerBytes(username).orElse(null);
            } catch (IOException e) {
                System.err.println("[LOAD] Failed to load player '" + username + "': " + e.getMessage());
                return null;
            }
        }, loadPool);
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
        savePool.shutdown();
        loadPool.shutdown();
        repository.close();
        System.out.println("[SAVE] SaveService closed");
    }

    private List<ChunkSaveTask> collectDirtyChunkTasks() {
        if (world == null) {
            return List.of();
        }
        // Entities (mobs, drops) live in the EntityManager, not in block data, so a chunk whose
        // only change is the entities standing in it stays block-clean and would be skipped. Mark
        // such chunks dirty first so every save captures current entity state. Cheap (bounded by
        // the mob cap) and server-side (the headless world owns the SaveService + real entities).
        var entityManager = world.getEntityManager();
        if (entityManager != null) {
            entityManager.markChunksWithLiveEntitiesDirty();
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

    /**
     * Fans the batch out as ONE task per chunk across the save pool (parallel
     * encode + write on SSD) instead of a single monolithic batch task — a
     * 2600-chunk auto-save used to hold the sole IO thread for 5+ seconds,
     * starving every chunk-load probe behind it. Same-chunk ordering is kept
     * by chaining each chunk's task behind its previous in-flight save; a
     * failed chunk re-marks only itself dirty for the next save round.
     */
    private CompletableFuture<Void> submitSave(SaveWork work) {
        List<ChunkSaveTask> chunkTasks = work.chunks();
        List<CompletableFuture<Void>> parts = new ArrayList<>(chunkTasks.size() + 1);

        if (work.worldData() != null || work.playerData() != null) {
            parts.add(CompletableFuture.runAsync(() -> {
                try {
                    if (work.worldData() != null) {
                        repository.saveWorld(work.worldData());
                    }
                    if (work.playerData() != null) {
                        repository.savePlayer(work.playerData());
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Metadata save failed (" + work.reason() + ")", e);
                }
            }, savePool).whenComplete((ignored, throwable) -> {
                if (throwable == null && work.worldData() != null) {
                    worldData = work.worldData();
                } else if (throwable != null) {
                    System.err.println("[SAVE] Metadata save failed (" + work.reason() + "): "
                        + throwable.getMessage());
                }
            }));
        }

        for (ChunkSaveTask task : chunkTasks) {
            parts.add(submitChunkSave(task, work.reason()));
        }
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> submitChunkSave(ChunkSaveTask task, String reason) {
        long key = chunkKey(task.data().getChunkX(), task.data().getChunkZ());
        CompletableFuture<Void> run = pendingChunkSaves.compute(key, (k, prev) -> {
            CompletableFuture<Void> gate = prev == null
                ? CompletableFuture.completedFuture(null)
                : prev.exceptionally(t -> null);
            return gate.thenRunAsync(() -> {
                try {
                    repository.saveChunk(task.data());
                } catch (IOException e) {
                    throw new RuntimeException("Chunk save failed (" + reason + ")", e);
                }
            }, savePool);
        });
        run.whenComplete((ignored, throwable) -> {
            pendingChunkSaves.remove(key, run);
            if (throwable != null) {
                task.chunk().getCcoDirtyTracker().markDataDirtyOnly();
                System.err.println("[SAVE] Chunk (" + task.data().getChunkX() + ","
                    + task.data().getChunkZ() + ") save failed (" + reason + "): "
                    + throwable.getMessage());
            }
        });
        return run;
    }

    private void performAutoSave() {
        if (worldData == null || player == null || world == null) {
            return;
        }
        if (!autoSaveInProgress.compareAndSet(false, true)) {
            return;
        }

        long started = System.currentTimeMillis();

        // Update world data with current play time and world time
        long sessionTime = started - lastAutoSaveTime;
        WorldData updatedWorld = worldData.withAddedPlayTime(sessionTime);

        // Capture current world time from the authoritative clock (server-owned in the two-world
        // model; falls back to the Game singleton's clock when none is injected).
        com.stonebreak.world.TimeOfDay timeSource = resolveWorldTimeSource();
        if (timeSource != null) {
            updatedWorld = updatedWorld.withWorldTime(timeSource.getTicks());
        }

        this.worldData = updatedWorld;

        // Convert player to data model
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
