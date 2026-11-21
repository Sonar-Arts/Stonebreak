package com.stonebreak.ui.debug;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.server.IntegratedServer;
import com.stonebreak.player.Camera;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import com.stonebreak.world.World;
import com.openmason.engine.voxel.world.StreamStats;
import java.util.ArrayDeque;
import org.joml.Vector3f;

/**
 * Gathers the running game-state diagnostics shown on the debug info card:
 * the rolling FPS average, cube-stream throughput rates, world-gen noise
 * backend, mob path-search load, and the block / water the player is looking
 * at. Holds the frame-to-frame sampling state those readings need.
 */
public final class DebugDiagnostics {

    // FPS averaging
    private static final int FPS_SAMPLE_SIZE = 60; // Average over 60 frames
    private ArrayDeque<Float> fpsHistory = new ArrayDeque<>(FPS_SAMPLE_SIZE);
    private float averageFPS = 0.0f;

    // Per-second rates of the rendered world's stream and, in singleplayer/host, the integrated server's.
    private final StreamRates localRates = new StreamRates();
    private final StreamRates serverRates = new StreamRates();

    /**
     * Updates the average FPS calculation with the current frame's FPS.
     */
    public void updateAverageFPS() {
        float currentFPS = 1.0f / Game.getDeltaTime();

        // Add current FPS to history
        fpsHistory.addLast(currentFPS);

        // Remove oldest FPS if we exceed sample size
        if (fpsHistory.size() > FPS_SAMPLE_SIZE) {
            fpsHistory.removeFirst();
        }

        // Calculate average
        if (!fpsHistory.isEmpty()) {
            float sum = 0.0f;
            for (Float fps : fpsHistory) {
                sum += fps;
            }
            averageFPS = sum / fpsHistory.size();
        }
    }

    /** The rolling average from the last {@value #FPS_SAMPLE_SIZE} frames. */
    public float averageFPS() {
        return averageFPS;
    }

    /**
     * Returns a one-line summary of the block the player is looking at.
     * Used by the compact debug panel (MStatPanel). Returns null when nothing
     * is targeted.
     */
    public String getTargetedBlockSummary(Player player) {
        Camera camera = player.getCamera();
        World world = Game.getWorld();

        if (camera == null || world == null) return null;

        var position = player.getPosition();
        double ox = position.x, oy = position.y + 1.6, oz = position.z;
        Vector3f rayDirection = camera.getFront();

        for (float d = 0; d < 6.0f; d += 0.05f) {
            long bx = (long) Math.floor(ox + rayDirection.x * d);
            long by = (long) Math.floor(oy + rayDirection.y * d);
            long bz = (long) Math.floor(oz + rayDirection.z * d);

            BlockType bt = world.getBlockAt(bx, by, bz);
            if (bt != null && bt != BlockType.AIR) {
                Renderer renderer = Game.getRenderer();
                SBOBlockBridge bridge = renderer != null ? renderer.getSBOBlockBridge() : null;
                String model = (bridge != null && bridge.isSBOBlock(bt)) ? "SBO" : "Mesh";
                return String.format("%s [%s] ~ (%d,%d,%d)", bt.name(), model, bx, by, bz);
            }
        }
        return null;
    }

    /**
     * Returns a one-line summary of the water block the player is looking at.
     * Returns null when not looking at water.
     */
    public String getWaterStateSummary(Player player) {
        Camera camera = player.getCamera();
        World world = Game.getWorld();

        if (camera == null || world == null) return null;

        var position = player.getPosition();
        double ox = position.x, oy = position.y + 1.6, oz = position.z;
        Vector3f rayDirection = camera.getFront();

        for (float d = 0; d < 5.0f; d += 0.05f) {
            long bx = (long) Math.floor(ox + rayDirection.x * d);
            long by = (long) Math.floor(oy + rayDirection.y * d);
            long bz = (long) Math.floor(oz + rayDirection.z * d);

            BlockType bt = world.getBlockAt(bx, by, bz);
            if (bt == BlockType.WATER) {
                int value = world.getWaterLevelAt(bx, by, bz);
                String type = switch (value) {
                    case com.stonebreak.world.chunk.ChunkWaterLayer.SOURCE -> "Source";
                    case com.stonebreak.world.chunk.ChunkWaterLayer.FALLING -> "Falling";
                    default -> "Flowing " + value;
                };
                String queued = (world.getWaterSim() != null)
                        ? String.format(" (%d queued)", world.getWaterSim().getQueuedUpdateCount())
                        : "";
                return String.format("Water %s%s ~ (%d,%d,%d)", type, queued, bx, by, bz);
            }
            if (bt != null && bt != BlockType.AIR) break;
        }
        return null;
    }

    /**
     * Per-second rates of the rendered world's cube stream: cubes generated, loaded from the store, populated,
     * meshed, saved and unloaded, from frame-to-frame deltas of the native {@link StreamStats} totals. "idle"
     * for a detached world (no stream).
     */
    public String chunkPipelineSummary() {
        World w = Game.getWorld();
        return streamRatesSummary(w != null ? w.streamStats() : null, localRates);
    }

