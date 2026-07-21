package com.stonebreak.world;

import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.function.Consumer;

import com.stonebreak.world.chunk.utils.ChunkManager;
import com.stonebreak.world.chunk.utils.ChunkPosition;
import org.joml.Vector3f;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterSim;
import com.stonebreak.blocks.waterSystem.WorldFlowWorld;
import com.stonebreak.core.Game;
import com.stonebreak.world.chunk.*;
import com.stonebreak.world.chunk.api.commonChunkOperations.operations.CcoNeighborCoordinator;
import com.stonebreak.world.chunk.api.mightyMesh.MmsAPI;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshPipeline;
import com.stonebreak.world.chunk.utils.ChunkErrorReporter;
import com.stonebreak.world.chunk.utils.WorldChunkStore;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.fastlod.FastLodManager;
import com.stonebreak.world.fastlod.FastLodStore;
import com.stonebreak.world.operations.WorldConfiguration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages the game world and chunks using a modular architecture.
 */
public class World {
    // Configuration and core systems
    private final WorldConfiguration config;
    private final TerrainGenerationSystem terrainSystem;
    private final ChunkManager chunkManager;
    private final SnowLayerManager snowLayerManager;
    private final com.stonebreak.blocks.furnace.FurnaceStateRegistry furnaceRegistry;
    private final com.stonebreak.blocks.anim.AnimatedBlockRegistry animatedBlockRegistry =
            new com.stonebreak.blocks.anim.AnimatedBlockRegistry();
    
    // World spawn position
    private Vector3f spawnPosition = new Vector3f(0, 100, 0);
    
    // Modular components
    private final WorldChunkStore chunkStore;
    private final CcoNeighborCoordinator neighborCoordinator;
    private final MmsMeshPipeline meshPipeline;
    private final ChunkErrorReporter errorReporter;
    private final WaterSim waterSim;
    private final com.stonebreak.world.generation.features.FeatureQueue featureQueue;

    // Lazily constructed once the render-thread hands us a texture atlas.
    private volatile FastLodManager fastLodManager;

    // Per-world persistence. Null = this world is not persisted (e.g. a client render
    // world, whose state is authoritative on the server). Set by SaveService.initialize().
    // Replaces WorldChunkStore's old dependency on the Game-singleton SaveService.
    private volatile com.stonebreak.world.save.SaveService saveService;

    // Render-only client view: fully rendered (mesh pipeline present), but generates no terrain
    // and runs no authoritative sim (water/furnace/features/spawn/time). All block + chunk +
    // entity state arrives from the server. Set only via createClientView(); the
    // authoritative/singleplayer world is never render-only. Drives GameLoop's update branch.
    private volatile boolean renderOnly = false;

    // Per-world entity spawner used for initial mob spawning during chunk generation. The
    // headless server world sets this to ITS OWN spawner so generated mobs land in the server's
    // EntityManager (not the client's, which the Game singleton would resolve to). Null = fall
    // back to the Game singleton's spawner (the co-located / single-world behavior).
    private volatile com.stonebreak.mobs.entities.EntitySpawner entitySpawner;

    // Per-world entity manager, used when loading saved chunk entities (Chunk.loadFromSnapshot).
    // The headless server world sets its own so restored mobs go to the server (not the Game
    // singleton's manager, which during server boot is the previous session's terminated one).
    private volatile com.stonebreak.mobs.entities.EntityManager entityManager;

    /**
     * Sink for authoritative SIMULATION block mutations (water flow, and any future server-side
     * system that writes via {@code chunk.setBlock} instead of {@code setBlockAt}). Installed by
     * the integrated server on the HEADLESS world only, so flowing water etc. replicates to
     * clients live; null everywhere else (client render worlds must never feed it — that would
     * loop server echoes back out).
     */
    public interface ServerBlockMutationCallback {
        void onServerBlockChange(int x, int y, int z, com.stonebreak.blocks.BlockType type);
    }

    private volatile ServerBlockMutationCallback serverMutationCallback;

    /** Install the server-side sim mutation sink (headless server world only). */
    public void setServerMutationCallback(ServerBlockMutationCallback callback) {
        this.serverMutationCallback = callback;
    }

    /** The sim mutation sink, or null on client/render worlds. */
    public ServerBlockMutationCallback serverMutationCallback() {
        return serverMutationCallback;
    }

    /**
     * Sink for authoritative snow-layer mutations ({@code layers == 0} = removed), installed
     * by the integrated server on the HEADLESS world only so layer changes replicate to
     * clients as {@code BlockMetaS2C}. Null everywhere else.
     */
    public interface ServerSnowMutationCallback {
        void onServerSnowChange(int x, int y, int z, int layers);
    }

    private volatile ServerSnowMutationCallback serverSnowCallback;

    /** Install the server-side snow mutation sink (headless server world only). */
    public void setServerSnowCallback(ServerSnowMutationCallback callback) {
        this.serverSnowCallback = callback;
    }

    /**
     * Sink for authoritative water-layer mutations (value = layer byte: 1..7 flowing,
     * 8 falling, 0 = entry removed / became source), installed by the integrated server
     * on the HEADLESS world only so flow levels replicate to clients as
     * {@code BlockMetaS2C} (KIND_WATER_LEVEL). Null everywhere else. Fired from
     * {@code WorldFlowWorld.markWaterChanged} on the server tick thread.
     */
    public interface ServerWaterMutationCallback {
        void onServerWaterChange(int x, int y, int z, int value);
    }

    private volatile ServerWaterMutationCallback serverWaterCallback;

    /** Install the server-side water mutation sink (headless server world only). */
    public void setServerWaterCallback(ServerWaterMutationCallback callback) {
        this.serverWaterCallback = callback;
    }

    /** The water mutation sink, or null on client/render worlds. */
    public ServerWaterMutationCallback serverWaterCallback() {
        return serverWaterCallback;
    }

    public World() {
        this(new WorldConfiguration());
    }

    public World(WorldConfiguration config) {
        this(config, System.currentTimeMillis());
    }

    public World(WorldConfiguration config, long seed) {
        this(config, seed, false);
    }

    /**
     * Create a headless world (no MmsAPI / mesh pipeline / OpenGL) for an authoritative
     * server. Block data, generation, water, and feature population work; rendering does not.
     * The server drives chunk loading via {@code getChunkAt} (no {@code chunkManager}).
     */
    public static World createHeadless(WorldConfiguration config, long seed) {
        return new World(config, seed, true);
    }

