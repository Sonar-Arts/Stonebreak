package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.ClimateLayout;
import com.stonebreak.world.generation.HeightCurveVersion;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.diffusion.TerrainScale;
import com.stonebreak.world.generation.diffusion.TGMPipeException;
import com.stonebreak.world.generation.diffusion.TerrainTile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The game's terrain model, {@value #MODEL_NAME}: the native pipeline in-process ({@link NativeTerrainService} over
 * {@code libstonebreak_terrain} + the Cenda inference runtime, reading the model's release export
 * {@code <model>/native}). The game never runs Python or PyTorch: they are for training and for writing the release
 * export only. The old Python child process ({@code terrain_slm.tgmpipe} over stdin/stdout) was removed 2026-11-20;
 * the name TGMPipe stays as this facade's.
 *
 * <ul>
 *   <li><b>Any seed, per request.</b> The pipeline keys its caches by seed, so the world and the
 *       terrain mapper share one loaded model and a seed change costs nothing.</li>
 *   <li><b>Pushed, prioritised, cancellable.</b> Tiles arrive the moment they finish; world
 *       tiles go before preview tiles; a closed tile cache withdraws what it no longer wants.</li>
 * </ul>
 *
 * <p>Lifecycle is explicit: {@link #ensureRunning} starts it (world load, terrain mapper), and
 * {@link #shutdown} stops it. Requests made while it is stopped fail fast. A pipeline that cannot start (no native
 * library, no CUDA compiler, no release export) fails with the reason: there is no fallback.
 */
public final class TGMPipe {

    private static final Logger LOG = Logger.getLogger(TGMPipe.class.getName());
    private static final TGMPipe INSTANCE = new TGMPipe();

    /** Official name of the terrain generation model every world uses. */
    public static final String MODEL_NAME = "DaedalusTGM-Exp";
    /**
     * Tile priorities (lower goes first; one unsigned byte on the wire): chunks being generated, then FastLOD's terrain
     * nearest first ({@link #lodPriority}: {@code PRIORITY_LOD} .. {@link #LOD_PRIORITY_MAX}), then the weather's
     * relief, then the terrain mapper. A tile already queued is moved up when a more urgent request lands on it
     * ({@code DiffusionTileCache}).
     */
    public static final int PRIORITY_WORLD = 0;
    public static final int PRIORITY_LOD = 1;
    /** Least urgent LOD priority: FastLOD nodes this far ({@value #LOD_PRIORITY_BLOCKS} blocks per step) or further. */
    public static final int LOD_PRIORITY_MAX = 199;
    /** Blocks of distance from the player per LOD priority step (FX2, 2026-11-19). */
    public static final int LOD_PRIORITY_BLOCKS = 64;
    /** Coarse relief for the clouds ({@code TerrainGenerator.sampleReliefHeights}): after every LOD tile. */
    public static final int PRIORITY_RELIEF = 200;
    /** The terrain mapper's previews (was 2, i.e. before most LOD tiles once those got distance priorities). */
    public static final int PRIORITY_PREVIEW = 255;

    /** When a tile more urgent than the relief was last asked for (see {@link #awaitQuiet}). */
    private static volatile long lastUrgentRequestNanos = System.nanoTime();

    /**
     * Blocks the calling (background) thread until no tile more urgent than {@link #PRIORITY_RELIEF} has been asked for
     * during {@code quietMs}, or {@code maxWaitMs} passed. The service builds one tile at a time and never preempts:
     * chunk and FastLOD work arrives a request at a time from threads that wait for each answer, so background work
     * queued meanwhile slips into every gap and each near tile then waits behind a background tile (measured in-game,
     * FX2). The weather relief waits for a quiet service instead.
     */
    public static void awaitQuiet(long quietMs, long maxWaitMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        long quiet = TimeUnit.MILLISECONDS.toNanos(quietMs);
        try {
            while (System.nanoTime() - lastUrgentRequestNanos < quiet && System.nanoTime() < deadline) {
                Thread.sleep(Math.max(10L, Math.min(250L, quietMs / 4)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Priority of a FastLOD tile {@code distanceBlocks} from the player (Chebyshev, blocks): nearest first, all after
     * the chunks being generated. In the real game ~90 % of the tiles were for the far rings and went FIFO at
     * priority 0 in sweep order, ahead of the near ones (review R4, F5).
     */
    public static int lodPriority(long distanceBlocks) {
        long step = Math.max(0L, distanceBlocks) / LOD_PRIORITY_BLOCKS;
        return (int) Math.min(LOD_PRIORITY_MAX, PRIORITY_LOD + step);
    }

    static final String MODELS_DIR = "Models";
    static final String SLM_DIR = MODELS_DIR + "/" + MODEL_NAME;
    /**
     * Default model directory, relative to {@link #SLM_DIR}; its name is the model version. DaedalusTGM-Exp-2's
     * release folder: real files built by {@code scripts/make_release.py} (licence gate, inference-only weights,
     * {@code release.json} manifest) from a model folder of training symlinks ({@code checkpoints/exp2/world_exp2f}; exp2-r3 = 2026-11-19 release:
     * MeanFlow relief, FP8 detail, SR-15 v2, surface + vegetation nodes).
     * The game reads only its native export ({@code native/manifest.json} + {@code *.cim}, written by
     * {@code python -m terrain_slm.export.native_release} when the release is made); the export and the
     * {@code *.pt} weights are stored with Git LFS: a checkout without {@code git lfs pull} has pointer
     * files instead, and the game refuses to start the model with a message saying so
     * ({@link TerrainModels#installProblem}). There is no fallback model (EXP-1's v4 was removed 2026-11-19).
     * {@code -Dstonebreak.tgmpipe.model=<dir>} picks another model folder for new worlds (a candidate such as
     * {@code checkpoints/exp2/world_exp2f}).
     */
    static final String DEFAULT_MODEL = "checkpoints/exp2-r3";

    /**
     * The model folder the service loads, relative to {@link #SLM_DIR} (or absolute): the current world's model
     * ({@link #useWorldModel}: the one a saved world was made with, review-python HIGH-1) once a world chose one, else
     * {@link TerrainModels#defaultModelPath()} ({@code -Dstonebreak.tgmpipe.model} when set, else {@link #DEFAULT_MODEL}).
     * Every backend reads the model through here.
     */
    static String modelPath() {
        TGMPipe pipe = INSTANCE;   // null only while the instance itself is being built
        String world = pipe != null ? pipe.worldModel : null;
        return world != null ? world : TerrainModels.defaultModelPath();
    }

    /** What generates the terrain, for the UI: {@code "DaedalusTGM-Exp · v3"}. */
    public static String generatorLabel() {
        return MODEL_NAME + " · " + Path.of(modelPath()).getFileName();
    }

    public static TGMPipe getInstance() {
        return INSTANCE;
    }

    /** The world's model ({@link #useWorldModel}), null = {@link TerrainModels#defaultModelPath()}. */
    private volatile String worldModel;
    /** The model the running pipeline was started with (null while it is down). */
    private String runningModel;
    private final Path logFile;

    private final Object lock = new Object();
    /** The in-process pipeline while it runs. */
    private NativeTerrainService nativeService;
    /** When the last stopped pipeline is really freed: a new one never opens beside one that is still closing. */
    private CompletableFuture<Void> lastRelease = CompletableFuture.completedFuture(null);
    /** How long a start waits for the previous pipeline to be freed. */
    static final long RELEASE_WAIT_SECONDS = Long.getLong("stonebreak.terrain.releaseWaitSeconds", 60L);
    /** True between {@link #ensureRunning} and {@link #shutdown}. */
    private boolean wanted;
    /** True after {@link #shutdown} until the next {@link #ensureRunning}: stopped on purpose (quit, world switch). */
    private volatile boolean shutDown;
    /** The running pipeline's tile-cache namespaces, one per handshake world ({@code TerrainScale.worldIndex}). */
    private volatile List<String> cacheNamespaces = List.of();
    /** When {@link #prestart} started the running service, while nobody has claimed it; 0 = not prestart-only. */
    private volatile long prestartedAtNanos;
    /** Seconds a prestarted service may wait for a world before it is stopped. */
    private static final long PRESTART_IDLE_SECONDS = Long.getLong("stonebreak.tgmpipe.prestartIdleSeconds", 300L);

    private TGMPipe() {
        this.logFile = logDir().resolve("tgmpipe.log");

        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tgmpipe-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleWithFixedDelay(this::checkPrestartIdle, 15, 15, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "tgmpipe-shutdown"));
    }

    /**
     * The checkout root: the working directory or the nearest parent holding {@link #SLM_DIR}, so
     * the game finds its model whether it runs from the repo root (the IDE) or a module directory
     * (Maven's tests).
     */
    static Path repoRoot(Path start) {
        return TerrainModels.repoRoot(start);
    }

    private static Path root() {
        return repoRoot(Path.of(System.getProperty("user.dir")).toAbsolutePath());
    }

    /** {@code Models/} in this checkout. */
    static Path modelsDir() {
        return root().resolve(MODELS_DIR);
    }

    /** The model's project folder, holding the model folders ({@code -Dstonebreak.tgmpipe.repoDir}). */
    static Path slmDir() {
        return TerrainModels.slmDir();
    }

    static Path logDir() {
        return resolve(root(), "stonebreak.tgmpipe.logDir", MODELS_DIR + "/logs");
    }

    /** Downloaded tools ({@link NvrtcTool}; ignored by git). */
    static Path toolsDir() {
        return modelsDir().resolve("tools");
    }

    /** Compiled native kernels (NVRTC cubins, content-addressed), shared by the setup's warm-up and the pipeline
     *  (ignored by git). */
    static Path kernelCacheDir() {
        return modelsDir().resolve("kernel_cache");
    }

    private static Path resolve(Path root, String property, String defaultRelative) {
        Path path = Path.of(System.getProperty(property, defaultRelative));
        return path.isAbsolute() ? path : root.resolve(path);
    }

    public void ensureRunning() {
        ensureRunning(fraction -> { });
    }

    /**
     * Starts the pipeline if it is not running, blocking until it has loaded the model and
     * registered the handshake's worlds. {@code progress} hears 0 when a start begins and 1 when it is ready;
     * it is not called at all when the pipeline is already up.
     *
     * @throws TGMPipeException if this machine is below {@link DaedalusRequirements}, or the service fails
     *                          to start or become ready in time
     */
    public void ensureRunning(DoubleConsumer progress) {
        ensureRunning(progress, true);
    }

    /** {@code claim}: a real user (world, terrain mapper); false = {@link #prestart}, which a claim turns into a use. */
    private void ensureRunning(DoubleConsumer progress, boolean claim) {
        // Below the requirements the model is disabled outright (it would land on the experimental CPU kernels).
        DaedalusRequirements.requireMet();
        // A launch with something new gets the CUDA compiler and compiles the kernels (ModelSetup, started with the
        // game): wait for it here, outside the lock, rather than racing it for the GPU.
        ModelSetup.getInstance().awaitUsable(progress);
        synchronized (lock) {
            if (claim) {
                prestartedAtNanos = 0L;
            } else if (wanted && isUpLocked()) {
                return;     // somebody else's pipeline: never mark it unused
            }
            wanted = true;
            shutDown = false;
            if (isUpLocked()) {
                return;
            }
            progress.accept(0.0);
            startOrGiveUpLocked();
            if (!claim) {
                prestartedAtNanos = System.nanoTime();
            }
            progress.accept(1.0);
        }
    }

    /**
     * Starts the pipeline in the background ahead of a world that will probably need it (the world list with a
     * Daedalus world selected), so entering the world does not wait for its start (7.5-11 s warm, 17-25 s after a
     * kernel change; review R4). No-op when it already runs or is starting, below {@link DaedalusRequirements}, or with
     * {@code -Dstonebreak.tgmpipe.prestart=off}. Until a world or the terrain mapper claims it
     * ({@link #ensureRunning}), it is stopped by {@link #releasePrestart} or after
     * {@code stonebreak.tgmpipe.prestartIdleSeconds} (default 300) unclaimed. It holds its full GPU and pinned host
     * memory meanwhile (the native pipeline has no idle release).
     */
    public void prestart() {
        prestart(null);
    }

    /**
     * {@link #prestart()} with the model the world will run ({@code ref}, as {@link #useWorldModel} will pick it; null =
     * the current choice). A prestarted service of another model nobody claimed is replaced; a claimed one is left alone.
     * Never blocks the caller (the UI thread): wishes go to one worker thread and only the latest one is acted on.
     */
    public void prestart(com.stonebreak.world.generation.TerrainModelRef ref) {
        if (!"off".equalsIgnoreCase(System.getProperty("stonebreak.tgmpipe.prestart"))) {
            wishPrestart(ref != null ? ref : NO_MODEL_WISH);
        }
    }

    /**
     * Stops a service that only {@link #prestart} started and nobody claimed (the player left the world list, or
     * selected a world that does not use the model). Never blocks the caller; a no-op for a claimed service.
     */
    public void releasePrestart() {
        wishPrestart(RELEASE_WISH);
    }

    /** Prestart wishes: a TerrainModelRef, {@link #NO_MODEL_WISH} (the current model) or {@link #RELEASE_WISH}. */
    private static final Object NO_MODEL_WISH = new Object();
    private static final Object RELEASE_WISH = new Object();
    private final java.util.concurrent.atomic.AtomicReference<Object> prestartWish =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.ExecutorService prestartWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tgmpipe-prestart");
        t.setDaemon(true);
        return t;
    });

    private void wishPrestart(Object wish) {
        if (prestartWish.getAndSet(wish) == null) {
            prestartWorker.execute(this::actOnPrestartWish);
        }
    }

    /** Worker thread: the latest wish only (selection changes in a row collapse into one start or stop). */
    private void actOnPrestartWish() {
        Object wish = prestartWish.getAndSet(null);
        if (wish == null) {
            return;
        }
        if (wish == RELEASE_WISH) {
            shutdown(true);
            return;
        }
        try {
            long t0 = System.nanoTime();
            String model = wish instanceof com.stonebreak.world.generation.TerrainModelRef ref
                    ? TerrainModels.resolve(ref).path() : null;
            boolean replace;
            synchronized (lock) {
                if (wanted && isUpLocked() && prestartedAtNanos == 0L) {
                    return;           // claimed by a world or the terrain mapper: never touch it
                }
                replace = model != null && !model.equals(modelPath());
                if (replace) {
                    worldModel = model;   // what useWorldModel will choose for this world anyway
                }
                if (!replace && wanted && isUpLocked()) {
                    prestartedAtNanos = System.nanoTime();   // still on the list: the idle clock starts again
                    return;
                }
            }
            if (replace) {
                shutdown(true);       // an unclaimed prestart of another world's model
            }
            ensureRunning(fraction -> { }, false);
            LOG.info(() -> "TGMPipe prestarted in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        } catch (RuntimeException e) {
            LOG.log(Level.INFO, "TGMPipe prestart failed (the world start will try again): " + e.getMessage());
        }
    }

    /** A prestarted service nobody claimed within the idle time is stopped (watchdog). */
    private void checkPrestartIdle() {
        long at = prestartedAtNanos;
        if (at != 0L && System.nanoTime() - at > TimeUnit.SECONDS.toNanos(PRESTART_IDLE_SECONDS)) {
            LOG.info("TGMPipe was prestarted but no world used it; stopping it");
            shutdown(true);
        }
    }

    /**
     * True once {@link #shutdown} stopped the pipeline on purpose (until the next {@link #ensureRunning}): tile
     * requests then fail by design, so the terrain queries that fail with them are refused quietly
     * ({@code TerrainClosedException}), not reported as terrain failures. A failed start is not a shutdown.
     */
    public boolean isShutDown() {
        return shutDown;
    }

    /** Stops the service if it is running. Safe to call more than once; a JVM shutdown hook. */
    public void shutdown() {
        shutdown(false);
    }

    /** {@code onlyIfPrestart}: stop it only while it is a prestarted service nobody claimed ({@link #releasePrestart}). */
    private void shutdown(boolean onlyIfPrestart) {
        NativeTerrainService n;
        synchronized (lock) {
            if (onlyIfPrestart && prestartedAtNanos == 0L) {
                return;
            }
            prestartedAtNanos = 0L;
            wanted = false;
            shutDown = true;
            n = nativeService;
            nativeService = null;
            runningModel = null;
            if (n != null) {
                lastRelease = n.released();   // before close(): a start racing this shutdown waits for it
            }
        }
        if (n != null) {
            LOG.info("Stopping the native terrain pipeline");
            n.close();
        }
    }

    /**
     * A tile, pushed when it is ready. Completes exceptionally with {@link TGMPipeException}
     * when the service is stopped, cannot be (re)started, or fails the tile.
     */
    public CompletableFuture<TerrainTile> requestTile(long seed, int tileX, int tileZ, int lod, int priority) {
        return requestTile(seed, tileX, tileZ, lod, priority, TerrainScalePreset.ONE_TO_FOUR);
    }

    /** {@link #requestTile(long, int, int, int, int)} for a belts world at {@code scale}. */
    public CompletableFuture<TerrainTile> requestTile(long seed, int tileX, int tileZ, int lod, int priority,
                                                     TerrainScalePreset scale) {
        return requestTile(seed, tileX, tileZ, lod, priority, scale, ClimateLayout.DEFAULT);
    }

    /**
     * {@link #requestTile(long, int, int, int, int)} for a world at {@code scale} (blocks, curve, cache namespace)
     * with the climate {@code layout}: names the handshake world {@link TerrainScale#worldIndex}.
     */
    public CompletableFuture<TerrainTile> requestTile(long seed, int tileX, int tileZ, int lod, int priority,
                                                     TerrainScalePreset scale, ClimateLayout layout) {
        return requestTile(seed, tileX, tileZ, lod, priority, scale, layout, null);
    }

    /**
     * {@link #requestTile(long, int, int, int, int, TerrainScalePreset, ClimateLayout)} for a world created with the
     * height curve {@code curve} (null = {@link HeightCurveVersion#CURRENT}; a saved world passes its own).
     */
    public CompletableFuture<TerrainTile> requestTile(long seed, int tileX, int tileZ, int lod, int priority,
                                                     TerrainScalePreset scale, ClimateLayout layout,
                                                     HeightCurveVersion curve) {
        if (priority < PRIORITY_RELIEF) {
            lastUrgentRequestNanos = System.nanoTime();
        }
        var request = new TileRequest(seed, tileX, tileZ, lod, priority,
                TerrainScale.worldIndex(layout, scale, curve), new CompletableFuture<>());
        submit(request);
        return request.future();
    }

    /**
     * Fingerprint of everything that decides a tile's content — world config, model checkpoint and
     * generator source — as the pipeline reported it when it started; null before the first
     * start. Anything the game caches from tiles (FastLOD's node store) keys on it, so a retrained
     * model or edited pipeline is never shown from a stale cache.
     */
    public String cacheNamespace() {
        return cacheNamespace(TerrainScalePreset.ONE_TO_FOUR);
    }

    /** {@link #cacheNamespace()} of the tiles built at {@code scale} for a belts world. */
    public String cacheNamespace(TerrainScalePreset scale) {
        return cacheNamespace(scale, ClimateLayout.DEFAULT);
    }

    /**
     * {@link #cacheNamespace()} of the tiles built at {@code scale} with the climate {@code layout}: every
     * (layout, scale) world is cached apart (the service puts the layout in the world's fingerprint).
     */
    public String cacheNamespace(TerrainScalePreset scale, ClimateLayout layout) {
        return cacheNamespace(scale, layout, null);
    }

    /** {@link #cacheNamespace(TerrainScalePreset, ClimateLayout)} of a world created with the height curve {@code curve}. */
    public String cacheNamespace(TerrainScalePreset scale, ClimateLayout layout, HeightCurveVersion curve) {
        List<String> all = cacheNamespaces;
        int i = TerrainScale.worldIndex(layout, scale, curve);
        return i < all.size() ? all.get(i) : null;
    }

    /** The pipeline's status json (model, queue depth, cache, timings), for diagnostics. */
    public CompletableFuture<String> status() {
        NativeTerrainService n;
        synchronized (lock) {
            n = nativeService;
        }
        return n == null
                ? CompletableFuture.failedFuture(new TGMPipeException("the terrain pipeline is not running"))
                : CompletableFuture.completedFuture(n.statusJson());
    }

    private void submit(TileRequest request) {
        NativeTerrainService n;
        try {
            synchronized (lock) {
                if (!wanted) {
                    throw new TGMPipeException("the terrain pipeline is not running");
                }
                if (!isUpLocked()) {
                    startOrGiveUpLocked();
                }
                n = nativeService;
            }
            n.submit(request);
        } catch (TGMPipeException e) {
            request.future().completeExceptionally(e);
        }
    }

    private boolean isUpLocked() {
        return nativeService != null;
    }

    /** A pipeline that cannot start stays down: queued requests fail fast instead of each retrying. */
    private void startOrGiveUpLocked() {
        try {
            startLocked();
        } catch (TGMPipeException e) {
            wanted = false;
            throw e;
        }
    }

    /** Waits (bounded) for the previous pipeline to be freed: two at once would double the GPU and host memory. */
    private void awaitPreviousRelease() {
        CompletableFuture<Void> previous = lastRelease;
        if (previous.isDone()) {
            return;
        }
        LOG.info("waiting for the previous terrain pipeline to be freed before starting a new one");
        try {
            previous.get(RELEASE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new TGMPipeException("the previous terrain pipeline is still shutting down (a tile build has not"
                    + " finished in " + RELEASE_WAIT_SECONDS + " s); not opening a second one - try again shortly");
        } catch (java.util.concurrent.ExecutionException e) {
            LOG.log(Level.WARNING, "previous terrain pipeline release failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TGMPipeException("interrupted while the previous terrain pipeline was shutting down", e);
        }
    }

    private void startLocked() {
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            throw new TGMPipeException("could not create the terrain log directory " + logFile.getParent(), e);
        }
        String model = modelPath();   // the world's model (useWorldModel) or the default
        // Missing release folder / Git LFS pointers instead of the export: say so (and how to fetch it) instead of
        // handing the engine a pointer file or falling back to another model.
        TerrainModels.requireInstalled(model);
        awaitPreviousRelease();
        NativeTerrainService n = NativeTerrainLauncher.start();
        nativeService = n;
        runningModel = model;
        cacheNamespaces = n.namespaces();
        LOG.info(() -> "terrain: " + MODEL_NAME + " in-process (native), model " + n.modelId());
    }

    /**
     * Runs the terrain model a world was created with (review-python HIGH-1): the server calls this when it loads a
     * DaedalusTGM-Exp world, a remote client with the model the server's welcome names (its FastLOD fallback must
     * match). When the world's model is installed it is used; otherwise the default model is, and the returned
     * {@link TerrainModels.Choice#notice()} says so (the caller shows it in chat). A service already running another
     * model is stopped; the world's next {@link #ensureRunning} starts the chosen one (tiles in flight are cancelled:
     * call this before the world asks for tiles).
     */
    public TerrainModels.Choice useWorldModel(com.stonebreak.world.generation.TerrainModelRef ref) {
        TerrainModels.Choice choice = TerrainModels.resolve(ref);
        if (choice.notice() != null) {
            LOG.warning(choice.notice());
        } else {
            LOG.info(() -> "terrain: world model " + choice.path());
        }
        select(choice.path());
        return choice;
    }

    /** Back to the default model (the terrain mapper's previews, which become new worlds): {@link #useWorldModel}'s twin. */
    public void useDefaultModel() {
        select(null);
    }

    private void select(String model) {
        boolean restart;
        synchronized (lock) {
            worldModel = model;
            String wantedModel = modelPath();
            restart = isUpLocked() && runningModel != null && !runningModel.equals(wantedModel);
            if (restart) {
                LOG.info("terrain model changes from " + runningModel + " to " + wantedModel + ": restarting the service");
            }
        }
        if (restart) {
            shutdown();   // the next ensureRunning starts the chosen model
        }
    }
}