    /** {@link #chunkPipelineSummary()} for the integrated server's world; null without one. */
    public String serverPipelineSummary() {
        StreamStats st = serverStreamStats();
        return st == null ? null : streamRatesSummary(st, serverRates);
    }

    /** The integrated server's stream counters (singleplayer/host), or null. */
    public static StreamStats serverStreamStats() {
        World rendered = Game.getWorld();
        World authoritative = navigationWorld(rendered);
        return authoritative != null && authoritative != rendered ? authoritative.streamStats() : null;
    }

    /** Residency of a stream: installed cubes, loads/generations in flight, meshing, queued (absent, wanted). */
    public static String streamCubesSummary(StreamStats st) {
        if (st == null) {
            return "detached";
        }
        return String.format("%,d loaded / %d gen / %d mesh / %d queued", st.cubesLoaded(), st.cubesGenerating(),
            st.cubesMeshing() + st.meshesPending(), st.cubesQueued() + st.installsPending());
    }

    /** Owner-thread cost of a stream: last/max tick, last plan, ready columns. */
    public static String streamTickSummary(StreamStats st) {
        if (st == null) {
            return "detached";
        }
        return String.format("tick %d us (max %d) plan %d us, cols %d/%d ready%s", st.tickUsLast(), st.tickUsMax(),
            st.planUsLast(), st.columnsReady(), st.columnsTracked(),
            st.errorsTotal() > 0 ? ", " + st.errorsTotal() + " ERR" : "");
    }

    static String streamRatesSummary(StreamStats st, StreamRates rates) {
        if (st == null) {
            return "detached";
        }
        double[] r = rates.sample(System.nanoTime(), new long[]{
            st.generatedTotal(), st.loadedTotal(), st.populatedTotal(),
            st.meshedTotal(), st.savedTotal(), st.unloadedTotal(),
        });
        return String.format("gen %.0f load %.0f pop %.0f mesh %.0f save %.0f unload %.0f /s",
            r[0], r[1], r[2], r[3], r[4], r[5]);
    }

    /**
     * Rates from counter totals, refreshed every ~500 ms so the row is readable, not flickering. A total that
     * goes backwards (a new world) restarts the sampling.
     */
    static final class StreamRates {
        private long sampleNanos;
        private long[] last;
        private double[] rates = new double[0];

        double[] sample(long now, long[] totals) {
            if (last == null || last.length != totals.length) {
                last = totals.clone();
                rates = new double[totals.length];
                sampleNanos = now;
                return rates;
            }
            if (now - sampleNanos >= 500_000_000L) {
                double seconds = (now - sampleNanos) / 1_000_000_000.0;
                for (int i = 0; i < totals.length; i++) {
                    long delta = totals[i] - last[i];
                    rates[i] = delta >= 0 ? delta / seconds : 0;
                    last[i] = totals[i];
                }
                sampleNanos = now;
            }
            return rates;
        }
    }

    /** One-line world-gen backend status: Cenda native noise vs classic Java, plus whether the
     *  game's worldgen natives (#386) carry the carver and fused chunk generator. */
    public static String noiseBackendSummary() {
        if (com.stonebreak.world.generation.noise.TerrainNoise.backend()
                == com.stonebreak.world.generation.noise.TerrainNoise.Backend.NATIVE) {
            return "Cenda FastNoise2 (" + com.openmason.engine.cenda.CendaKernels.simdLevel() + "), worldgen "
                + (com.stonebreak.world.generation.NativeTerrain.isAvailable() ? "native cubes" : "MISSING");
        }
        return "Java (classic simplex)";
    }

    /**
     * Mob path-search load: how many searches are running, how many have run, how long they take,
     * and how many came back partial.
     *
     * <p>Partials are the number worth watching: a few are normal (mobs do aim at spots they cannot
     * reach), but a steady stream means either the expansion budget is too tight for the terrain or
     * something is asking for routes that do not exist.
     */
    public static String navigationSummary(com.stonebreak.world.World world) {
        // The searches happen on the authoritative world, alongside the AI that asks for them —
        // the render world's own service sits at zero forever. See navigationEntitySource().
        com.stonebreak.world.World searching = navigationWorld(world);
        com.stonebreak.mobs.entities.ai.nav.PathfindingService service = searching.pathfindingIfStarted();
        if (service == null) {
            return "off";
        }
        var stats = service.stats();
        return String.format("%d searching / %d done @ %d µs / %d partial / %d rejected",
                stats.inFlight(), stats.completed(), stats.averageMicros(),
                stats.partial(), stats.rejected());
    }

    /** The world whose pathfinder the mobs actually use; falls back to the rendered one. */
    private static com.stonebreak.world.World navigationWorld(com.stonebreak.world.World rendered) {
        if (MultiplayerSession.hasIntegratedServer()) {
            IntegratedServer server = MultiplayerSession.getServer();
            if (server != null) {
                com.stonebreak.world.World authoritative = server.worldContext().world();
                if (authoritative != null) {
                    return authoritative;
                }
            }
        }
        return rendered;
    }
}