    /**
     * Create a client render-view world: a fully rendered {@code World} (mesh pipeline, GL,
     * chunk manager) that generates <b>no</b> terrain and runs <b>no</b> authoritative
     * simulation. Every chunk arrives from the server via {@link #installNetworkChunk}; blocks,
     * entities, water, furnaces, and time are all server-authoritative. {@code GameLoop} routes
     * such a world through {@link #updateClient} instead of {@link #update}, and it carries no
     * {@code SaveService} (never persists locally). The seed is still used to construct the
     * terrain system (cheap, deterministic) but it is never invoked because generation is off.
     */
    public static World createClientView(WorldConfiguration config, long seed) {
        World w = new World(config, seed, false); // full rendering pipeline, no testMode
        w.renderOnly = true;
        w.chunkStore.setTerrainGenerationEnabled(false);
        return w;
    }

    /**
     * Protected constructor for testing that bypasses MmsAPI initialization.
     * WARNING: Only use this for unit tests that don't require rendering!
     * This constructor is protected to allow test subclasses in test packages.
     *
     * @param config World configuration
     * @param seed World generation seed
     * @param testMode If true, skips MmsAPI/rendering initialization (for tests only)
     */
    protected World(WorldConfiguration config, long seed, boolean testMode) {
        this.config = config;

        // In production runs, align the world config with the latest persisted
        // user settings before any subsystem reads from it. Tests construct
        // their own configs and skip the singleton.
        if (!testMode) {
            try {
                com.stonebreak.config.Settings s = com.stonebreak.config.Settings.getInstance();
                config.setRenderDistance(s.getRenderDistance());
                config.setLodRange(s.getLodDistance());
                config.setLodEnabled(s.getLodEnabled());
            } catch (Exception ignored) {
                // Settings singleton unavailable (e.g. very early bootstrap) — use config defaults.
            }
        }

        this.terrainSystem = new TerrainGenerationSystem(seed);
        this.snowLayerManager = new SnowLayerManager();
        // Per-world furnace registry (see getFurnaceRegistry). The smelting manager comes
        // from the Game singleton when available; in bare unit tests it is null and the
        // registry's tick loop no-ops.
        com.stonebreak.crafting.SmeltingManager smelting = null;
        try {
            Game g = Game.getInstance();
            if (g != null) {
                smelting = g.getSmeltingManager();
            }
        } catch (Exception ignored) {
            // very early bootstrap / tests
        }
        this.furnaceRegistry = new com.stonebreak.blocks.furnace.FurnaceStateRegistry(smelting);
        // Gameplay snow mutations (not putRaw hydration): mark the chunk save-dirty so the
        // layer counts persist (v3 save format), and forward to the server snow replication
        // sink when installed (headless server world only).
        this.snowLayerManager.setMutationListener((x, y, z, layers) -> {
            var chunk = getChunkIfLoaded(Math.floorDiv(x, com.stonebreak.world.operations.WorldConfiguration.CHUNK_SIZE),
                                         Math.floorDiv(z, com.stonebreak.world.operations.WorldConfiguration.CHUNK_SIZE));
            if (chunk != null) {
                chunk.markDirty();
            }
            ServerSnowMutationCallback sink = serverSnowCallback;
            if (sink != null) {
                sink.onServerSnowChange(x, y, z, layers);
            }
        });

        // Initialize modular components
        this.errorReporter = new ChunkErrorReporter();

        if (testMode) {
            // Test mode: Skip MmsAPI and rendering-related initialization
            this.meshPipeline = null;
            System.out.println("[TEST MODE] Creating World without MmsAPI/rendering systems");
        } else {
            // Normal mode: Create MMS mesh pipeline using MmsAPI
            // MmsAPI is initialized in Game.initCoreComponents() before any World is created
            if (!MmsAPI.isInitialized()) {
                throw new IllegalStateException("MmsAPI must be initialized before creating World");
            }
            this.meshPipeline = MmsAPI.getInstance().createMeshPipeline(this, config, errorReporter);
        }

        // Create FeatureQueue for multi-chunk features
        this.featureQueue = new com.stonebreak.world.generation.features.FeatureQueue();

        // Always create chunk store - tests may need chunk loading functionality
        // In test mode, meshPipeline is null but WorldChunkStore handles this gracefully
        this.chunkStore = new WorldChunkStore(terrainSystem, config, meshPipeline, this, featureQueue);

        if (testMode) {
            // Test mode: Minimal initialization for save/load testing
            this.neighborCoordinator = null;
            this.waterSim = new WaterSim(new WorldFlowWorld(this));
            this.chunkManager = null;
            System.out.println("[TEST MODE] World created with seed: " + terrainSystem.getSeed() + " (rendering disabled)");
        } else {
            // Normal mode: Full initialization
            // Create CCO neighbor coordinator with WorldChunkStore as ChunkProvider
            this.neighborCoordinator = new CcoNeighborCoordinator(new CcoNeighborCoordinator.ChunkProvider() {
                @Override
                public Chunk getChunk(int chunkX, int chunkZ) {
                    return chunkStore.getChunk(chunkX, chunkZ);
                }

                @Override
                public void ensureChunkExists(int chunkX, int chunkZ) {
                    chunkStore.ensureChunkExists(chunkX, chunkZ);
                }
            }, config);

            this.waterSim = new WaterSim(new WorldFlowWorld(this));
            this.chunkManager = new ChunkManager(this, config.getRenderDistance());

            System.out.println("Creating world with seed: " + terrainSystem.getSeed() + ", using " + config.getChunkBuildThreads() + " mesh builder threads.");
        }

        // Chunk listeners (wired for BOTH the headless server world and rendered worlds).
        // Water simulation load runs only on authoritative worlds (a render-only client
        // receives water via streamed chunks/block changes). The furnace registry is now
        // PER-WORLD, so its chunk hooks run everywhere — on a client they hydrate the
        // display registry from streamed chunk block-states. Mesh-seam rebuilds run only
        // where there's a mesh pipeline.
        this.chunkStore.setChunkListeners(chunk -> {
            if (!renderOnly) {
                waterSim.onChunkLoaded(chunk);
            }
            if (furnaceRegistry != null) {
                furnaceRegistry.onChunkLoaded(chunk);
            }
            animatedBlockRegistry.onChunkLoaded(chunk);
            if (meshPipeline != null) {
                int cx = chunk.getX();
                int cz = chunk.getZ();
                markMeshedNeighborDirty(cx - 1, cz);
                markMeshedNeighborDirty(cx + 1, cz);
                markMeshedNeighborDirty(cx, cz - 1);
                markMeshedNeighborDirty(cx, cz + 1);
            }
        }, chunk -> {
            if (furnaceRegistry != null) {
                furnaceRegistry.onChunkUnloaded(chunk);
            }
            animatedBlockRegistry.onChunkUnloaded(chunk);
            // Water state is chunk-owned (ChunkWaterLayer) and leaves with the chunk;
            // the sim just drops its pending queue entries. Snow layers remain a
            // world-global map and must still purge everywhere (render-only clients
            // included) or they grow unbounded as streamed chunks come and go.
            waterSim.onChunkUnloaded(chunk);
            snowLayerManager.onChunkUnloaded(chunk.getChunkX(), chunk.getChunkZ());
        });
    }
    
