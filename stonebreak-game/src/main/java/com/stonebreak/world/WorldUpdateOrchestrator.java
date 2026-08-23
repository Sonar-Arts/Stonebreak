package com.stonebreak.world;

import org.joml.Vector3f;

import com.stonebreak.blocks.waterSystem.WaterSim;
import com.stonebreak.core.Game;
import com.stonebreak.world.chunk.utils.ChunkManager;
import com.stonebreak.world.chunk.utils.WorldChunkStore;
import com.stonebreak.world.leaves.LeafDecaySystem;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Owns the per-tick update sequencing of a {@link World}: the rendered singleplayer/host
 * update, the headless authoritative simulation step, and the render-only client update
 * (including the client's out-of-view chunk unload sweep). Each sequence calls the same
 * collaborators (water, leaf decay, furnaces, chunk store, mesh scheduler, FastLOD) in
 * the order the original {@code World} methods did.
 */
final class WorldUpdateOrchestrator {
    /** How far into the 50 ms server tick the feature-population drain may run. */
    private static final long FEATURE_TICK_DEADLINE_NANOS = 30_000_000L;

    private final World world;
    private final WaterSim waterSim;
    private final LeafDecaySystem leafDecay;
    private final com.stonebreak.blocks.furnace.FurnaceStateRegistry furnaceRegistry;
    private final WorldChunkStore chunkStore;
    private final ChunkManager chunkManager;
    private final ChunkMeshScheduler meshScheduler;
    private final FastLodLifecycle fastLod;

    // Last position/radius the client unload sweep ran for. The sweep only does work when the
    // player crosses a chunk boundary or the keep radius shrinks/grows (settings Apply) —
    // chunks the server streams in are always within the keep radius, so a stationary player
    // can never accumulate out-of-range chunks between crossings.
    private int lastUnloadSweepCx = Integer.MIN_VALUE;
    private int lastUnloadSweepCz = Integer.MIN_VALUE;
    private int lastUnloadSweepKeepRadius = -1;

    WorldUpdateOrchestrator(World world,
                            WaterSim waterSim,
                            LeafDecaySystem leafDecay,
                            com.stonebreak.blocks.furnace.FurnaceStateRegistry furnaceRegistry,
                            WorldChunkStore chunkStore,
                            ChunkManager chunkManager,
                            ChunkMeshScheduler meshScheduler,
                            FastLodLifecycle fastLod) {
        this.world = world;
        this.waterSim = waterSim;
        this.leafDecay = leafDecay;
        this.furnaceRegistry = furnaceRegistry;
        this.chunkStore = chunkStore;
        this.chunkManager = chunkManager;
        this.meshScheduler = meshScheduler;
        this.fastLod = fastLod;
    }

    /** Rendered, authoritative update (singleplayer / integrated-host render world). */
    void update() {
        if (!meshScheduler.hasPipeline()) return; // Test mode - skip rendering updates

        waterSim.tick(Game.getDeltaTime());
        leafDecay.tick(Game.getDeltaTime());
        com.stonebreak.blocks.furnace.FurnaceStateRegistry fr = furnaceRegistry;
        if (fr != null) fr.tick(world, Game.getDeltaTime());
        meshScheduler.requeueFailedChunks();
        if (chunkManager != null) {
            chunkManager.update(Game.getPlayer());
        }

        // Process deferred feature population (breaks recursive generation cycles)
        if (chunkStore != null) {
            chunkStore.processPendingFeaturePopulation();
        }

        meshScheduler.processChunkMeshBuildRequests(world);
    }

    /** Authoritative simulation step, independent of rendering (headless server world). */
    void updateSimulation(float deltaTime) {
        long tickStart = System.nanoTime();
        waterSim.tick(deltaTime);
        leafDecay.tick(deltaTime);
        com.stonebreak.blocks.furnace.FurnaceStateRegistry fr = furnaceRegistry;
        if (fr != null) fr.tick(world, deltaTime);
        if (chunkStore != null) {
            // Throughput-bound feature population: drain against the tick's own
            // clock (leave ~20 ms of the 50 ms period for entity sim + chunk
            // streaming that follow in ServerLevel.tick) instead of a fixed
            // 8 ms slice — a light tick populates ~4x more chunks per tick,
            // a heavy one (water settling etc.) backs off automatically.
            chunkStore.processPendingFeaturePopulation(tickStart + FEATURE_TICK_DEADLINE_NANOS);
        }
    }

    /** Render-only client update: mesh pumping, unload sweep and the FastLOD ring tick. */
    void updateClient() {
        if (!meshScheduler.hasPipeline()) return; // No rendering infrastructure — nothing to do.

        // Deliberately NO chunkManager.update here: on a render-only world it calls
        // getOrCreateChunk around the player and, with terrain generation disabled, manufactures
        // empty all-air placeholder chunks the server never streams — their empty meshes get
        // treated as failed builds and spam the retry path. The client only meshes chunks the
        // server installs (installNetworkChunk schedules their build directly); we just pump the
        // build queue. Distant streamed chunks unload via unloadClientChunksOutsideView
        // below, so client memory stays bounded to the keep radius.
        meshScheduler.requeueFailedChunks();
        meshScheduler.processChunkMeshBuildRequests(world);
        unloadClientChunksOutsideView();

        // FastLOD ring tick (see FastLodLifecycle.updateRing for why the client drives it).
        var lodPlayer = Game.getPlayer();
        if (lodPlayer != null) {
            fastLod.updateRing(lodPlayer.getPosition());
        }
    }

    /**
     * Unload streamed chunks that have left the client's keep radius. Render-only worlds never
     * regenerate, so a dropped chunk simply re-streams from the server if the player returns
     * (the server forgets it at the same radius). Bounds the client's memory as it explores;
     * no save (the client never persists). Skips entirely while the player stays inside one
     * chunk — the previous per-frame full scan copied every resident chunk position each frame.
     */
    private void unloadClientChunksOutsideView() {
        var player = Game.getPlayer();
        if (player == null || chunkStore == null) {
            return;
        }
        Vector3f pos = player.getPosition();
        int pcx = Math.floorDiv((int) Math.floor(pos.x), WorldConfiguration.CHUNK_SIZE);
        int pcz = Math.floorDiv((int) Math.floor(pos.z), WorldConfiguration.CHUNK_SIZE);
        int keepRadius = world.clientKeepRadius();
        if (pcx == lastUnloadSweepCx && pcz == lastUnloadSweepCz && keepRadius == lastUnloadSweepKeepRadius) {
            return;
        }
        lastUnloadSweepCx = pcx;
        lastUnloadSweepCz = pcz;
        lastUnloadSweepKeepRadius = keepRadius;
        chunkStore.unloadChunksOutside(pcx, pcz, keepRadius);
    }
}
