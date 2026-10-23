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
    private static final int PLAYER_SNAPSHOT_TIMEOUT_SECONDS = 10;

    private final String worldPath;
    private final FileSaveRepository repository;
    private final ExecutorService savePool;
    private final ExecutorService loadPool;
    private final ScheduledExecutorService autoSaveScheduler;
    private final AtomicBoolean autoSaveInProgress = new AtomicBoolean(false);

    /** In-flight save future per chunk key ((cx<<32)|cz) — the same-chunk ordering gate. */
    private final java.util.concurrent.ConcurrentHashMap<Long, CompletableFuture<Void>> pendingChunkSaves =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * In-flight write per remote player name — the same ordering gate as
     * {@link #pendingChunkSaves}, for {@code players/<name>.json}: a write chains behind that
     * player's previous one (the save pool has several threads), and a load or set-aside of
     * the name waits for it. Without it a quick re-login read the pre-quit file while the
     * quit save was still in flight — items dropped just before quitting came back (#334).
     */
    private final java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<Void>> pendingNamedSaves =
        new java.util.concurrent.ConcurrentHashMap<>();

    private ScheduledFuture<?> autoSaveTask;
    /** Once true, close() has started: no further autosave may snapshot or submit. */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** The autosave currently past its in-progress gate, so close() can join it before the final flush. */
    private volatile CompletableFuture<Void> inFlightAutoSave;
    private volatile WorldData worldData;
    /**
     * Builds the player record this service persists as {@code player.json}. For an integrated
     * server's host it reads the server's own {@code ServerPlayer} — the authoritative record,
     * as Minecraft's integrated server writes the host's level.dat {@code Player} tag from the
     * server-side player, never from the client's predicted copy.
     */
    @FunctionalInterface
    public interface PlayerSource {
        PlayerData snapshot(String worldName);
    }

    private volatile PlayerSource player;
    /**
     * Runs work on the thread that owns {@link #player} (the server tick thread for a host's
     * server record; the client main thread for a bound client {@link Player}). Saves take the
     * player snapshot there so they never read state that thread is mutating (#356). Direct by
     * default (tests, headless binds).
     */
    private volatile java.util.concurrent.Executor playerOwner = Runnable::run;
    /** The autosave's player snapshot still waiting for the owner thread; close() cancels it. */
    private volatile CompletableFuture<PlayerData> pendingPlayerSnapshot;
    private volatile World world;
    /** Wall clock the play time is measured on (milliseconds); a test seam. */
    private final java.util.function.LongSupplier clock;
    /** Clock reading up to which play time is already in {@link #worldData}; guarded by {@code this}. */
    private long lastAutoSaveTime;

    // World-time source for save snapshots. In the two-world model the authoritative clock is
    // the server's TimeOfDay (a render-only client's clock is frozen), so it is injected here.
    // Null = fall back to the Game singleton's clock (the pre-two-world / co-located behavior).
    private volatile com.stonebreak.world.TimeOfDay worldTimeSource;

    public SaveService(String worldPath) {
        this(worldPath,
            Executors.newFixedThreadPool(SAVE_THREADS, namedDaemonFactory("Save-IO")),
            Executors.newFixedThreadPool(LOAD_THREADS, namedDaemonFactory("Load-IO")));
    }

    /** Test seam: inject the save/load pools so tests can stall or observe IO ordering. */
    SaveService(String worldPath, ExecutorService savePool, ExecutorService loadPool) {
        this(worldPath, savePool, loadPool, System::currentTimeMillis);
    }

    /** Test seam: also inject the play-time clock so tests never depend on wall-clock sleeps. */
    SaveService(String worldPath, ExecutorService savePool, ExecutorService loadPool,
                java.util.function.LongSupplier clock) {
        this.worldPath = Objects.requireNonNull(worldPath, "worldPath");
        this.repository = new FileSaveRepository(worldPath);
        this.savePool = savePool;
        this.loadPool = loadPool;
        this.autoSaveScheduler = Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("AutoSave"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastAutoSaveTime = clock.getAsLong();

        try {
            repository.ensureWorldDirectory();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create world directory: " + worldPath, e);
        }
    }

    private static java.util.concurrent.ThreadFactory namedDaemonFactory(String prefix) {
        java.util.concurrent.atomic.AtomicInteger id = new java.util.concurrent.atomic.AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + id.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    public void initialize(WorldData worldData, Player player, World world) {
        this.worldData = worldData;
        this.player = sourceOf(player);
        this.world = world;
        // Bind this service to the world so its chunk store persists through it, instead of
        // the chunk store reaching into the Game singleton's save service.
        if (world != null) {
            world.setSaveService(this);
        }
    }

    /**
     * Rebinds the player this service persists (e.g. when the local player registers with an
     * integrated server) without touching {@link WorldData} — {@link #initialize} must never
     * run again once autosaves have begun, because it would replace the accumulated play time
     * and runtime edits with the caller's (stale) copy. No-op before the service is initialized.
     */
    public void bindPlayer(Player player) {
        bindPlayer(player, Runnable::run);
    }

    /**
     * {@link #bindPlayer(Player)} for a player that is mutated on one thread: {@code owner} runs
     * a task on that thread (e.g. {@code Game::runOnMainThread}), and the autosave snapshots the
     * player through it instead of reading it from the AutoSave thread (#356).
     */
    public void bindPlayer(Player player, java.util.concurrent.Executor owner) {
        bindPlayerSource(sourceOf(player), owner);
    }

    /**
     * Binds the record {@code player.json} is written from, snapshotted on {@code owner}'s
     * thread. An integrated server binds its host's {@code ServerPlayer} here with its tick
     * executor. No-op before the service is initialized.
     */
    public void bindPlayerSource(PlayerSource source, java.util.concurrent.Executor owner) {
        if (worldData != null) {
            this.playerOwner = Objects.requireNonNull(owner, "owner");
            this.player = source;
        }
    }

    private static PlayerSource sourceOf(Player player) {
        return player == null ? null : worldName -> StateConverter.toPlayerData(player, worldName);
    }

    /**
     * Atomically rewrites the in-memory {@link WorldData} the next save persists — the way
     * runtime world settings (e.g. the per-world cheats flag) reach disk. Never replace it
     * via {@link #initialize}: that would discard the play time accumulated since load.
     * No-op before the service is initialized.
     */
    public synchronized void updateWorldData(java.util.function.UnaryOperator<WorldData> update) {
        WorldData current = worldData;
        if (current != null) {
            worldData = Objects.requireNonNull(update.apply(current), "updated WorldData");
        }
    }

    /**
     * Stamps the accumulated play time and the authoritative world time into the in-memory
     * {@link WorldData} and returns the snapshot to persist. Synchronized with
     * {@link #updateWorldData} so a concurrent edit can't be lost between read and write.
     * The interval is marked counted here, in the same step that adds it: the in-memory
     * {@code WorldData} owns the play time, so an overlapping save or one taken right after a
     * failed write never adds the same interval twice, and a failed write loses nothing.
     */
    private synchronized WorldData advanceWorldDataForSave(long now) {
        WorldData updatedWorld = worldData.withAddedPlayTime(now - lastAutoSaveTime);
        lastAutoSaveTime = now;

        // Capture current world time from the authoritative clock (server-owned in the two-world
        // model; falls back to the Game singleton's clock when none is injected).
        com.stonebreak.world.TimeOfDay timeSource = resolveWorldTimeSource();
        if (timeSource != null) {
            updatedWorld = updatedWorld.withWorldTime(timeSource.getTicks());
        }

        this.worldData = updatedWorld;
        return updatedWorld;
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
        flushSavesBlocking(reason, false);
    }

    /**
     * @param snapshotInline take the player snapshot on this thread — the close flush: by then
     *                       the owner thread has stopped (server teardown) or is this one (it
     *                       could not run the snapshot while blocked here)
     */
    private void flushSavesBlocking(String reason, boolean snapshotInline) {
        CompletableFuture<Void> future;
        if (worldData != null && world != null) {
            future = saveAll(snapshotInline);
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

    /**
     * Saves world metadata, every dirty chunk and — when one is bound — the player, snapshotted
     * on its owner thread (an owner that has stopped running tasks runs it inline, so a close
     * after the server thread exits still saves the host). No bound player: everything else
     * is still saved.
     */
    public CompletableFuture<Void> saveAll() {
        return saveAll(false);
    }

    private CompletableFuture<Void> saveAll(boolean snapshotInline) {
        if (worldData == null || world == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Save service not initialized")
            );
        }

        long now = clock.getAsLong();
        WorldData updatedWorld = advanceWorldDataForSave(now);
        PlayerSource source = player;
        CompletableFuture<PlayerData> playerData = source == null
            ? CompletableFuture.completedFuture(null)
            : CompletableFuture.supplyAsync(() -> source.snapshot(updatedWorld.getWorldName()),
                    snapshotInline ? Runnable::run : playerOwner);

        return playerData.thenCompose(pd -> submitSave(
                new SaveWork(updatedWorld, pd, collectDirtyChunkTasks(), "manual/full")))
            .whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    System.out.printf("[SAVE] Complete save finished in %dms%n", clock.getAsLong() - now);
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
                System.out.printf("[LOAD] World metadata loaded in %dms%n", System.currentTimeMillis() - start);
                return loadPlayerInto(data);
            } catch (IOException e) {
                System.err.printf("[LOAD] Failed in %dms: %s%n", System.currentTimeMillis() - start, e.getMessage());
                return new LoadResult(false, e.getMessage(), null, null);
            }
        }, loadPool);
    }

    /**
     * Loads {@code player.json} apart from the world metadata, so a damaged player file can never
     * make the world look new (issue #358). An unreadable file is moved aside and the player
     * starts fresh in the loaded world; the result's {@link LoadResult#getPlayerNotice} says so.
     */
    private LoadResult loadPlayerInto(WorldData data) {
        try {
            return new LoadResult(true, null, data, repository.loadPlayer().orElse(null));
        } catch (IOException | RuntimeException e) {
            System.err.println("[LOAD] player.json is unreadable: " + e.getMessage());
            String notice;
            try {
                java.nio.file.Path aside = repository.setPlayerFileAside();
                notice = "Your saved player data could not be read; it was kept as "
                    + aside.getFileName() + " and you start fresh.";
            } catch (IOException moveFailure) {
                notice = "Your saved player data could not be read or set aside ("
                    + moveFailure.getMessage() + "); the next save will replace it.";
            }
            System.err.println("[LOAD] " + notice);
            return new LoadResult(true, null, data, null, notice);
        }
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
     * Opaque to the save service — the bytes are the server-built record. Runs on the IO
     * thread, ordered behind this player's previous write ({@link #pendingNamedSaves}).
     */
    public CompletableFuture<Void> saveNamedPlayer(String username, byte[] json) {
        if (username == null || json == null || json.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return chainNamed(username, () -> {
            try {
                repository.saveNamedPlayerBytes(username, json);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save player '" + username + "'", e);
            }
        }).exceptionally(t -> {
            System.err.println("[SAVE] Failed to save player '" + username + "': " + t.getMessage());
            return null;
        });
    }

    /**
     * Load a remote player's serialized PlayerData blob, or null if none is saved. Waits for
     * any in-flight write of that player first. An I/O failure completes the future
     * EXCEPTIONALLY — it is not "no saved data": treating it as a new player would let the
     * next save overwrite the real file (#358's rule, applied to remote players; #334).
     */
    public CompletableFuture<byte[]> loadNamedPlayer(String username) {
        return namedGate(username).thenApplyAsync(ignored -> {
            try {
                return repository.loadNamedPlayerBytes(username).orElse(null);
            } catch (IOException e) {
                System.err.println("[LOAD] Failed to load player '" + username + "': " + e.getMessage());
                throw new java.io.UncheckedIOException(e);
            }
        }, loadPool);
    }

    /**
     * Moves an unreadable {@code players/<username>.json} aside (to {@code .corrupt[.N]}) so
     * the player's next save starts a fresh file instead of overwriting it (#358's rule for
     * remote players; #334). Ordered behind that player's in-flight writes; completes with the
     * new path, or exceptionally when the file could not be moved.
     */
    public CompletableFuture<java.nio.file.Path> setNamedPlayerAside(String username) {
        CompletableFuture<java.nio.file.Path> result = new CompletableFuture<>();
        chainNamed(username, () -> {
            try {
                result.complete(repository.setNamedPlayerFileAside(username));
            } catch (IOException | RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /** Completed when no write of {@code username} is in flight; else that write (errors swallowed). */
    private CompletableFuture<Void> namedGate(String username) {
        CompletableFuture<Void> pending = username == null ? null : pendingNamedSaves.get(username);
        return pending == null ? CompletableFuture.completedFuture(null)
            : pending.exceptionally(t -> null);
    }

    /** Runs {@code work} on the save pool after {@code username}'s previous write. */
    private CompletableFuture<Void> chainNamed(String username, Runnable work) {
        CompletableFuture<Void> next = pendingNamedSaves.compute(username, (k, prev) ->
            (prev == null ? CompletableFuture.<Void>completedFuture(null) : prev.exceptionally(t -> null))
                .thenRunAsync(work, savePool));
        next.whenComplete((v, t) -> pendingNamedSaves.remove(username, next));
        return next;
    }

    public String getWorldPath() {
        return worldPath;
    }

    public WorldData getWorldData() {
        return worldData;
    }

    @Override
    public void close() {
        // First, and atomically: any autosave not yet past its in-progress gate bails instead of
        // submitting, and a second (or concurrent) close() returns without a second teardown.
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopAutoSave();
        // An autosave waiting for the owner thread's snapshot would wait out its timeout if
        // close() runs on that very thread (it cannot drain its task queue while in here).
        CompletableFuture<PlayerData> snapshot = pendingPlayerSnapshot;
        if (snapshot != null) {
            snapshot.cancel(false);
        }
        awaitInFlightAutoSave();
        flushSavesBlocking("service close", true);
        autoSaveScheduler.shutdown();
        shutdownAndAwait(savePool, "save");
        shutdownAndAwait(loadPool, "load");
        repository.close();
        System.out.println("[SAVE] SaveService closed");
    }

    /**
     * Drains an autosave that is already executing ({@code cancel(false)} cannot stop it) before
     * the final flush, so its older snapshot can never land after — and overwrite — the flush.
     */
    private void awaitInFlightAutoSave() {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (autoSaveInProgress.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        CompletableFuture<Void> inFlight = inFlightAutoSave;
        if (inFlight != null) {
            try {
                inFlight.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("[SAVE] Waiting for the in-flight auto-save failed: " + e.getMessage());
            }
        }
    }

    private void shutdownAndAwait(ExecutorService pool, String name) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                System.err.println("[SAVE] " + name + " pool did not terminate within 30s; continuing close");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[SAVE] Interrupted while awaiting " + name + " pool termination");
        }
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
                // No write-back of work.worldData() on success: the snapshot was already
                // stored by advanceWorldDataForSave, and writing it back here would clobber
                // any updateWorldData edit (e.g. a /cheats toggle) made while the IO ran.
                if (throwable != null) {
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

    void performAutoSave() {
        if (closed.get() || worldData == null || world == null) {
            return;
        }
        if (!autoSaveInProgress.compareAndSet(false, true)) {
            return;
        }
        if (closed.get()) {
            // close() started between the entry check and the CAS: bail before any snapshot
            // or submit, or this older autosave would land after the final flush.
            autoSaveInProgress.set(false);
            return;
        }

        // Published before the snapshot, so a close() that gives up waiting on the in-progress
        // flag still joins this autosave rather than a previous, already completed one.
        CompletableFuture<Void> inFlight = new CompletableFuture<>();
        inFlightAutoSave = inFlight;
        long started = clock.getAsLong();
        int chunkCount;
        CompletableFuture<Void> write;
        try {
            // A missing snapshot (no bound player, owner thread busy, close cancelled it) skips
            // only player.json: chunks and world time still save. It used to skip the whole
            // autosave — every chunk of a host world went unsaved while the player was unbound.
            PlayerData playerData = player == null ? null : snapshotPlayerOnOwner(worldData.getWorldName());
            if (playerData == null && closed.get()) {
                autoSaveInProgress.set(false);
                inFlight.complete(null);
                return;
            }
            WorldData updatedWorld = advanceWorldDataForSave(started);
            List<ChunkSaveTask> chunkTasks = collectDirtyChunkTasks();
            chunkCount = chunkTasks.size();
            write = submitSave(new SaveWork(updatedWorld, playerData, chunkTasks, "auto"));
        } catch (RuntimeException e) {
            // Never leave the gate set: every later autosave would skip, and close() would wait
            // out its full deadline. Not rethrown, so the fixed-rate schedule keeps running.
            autoSaveInProgress.set(false);
            inFlight.completeExceptionally(e);
            System.err.println("[AUTO-SAVE] Failed before writing: " + e);
            return;
        }

        write.whenComplete((ignored, throwable) -> {
            autoSaveInProgress.set(false);
            if (throwable == null) {
                inFlight.complete(null);
                long duration = clock.getAsLong() - started;
                System.out.printf("[AUTO-SAVE] Completed in %dms (%d chunks)%n", duration, chunkCount);
                if (duration > 5000) {
                    System.err.printf("[AUTO-SAVE] WARNING: Save took %dms%n", duration);
                }
            } else {
                inFlight.completeExceptionally(throwable);
            }
        });
    }

    /**
     * Converts the bound player on its owner thread and waits for the result here (the AutoSave
     * thread). Null when the snapshot was cancelled by {@link #close} or the owner thread did not
     * get to it in time — this autosave then saves everything but the player, and the next one
     * (or the close flush) saves the player.
     */
    private PlayerData snapshotPlayerOnOwner(String worldName) {
        PlayerSource bound = player;
        CompletableFuture<PlayerData> snapshot = CompletableFuture.supplyAsync(
            () -> bound.snapshot(worldName), playerOwner);
        pendingPlayerSnapshot = snapshot;
        try {
            if (closed.get()) {
                snapshot.cancel(false);
            }
            return snapshot.get(PLAYER_SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.CancellationException e) {
            return null;
        } catch (java.util.concurrent.TimeoutException e) {
            snapshot.cancel(false);
            System.err.println("[AUTO-SAVE] Player not saved this round: its thread did not snapshot it within "
                + PLAYER_SNAPSHOT_TIMEOUT_SECONDS + "s");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            snapshot.cancel(false);
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException("Player snapshot failed", e.getCause());
        } finally {
            pendingPlayerSnapshot = null;
        }
    }

    /** Test view of the autosave currently in flight (null when none). Package-private. */
    CompletableFuture<Void> inFlightAutoSaveForTesting() {
        return inFlightAutoSave;
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
        private final String playerNotice;

        public LoadResult(boolean success, String error, WorldData worldData, PlayerData playerData) {
            this(success, error, worldData, playerData, null);
        }

        public LoadResult(boolean success, String error, WorldData worldData, PlayerData playerData,
                          String playerNotice) {
            this.success = success;
            this.error = error;
            this.worldData = worldData;
            this.playerData = playerData;
            this.playerNotice = playerNotice;
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

        /** Message for the player when their saved data could not be restored, else null. */
        public String getPlayerNotice() {
            return playerNotice;
        }
    }
}