    /**
     * Updates loading progress during world generation.
     */
    private void updateLoadingProgress(String stageName) {
        Game game = Game.getInstance();
        if (game != null && game.getLoadingScreen() != null && game.getLoadingScreen().isVisible()) {
            game.getLoadingScreen().updateProgress(stageName);
        }
    }
    
    
    public void update(com.stonebreak.rendering.Renderer renderer) {
        if (meshPipeline == null) return; // Test mode - skip rendering updates

        waterSim.tick(Game.getDeltaTime());
        com.stonebreak.blocks.furnace.FurnaceStateRegistry fr = furnaceRegistryOrNull();
        if (fr != null) fr.tick(this, Game.getDeltaTime());
        meshPipeline.requeueFailedChunks();
        if (chunkManager != null) {
            chunkManager.update(Game.getPlayer());
        }

        // Process deferred feature population (breaks recursive generation cycles)
        if (chunkStore != null) {
            chunkStore.processPendingFeaturePopulation();
        }

        meshPipeline.processChunkMeshBuildRequests(this);
    }

    /**
     * Authoritative simulation step, independent of rendering. Runs the parts of
     * {@link #update} that mutate world state — water flow, furnace smelting, deferred
     * feature population — but none of the mesh/GL work. Used by the headless server world
     * ({@code ServerLevel.tick}), where {@code meshPipeline == null} and {@link #update}
     * is a no-op. Safe to call with no render infrastructure.
     */
    public void updateSimulation(float deltaTime) {
        waterSim.tick(deltaTime);
        com.stonebreak.blocks.furnace.FurnaceStateRegistry fr = furnaceRegistryOrNull();
        if (fr != null) fr.tick(this, deltaTime);
        if (chunkStore != null) {
            chunkStore.processPendingFeaturePopulation();
        }
    }

    /**
     * Render-only client update, run by {@code GameLoop} on a {@link #createClientView} world.
     * Mirrors {@link #update} but drops every authoritative-sim step — no water flow, no furnace
     * smelting, no feature population — because the server owns all of that and pushes results
     * via streamed chunks and block changes. It keeps only the render-side work: requeue failed
     * meshes, stream chunks in/out around the local player ({@code chunkManager}), and build the
     * pending chunk meshes. Terrain generation is disabled on this world, so the chunk manager's
     * "load" produces empty placeholders that {@link #installNetworkChunk} then fills.
     */
    public void updateClient(com.stonebreak.rendering.Renderer renderer) {
        if (meshPipeline == null) return; // No rendering infrastructure — nothing to do.

        // Deliberately NO chunkManager.update here: on a render-only world it calls
        // getOrCreateChunk around the player and, with terrain generation disabled, manufactures
        // empty all-air placeholder chunks the server never streams — their empty meshes get
        // treated as failed builds and spam the retry path. The client only meshes chunks the
        // server installs (installNetworkChunk schedules their build directly); we just pump the
        // build queue. Distant streamed chunks unload via unloadClientChunksOutsideView
        // below, so client memory stays bounded to the keep radius.
        meshPipeline.requeueFailedChunks();
        meshPipeline.processChunkMeshBuildRequests(this);
        unloadClientChunksOutsideView();

        // FastLOD ring tick. On a full world ChunkManager.update drives this,
        // but render-only worlds skip the chunk manager entirely (chunks
        // stream from the server), so without this call the LOD manager is
        // created by the render pass yet never schedules a single node —
        // distant terrain simply never appears. The sampler reads the local
        // deterministic TerrainGenerationSystem (seeded from the server's
        // WelcomeS2C world seed), so client-side LOD matches server terrain
        // without any chunk streaming. Runs on the same logic-thread executor
        // that ticks full-world updateRing — threading contract unchanged.
        var lodPlayer = Game.getPlayer();
        if (lodPlayer != null && fastLodManager != null) {
            Vector3f lodPos = lodPlayer.getPosition();
            fastLodManager.updateRing(
                    (int) Math.floor(lodPos.x / WorldConfiguration.CHUNK_SIZE),
                    (int) Math.floor(lodPos.z / WorldConfiguration.CHUNK_SIZE));
        }
    }

    /**
     * Chebyshev radius (chunks) a client retains around the player before unloading:
     * the render distance plus a 2-chunk margin. The server streams within the
     * player's reported view distance and FORGETS a player's chunks beyond this SAME
     * radius (view + 2, see {@code ServerChunkHandler}) so they re-stream on return —
     * the two must stay in lockstep or a returning player gets holes. Tracks the
     * render-distance setting live (config.renderDistance is volatile and updated by
     * the settings Apply path).
     */
    public int clientKeepRadius() {
        return config.getRenderDistance() + 2;
    }

