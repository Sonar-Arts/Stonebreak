package com.stonebreak.world.generation.diffusion.process;

import com.stonebreak.world.generation.diffusion.TerrainBridgeException;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Launches and supervises the two local Python processes {@code TerrainGenerationSystem}
 * depends on for a "no fallback" terrain source (plan.md Phase 2): upstream's
 * {@code terrain_diffusion.inference.minecraft_api} model server, and {@code terrain-bridge}'s
 * FastAPI adapter in front of it. Without this, a player would have to hand-start both in
 * separate terminals before launching Stonebreak (see terrain-bridge/README.md and
 * Dev Working/terrain-diffusion-spike/ for how that was done manually during Phase 0/1).
 *
 * <p>Both processes are seed-pinned at startup — upstream via {@code --seed}, the bridge via
 * {@code TERRAIN_BRIDGE_SEED} — and neither can be re-seeded on a live instance
 * (terrain-bridge/bridge/upstream_client.py). So {@link #ensureRunningForSeed(long)} restarts
 * both from scratch whenever the requested seed differs from whatever is currently pinned,
 * rather than trying to reseed in place.
 *
 * <p>Startup reclaims {@code upstreamPort} and {@code bridgePort} before spawning anything, because
 * the shutdown hook below is skipped whenever the JVM dies hard and the two children outlive it. A
 * leaked service holding the port is not merely in the way: it answers the health probe for the
 * child that just died on {@code EADDRINUSE}, so startup reports success, the next
 * {@code ensureRunningForSeed} finds a dead child and tears both services down again, and the map
 * meanwhile renders the leaked instance's seed instead of the requested one.
 */
public final class TerrainServiceProcessManager {

    private static final Logger LOG = Logger.getLogger(TerrainServiceProcessManager.class.getName());
    private static final TerrainServiceProcessManager INSTANCE = new TerrainServiceProcessManager();

    /** How long a reclaimed port has to actually come free before we give up on it. */
    private static final long PORT_RECLAIM_TIMEOUT_MS = 10_000L;
    /** Pulls {@code "seed": 123} out of terrain-bridge's /health body without dragging in a JSON parser. */
    private static final Pattern HEALTH_SEED = Pattern.compile("\"seed\"\\s*:\\s*(-?\\d+)");

    public static TerrainServiceProcessManager getInstance() {
        return INSTANCE;
    }

    private final boolean autostart;
    private final Path upstreamPythonExe;
    private final Path upstreamRepoDir;
    private final String model;
    private final String device;
    private final String upstreamCacheSize;
    private final int upstreamPort;
    private final Path bridgePythonExe;
    private final Path bridgeDir;
    private final int bridgePort;
    private final Path logDir;
    private final long startupTimeoutMs;

    private final Object lock = new Object();
    private Process upstreamProcess;
    private Process bridgeProcess;
    private Long pinnedSeed;

    private TerrainServiceProcessManager() {
        Path userDir = Path.of(System.getProperty("user.dir"));

        this.autostart = Boolean.parseBoolean(System.getProperty("stonebreak.terrainService.autostart", "true"));
        this.upstreamPythonExe = resolvePath(userDir, "stonebreak.terrainService.pythonExe",
                "Dev Working/terrain-diffusion-spike/venv/bin/python");
        this.upstreamRepoDir = resolvePath(userDir, "stonebreak.terrainService.repoDir",
                "Dev Working/terrain-diffusion-spike/repo");
        this.model = System.getProperty("stonebreak.terrainService.model", "xandergos/terrain-diffusion-30m");
        this.device = System.getProperty("stonebreak.terrainService.device", "cuda");
        // In-memory generation-tile cache for the upstream pipeline (CPU RAM). The upstream
        // default of 100 MB thrashes during the hydrology solvers' bulk native fetches — a
        // single L0 macro-region sweeps a ~600 MB working set, and eviction re-runs UNet
        // inference for ground generated seconds earlier (measured 2026-08-01: ~9 s/fetch
        // thrashing vs ~3.4 s with a real cache, and repeat gets 3 s -> 0.02 s). 4G holds a
        // whole region solve plus the L1/tile re-reads over the same ground.
        this.upstreamCacheSize = System.getProperty("stonebreak.terrainService.cacheSize", "4G");
        this.upstreamPort = Integer.getInteger("stonebreak.terrainService.upstreamPort", 8010);
        this.bridgePythonExe = resolvePath(userDir, "stonebreak.terrainService.bridgePythonExe",
                "terrain-bridge/venv/bin/python");
        this.bridgeDir = resolvePath(userDir, "stonebreak.terrainService.bridgeDir", "terrain-bridge");
        // Same property com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig reads for
        // its base URL, so the port this manager binds uvicorn to and the port Java's HTTP client
        // talks to can never drift apart.
        this.bridgePort = extractPort(System.getProperty("stonebreak.terrainBridge.url", "http://localhost:8180"), 8180);
        this.logDir = resolvePath(userDir, "stonebreak.terrainService.logDir",
                "Dev Working/terrain-diffusion-spike/logs");
        this.startupTimeoutMs = Long.getLong("stonebreak.terrainService.startupTimeoutMs", 120_000L);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "terrain-service-shutdown"));
    }

    private static Path resolvePath(Path userDir, String property, String defaultRelative) {
        String value = System.getProperty(property, defaultRelative);
        Path path = Path.of(value);
        return path.isAbsolute() ? path : userDir.resolve(path);
    }

    private static int extractPort(String url, int fallback) {
        try {
            int port = URI.create(url).getPort();
            return port > 0 ? port : fallback;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * Ensures both processes are up and pinned to {@code seed}, starting or restarting them as
     * needed. Blocks until both report healthy or {@code startupTimeoutMs} elapses. A no-op if
     * autostart is disabled ({@code -Dstonebreak.terrainService.autostart=false}, for developers
     * who prefer to run the two services by hand per terrain-bridge/README.md) or if both are
     * already running for this exact seed.
     *
     * @throws TerrainBridgeException if either process fails to start or become healthy in time
     */
    public void ensureRunningForSeed(long seed) {
        if (!autostart) {
            return;
        }
        synchronized (lock) {
            if (pinnedSeed != null && pinnedSeed == seed && isAlive(upstreamProcess) && isAlive(bridgeProcess)) {
                return;
            }
            stopLocked();
            try {
                Files.createDirectories(logDir);
            } catch (IOException e) {
                throw new TerrainBridgeException("could not create terrain service log directory " + logDir, e);
            }
            startUpstreamLocked(seed);
            startBridgeLocked(seed);
            pinnedSeed = seed;
        }
    }

    private void startUpstreamLocked(long seed) {
        Path logFile = logDir.resolve("minecraft_api.log");
        List<String> command = List.of(
                upstreamPythonExe.toString(), "-m", "terrain_diffusion.inference.minecraft_api",
                model,
                "--no-compile",
                "--device", device,
                "--cache-size", upstreamCacheSize,
                "--port", String.valueOf(upstreamPort),
                "--hdf5-file", "TEMP",
                "--seed", String.valueOf(seed)
        );
        reclaimPort(upstreamPort, "terrain_diffusion.inference.minecraft_api",
                "upstream terrain-diffusion server");
        LOG.info(() -> "Starting upstream terrain-diffusion server (seed " + seed + "): " + command);
        upstreamProcess = startProcess(command, upstreamRepoDir, logFile, null);
        waitForHealth("http://localhost:" + upstreamPort + "/health", upstreamProcess,
                "upstream terrain-diffusion server", logFile, null);
    }

    private void startBridgeLocked(long seed) {
        Path logFile = logDir.resolve("bridge.log");
        List<String> command = List.of(
                bridgePythonExe.toString(), "-m", "uvicorn", "bridge.main:app",
                "--port", String.valueOf(bridgePort)
        );
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("TERRAIN_BRIDGE_SEED", String.valueOf(seed));
        env.put("TERRAIN_BRIDGE_UPSTREAM_URL", "http://localhost:" + upstreamPort);
        // With the native water backend (the default), inland water is derived
        // game-side by Cenda's ck_carve_water over raw tiles, so the bridge's
        // hydrological L0/L1 solve — the ~90 s-per-region / ~20-min-worst-case
        // cold-start cost — is switched off and tiles revert to sub-second
        // sea-level-only generation. -Dstonebreak.water.backend=bridge restores
        // the old solve (NativeWaterTiles reads the same property and then does
        // not wrap the tile source).
        if (com.stonebreak.world.generation.water.NativeWaterTiles.nativeBackendSelected()) {
            env.put("TERRAIN_BRIDGE_HYDROLOGY", "0");
        }
        reclaimPort(bridgePort, "bridge.main:app", "terrain-bridge");
        LOG.info(() -> "Starting terrain-bridge (seed " + seed + "): " + command);
        bridgeProcess = startProcess(command, bridgeDir, logFile, env);
        waitForHealth("http://localhost:" + bridgePort + "/health", bridgeProcess, "terrain-bridge", logFile, seed);
    }

    private static Process startProcess(List<String> command, Path workingDir, Path logFile,
                                         java.util.Map<String, String> extraEnv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            if (extraEnv != null) {
                pb.environment().putAll(extraEnv);
            }
            return pb.start();
        } catch (IOException e) {
            throw new TerrainBridgeException("failed to launch process " + command + " in " + workingDir, e);
        }
    }

    /**
     * Polls {@code healthUrl} until {@code process} reports ready.
     *
     * <p>A 200 here is only trustworthy because {@link #reclaimPort} guaranteed the port was free
     * before {@code process} was spawned: a health probe cannot tell our child apart from any other
     * server on the same port, and when it could not, a leaked service from an earlier run answered
     * for a child that had already died on {@code EADDRINUSE} — which read as "healthy", then as a
     * dead process on the next {@link #ensureRunningForSeed} call, restarting both services in a
     * tight loop. The liveness re-check below and {@code expectedSeed} are the second and third
     * lines of that defence.
     *
     * @param expectedSeed seed the service must report from /health, or {@code null} if its health
     *                     body does not carry one (upstream's does not)
     */
    private void waitForHealth(String healthUrl, Process process, String label, Path logFile, Long expectedSeed) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(healthUrl))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        long deadline = System.currentTimeMillis() + startupTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new TerrainBridgeException(label + " exited before becoming healthy (exit code "
                        + process.exitValue() + "); see " + logFile);
            }
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    if (!process.isAlive()) {
                        throw new TerrainBridgeException(label + " died while starting, but something else is"
                                + " already answering " + healthUrl + " (exit code " + process.exitValue()
                                + "); see " + logFile);
                    }
                    verifySeed(response.body(), expectedSeed, label, healthUrl);
                    LOG.info(label + " is healthy at " + healthUrl);
                    return;
                }
            } catch (IOException e) {
                // Not up yet — keep polling until the deadline.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TerrainBridgeException("interrupted while waiting for " + label + " to start", e);
            }
            sleep(500);
        }
        throw new TerrainBridgeException(label + " did not become healthy within " + startupTimeoutMs
                + "ms; see " + logFile);
    }

    /**
     * Fails loudly when the service answering /health is pinned to a seed we did not ask for. Neither
     * service can be re-seeded in place, so a mismatch means we are talking to somebody else's
     * instance and every tile it returns would be a different world drawn without complaint.
     */
    static void verifySeed(String healthBody, Long expectedSeed, String label, String healthUrl) {
        if (expectedSeed == null) {
            return;
        }
        Matcher matcher = HEALTH_SEED.matcher(healthBody);
        if (!matcher.find()) {
            throw new TerrainBridgeException(label + " at " + healthUrl + " did not report a seed; expected "
                    + expectedSeed + ". Something other than terrain-bridge is on that port.");
        }
        long reported = Long.parseLong(matcher.group(1));
        if (reported != expectedSeed) {
            throw new TerrainBridgeException(label + " at " + healthUrl + " is pinned to seed " + reported
                    + " but this world needs " + expectedSeed + "; it is not the process we started.");
        }
    }

    /**
     * Makes sure {@code port} is free before we spawn a child that binds it, killing a leaked
     * terrain service from an earlier run if one still owns it. The JVM shutdown hook misses these
     * whenever the process is killed hard (SIGKILL, an IDE stop button), and the children outlive it.
     *
     * <p>Only processes whose command line is recognisably this service on this port are killed — an
     * unrelated program on the port is reported instead of shot.
     */
    private void reclaimPort(int port, String moduleArg, String label) {
        if (isPortFree(port)) {
            return;
        }
        List<ProcessHandle> leaked = ProcessHandle.allProcesses()
                .filter(handle -> ownsService(handle, moduleArg, port))
                .toList();
        if (leaked.isEmpty()) {
            throw new TerrainBridgeException("port " + port + " (" + label + ") is held by a process that is not"
                    + " a terrain service; stop it, or start the game with"
                    + " -Dstonebreak.terrainService.autostart=false and run the services by hand.");
        }
        for (ProcessHandle handle : leaked) {
            LOG.log(Level.WARNING, "Reclaiming port {0}: killing leaked {1} (pid {2})",
                    new Object[] {port, label, handle.pid()});
            handle.destroy();
        }
        waitForPortFree(port, leaked, label);
    }

    /**
     * True only for a python process running {@code moduleArg} on {@code port}.
     *
     * <p>Deliberately matches the executable and exact argv tokens rather than searching the joined
     * command line: a shell that launched the service carries the whole invocation inside its own
     * {@code bash -c '...'} argument, so a substring test also matches the wrapper — and, worse, any
     * unrelated terminal that once typed the command.
     */
    private static boolean ownsService(ProcessHandle handle, String moduleArg, int port) {
        ProcessHandle.Info info = handle.info();
        String executable = info.command().orElse("");
        if (!isPython(executable)) {
            return false;
        }
        List<String> args = List.of(info.arguments().orElse(new String[0]));
        return args.contains(moduleArg) && runsOnPort(args, port);
    }

    static boolean isPython(String executable) {
        int slash = executable.lastIndexOf('/');
        String name = slash < 0 ? executable : executable.substring(slash + 1);
        return name.startsWith("python");
    }

    static boolean runsOnPort(List<String> args, int port) {
        String value = String.valueOf(port);
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--port=" + value)) {
                return true;
            }
            if (arg.equals("--port") && i + 1 < args.size() && args.get(i + 1).equals(value)) {
                return true;
            }
        }
        return false;
    }

    private void waitForPortFree(int port, List<ProcessHandle> leaked, String label) {
        long deadline = System.currentTimeMillis() + PORT_RECLAIM_TIMEOUT_MS;
        boolean escalated = false;
        while (System.currentTimeMillis() < deadline) {
            if (leaked.stream().noneMatch(ProcessHandle::isAlive) && isPortFree(port)) {
                return;
            }
            if (!escalated && System.currentTimeMillis() > deadline - PORT_RECLAIM_TIMEOUT_MS / 2) {
                LOG.log(Level.WARNING, "Leaked {0} ignored SIGTERM; killing it", label);
                leaked.forEach(ProcessHandle::destroyForcibly);
                escalated = true;
            }
            sleep(200);
        }
        throw new TerrainBridgeException("port " + port + " (" + label + ") was still in use "
                + PORT_RECLAIM_TIMEOUT_MS + "ms after killing the leaked process that held it");
    }

    /** Binds the port the same way the child will, so "free" here means the child's bind will succeed. */
    static boolean isPortFree(int port) {
        try (ServerSocket probe = new ServerSocket(port, 1)) {
            return probe.getLocalPort() == port;
        } catch (IOException e) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isAlive(Process process) {
        return process != null && process.isAlive();
    }

    /** Stops both processes if running. Safe to call more than once. Registered as a JVM shutdown hook. */
    public void shutdown() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private void stopLocked() {
        stopProcess(bridgeProcess, "terrain-bridge");
        bridgeProcess = null;
        stopProcess(upstreamProcess, "upstream terrain-diffusion server");
        upstreamProcess = null;
        pinnedSeed = null;
    }

    private static void stopProcess(Process process, String label) {
        if (process == null || !process.isAlive()) {
            return;
        }
        LOG.info(() -> "Stopping " + label + " (pid " + process.pid() + ")");
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                LOG.log(Level.WARNING, "{0} did not exit after SIGTERM; killing", label);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
