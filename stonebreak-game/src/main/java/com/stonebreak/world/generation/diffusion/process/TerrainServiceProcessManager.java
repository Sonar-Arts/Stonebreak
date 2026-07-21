package com.stonebreak.world.generation.diffusion.process;

import com.stonebreak.world.generation.diffusion.TerrainBridgeException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
 */
public final class TerrainServiceProcessManager {

    private static final Logger LOG = Logger.getLogger(TerrainServiceProcessManager.class.getName());
    private static final TerrainServiceProcessManager INSTANCE = new TerrainServiceProcessManager();

    public static TerrainServiceProcessManager getInstance() {
        return INSTANCE;
    }

    private final boolean autostart;
    private final Path upstreamPythonExe;
    private final Path upstreamRepoDir;
    private final String model;
    private final String device;
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
                "--port", String.valueOf(upstreamPort),
                "--hdf5-file", "TEMP",
                "--seed", String.valueOf(seed)
        );
        LOG.info(() -> "Starting upstream terrain-diffusion server (seed " + seed + "): " + command);
        upstreamProcess = startProcess(command, upstreamRepoDir, logFile, null);
        waitForHealth("http://localhost:" + upstreamPort + "/health", upstreamProcess,
                "upstream terrain-diffusion server", logFile);
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
        LOG.info(() -> "Starting terrain-bridge (seed " + seed + "): " + command);
        bridgeProcess = startProcess(command, bridgeDir, logFile, env);
        waitForHealth("http://localhost:" + bridgePort + "/health", bridgeProcess, "terrain-bridge", logFile);
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

    private void waitForHealth(String healthUrl, Process process, String label, Path logFile) {
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
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
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
