package com.stonebreak.network.server;

import com.openmason.engine.net.server.ServerLimits;
import com.openmason.engine.net.transport.NetAddress;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.WorldStorage;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A real {@link IntegratedServer} for tests (issue #347): a headless {@link ServerLevel} booted in
 * a temporary home, listening on loopback TCP, ticked by its own thread the way
 * {@code MultiplayerSession} ticks it. Clients are {@link HarnessClient}s speaking the real wire
 * protocol. {@link #close} stops it the way a session stops it — final drains, then
 * shutdown (which persists the connected players) and the level's cleanup.
 *
 * <p>The data directory ({@link WorldStorage#DATA_DIR_PROPERTY}) is redirected for the harness's
 * lifetime so worlds and player saves land in the temporary home, on every platform. One harness
 * at a time per JVM.
 */
public final class ServerHarness implements AutoCloseable {

    /** How often the tick thread pumps {@code tick()} — the session's own poll interval. */
    private static final long POLL_MS = 5;

    private final IntegratedServer server;
    private final Thread tickThread;
    private final String previousDataDir;
    private volatile boolean running = true;
    private volatile Throwable tickFailure;

    private ServerHarness(IntegratedServer server, String previousDataDir) {
        this.server = server;
        this.previousDataDir = previousDataDir;
        this.tickThread = new Thread(this::tickLoop, "Harness-Server");
        this.tickThread.setDaemon(true);
    }

    /** Whether worlds can be redirected to a temporary home — always, through the data-dir property. */
    public static boolean isolatedHomeAvailable() {
        return true;
    }

    /** A server with the real limits and timeouts. */
    public static ServerHarness boot(Path home, String worldName) throws InterruptedException {
        return boot(home, worldName, ServerLimits.DEFAULTS, IntegratedServer.INBOUND_SILENCE_TIMEOUT_NS,
                IntegratedServer.KEEPALIVE_INTERVAL_TICKS);
    }

    /** A server with its own limits and silence timing (so timeouts run in seconds). */
    public static ServerHarness boot(Path home, String worldName, ServerLimits limits,
                                     long silenceTimeoutNs, int keepaliveIntervalTicks)
            throws InterruptedException {
        // The engine bounds the game installs at startup (GameBootstrap.configureEngine): feature
        // population and the CCO coordinate helpers read them.
        com.openmason.engine.voxel.cco.coordinates.CcoBounds.configure(
                new com.openmason.engine.voxel.VoxelWorldConfig(WorldConfiguration.CHUNK_SIZE,
                        WorldConfiguration.WORLD_HEIGHT, WorldConfiguration.SEA_LEVEL));
        String previousDataDir = System.getProperty(WorldStorage.DATA_DIR_PROPERTY);
        System.setProperty(WorldStorage.DATA_DIR_PROPERTY, home.toString());
        IntegratedServer server = new IntegratedServer(limits, silenceTimeoutNs, keepaliveIntervalTicks);
        try {
            server.start(null, NetAddress.tcp("127.0.0.1", 0), worldName, 347L);
        } catch (InterruptedException | RuntimeException e) {
            restoreDataDir(previousDataDir);
            throw e;
        }
        ServerHarness harness = new ServerHarness(server, previousDataDir);
        harness.tickThread.start();
        return harness;
    }

    private static void restoreDataDir(String previous) {
        if (previous == null) {
            System.clearProperty(WorldStorage.DATA_DIR_PROPERTY);
        } else {
            System.setProperty(WorldStorage.DATA_DIR_PROPERTY, previous);
        }
    }

    /** Limits like the real ones, with a short admission deadline and outbound cap for tests. */
    public static ServerLimits limits(long admissionTimeoutMillis, long maxOutboundBytes) {
        ServerLimits d = ServerLimits.DEFAULTS;
        return new ServerLimits(d.maxConnections(), d.maxConnectionsPerAddress(), admissionTimeoutMillis,
                d.readIdleTimeoutSeconds(), maxOutboundBytes, d.maxInboundFrameBytes(), d.packetsPerSecond(),
                d.packetBurst(), d.bytesPerSecond(), d.byteBurst(), d.readPauseDepth(), d.readResumeDepth());
    }

    private void tickLoop() {
        while (running) {
            try {
                server.tick();
            } catch (Throwable t) {
                tickFailure = t; // a test asserting server health checks this
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public IntegratedServer server() { return server; }

    public ServerWorldContext ctx() { return server.worldContext(); }

    public int port() {
        return ((InetSocketAddress) server.tcpBoundAddress()).getPort();
    }

    /** The first exception a tick threw, or null while every tick has completed. */
    public Throwable tickFailure() { return tickFailure; }

    /** Runs {@code read} on the tick thread and waits for its answer — the safe way to read server state. */
    public <T> T onTick(Callable<T> read) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        server.executeOnTick(() -> {
            try {
                result.complete(read.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    /** The rostered player with {@code username}, or null (read on the tick thread). */
    public ServerPlayer player(String username) throws Exception {
        return onTick(() -> ctx().players().stream()
                .filter(p -> username.equals(p.username())).findFirst().orElse(null));
    }

    /** How many players are rostered (read on the tick thread). */
    public int rosterSize() throws Exception {
        return onTick(() -> ctx().players().size());
    }

    /** {@code username}'s saved record as the server would read it at a join (null = none). */
    public byte[] savedPlayer(String username) throws Exception {
        return ctx().serverLevel().saveService().loadNamedPlayer(username).get(10, TimeUnit.SECONDS);
    }

    /** Polls {@code condition} until it holds or {@code timeout} passes; returns whether it held. */
    public static boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    /** Stops the server as a session does: last drains, shutdown (persisting players), level cleanup. */
    @Override
    public void close() throws InterruptedException {
        try {
            running = false;
            tickThread.join(15_000);
            server.drainInboundForShutdown();
            server.stopTicking();
            ServerLevel level = server.worldContext().serverLevel();
            server.shutdown();
            if (level != null) {
                level.cleanup();
            }
        } finally {
            restoreDataDir(previousDataDir);
        }
    }
}
