package com.stonebreak.world.save;

import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.repository.FileSaveRepository;
import com.stonebreak.world.save.util.StateConverter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates a world's saves (cubic voxel runtime, #447). What is saved where:
 * <ul>
 *   <li><b>Cubes and columns</b> - by the world's native stream into its cube store ({@code <world>/cubes}):
 *       a cube is written when it unloads and at every save point; nothing here encodes blocks. A save point
 *       is two steps: {@link World#flushCubeBlobs()} on the world's OWNER thread (furnace/workbench registries
 *       and the {@code SaveHook}s - entities, saplings - into each cube's game blob; no I/O), then
 *       {@link World#commitCubes()} on the save pool (block-state table, every changed cube + column record,
 *       {@code cv_store_flush} = durable).</li>
 *   <li><b>metadata.json / player.json / players/*.json</b> - JSON records written here (atomic + fsync).</li>
 * </ul>
 * Threads: the owner hand-offs are executors the caller binds - {@link #setWorldOwner} (the integrated server's
 * {@code executeOnTick}) for the world, {@link #bindPlayerSource} for the player record. Without a world owner
 * an autosave/pause save only commits what the world already handed to the store (blobs are still written at
 * unload and on close); the close flush runs both steps inline (the owner has stopped by then).
 * Close ordering: {@link #close()} (final save point, waits for any autosave) must run BEFORE
 * {@link World#cleanup()} (stream close = last save + store close) and while the entity manager is alive.
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

    /**
     * In-flight write per remote player name, for {@code players/<name>.json}: a write chains behind that
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
    /**
     * Runs work on the thread that owns {@link #world}'s game state (the server tick thread). Null = not bound:
     * save points skip the blob flush (see the class comment) except the close flush.
     */
    private volatile Executor worldOwner;
    /** Logged once per service when a save point had no world owner. */
    private final AtomicBoolean warnedNoWorldOwner = new AtomicBoolean();
    /** Serializes cube commits so an autosave and a pause/manual save never write the store concurrently. */
    private CompletableFuture<Void> cubeCommitChain = CompletableFuture.completedFuture(null);
    /** Wall clock the play time is measured on (milliseconds); a test seam. */
    private final java.util.function.LongSupplier clock;
    /** Clock reading up to which play time is already in {@link #worldData}; guarded by {@code this}. */
    private long lastAutoSaveTime;

    // World-time source for save snapshots. In the two-world model the authoritative clock is
    // the server's TimeOfDay (a render-only client's clock is frozen), so it is injected here.
    // Null = fall back to the Game singleton's clock (the pre-two-world / co-located behavior).
    private volatile com.stonebreak.world.TimeOfDay worldTimeSource;
    private volatile com.stonebreak.world.weather.WeatherState weatherSource;

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
        if (world != null) {
            world.setSaveService(this);
        }
    }

    /**
     * Binds the thread that owns the world's game state: {@code owner} runs a task on it (the integrated
     * server's {@code executeOnTick}, which runs inline on the tick thread itself and once the loop stopped).
     * Save points flush the cubes' game blobs there ({@link World#flushCubeBlobs()}); never from the AutoSave
     * thread. {@code Runnable::run} for a world driven on the calling thread (tests, tools).
     */
    public void setWorldOwner(Executor owner) {
        this.worldOwner = owner;
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
            updatedWorld = updatedWorld.withWorldTime(timeSource.getTotalTicks());
        }
        com.stonebreak.world.weather.WeatherState weather = weatherSource;
        if (weather != null) {
            updatedWorld = updatedWorld.withWeather((long) weather.seconds(), weather.mode(), weather.zones());
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

    /** Inject the server's weather state, stamped into saves like the world clock. */
    public void setWeatherSource(com.stonebreak.world.weather.WeatherState weather) {
        this.weatherSource = weather;
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
            future = saveCubes(snapshotInline);
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
     * A full save point: world metadata, the cubes (blob flush on the world owner, then the store commit) and -
     * when one is bound - the player, snapshotted on its owner thread (an owner that has stopped running tasks
     * runs it inline, so a close after the server thread exits still saves the host). No bound player:
     * everything else is still saved. The future completes when everything is durable.
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

        CompletableFuture<Boolean> blobs = flushBlobsAsync(snapshotInline);

        return playerData.thenCombine(blobs, (pd, flushed) -> pd)
            .thenCompose(pd -> submitSave(new SaveWork(updatedWorld, pd, true, "manual/full")))
            .whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    System.out.printf("[SAVE] Complete save finished in %dms%n", clock.getAsLong() - now);
                }
            });
    }

    /** A save point of the cubes only (no metadata / player). */
    public CompletableFuture<Void> saveCubes() {
        return saveCubes(false);
    }

    private CompletableFuture<Void> saveCubes(boolean inline) {
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        return flushBlobsAsync(inline).thenCompose(ignored -> submitSave(new SaveWork(null, null, true, "cubes")));
    }

    /**
     * Step 1 of a save point: the cubes' game blobs, on the world owner ({@code inline}: on this thread - the
     * close flush). Completes with whether the flush ran; never exceptionally (a failed flush still lets the
     * commit save what the store already has).
     */
    private CompletableFuture<Boolean> flushBlobsAsync(boolean inline) {
        World w = world;
        if (w == null || w.cubeStore() == null) {
            return CompletableFuture.completedFuture(false);
        }
        Executor owner = inline ? Runnable::run : worldOwner;
        if (owner == null) {
            warnNoWorldOwner();
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        try {
            owner.execute(() -> {
                try {
                    w.flushCubeBlobs();
                    done.complete(true);
                } catch (RuntimeException e) {
                    System.err.println("[SAVE] Cube blob flush failed: " + e);
                    done.complete(false);
                }
            });
        } catch (RuntimeException e) {
            System.err.println("[SAVE] Could not reach the world's thread for the blob flush: " + e);
            done.complete(false);
        }
        return done;
    }

    private void warnNoWorldOwner() {
        if (warnedNoWorldOwner.compareAndSet(false, true)) {
            System.err.println("[SAVE] No world owner bound (SaveService.setWorldOwner): save points commit the cube"
                + " store without refreshing cube blobs (entities/furnaces are still saved at unload and on close)");
        }
    }

    /**
     * Loads the world metadata and player record. {@code metadata.json} is guarded like every
     * other save file: a load that cannot be trusted never reads as a new world, whose first
     * save would overwrite the real one.
     * <ul>
     *   <li>missing, nothing else saved: {@link LoadResult#isWorldMissing()}, a new world;</li>
     *   <li>unreadable: set aside to {@code metadata.json.corrupt[.N]} and rebuilt from
     *       {@code world.json} (seed, generator: the terrain is unchanged), with a
     *       {@link LoadResult#getWorldNotice() notice};</li>
     *   <li>missing although the world has saved data (e.g. set aside by an earlier load): rebuilt
     *       the same way;</li>
     *   <li>not rebuildable, or not readable at all (an I/O error: the file may be fine): a failed
     *       load, file untouched. The caller must not open the world.</li>
     * </ul>
     */
    public CompletableFuture<LoadResult> loadWorld() {
        long start = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> {
            try {
                var worldOpt = repository.loadWorld();
                if (worldOpt.isPresent()) {
                    System.out.printf("[LOAD] World metadata loaded in %dms%n", System.currentTimeMillis() - start);
                    LoadResult refused = refuseIfIncompatible(worldOpt.get());
                    return refused != null ? refused : loadPlayerInto(worldOpt.get(), null);
                }
                if (!repository.hasSavedWorldContent()) {
                    return LoadResult.worldMissing();
                }
                return rebuildWorldData("metadata.json is missing but the world has saved data", null);
            } catch (com.stonebreak.world.save.repository.UnreadableSaveFileException e) {
                System.err.println("[LOAD] " + e.getMessage());
                java.nio.file.Path aside;
                try {
                    aside = repository.setWorldFileAside();
                } catch (IOException moveFailure) {
                    // Opening the world anyway would overwrite the damaged file on the next save.
                    return LoadResult.failed("World metadata is unreadable and could not be set aside ("
                        + moveFailure.getMessage() + "); the world was not opened.");
                }
                return rebuildWorldData(e.getMessage(), aside);
            } catch (IOException | RuntimeException e) {
                System.err.printf("[LOAD] Failed in %dms: %s%n", System.currentTimeMillis() - start, e.getMessage());
                return LoadResult.failed("World metadata could not be read (" + e.getMessage()
                    + "); the world was not opened.");
            }
        }, loadPool);
    }

    /**
     * Rebuilds the metadata from {@code world.json}. What only {@code metadata.json} held (play
     * time, world time, weather, world settings, the chosen spawn) starts over; the terrain does
     * not change. {@code aside} is where the unreadable file went, or null when it was missing.
     */
    private LoadResult rebuildWorldData(String why, java.nio.file.Path aside) {
        String kept = aside == null ? "" : " (the damaged file was kept as " + aside.getFileName() + ")";
        java.util.Optional<WorldData> record;
        try {
            record = repository.loadWorldCreationRecord();
        } catch (IOException | RuntimeException e) {
            return LoadResult.failed("World metadata is lost" + kept + " and world.json cannot replace it ("
                + e.getMessage() + "); the world was not opened.");
        }
        if (record.isEmpty()) {
            return LoadResult.failed("World metadata is lost" + kept
                + " and there is no world.json to rebuild it from; the world was not opened.");
        }
        LoadResult refused = refuseIfIncompatible(record.get());
        if (refused != null) {
            return refused;
        }
        String notice = "This world's metadata could not be read" + kept + "; it was rebuilt from world.json."
            + " The terrain is unchanged, but play time, world time, weather and world settings were reset.";
        System.err.println("[LOAD] " + why + ": " + notice);
        return loadPlayerInto(record.get(), notice);
    }

    /**
     * Null when this build can open the world; else the refusal. An incompatible world (another save format,
     * other bounds) is never opened, rewritten or deleted - no migration (pre-alpha rule).
     */
    private LoadResult refuseIfIncompatible(WorldData data) {
        WorldFormat.Check check = WorldFormat.check(data, repository.worldRoot());
        if (check.compatible()) {
            return null;
        }
        System.err.println("[LOAD] " + check.reason());
        return LoadResult.incompatible(check.reason());
    }

    /**
     * Loads {@code player.json} apart from the world metadata, so a damaged player file can never
     * make the world look new (issue #358). An unreadable file is moved aside and the player
     * starts fresh in the loaded world; the result's {@link LoadResult#getPlayerNotice} says so.
     */
    private LoadResult loadPlayerInto(WorldData data, String worldNotice) {
        try {
            return new LoadResult(true, null, data, repository.loadPlayer().orElse(null), null, worldNotice);
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
            return new LoadResult(true, null, data, null, notice, worldNotice);
        }
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

    /**
     * Step 2 of a save point, on the save pool: the JSON records and the cube commit
     * ({@link World#commitCubes()}: block states + every changed cube/column record + store flush) run in
     * parallel; cube commits are chained so two save points never commit at once. Completes exceptionally
     * when a part failed (logged here too).
     */
    private CompletableFuture<Void> submitSave(SaveWork work) {
        List<CompletableFuture<Void>> parts = new ArrayList<>(2);

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

        World w = world;
        if (work.cubes() && w != null && w.cubeStore() != null) {
            parts.add(chainCubeCommit(w, work.reason()));
        }
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new));
    }

    private synchronized CompletableFuture<Void> chainCubeCommit(World w, String reason) {
        CompletableFuture<Void> next = cubeCommitChain.exceptionally(t -> null)
            .thenRunAsync(w::commitCubes, savePool)
            .whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    System.err.println("[SAVE] Cube save failed (" + reason + "): " + throwable.getMessage());
                }
            });
        cubeCommitChain = next;
        return next;
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
        boolean blobsFlushed;
        CompletableFuture<Void> write;
        try {
            // A missing snapshot (no bound player, owner thread busy, close cancelled it) skips
            // only player.json: cubes and world time still save. It used to skip the whole
            // autosave — every chunk of a host world went unsaved while the player was unbound.
            PlayerData playerData = player == null ? null : snapshotPlayerOnOwner(worldData.getWorldName());
            if (playerData == null && closed.get()) {
                autoSaveInProgress.set(false);
                inFlight.complete(null);
                return;
            }
            blobsFlushed = awaitBlobFlush();
            WorldData updatedWorld = advanceWorldDataForSave(started);
            write = submitSave(new SaveWork(updatedWorld, playerData, true, "auto"));
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
                System.out.printf("[AUTO-SAVE] Completed in %dms (cube blobs %s)%n", duration,
                    blobsFlushed ? "refreshed" : "not refreshed");
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

    /**
     * Step 1 for the AutoSave thread: waits (bounded) for the blob flush on the world owner. False when there is
     * no owner, the owner did not get to it in time, or close() started - the commit then saves what the store
     * already has, and the next save point (or the close flush) refreshes the blobs.
     */
    private boolean awaitBlobFlush() {
        CompletableFuture<Boolean> flush = flushBlobsAsync(false);
        try {
            return flush.get(PLAYER_SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("[AUTO-SAVE] Cube blobs not refreshed this round: the world's thread did not run the"
                + " flush within " + PLAYER_SNAPSHOT_TIMEOUT_SECONDS + "s");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
            return false;
        }
    }

    /** Test view of the autosave currently in flight (null when none). Package-private. */
    CompletableFuture<Void> inFlightAutoSaveForTesting() {
        return inFlightAutoSave;
    }

    private record SaveWork(WorldData worldData,
                            PlayerData playerData,
                            boolean cubes,
                            String reason) { }

    public static class LoadResult {
        static final String WORLD_NOT_FOUND = "World metadata not found";
        private boolean incompatible;

        private final boolean success;
        private final String error;
        private final WorldData worldData;
        private final PlayerData playerData;
        private final String playerNotice;
        private final String worldNotice;

        public LoadResult(boolean success, String error, WorldData worldData, PlayerData playerData) {
            this(success, error, worldData, playerData, null, null);
        }

        public LoadResult(boolean success, String error, WorldData worldData, PlayerData playerData,
                          String playerNotice) {
            this(success, error, worldData, playerData, playerNotice, null);
        }

        public LoadResult(boolean success, String error, WorldData worldData, PlayerData playerData,
                          String playerNotice, String worldNotice) {
            this.success = success;
            this.error = error;
            this.worldData = worldData;
            this.playerData = playerData;
            this.playerNotice = playerNotice;
            this.worldNotice = worldNotice;
        }

        static LoadResult worldMissing() {
            return new LoadResult(false, WORLD_NOT_FOUND, null, null);
        }

        static LoadResult failed(String error) {
            return new LoadResult(false, error, null, null);
        }

        static LoadResult incompatible(String reason) {
            LoadResult r = new LoadResult(false, reason, null, null);
            r.incompatible = true;
            return r;
        }

        /**
         * True when the world exists but was saved in a format this build cannot open
         * ({@link WorldFormat}); {@link #getError()} says why, starting with {@link WorldFormat#INCOMPATIBLE}.
         */
        public boolean isIncompatible() {
            return incompatible;
        }

        public boolean isSuccess() {
            return success;
        }

        /**
         * True only when the world has never been saved: the one failed load that may be booted
         * as a new world. Any other failure means the world exists but could not be opened.
         */
        public boolean isWorldMissing() {
            return !success && WORLD_NOT_FOUND.equals(error);
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

        /** Message for the player when the world metadata had to be rebuilt, else null. */
        public String getWorldNotice() {
            return worldNotice;
        }
    }
}
