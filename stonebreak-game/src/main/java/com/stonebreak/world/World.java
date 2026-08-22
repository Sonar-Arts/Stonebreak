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
import com.stonebreak.world.leaves.LeafDecaySystem;
import com.stonebreak.world.leaves.WorldLeafWorld;
import com.stonebreak.world.operations.WorldConfiguration;


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

    /**
     * Mob path searching for this world. Created on first use — a world that never holds a mob
     * (thumbnail renders, tests) never starts the threads — and closed in {@link #cleanup()}.
     */
    private volatile com.stonebreak.mobs.entities.ai.nav.PathfindingService pathfinding;
    private final Object pathfindingLock = new Object();
    /** Set once {@link #cleanup()} has run, so a late caller cannot resurrect a torn-down world's service. */
    private volatile boolean cleanedUp;


    // World spawn position
    private Vector3f spawnPosition = new Vector3f(0, 100, 0);
    
    // Modular components
    private final WorldChunkStore chunkStore;
    private final CcoNeighborCoordinator neighborCoordinator;
    private final MmsMeshPipeline meshPipeline;
    private final ChunkErrorReporter errorReporter;
    private final WaterSim waterSim;
    private final LeafDecaySystem leafDecay;
    private final com.stonebreak.world.generation.features.FeatureQueue featureQueue;

    // Extracted collaborators (see their class docs): mesh scheduling, FastLOD lifecycle,
    // network chunk install and per-tick update sequencing.
    private final ChunkMeshScheduler meshScheduler;
    private final FastLodLifecycle fastLod;
    private final NetworkChunkInstaller networkChunkInstaller;
    private final WorldUpdateOrchestrator updates;

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
     * Authoritative simulation mutations reported for replication. Populated by the integrated
     * server on the HEADLESS world only; every sink stays null on client render worlds.
     */
    private final ServerMutationSinks serverSinks = new ServerMutationSinks();

    /** The replication sinks for this world. See {@link ServerMutationSinks}. */
    public ServerMutationSinks serverSinks() {
        return serverSinks;
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
            ServerMutationSinks.SnowSink sink = serverSinks.snow();
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
            this.leafDecay = new LeafDecaySystem(new WorldLeafWorld(this));
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
            this.leafDecay = new LeafDecaySystem(new WorldLeafWorld(this));
            this.chunkManager = new ChunkManager(this, config.getRenderDistance());

            System.out.println("Creating world with seed: " + terrainSystem.getSeed() + ", using " + config.getChunkBuildThreads() + " mesh builder threads.");
        }

        this.meshScheduler = new ChunkMeshScheduler(meshPipeline, neighborCoordinator, chunkStore);
        this.fastLod = new FastLodLifecycle(config, terrainSystem);
        this.networkChunkInstaller = new NetworkChunkInstaller(
                chunkStore, snowLayerManager, furnaceRegistry, animatedBlockRegistry, meshScheduler);
        this.updates = new WorldUpdateOrchestrator(
                this, waterSim, leafDecay, furnaceRegistry, chunkStore, chunkManager, meshScheduler, fastLod);

        // Chunk listeners (wired for BOTH the headless server world and rendered worlds).
        // Water simulation load runs only on authoritative worlds (a render-only client
        // receives water via streamed chunks/block changes). The furnace registry is now
        // PER-WORLD, so its chunk hooks run everywhere — on a client they hydrate the
        // display registry from streamed chunk block-states. Mesh-seam rebuilds run only
        // where there's a mesh pipeline.
        this.chunkStore.setChunkListeners(chunk -> {
            if (!renderOnly) {
                waterSim.onChunkLoaded(chunk);
                // Leaf-decay rescan resumes collapses interrupted by eviction or
                // quit. Only for chunks that already have their features: a
                // freshly generated chunk has no trees yet at listener time
                // (they populate later), so scanning it would find nothing.
                if (chunk.areFeaturesPopulated()) {
                    leafDecay.onChunkLoaded(chunk.getChunkX(), chunk.getChunkZ());
                }
            }
            if (furnaceRegistry != null) {
                furnaceRegistry.onChunkLoaded(chunk);
            }
            animatedBlockRegistry.onChunkLoaded(chunk);
            meshScheduler.onChunkLoaded(chunk.getX(), chunk.getZ());
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
            leafDecay.onChunkUnloaded(chunk.getChunkX(), chunk.getChunkZ());
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
        updates.update();
    }

    /**
     * Authoritative simulation step, independent of rendering. Runs the parts of
     * {@link #update} that mutate world state — water flow, furnace smelting, deferred
     * feature population — but none of the mesh/GL work. Used by the headless server world
     * ({@code ServerLevel.tick}), where {@code meshPipeline == null} and {@link #update}
     * is a no-op. Safe to call with no render infrastructure.
     */
    public void updateSimulation(float deltaTime) {
        updates.updateSimulation(deltaTime);
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
        updates.updateClient();
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

    public void updateMainThread() {
        if (meshPipeline == null) return; // Test mode - skip rendering updates

        meshScheduler.applyPendingGLUpdates();
        meshScheduler.processGpuCleanupQueue();
    }

    public void processGpuCleanupQueue() {
        meshScheduler.processGpuCleanupQueue();
    }
    public void ensureChunkIsReadyForRender(int cx, int cz) {
        meshScheduler.ensureChunkIsReadyForRender(cx, cz, pos -> getChunkAt(pos[0], pos[1]));
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
        // Either mesh representation counts: legacy per-chunk handle, or a
        // segment in the shared region arenas (region rendering mode).
        return c != null
            && (c.getMmsRenderableHandle() != null || c.getRegionAtlasHandle() != null);
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
        String previous = chunk.getBlockState(localX, y, localZ);
        chunk.setBlockState(localX, y, localZ, state);
        // The state picks which mesh variant a chunk-baked block draws (a lit
        // furnace, a stair's facing), and marking the chunk dirty on its own
        // never schedules anything. Placement writes the state right after the
        // block, so the rebuild has to be queued again or it can race the build
        // already in flight and bake the old variant.
        if (com.stonebreak.blocks.BlockRenderState.affectsMesh(previous, state)) {
            scheduleChunkRemeshAt(x, y, z);
        }
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

        meshScheduler.onBlockChanged(chunk, chunkX, chunkZ, localX, localZ, isPlayerModification);

        // Only authoritative worlds simulate flow; a render-only client applying
        // streamed changes must not queue sim work (its layer is display-only).
        if (!renderOnly) {
            waterSim.onBlockChanged(x, y, z, previous, blockType);
            leafDecay.onBlockChanged(x, y, z, previous, blockType);
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

    /** The leaf-decay simulation engine (debug/inspection). */
    public LeafDecaySystem getLeafDecay() {
        return leafDecay;
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
    
    
    
    /**
     * Gets the continentalness value at the specified world position.
     */
    /**
     * The terrain generator backing this world — the source of every noise-derived sample
     * (biome, climate, the height stages). Queried directly rather than mirrored here, so
     * adding a terrain channel needs no change to this class.
     */
    public TerrainGenerationSystem terrain() {
        return terrainSystem;
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
        // First: cancel in-flight path searches. They read this world's chunks, so they must stop
        // before the chunk store is torn down beneath them.
        cleanedUp = true;
        synchronized (pathfindingLock) {
            if (pathfinding != null) {
                pathfinding.close();
                pathfinding = null;
            }
        }

        if (chunkManager != null) {
            chunkManager.shutdown();
        }

        fastLod.shutdownDeferred();

        meshScheduler.shutdown();
        chunkStore.cleanup();
        // Deferred AFTER chunkStore.cleanup() so anything it queued is included in the
        // final main-thread drain (nothing ticks this pipeline's queue once the world is
        // swapped out).
        //
        // NOTE deliberately NO ChunkRegionRenderer.reset() here: the region renderer is
        // a process-wide singleton, and cleanup() also runs for worlds that never owned
        // the active regions (superseded ClientWorld-Build instances, the headless
        // server world) — a wholesale reset from those would delete regions the ACTIVE
        // world is drawing. The per-chunk cleanup above frees every region segment this
        // world held, and the next rendered frame's beginFrame() prunes emptied regions.
        meshScheduler.deferFinalGpuCleanup();
    }

    /**
     * Constructs the Fast LOD manager the first time the render thread hands
     * us a texture atlas. Opens a persistent SQLite cache under the active
     * world's save directory when one is available; otherwise runs without
     * persistence. Idempotent; safe to call each frame.
     */
    public void ensureFastLodManager(com.stonebreak.rendering.textures.BlockTextureArray textureArray) {
        fastLod.ensure(textureArray);
    }

    public FastLodManager getFastLodManager() {
        return fastLod.get();
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
        // closed (see FastLodLifecycle.shutdownInline).
        fastLod.shutdownInline();

        // Process any pending GPU cleanup without shutting down the pipeline
        meshScheduler.processGpuCleanupQueue();

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
        return meshScheduler.getPendingMeshBuildCount();
    }

    /**
     * Returns the number of chunks pending GL upload.
     * This is used for debugging purposes.
     */
    public int getPendingGLUploadCount() {
        return meshScheduler.getPendingGLUploadCount();
    }
    
    /**
     * Gets the snow layer manager for this world
     */
    public SnowLayerManager getSnowLayerManager() {
        return snowLayerManager;
    }

    /**
     * This world's mob path searching, started on first request.
     *
     * <p>Per world rather than global on purpose: a search reads the chunks of the world it was
     * asked about, and {@link #cleanup()} closes the service before those chunks go away — so a
     * world swap can never leave a worker planning routes through a world that no longer exists.
     *
     * @return the service, or null once this world has been cleaned up
     */
    public com.stonebreak.mobs.entities.ai.nav.PathfindingService pathfinding() {
        com.stonebreak.mobs.entities.ai.nav.PathfindingService service = pathfinding;
        if (service != null) {
            return service;
        }
        synchronized (pathfindingLock) {
            if (pathfinding == null && !cleanedUp) {
                pathfinding = com.stonebreak.mobs.entities.ai.nav.PathfindingService.forWorld(this);
            }
            return pathfinding;
        }
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
        meshScheduler.triggerChunkRebuild(worldX, worldZ);
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
        return networkChunkInstaller.install(chunkX, chunkZ, payload, metaPayload);
    }

    /**
     * Install half of {@link #installNetworkChunk}: swaps pre-decoded storage
     * into the chunk slot and applies metadata + mesh scheduling. MUST run on
     * the main game thread. {@code heights} is the worker-precomputed sky
     * heightmap (see {@code NetworkChunkDecoder.computeSkyHeights}); null falls back to a
     * main-thread rescan.
     */
    public boolean installDecodedNetworkChunk(int chunkX, int chunkZ,
            com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage decoded,
            int[] heights, byte[] metaPayload) {
        return networkChunkInstaller.installDecoded(chunkX, chunkZ, decoded, heights, metaPayload);
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
                meshScheduler.scheduleRebuild(chunk);
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
        meshScheduler.scheduleChunkRemesh(chunk);
    }
}