    // Last position/radius the client unload sweep ran for. The sweep only does work when the
    // player crosses a chunk boundary or the keep radius shrinks/grows (settings Apply) —
    // chunks the server streams in are always within the keep radius, so a stationary player
    // can never accumulate out-of-range chunks between crossings.
    private int lastUnloadSweepCx = Integer.MIN_VALUE;
    private int lastUnloadSweepCz = Integer.MIN_VALUE;
    private int lastUnloadSweepKeepRadius = -1;

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
        int keepRadius = clientKeepRadius();
        if (pcx == lastUnloadSweepCx && pcz == lastUnloadSweepCz && keepRadius == lastUnloadSweepKeepRadius) {
            return;
        }
        lastUnloadSweepCx = pcx;
        lastUnloadSweepCz = pcz;
        lastUnloadSweepKeepRadius = keepRadius;
        chunkStore.unloadChunksOutside(pcx, pcz, keepRadius);
    }

    public void updateMainThread() {
        if (meshPipeline == null) return; // Test mode - skip rendering updates

        meshPipeline.applyPendingGLUpdates();
        meshPipeline.processGpuCleanupQueue();
    }

    public void processGpuCleanupQueue() {
        if (meshPipeline == null) return; // Test mode - skip rendering updates

        meshPipeline.processGpuCleanupQueue();
    }
    public void ensureChunkIsReadyForRender(int cx, int cz) {
        if (meshPipeline == null || neighborCoordinator == null) return; // Test mode - no rendering

        Chunk chunk = chunkStore.getChunk(cx, cz);

        if (chunk == null) {
            chunk = getChunkAt(cx, cz);
            if (chunk == null) {
                return;
            }
        }

        // Features are now always populated during chunk generation - no need to check

        boolean isMeshReady = chunk.isMeshGenerated() && chunk.isDataReadyForGL();
        boolean isMeshGenerating = chunk.isMeshDataGenerationScheduledOrInProgress();

        // CRITICAL FIX: If chunk has features but no mesh and isn't generating, force retry
        // This handles cases where mesh generation silently failed or was never attempted
        if (chunk.areFeaturesPopulated() && !isMeshReady && !isMeshGenerating) {
            // Force reset mesh state to allow retry
            resetMeshGenerationState(chunk);
            meshPipeline.scheduleConditionalMeshBuild(chunk);
        } else if (!isMeshReady) {
            meshPipeline.scheduleConditionalMeshBuild(chunk);
        }

        neighborCoordinator.ensureNeighborsReadyForRender(cx, cz, meshPipeline::scheduleConditionalMeshBuild);
    }
    
    /**
     * Gets the chunk at the specified position.
     * If the chunk doesn't exist, it will be generated.
     *
     * <p>Reserved for the chunk-loading pipeline (ChunkManager, world
     * generation, network sync). Runtime queries from water, mob AI, trees,
     * etc. must use {@link #getChunkIfLoaded} instead — generating a chunk as
     * a side effect of a block read produces orphaned chunks outside the
     * render band that the manager then has to unload, causing load/unload
     * churn.
     */
    public Chunk getChunkAt(int x, int z) {
        if (chunkStore == null) return null; // Test mode - no chunk store

        return chunkStore.getOrCreateChunk(x, z);
    }

    /**
     * Gets the chunk at the specified position without ever generating it.
     * Returns {@code null} when the chunk is not currently resident.
     *
     * <p>This is the correct accessor for any runtime query (block reads,
     * water flow, mob AI) that must not trigger chunk generation.
     */
    public Chunk getChunkIfLoaded(int x, int z) {
        if (chunkStore == null) return null; // Test mode - no chunk store

        return chunkStore.getChunk(x, z);
    }

    /**
     * Checks if a chunk exists at the specified position.
     */
    public boolean hasChunkAt(int x, int z) {
        if (chunkStore == null) return false; // Test mode - no chunk store

        return chunkStore.hasChunk(x, z);
    }

    /**
     * True when the chunk is resident AND has a GPU mesh (i.e. it has been filled with real
     * data and rendered). On a client render world this distinguishes a fully streamed chunk
     * from an empty, not-yet-filled placeholder — used to stop the player falling through
     * terrain that hasn't arrived yet.
     */
    public boolean isChunkRenderableAt(int chunkX, int chunkZ) {
        Chunk c = getChunkIfLoaded(chunkX, chunkZ);
        return c != null && c.getMmsRenderableHandle() != null;
    }

    /**
     * Gets the block type at the specified world position.
     */
    public BlockType getBlockAt(int x, int y, int z) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return BlockType.AIR;
        }

        int chunkX = Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE);

        Chunk chunk = getChunkIfLoaded(chunkX, chunkZ);

        if (chunk == null) {
            return BlockType.AIR;
        }

        int localX = Math.floorMod(x, WorldConfiguration.CHUNK_SIZE);
        int localZ = Math.floorMod(z, WorldConfiguration.CHUNK_SIZE);

        return chunk.getBlock(localX, y, localZ);
    }

    /**
     * Returns the SBO state name at the given world position, or
     * {@code null} if the block carries no non-default state (1.3+).
     */
    public String getBlockStateAt(int x, int y, int z) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) return null;
        int chunkX = Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE);
        Chunk chunk = getChunkIfLoaded(chunkX, chunkZ);
        if (chunk == null) return null;
        int localX = Math.floorMod(x, WorldConfiguration.CHUNK_SIZE);
        int localZ = Math.floorMod(z, WorldConfiguration.CHUNK_SIZE);
        return chunk.getBlockState(localX, y, localZ);
    }

    /**
     * Sets the SBO state name for a block at the given world position. Pass
     * {@code null} to clear (1.3+). No-op when the chunk isn't loaded.
     */
    public void setBlockStateAt(int x, int y, int z, String state) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) return;
        int chunkX = Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE);
        Chunk chunk = getChunkIfLoaded(chunkX, chunkZ);
        if (chunk == null) return;
        int localX = Math.floorMod(x, WorldConfiguration.CHUNK_SIZE);
        int localZ = Math.floorMod(z, WorldConfiguration.CHUNK_SIZE);
        chunk.setBlockState(localX, y, localZ, state);
    }

    // ===== Water state (chunk-owned water layer) =====

    /**
     * Water flow value at a world position, read from the chunk's water layer:
     * 0 = source, 1-7 = flowing level, {@link com.stonebreak.world.chunk.ChunkWaterLayer#FALLING}
     * (8) = falling, -1 = not water or chunk not loaded.
     */
    public int getWaterLevelAt(int x, int y, int z) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return -1;
        }
        Chunk chunk = getChunkIfLoaded(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
                                       Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE));
        if (chunk == null) {
            return -1;
        }
        int localX = Math.floorMod(x, WorldConfiguration.CHUNK_SIZE);
        int localZ = Math.floorMod(z, WorldConfiguration.CHUNK_SIZE);
        if (chunk.getBlock(localX, y, localZ) != BlockType.WATER) {
            return -1;
        }
        return chunk.getWaterLayer().get(localX, y, localZ);
    }

    /** True when the block is WATER and its water-layer entry is absent (level 0). */
    public boolean isWaterSourceAt(int x, int y, int z) {
        return getWaterLevelAt(x, y, z) == com.stonebreak.world.chunk.ChunkWaterLayer.SOURCE;
    }

    /**
     * Water cell state as a {@link com.stonebreak.blocks.waterSystem.WaterBlock},
     * or {@code null} when the position is not water. Falling cells report
     * level 0 (full strength) with the falling flag set.
     */
    public com.stonebreak.blocks.waterSystem.WaterBlock getWaterStateAt(int x, int y, int z) {
        int value = getWaterLevelAt(x, y, z);
        if (value < 0) {
            return null;
        }
        if (value == com.stonebreak.world.chunk.ChunkWaterLayer.FALLING) {
            return com.stonebreak.blocks.waterSystem.WaterBlock.falling(0);
        }
        return new com.stonebreak.blocks.waterSystem.WaterBlock(value, false);
    }

    /**
     * Checks if the specified world position is underwater (contains a water block).
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @return true if the position contains water, false otherwise
     */
    public boolean isPositionUnderwater(int x, int y, int z) {
        BlockType block = getBlockAt(x, y, z);
        return block == BlockType.WATER;
    }
    
    /**
     * Sets the block type at the specified world position.
     * @return true if the block was successfully set, false otherwise (e.g., out of bounds).
     */
    public boolean setBlockAt(int x, int y, int z, BlockType blockType) {
        return setBlockAt(x, y, z, blockType, false);
    }

    /**
     * Sets the block type at the specified world position with priority-based mesh regeneration.
     * @param isPlayerModification If true, uses high priority for instant visual feedback (1-frame latency)
     * @return true if the block was successfully set, false otherwise (e.g., out of bounds).
     */
    public boolean setBlockAt(int x, int y, int z, BlockType blockType, boolean isPlayerModification) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return false;
        }

        int chunkX = Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE);

        Chunk chunk = getChunkIfLoaded(chunkX, chunkZ);
        if (chunk == null) {
            // Caller is editing a chunk that isn't currently in the chunk store
            // (e.g. async generation in flight, multiplayer client edit in an
            // area the host hasn't loaded). Drop the edit instead of NPE'ing.
            return false;
        }

        int localX = Math.floorMod(x, WorldConfiguration.CHUNK_SIZE);
        int localZ = Math.floorMod(z, WorldConfiguration.CHUNK_SIZE);

        BlockType previous = chunk.getBlock(localX, y, localZ);
        if (previous == blockType) {
            return true;
        }

        chunk.setBlock(localX, y, localZ, blockType);

        if (meshPipeline != null && neighborCoordinator != null) {
            if (isPlayerModification) {
                // PRIORITY PATH: Player modification - high priority async mesh generation
                // Uses PRIORITY_PLAYER_MODIFICATION to bypass batch limits for 1-frame feedback
                markChunkForMeshRebuildWithScheduling(chunk,
                    c -> meshPipeline.scheduleConditionalMeshBuild(c, MmsMeshPipeline.PRIORITY_PLAYER_MODIFICATION));
                neighborCoordinator.markAndScheduleNeighbors(chunkX, chunkZ, localX, localZ,
                    c -> meshPipeline.scheduleConditionalMeshBuild(c, MmsMeshPipeline.PRIORITY_NEIGHBOR_CHUNK));
            } else {
                // NORMAL PATH: World gen/loading - standard priority async mesh generation
                markChunkForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
                neighborCoordinator.markAndScheduleNeighbors(chunkX, chunkZ, localX, localZ, meshPipeline::scheduleConditionalMeshBuild);
            }
        }

        // Only authoritative worlds simulate flow; a render-only client applying
        // streamed changes must not queue sim work (its layer is display-only).
        if (!renderOnly) {
            waterSim.onBlockChanged(x, y, z, previous, blockType);
        }
        animatedBlockRegistry.onBlockChanged(x, y, z, previous, blockType);

        // Multiplayer: forward locally-driven block edits (player modifications) to the local
        // client, which sends them to the authoritative server as intents. Inbound network
        // changes are applied by the client handlers via setBlockAt(..., false) — the
        // non-broadcasting path — so they never re-enter this hook and loop back out.
        if (isPlayerModification) {
            // Pass `previous` so the server can spawn break drops from the client's view (its
            // own world snapshot may lag — esp. for fast non-host breaks on a busy tick).
            com.stonebreak.network.MultiplayerSession.onLocalBlockChange(x, y, z, blockType, previous);
        }

        return true;
    }

    /** The water flow simulation engine (debug/inspection; state lives in the chunks). */
    public WaterSim getWaterSim() {
        return waterSim;
    }

    /** Per-world save service, or null if this world is not persisted (e.g. a client view). */
    public com.stonebreak.world.save.SaveService getSaveService() {
        return saveService;
    }

    /**
     * True when this is a client render-view world ({@link #createClientView}): generates no
     * terrain, runs no authoritative sim. {@code GameLoop} uses this to choose {@link #updateClient}
     * over {@link #update} and to skip server-owned steps (spawning, time-of-day).
     */
    public boolean isRenderOnly() {
        return renderOnly;
    }

    /**
     * Bind the spawner that initial chunk-gen mob spawning should use for THIS world. The
     * headless server world sets its own so spawns land in the server's EntityManager.
     */
    public void setEntitySpawner(com.stonebreak.mobs.entities.EntitySpawner entitySpawner) {
        this.entitySpawner = entitySpawner;
    }

    /** This world's spawner if bound, else null (caller falls back to the Game singleton). */
    public com.stonebreak.mobs.entities.EntitySpawner getEntitySpawner() {
        return entitySpawner;
    }

    /** Bind the entity manager that saved-chunk entity loading should target for THIS world. */
    public void setEntityManager(com.stonebreak.mobs.entities.EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** This world's entity manager if bound, else null (caller falls back to the Game singleton). */
    public com.stonebreak.mobs.entities.EntityManager getEntityManager() {
        return entityManager;
    }

    /** Bind this world's save service. Called by {@code SaveService.initialize}. */
    public void setSaveService(com.stonebreak.world.save.SaveService saveService) {
        this.saveService = saveService;
    }

    public com.stonebreak.world.generation.features.FeatureQueue getFeatureQueue() {
        return featureQueue;
    }
    
    
    
    public BiomeType getBiomeAt(int x, int z) {
        return terrainSystem.getBiomeAt(x, z);
    }

    public int getBaseHeightAt(int x, int z) {
        return terrainSystem.getBaseHeightAt(x, z);
    }

    public int getShapedHeightAt(int x, int z) {
        return terrainSystem.getShapedHeightAt(x, z);
    }

    public int getFinalTerrainHeightAt(int x, int z) {
        return terrainSystem.getFinalTerrainHeightAt(x, z);
    }

    public java.util.concurrent.CompletableFuture<Void> awaitPendingChunkLoads() {
        return chunkStore != null ? chunkStore.awaitPendingLoads() : java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /**
     * Gets a cached chunk position for coordinate lookup.
     */
    public ChunkPosition getCachedChunkPosition(int x, int z) {
        return chunkStore.getCachedChunkPosition(x, z);
    }
    

    

    /**
     * Visits every resident chunk around the specified position within render distance.
     * This method performs side effects:
     * - Ensures border chunks exist for neighbor meshing
     *
     * Use this method when preparing chunks for rendering. Replaces the old
     * {@code getChunksAroundPlayer}, which materialized a fresh HashMap of every in-range
     * chunk per render frame.
     */
    public void forEachChunkAroundPlayer(int playerChunkX, int playerChunkZ, Consumer<Chunk> action) {
        chunkStore.forEachChunkInRenderDistance(playerChunkX, playerChunkZ, action);

        // Ensure border chunks exist for meshing purposes (triggers generation cascade).
        // Skip on a render-only client world: it generates no terrain, so this would only
        // manufacture empty placeholder chunks that then fail meshing.
        if (neighborCoordinator != null && !renderOnly) {
            neighborCoordinator.ensureBorderChunksExist(playerChunkX, playerChunkZ);
        }
    }

    /**
     * Get all dirty chunks that need to be saved.
     * @return List of chunks that have been modified and need saving
     */
    public List<Chunk> getDirtyChunks() {
        if (chunkStore == null) return new java.util.ArrayList<>(); // Test mode - no chunk store

        return chunkStore.getDirtyChunks();
    }
    
    /**
     * Unloads a chunk at a specific position, cleaning up its resources.
     * This is now called by the ChunkLoader.
     */
    public void unloadChunk(int chunkX, int chunkZ) {
        chunkStore.unloadChunk(chunkX, chunkZ);
    }
    /**
     * Cleans up resources when the game exits.
     */
    public void cleanup() {
        if (chunkManager != null) {
            chunkManager.shutdown();
        }

        if (fastLodManager != null) {
            fastLodManager.shutdown();
            final com.stonebreak.world.fastlod.FastLodManager lod = fastLodManager;
            com.stonebreak.core.Game.getInstance().runOnMainThread(lod::applyGLUpdates);
        }

        if (meshPipeline != null) {
            meshPipeline.shutdown();
        }
        chunkStore.cleanup();
        // Deferred AFTER chunkStore.cleanup() so anything it queued is included in the
        // final main-thread drain (nothing ticks this pipeline's queue once the world is
        // swapped out).
        if (meshPipeline != null) {
            final MmsMeshPipeline mp = meshPipeline;
            com.stonebreak.core.Game.getInstance().runOnMainThread(mp::processGpuCleanupQueue);
        }
    }

    /**
     * Constructs the Fast LOD manager the first time the render thread hands
     * us a texture atlas. Opens a persistent SQLite cache under the active
     * world's save directory when one is available; otherwise runs without
     * persistence. Idempotent; safe to call each frame.
     */
    public void ensureFastLodManager(com.stonebreak.rendering.textures.BlockTextureArray textureArray) {
        if (fastLodManager != null || textureArray == null || terrainSystem == null) return;
        synchronized (this) {
            if (fastLodManager != null) return;
            FastLodStore store = openFastLodStoreIfPossible();
            fastLodManager = new FastLodManager(config, terrainSystem, textureArray, store);
        }
    }

    private static FastLodStore openFastLodStoreIfPossible() {
        // Resolves the save directory without coupling World to how save state
        // is plumbed. Any failure (no save path, SQLite driver missing) falls
        // through to pure in-memory LOD.
        try {
            String worldPath = null;
            com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
            com.stonebreak.world.save.SaveService svc = (game != null) ? game.getSaveService() : null;
            if (svc != null) {
                worldPath = svc.getWorldPath();
            }
            if (worldPath == null || worldPath.isEmpty()) {
                // Two-world model: the client RENDER world carries no
                // SaveService — the authoritative one lives on the co-located
                // integrated server (singleplayer + LAN host). Only the render
                // world ever opens a FastLOD store (the headless server world
                // is never rendered), so there is no double-open on the file.
                // Remote-join clients have no integrated server and correctly
                // fall through to in-memory LOD.
                var server = com.stonebreak.network.MultiplayerSession.getServer();
                var ctx = (server != null) ? server.worldContext() : null;
                var level = (ctx != null) ? ctx.serverLevel() : null;
                var save = (level != null) ? level.saveService() : null;
                if (save != null) {
                    worldPath = save.getWorldPath();
                }
            }
            if (worldPath == null || worldPath.isEmpty()) return null;
            Path dbPath = Paths.get(worldPath, "fastlod", "cache.sqlite");
            return FastLodStore.open(dbPath);
        } catch (Exception e) {
            System.err.println("[World] FastLod store setup failed: " + e.getMessage());
            return null;
        }
    }

    public FastLodManager getFastLodManager() {
        return fastLodManager;
    }

    public WorldConfiguration getConfig() {
        return config;
    }

    /**
     * Clears world data for switching between worlds without shutting down critical systems.
     * This preserves thread pools and rendering systems while clearing chunks, caches, and queues.
     */
    public void clearWorldData() {
        // Clear chunks and caches without shutting down thread pools
        if (chunkStore != null) {
            chunkStore.cleanup();
        }

        // Shut down the Fast LOD manager so its world-specific SQLite cache is
        // closed. The store points at worlds/<name>/fastlod/cache.sqlite, so
        // leaving it open here would keep the .sqlite/.sqlite-wal/.sqlite-shm
        // files locked and block a later world deletion. Runs on the main/GL
        // thread (clearWorldData is invoked from the quit-to-menu path), so we
        // can drain the LOD GPU cleanup queue inline rather than deferring it.
        if (fastLodManager != null) {
            fastLodManager.shutdown();
            fastLodManager.applyGLUpdates();
            fastLodManager = null;
        }

        // Process any pending GPU cleanup without shutting down the pipeline
        if (meshPipeline != null) {
            meshPipeline.processGpuCleanupQueue();
        }

        // Reset spawn position to default for world isolation
        spawnPosition.set(0, 100, 0);

        // Clear any additional world state that may persist between worlds
        // Note: TerrainGenerationSystem seed cannot be changed, so fresh World instances
        // should be used for complete isolation instead

        System.out.println("World data cleared for world switching");
    }

    /**
     * Returns the total number of loaded chunks.
     * This is used for debugging purposes.
     */
    public int getLoadedChunkCount() {
        return chunkStore.getLoadedChunkCount();
    }

    /**
     * Returns the number of dirty chunks currently protected from unloading.
     * This is used for monitoring the dirty chunk protection system.
     */
    public int getDirtyChunkCount() {
        return chunkStore.getDirtyChunks().size();
    }

    /**
     * Returns all currently loaded chunks.
     * This is used for diagnostics and debugging.
     */
    public Collection<Chunk> getAllChunks() {
        return chunkStore.getAllChunks();
    }

    /**
     * Returns the positions of every chunk currently resident in the chunk store.
     * Unlike {@code ChunkManager}'s tracked set, this includes chunks created as a
     * side effect of generation (trees crossing chunk borders), water flow, and
     * mob AI via {@link #getChunkAt}. The chunk manager uses this to unload
     * orphaned chunks that it never explicitly loaded.
     */
    public Set<ChunkPosition> getLoadedChunkPositions() {
        return chunkStore.getAllChunkPositions();
    }

    /**
     * Returns the number of chunks pending mesh build.
     * This is used for debugging purposes.
     */
    public int getPendingMeshBuildCount() {
        return meshPipeline != null ? meshPipeline.getPendingMeshBuildCount() : 0;
    }

    /**
     * Returns the number of chunks pending GL upload.
     * This is used for debugging purposes.
     */
    public int getPendingGLUploadCount() {
        return meshPipeline != null ? meshPipeline.getPendingGLUploadCount() : 0;
    }
    
    /**
     * Gets the snow layer manager for this world
     */
    public SnowLayerManager getSnowLayerManager() {
        return snowLayerManager;
    }
    
    
    /**
     * Gets the snow layer count at a specific position
     */
    public int getSnowLayers(int x, int y, int z) {
        return snowLayerManager.getSnowLayers(x, y, z);
    }
    
    /**
     * Gets the visual/collision height of a snow block at a specific position
     */
    public float getSnowHeight(int x, int y, int z) {
        BlockType block = getBlockAt(x, y, z);
        if (block == BlockType.SNOW) {
            return snowLayerManager.getSnowHeight(x, y, z);
        }
        return block.getVisualHeight();
    }
    
    /**
     * Triggers a chunk mesh rebuild for the chunk containing the given world coordinates.
     * Use this when block visual properties change without changing the block type.
     */
    public void triggerChunkRebuild(int worldX, int worldY, int worldZ) {
        if (meshPipeline == null) return; // Test mode - no rendering

        int chunkX = Math.floorDiv(worldX, WorldConfiguration.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(worldZ, WorldConfiguration.CHUNK_SIZE);

        Chunk chunk = chunkStore.getChunk(chunkX, chunkZ);
        if (chunk != null) {
            markChunkForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
        }
    }

    /**
     * Overwrite a chunk's block contents with a payload received over the
     * network and trigger a mesh rebuild.
     *
     * <p>Used by the multiplayer chunk synchronizer to push the host's
     * authoritative chunk state onto a joining client so any pre-connection
     * modifications (player builds, etc.) are reflected exactly. Returns
     * silently if the world hasn't loaded enough infrastructure yet.
     */
    public boolean installNetworkChunk(int chunkX, int chunkZ, byte[] payload) {
        return installNetworkChunk(chunkX, chunkZ, payload, null);
    }

    /**
     * As {@link #installNetworkChunk(int, int, byte[])}, additionally applying the game-side
     * chunk metadata blob (snow layers, per-block SBO states — {@code GameChunkMetaCodec})
     * after the block install. Null/empty {@code metaPayload} skips the metadata step.
     *
     * @return false when the payload could not be decoded/installed — the caller should
     *         request a chunk resync, since the server has marked this chunk as sent.
     */
    public boolean installNetworkChunk(int chunkX, int chunkZ, byte[] payload, byte[] metaPayload) {
        if (chunkStore == null) return false;
        // Synchronous slot creation — the render-only client has no disk-load or terrain-gen,
        // so the chunk arrives ready in the same call. No async machinery, no race conditions,
        // no chance of dropping the payload because the slot "isn't ready yet".
        Chunk chunk = chunkStore.createOrGetNetworkChunkSlot(chunkX, chunkZ);
        // Decode into a detached paletted storage, then install with one section-level
        // copy. The old per-block chunk.setBlock path paid dirty-flag churn, state-map
        // removals, and an incremental heightmap update per block — and the heightmap
        // was recomputed wholesale below anyway. This keeps install cost low enough
        // for the lifted local-player streaming budget (ServerChunkHandler).
        com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage decoded =
            com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage.createEmpty(
                WorldConfiguration.CHUNK_SIZE, WorldConfiguration.WORLD_HEIGHT,
                WorldConfiguration.CHUNK_SIZE, com.stonebreak.blocks.BlockType.AIR);
        try {
            com.openmason.engine.net.protocol.codec.VoxelChunkCodec.decodeInto(
                    payload,
                    new com.stonebreak.network.bridge.StorageBlockSetter(decoded),
                    com.stonebreak.network.bridge.GameBlockTypeResolver.INSTANCE);
        } catch (Exception e) {
            System.err.println("[NETWORK] Failed to decode chunk (" + chunkX + "," + chunkZ + "): " + e.getMessage());
            return false; // caller requests a resync — the server thinks this chunk was sent
        }
        chunk.replaceAllBlocks(decoded);
        // The bulk block install bypasses Chunk.setBlock, so stale water-layer entries from a
        // previous stream of this chunk would survive it — clear unconditionally; the meta
        // below re-hydrates the authoritative set (absence = source, per the layer invariant).
        chunk.getWaterLayer().clear();

        // Apply streamed chunk metadata: snow layer heights + per-block SBO states + water
        // flow levels. Replaces (not merges) this chunk's previous entries so a re-stream is
        // a clean resync.
        if (metaPayload != null && metaPayload.length > 0) {
            try {
                var meta = com.stonebreak.network.bridge.GameChunkMetaCodec.decode(metaPayload);
                snowLayerManager.onChunkUnloaded(chunkX, chunkZ); // clear stale entries first
                int baseX = chunkX * WorldConfiguration.CHUNK_SIZE;
                int baseZ = chunkZ * WorldConfiguration.CHUNK_SIZE;
                for (var e : meta.snowLayers().entrySet()) {
                    int key = e.getKey();
                    snowLayerManager.putRaw(
                        baseX + com.stonebreak.world.chunk.utils.LocalBlockKey.x(key),
                        com.stonebreak.world.chunk.utils.LocalBlockKey.y(key),
                        baseZ + com.stonebreak.world.chunk.utils.LocalBlockKey.z(key),
                        e.getValue());
                }
                for (var e : meta.blockStates().entrySet()) {
                    int key = e.getKey();
                    chunk.setBlockState(
                        com.stonebreak.world.chunk.utils.LocalBlockKey.x(key),
                        com.stonebreak.world.chunk.utils.LocalBlockKey.y(key),
                        com.stonebreak.world.chunk.utils.LocalBlockKey.z(key),
                        e.getValue());
                }
                for (var e : meta.waterLevels().entrySet()) {
                    int key = e.getKey();
                    chunk.getWaterLayer().set(
                        com.stonebreak.world.chunk.utils.LocalBlockKey.x(key),
                        com.stonebreak.world.chunk.utils.LocalBlockKey.y(key),
                        com.stonebreak.world.chunk.utils.LocalBlockKey.z(key),
                        e.getValue());
                }
                // Hydrate the DISPLAY furnace registry from the states just applied. The
                // chunk-load listener fired at slot creation, BEFORE this meta landed, so
                // without this an idle furnace opens empty on a joiner — and their first
                // slot edit would then overwrite the server's real contents.
                if (!meta.blockStates().isEmpty() && furnaceRegistry != null) {
                    furnaceRegistry.onChunkLoaded(chunk);
                }
                // Same re-hydration for animated blocks (doors): the load-time
                // scan saw an all-air placeholder with no states, so streamed
                // doors were never indexed — and rendered invisible.
                if (!meta.blockStates().isEmpty()) {
                    animatedBlockRegistry.onChunkLoaded(chunk);
                }
            } catch (Exception e) {
                System.err.println("[NETWORK] Failed to decode chunk meta (" + chunkX + "," + chunkZ + "): " + e.getMessage());
            }
        }

        // The chunk was an empty placeholder (all-air heightmap). Now that real blocks are in,
        // rebuild the heightmap so sky-shadow/lighting and the mesher's Y-scan are correct —
        // generated/loaded chunks do this; streamed chunks must too.
        chunk.getHeightMap().recomputeAll(chunk.getOpacityProbe());
        if (meshPipeline != null) {
            markChunkForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
            if (neighborCoordinator != null) {
                // Re-mesh ALL four resident neighbors: any neighbor meshed before this
                // payload landed built its border against an absent or empty chunk
                // (culled/sentinel faces, or all-AIR reads → spurious water sheets).
                // Streaming order is not guaranteed to be west/north-first — movement
                // west or north delivers new chunks on the far side of already-meshed
                // ones — so both edge pairs must be marked. Non-resident neighbors
                // no-op, and the dirty-flag gating keeps this to one rebuild each.
                neighborCoordinator.markAndScheduleNeighbors(chunkX, chunkZ, 0, 0,
                        meshPipeline::scheduleConditionalMeshBuild);
                neighborCoordinator.markAndScheduleNeighbors(chunkX, chunkZ,
                        WorldConfiguration.CHUNK_SIZE - 1, WorldConfiguration.CHUNK_SIZE - 1,
                        meshPipeline::scheduleConditionalMeshBuild);
            }
        }
        return true;
    }

    /**
     * Triggers a mesh rebuild for all loaded chunks.
     * Use this when global visual settings change that affect block rendering.
     * This method requires a player position to determine which chunks are currently loaded.
     */
    public void rebuildAllLoadedChunks(int playerChunkX, int playerChunkZ) {
        if (meshPipeline == null) return; // Test mode - no rendering

        try {
            // Mark all chunks currently loaded around the player for mesh rebuild
            int[] marked = {0};
            forEachChunkAroundPlayer(playerChunkX, playerChunkZ, chunk -> {
                markChunkForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
                marked[0]++;
            });

            System.out.println("Marked " + marked[0] + " chunks for mesh rebuild due to settings change");
        } catch (Exception e) {
            System.err.println("Error rebuilding all chunks: " + e.getMessage());
        }
    }
    
    /**
     * Gets the seed used for world generation
     */
    public long getSeed() {
        return terrainSystem.getSeed();
    }
    
    /**
     * Sets the seed for world generation (used during world loading)
     */
    public void setSeed(long seed) {
        // Note: This method is primarily for save/load compatibility
        // The terrain system seed cannot be changed after construction
        // This will log a warning if attempting to change an existing seed
        if (terrainSystem.getSeed() != seed) {
            System.err.println("Warning: Attempting to set seed " + seed + 
                " but terrain system already has seed " + terrainSystem.getSeed() + 
                ". Seed cannot be changed after world creation.");
        }
    }
    
    /**
     * Gets the world spawn position
     */
    public Vector3f getSpawnPosition() {
        return new Vector3f(spawnPosition);
    }
    
    /**
     * Sets the world spawn position
     */
    public void setSpawnPosition(Vector3f newSpawnPosition) {
        this.spawnPosition.set(newSpawnPosition);
    }
    
    /**
     * Marks a chunk for mesh rebuild using CCO dirty tracker.
     */
    private void markChunkForMeshRebuild(Chunk chunk) {
        chunk.getCcoDirtyTracker().markMeshDirtyOnly();
    }

    /**
     * Marks a chunk for mesh rebuild and schedules it using CCO dirty tracker.
     */
    private void markChunkForMeshRebuildWithScheduling(Chunk chunk, Consumer<Chunk> meshBuildScheduler) {
        markChunkForMeshRebuild(chunk);
        meshBuildScheduler.accept(chunk);
    }

    /**
     * Resets mesh generation state using CCO dirty tracker.
     */
    private void resetMeshGenerationState(Chunk chunk) {
        chunk.getCcoDirtyTracker().markMeshDirtyOnly();
    }

    /**
     * Marks an existing neighbor chunk dirty and schedules a rebuild so its
     * border faces (water seams, sentinel-culled boundaries) recompute with
     * correct data once this chunk is available.
     *
     * <p>Do NOT skip when the neighbor hasn't finished its first mesh build:
     * if the neighbor was scheduled before this chunk loaded, its in-flight
     * build is racing against the sentinel-opaque path in MmsFaceCullingService
     * and may have already baked culled boundary faces. Dropping the signal
     * here leaves those faces missing until the player edits the chunk. The
     * mesh pipeline coalesces duplicate schedule requests, so re-scheduling a
     * pending build is cheap.
     */
    private void markMeshedNeighborDirty(int chunkX, int chunkZ) {
        Chunk neighbor = chunkStore.getChunk(chunkX, chunkZ);
        if (neighbor == null) return;
        neighbor.getCcoDirtyTracker().markMeshDirtyOnly();
        meshPipeline.scheduleConditionalMeshBuild(neighbor);
    }

    /**
     * Sets the world spawn position with coordinates
     */
    public void setSpawnPosition(float x, float y, float z) {
        this.spawnPosition.set(x, y, z);
    }
    
    /**
     * Sets a chunk at the given position (used for world loading)
     */
    public void setChunk(int x, int z, Chunk chunk) {
        chunkStore.setChunk(x, z, chunk);
    }

    /** This world's furnace registry (per-world since the two-world furnace split). */
    private com.stonebreak.blocks.furnace.FurnaceStateRegistry furnaceRegistryOrNull() {
        return furnaceRegistry;
    }

    /**
     * This world's furnace registry. PER-WORLD: the authoritative server world owns the
     * only registry that actually smelts (ticked in {@link #updateSimulation}); each client
     * render world holds a display copy hydrated from streamed chunk states and live
     * {@code BlockStateS2C} echoes. Previously this was a process-global singleton shared
     * between the host's render world and the server world — a host-only asymmetry that
     * remote clients could never match.
     */
    public com.stonebreak.blocks.furnace.FurnaceStateRegistry getFurnaceRegistry() {
        return furnaceRegistry;
    }

    /** This world's index of animated (dynamically rendered) block positions. */
    public com.stonebreak.blocks.anim.AnimatedBlockRegistry getAnimatedBlockRegistry() {
        return animatedBlockRegistry;
    }

    /**
     * Forces a re-mesh of the chunk containing the given world position. Used
     * by per-block-state changes (e.g. a furnace flipping lit↔unlit) where the
     * block ID didn't change but the rendered model variant did.
     * No-op when the chunk isn't loaded.
     */
    public void scheduleChunkRemeshAt(int x, int y, int z) {
        if (meshPipeline == null) return;
        int cx = Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE);
        int cz = Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE);
        Chunk chunk = getChunkIfLoaded(cx, cz);
        if (chunk == null) return;
        chunk.getCcoDirtyTracker().markMeshDirtyOnly();
        meshPipeline.scheduleConditionalMeshBuild(chunk, MmsMeshPipeline.PRIORITY_PLAYER_MODIFICATION);
    }
}
