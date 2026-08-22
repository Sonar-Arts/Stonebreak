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
import java.util.ArrayDeque;
import org.joml.Vector3f;

/**
 * Gathers the running game-state diagnostics shown on the debug info card:
 * the rolling FPS average, chunk-pipeline throughput rates, world-gen noise
 * backend, mob path-search load, and the block / water the player is looking
 * at. Holds the frame-to-frame sampling state those readings need.
 */
public final class DebugDiagnostics {

    // FPS averaging
    private static final int FPS_SAMPLE_SIZE = 60; // Average over 60 frames
    private ArrayDeque<Float> fpsHistory = new ArrayDeque<>(FPS_SAMPLE_SIZE);
    private float averageFPS = 0.0f;

    // Previous ChunkPipelineStats sample for per-second rate derivation.
    private long pipelineSampleNanos = 0L;
    private final long[] pipelineLast = new long[6];
    private final double[] pipelineRates = new double[6];

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

        Vector3f position = player.getPosition();
        Vector3f rayOrigin = new Vector3f(position.x, position.y + 1.6f, position.z);
        Vector3f rayDirection = camera.getFront();

        for (float d = 0; d < 6.0f; d += 0.05f) {
            Vector3f point = new Vector3f(rayDirection).mul(d).add(rayOrigin);
            int bx = (int) Math.floor(point.x);
            int by = (int) Math.floor(point.y);
            int bz = (int) Math.floor(point.z);

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

        Vector3f position = player.getPosition();
        Vector3f rayOrigin = new Vector3f(position.x, position.y + 1.6f, position.z);
        Vector3f rayDirection = camera.getFront();

        for (float d = 0; d < 5.0f; d += 0.05f) {
            Vector3f point = new Vector3f(rayDirection).mul(d).add(rayOrigin);
            int bx = (int) Math.floor(point.x);
            int by = (int) Math.floor(point.y);
            int bz = (int) Math.floor(point.z);

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
     * Per-second rates through the chunk pipeline stages
     * (gen → populate → stream → install → mesh → upload), derived from
     * frame-to-frame deltas of the {@code ChunkPipelineStats} totals.
     */
    public String chunkPipelineSummary() {
        long now = System.nanoTime();
        long[] totals = {
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.GENERATED.sum(),
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.POPULATED.sum(),
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.STREAMED.sum(),
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.INSTALLED.sum(),
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.MESHED.sum(),
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.UPLOADED.sum(),
        };
        // Refresh rates every ~500 ms so the row is readable, not flickering.
        if (pipelineSampleNanos == 0L || now - pipelineSampleNanos >= 500_000_000L) {
            double seconds = pipelineSampleNanos == 0L ? 0
                : (now - pipelineSampleNanos) / 1_000_000_000.0;
            for (int i = 0; i < totals.length; i++) {
                pipelineRates[i] = seconds > 0 ? (totals[i] - pipelineLast[i]) / seconds : 0;
                pipelineLast[i] = totals[i];
            }
            pipelineSampleNanos = now;
        }
        return String.format("gen %.0f pop %.0f str %.0f inst %.0f mesh %.0f gl %.0f /s",
            pipelineRates[0], pipelineRates[1], pipelineRates[2],
            pipelineRates[3], pipelineRates[4], pipelineRates[5]);
    }

    /** One-line world-gen noise backend status: Cenda native kernels vs classic Java. */
    public static String noiseBackendSummary() {
        if (com.stonebreak.world.generation.noise.TerrainNoise.backend()
                == com.stonebreak.world.generation.noise.TerrainNoise.Backend.NATIVE) {
            return "Cenda FastNoise2 (" + com.openmason.engine.cenda.CendaKernels.simdLevel() + ")";
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
        com.stonebreak.mobs.entities.ai.nav.PathfindingService service = searching.pathfinding();
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
