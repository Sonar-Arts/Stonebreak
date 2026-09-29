package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.TerrainScale;
import com.stonebreak.world.generation.diffusion.TGMPipeException;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TileRequestCancelledException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The game's terrain model, {@value #MODEL_NAME}: one Python child process
 * ({@code python -m terrain_slm.tgmpipe}, Models/DaedalusTGM-Exp/) spoken to over its stdin/stdout
 * with {@link TGMPipeProtocol} frames. No ports, no HTTP, no second process.
 *
 * <ul>
 *   <li><b>Any seed, per request.</b> The service keys its caches by seed, so the world and the
 *       terrain mapper share one loaded model and a seed change costs nothing.</li>
 *   <li><b>Pushed, prioritised, cancellable.</b> Tiles arrive the moment they finish; world
 *       tiles go before preview tiles; a closed tile cache withdraws what it no longer wants.</li>
 *   <li><b>Never outlives the game.</b> The service exits when its stdin closes — including when
 *       the JVM is killed — so there are no leaked processes or ports to reclaim.</li>
 *   <li><b>Heals.</b> If the service dies while running, it is restarted and every tile still in
 *       flight is re-sent ({@value #MAX_RESTARTS} restarts per {@value #RESTART_WINDOW_MINUTES}
 *       minutes; past that, requests fail loudly). A service that stops answering while tiles are
 *       pending for {@code stallTimeoutMs} is treated as dead.</li>
 * </ul>
 *
 * <p>Lifecycle is explicit: {@link #ensureRunning} starts it (world load, terrain mapper), and
 * {@link #shutdown} stops it. Requests made while it is stopped fail fast.
 */
public final class TGMPipe {

    private static final Logger LOG = Logger.getLogger(TGMPipe.class.getName());
    private static final TGMPipe INSTANCE = new TGMPipe();

    /** Official name of the terrain generation model every world uses. */
    public static final String MODEL_NAME = "DaedalusTGM-Exp";
    /**
     * Tile priorities (lower goes first): chunks being generated, then FastLOD's distant terrain,
     * then the terrain mapper.
     */
    public static final int PRIORITY_WORLD = 0;
    public static final int PRIORITY_LOD = 1;
    public static final int PRIORITY_PREVIEW = 2;

    static final String MODELS_DIR = "Models";
    static final String SLM_DIR = MODELS_DIR + "/" + MODEL_NAME;
    static final String MODULE = "terrain_slm.tgmpipe";
    /** Default model directory, relative to {@link #SLM_DIR}; its name is the model version. */
    static final String DEFAULT_MODEL = "checkpoints/v4";
    static final int MAX_RESTARTS = 3;
    static final int RESTART_WINDOW_MINUTES = 10;

    /** What generates the terrain, for the UI: {@code "DaedalusTGM-Exp · v3"}. */
    public static String generatorLabel() {
        String model = System.getProperty("stonebreak.tgmpipe.model", DEFAULT_MODEL);
        return MODEL_NAME + " · " + Path.of(model).getFileName();
    }

    public static TGMPipe getInstance() {
        return INSTANCE;
    }

    private final List<String> command;
    private final Path workingDir;
    private final Path logFile;
    private final long startupTimeoutMs;
    private final long stallTimeoutMs;

    private final Object lock = new Object();
    private final Deque<Long> crashes = new ArrayDeque<>();
    private Process process;
    private TGMPipeConnection connection;
    /** True between {@link #ensureRunning} and {@link #shutdown}: a death in between is a crash. */
    private boolean wanted;
    /** The running service's tile-cache namespace, from its handshake; see {@link #cacheNamespace}. */
    private volatile String cacheNamespace;

    private TGMPipe() {
        Path root = repoRoot(Path.of(System.getProperty("user.dir")).toAbsolutePath());
        this.workingDir = resolve(root, "stonebreak.tgmpipe.repoDir", SLM_DIR);
        this.command = List.of(
                resolve(root, "stonebreak.tgmpipe.pythonExe", SLM_DIR + "/.venv/bin/python").toString(),
                "-m", MODULE,
                System.getProperty("stonebreak.tgmpipe.model", DEFAULT_MODEL),
                "--device", System.getProperty("stonebreak.tgmpipe.device", "cuda"),
                // In-memory generation cache (GPU) and finished-tile cache on disk.
                "--cache-size", System.getProperty("stonebreak.tgmpipe.cacheSize", "4G"),
                "--disk-cache", System.getProperty("stonebreak.tgmpipe.diskCache", "8G"));
        this.logFile = resolve(root, "stonebreak.tgmpipe.logDir", MODELS_DIR + "/logs")
                .resolve("tgmpipe.log");
        this.startupTimeoutMs = Long.getLong("stonebreak.tgmpipe.startupTimeoutMs", 180_000L);
        this.stallTimeoutMs = Long.getLong("stonebreak.tgmpipe.stallTimeoutMs", 600_000L);

        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tgmpipe-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleWithFixedDelay(this::checkStall, 15, 15, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "tgmpipe-shutdown"));
    }

    /**
     * The checkout root: the working directory or the nearest parent holding {@link #SLM_DIR}, so
     * the game finds its model whether it runs from the repo root (the IDE) or a module directory
     * (Maven's tests).
     */
    static Path repoRoot(Path start) {
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(SLM_DIR))) {
                return dir;
            }
        }
        return start;
    }

    private static Path resolve(Path root, String property, String defaultRelative) {
        Path path = Path.of(System.getProperty(property, defaultRelative));
        return path.isAbsolute() ? path : root.resolve(path);
    }

    public void ensureRunning() {
        ensureRunning(fraction -> { });
    }

    /**
     * Starts the service if it is not running, blocking until it has loaded the model and
     * answered the handshake. {@code progress} hears 0 when a start begins and 1 when it is ready;
     * it is not called at all when the service is already up.
     *
     * @throws TGMPipeException if the service fails to start or become ready in time
     */
    public void ensureRunning(DoubleConsumer progress) {
        synchronized (lock) {
            wanted = true;
            crashes.clear();
            if (isUpLocked()) {
                return;
            }
            progress.accept(0.0);
            startOrGiveUpLocked();
            progress.accept(1.0);
        }
    }

    /** Stops the service if it is running. Safe to call more than once; a JVM shutdown hook. */
    public void shutdown() {
        TGMPipeConnection c;
        Process p;
        synchronized (lock) {
            wanted = false;
            c = connection;
            p = process;
            connection = null;
            process = null;
        }
        if (c != null) {
            c.close(); // stdin EOF: the service exits on its own
        }
        if (p != null) {
            LOG.info(() -> "Stopping TGMPipe (pid " + p.pid() + ")");
            stop(p);
        }
    }

    /**
     * A tile, pushed when it is ready. Completes exceptionally with {@link TGMPipeException}
     * when the service is stopped, cannot be (re)started, or fails the tile.
     */
    public CompletableFuture<TerrainTile> requestTile(long seed, int tileX, int tileZ, int lod, int priority) {
        var request = new TGMPipeConnection.TileRequest(seed, tileX, tileZ, lod, priority, new CompletableFuture<>());
        submit(request);
        return request.future();
    }

    /**
     * Fingerprint of everything that decides a tile's content — world config, model checkpoint and
     * generator source — as the service reported it at its last handshake; null before the first
     * one. Anything the game caches from tiles (FastLOD's node store) keys on it, so a retrained
     * model or edited pipeline is never shown from a stale cache.
     */
    public String cacheNamespace() {
        return cacheNamespace;
    }

    static String cacheNamespaceOf(String readyJson) {
        try {
            JsonNode ns = new ObjectMapper().readTree(readyJson).get("cache_namespace");
            return ns != null && ns.isTextual() ? ns.asText() : null;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "unreadable TGMPipe handshake: " + readyJson, e);
            return null;
        }
    }

    /** The service's status json (model, queue depth, cache, timings), for diagnostics. */
    public CompletableFuture<String> status() {
        TGMPipeConnection c;
        synchronized (lock) {
            c = connection;
        }
        return c == null
                ? CompletableFuture.failedFuture(new TGMPipeException("TGMPipe is not running"))
                : c.status();
    }

    private void submit(TGMPipeConnection.TileRequest request) {
        for (int attempt = 0; attempt < 2 && !request.future().isDone(); attempt++) {
            TGMPipeConnection c;
            try {
                synchronized (lock) {
                    if (!wanted) {
                        throw new TGMPipeException("TGMPipe is not running");
                    }
                    if (!isUpLocked()) {
                        startOrGiveUpLocked(); // after a crash: the restart thread may not have got here yet
                    }
                    c = connection;
                }
                c.send(request);
                return;
            } catch (TGMPipeException e) {
                if (attempt == 1 || !wanted) {
                    request.future().completeExceptionally(e);
                }
            }
        }
    }

    private boolean isUpLocked() {
        return connection != null && !connection.isClosed() && process != null && process.isAlive();
    }

    /** A service that cannot start stays down: queued requests fail fast instead of each retrying. */
    private void startOrGiveUpLocked() {
        try {
            startLocked();
        } catch (TGMPipeException e) {
            wanted = false;
            throw e;
        }
    }

    private void startLocked() {
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            throw new TGMPipeException("could not create TGMPipe log directory " + logFile.getParent(), e);
        }
        LOG.info(() -> "Starting TGMPipe (" + MODEL_NAME + "): " + command);
        Process p;
        try {
            p = new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .start();
        } catch (IOException e) {
            throw new TGMPipeException("failed to launch " + command + " in " + workingDir, e);
        }
        TGMPipeConnection c = new TGMPipeConnection(p.getInputStream(), p.getOutputStream(), this::closed);
        c.open(TerrainScale.worldConfigJson());
        try {
            String info = c.ready().get(startupTimeoutMs, TimeUnit.MILLISECONDS);
            cacheNamespace = cacheNamespaceOf(info);
            LOG.info(() -> "TGMPipe ready, serving " + MODEL_NAME + " (pid " + p.pid() + "): " + info);
        } catch (TimeoutException e) {
            c.close();
            stop(p);
            throw new TGMPipeException("TGMPipe did not become ready within "
                    + startupTimeoutMs + "ms; see " + logFile);
        } catch (ExecutionException e) {
            stop(p);
            throw new TGMPipeException("TGMPipe failed to start ("
                    + e.getCause().getMessage() + "); see " + logFile, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            c.close();
            stop(p);
            throw new TGMPipeException("interrupted while starting TGMPipe", e);
        }
        process = p;
        connection = c;
    }

    /** Reader thread of a finished connection: tells a crash from a shutdown and acts on it. */
    private void closed(TGMPipeConnection c, String reason, List<TGMPipeConnection.TileRequest> unfinished) {
        boolean resubmit;
        synchronized (lock) {
            boolean current = c == connection;
            Process dead = current ? process : null;
            if (current) {
                connection = null;
                process = null;
            }
            if (c.closedByClient() || !wanted) {
                // Stopped on purpose (or a start that failed): nobody will answer these.
                cancel(unfinished, "TGMPipe stopped");
                return;
            }
            resubmit = true;
            if (current) {
                long now = System.nanoTime();
                crashes.addLast(now);
                while (now - crashes.peekFirst() > TimeUnit.MINUTES.toNanos(RESTART_WINDOW_MINUTES)) {
                    crashes.removeFirst();
                }
                resubmit = crashes.size() <= MAX_RESTARTS;
                String exit = dead != null && !dead.isAlive() ? " (exit code " + dead.exitValue() + ")" : "";
                LOG.log(Level.WARNING, "TGMPipe ({0}) died{1}: {2}; {3} tile(s) in flight; {4}. See {5}",
                        new Object[] {MODEL_NAME, exit, reason, unfinished.size(),
                                resubmit ? "restarting" : "not restarting (" + crashes.size() + " crashes in "
                                        + RESTART_WINDOW_MINUTES + " min)", logFile});
                if (!resubmit) {
                    wanted = false;
                    if (dead != null) {
                        dead.destroyForcibly();
                    }
                }
            }
            // Not current: a request already restarted the service before this report ran; its
            // in-flight tiles still just need re-sending.
        }
        if (!resubmit) {
            TGMPipeException failure = new TGMPipeException("TGMPipe died ("
                    + reason + ") and is not being restarted after " + MAX_RESTARTS + " crashes in "
                    + RESTART_WINDOW_MINUTES + " minutes; see " + logFile);
            unfinished.forEach(r -> r.future().completeExceptionally(failure));
            return;
        }
        Thread restarter = new Thread(() -> unfinished.forEach(this::submit), "tgmpipe-restart");
        restarter.setDaemon(true);
        restarter.start();
    }

    /** A service with tiles pending that has sent nothing for {@code stallTimeoutMs} is hung: kill it. */
    private void checkStall() {
        Process p;
        synchronized (lock) {
            TGMPipeConnection c = connection;
            if (c == null || c.pendingCount() == 0
                    || System.nanoTime() - c.lastActivityNanos() < TimeUnit.MILLISECONDS.toNanos(stallTimeoutMs)) {
                return;
            }
            p = process;
        }
        if (p != null) {
            LOG.warning(() -> "TGMPipe sent nothing for " + stallTimeoutMs
                    + "ms with tiles pending; killing it (pid " + p.pid() + ") to restart");
            p.destroyForcibly(); // the reader sees the stream end: the crash path restarts it
        }
    }

    private static void cancel(List<TGMPipeConnection.TileRequest> requests, String why) {
        TileRequestCancelledException cancelled = new TileRequestCancelledException(why);
        requests.forEach(r -> r.future().completeExceptionally(cancelled));
    }

    private static void stop(Process process) {
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
