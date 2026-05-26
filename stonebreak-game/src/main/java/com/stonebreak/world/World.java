package com.stonebreak.world;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.function.Consumer;

import com.stonebreak.world.chunk.utils.ChunkManager;
import com.stonebreak.world.chunk.utils.ChunkPosition;
import org.joml.Vector3f;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterSystem;
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
    
    // World spawn position
    private Vector3f spawnPosition = new Vector3f(0, 100, 0);
    
    // Modular components
    private final WorldChunkStore chunkStore;
    private final CcoNeighborCoordinator neighborCoordinator;
    private final MmsMeshPipeline meshPipeline;
    private final ChunkErrorReporter errorReporter;
    private final WaterSystem waterSystem;
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
            this.waterSystem = new WaterSystem(this);
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

            this.waterSystem = new WaterSystem(this);
            this.chunkManager = new ChunkManager(this, config.getRenderDistance());

            System.out.println("Creating world with seed: " + terrainSystem.getSeed() + ", using " + config.getChunkBuildThreads() + " mesh builder threads.");
        }

        // Chunk listeners (wired for BOTH the headless server world and rendered worlds). The
        // authoritative water/furnace round-trip runs on every world EXCEPT a render-only client
        // view — there the server owns that state, so firing these would corrupt the shared
        // registry. Mesh-seam rebuilds run only where there's a mesh pipeline.
        this.chunkStore.setChunkListeners(chunk -> {
            if (!renderOnly) {
                waterSystem.onChunkLoaded(chunk);
                com.stonebreak.blocks.furnace.FurnaceStateRegistry fr = furnaceRegistryOrNull();
                if (fr != null) fr.onChunkLoaded(chunk);
            }
            if (meshPipeline != null) {
                int cx = chunk.getX();
                int cz = chunk.getZ();
                markMeshedNeighborDirty(cx - 1, cz);
                markMeshedNeighborDirty(cx + 1, cz);
                markMeshedNeighborDirty(cx, cz - 1);
                markMeshedNeighborDirty(cx, cz + 1);
            }
        }, chunk -> {
            if (!renderOnly) {
                com.stonebreak.blocks.furnace.FurnaceStateRegistry fr = furnaceRegistryOrNull();
                if (fr != null) fr.onChunkUnloaded(chunk);
                waterSystem.onChunkUnloaded(chunk);
            }
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

        waterSystem.tick(Game.getDeltaTime());
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
        waterSystem.tick(deltaTime);
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
        // build queue. (Distant streamed chunks are not yet unloaded — memory grows; add
        // client-side unloading later.)
        meshPipeline.requeueFailedChunks();
        meshPipeline.processChunkMeshBuildRequests(this);
        unloadClientChunksOutsideView();
    }

    /**
     * Chebyshev radius (chunks) a client retains around the player before unloading. The
     * server streams within its view distance (8) and FORGETS a player's chunks beyond this
     * SAME radius so they re-stream on return — so this MUST match
     * {@code ServerChunkHandler.FORGET_DISTANCE_CHUNKS} to avoid holes when revisiting. It is
     * independent of the user's render distance (the server caps streaming at its view distance).
     */
    public static final int CLIENT_KEEP_RADIUS = 10;

    /**
     * Unload streamed chunks that have left the client's keep radius. Render-only worlds never
     * regenerate, so a dropped chunk simply re-streams from the server if the player returns
     * (the server forgets it at the same radius). Bounds the client's memory as it explores;
     * no save (the client never persists).
     */
    private void unloadClientChunksOutsideView() {
        var player = Game.getPlayer();
        if (player == null || chunkStore == null) {
            return;
        }
        Vector3f pos = player.getPosition();
        int pcx = Math.floorDiv((int) Math.floor(pos.x), WorldConfiguration.CHUNK_SIZE);
        int pcz = Math.floorDiv((int) Math.floor(pos.z), WorldConfiguration.CHUNK_SIZE);
        for (ChunkPosition cp : chunkStore.getAllChunkPositions()) {
            int dist = Math.max(Math.abs(cp.getX() - pcx), Math.abs(cp.getZ() - pcz));
            if (dist > CLIENT_KEEP_RADIUS) {
                chunkStore.unloadChunk(cp.getX(), cp.getZ());
            }
        }
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

        waterSystem.onBlockChanged(x, y, z, previous, blockType);

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

    public WaterSystem getWaterSystem() {
        return waterSystem;
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
    public float getContinentalnessAt(int x, int z) {
        return terrainSystem.getContinentalnessAt(x, z);
    }

    public BiomeType getBiomeAt(int x, int z) {
        return terrainSystem.getBiomeAt(x, z);
    }

    public float getMoistureAt(int x, int z) {
        return terrainSystem.getMoistureAt(x, z);
    }

    public float getTemperatureAt(int x, int z) {
        return terrainSystem.getTemperatureAt(x, z);
    }

    public float getErosionAt(int x, int z) {
        return terrainSystem.getErosionAt(x, z);
    }

    public float getPeaksValleysAt(int x, int z) {
        return terrainSystem.getPeaksValleysAt(x, z);
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
     * Returns chunks around the specified position within render distance.
     * This method performs side effects:
     * - Ensures border chunks exist for neighbor meshing
     *
     * Use this method when preparing chunks for rendering.
     */
    public Map<ChunkPosition, Chunk> getChunksAroundPlayer(int playerChunkX, int playerChunkZ) {
        Map<ChunkPosition, Chunk> allChunks = chunkStore.getChunksInRenderDistance(playerChunkX, playerChunkZ);

        // Ensure border chunks exist for meshing purposes (triggers generation cascade).
        // Skip on a render-only client world: it generates no terrain, so this would only
        // manufacture empty placeholder chunks that then fail meshing.
        if (neighborCoordinator != null && !renderOnly) {
            neighborCoordinator.ensureBorderChunksExist(playerChunkX, playerChunkZ);
        }

        return allChunks;
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
            final MmsMeshPipeline mp = meshPipeline;
            com.stonebreak.core.Game.getInstance().runOnMainThread(mp::processGpuCleanupQueue);
        }
        chunkStore.cleanup();
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
        // Resolves the save directory through Game so World stays agnostic of
        // how save state is plumbed. Any failure (no save service, bad path,
        // SQLite driver missing) falls through to pure in-memory LOD.
        try {
            com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
            com.stonebreak.world.save.SaveService svc = (game != null) ? game.getSaveService() : null;
            if (svc == null) return null;
            String worldPath = svc.getWorldPath();
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
    public void installNetworkChunk(int chunkX, int chunkZ, byte[] payload) {
        if (chunkStore == null) return;
        // Synchronous slot creation — the render-only client has no disk-load or terrain-gen,
        // so the chunk arrives ready in the same call. No async machinery, no race conditions,
        // no chance of dropping the payload because the slot "isn't ready yet".
        Chunk chunk = chunkStore.createOrGetNetworkChunkSlot(chunkX, chunkZ);
        try {
            com.openmason.engine.net.protocol.codec.VoxelChunkCodec.decodeInto(
                    payload,
                    new com.stonebreak.network.bridge.GameBlockSetter(chunk),
                    com.stonebreak.network.bridge.GameBlockTypeResolver.INSTANCE);
        } catch (Exception e) {
            System.err.println("[NETWORK] Failed to decode chunk (" + chunkX + "," + chunkZ + "): " + e.getMessage());
            return;
        }
        // The chunk was an empty placeholder (all-air heightmap). Now that real blocks are in,
        // rebuild the heightmap so sky-shadow/lighting and the mesher's Y-scan are correct —
        // generated/loaded chunks do this; streamed chunks must too.
        chunk.getHeightMap().recomputeAll(chunk.getOpacityProbe());
        if (meshPipeline != null) {
            markChunkForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
            if (neighborCoordinator != null) {
                // Single-block (0,0) re-meshes only west+north neighbors. That's intentional and
                // sufficient for streaming: each newly-arriving chunk re-meshes the chunks west
                // and north of it (which are usually already installed); east/south neighbors
                // re-mesh themselves when THEY arrive and reference this chunk. The only stale
                // borders are at the view edge — not visible to the player.
                neighborCoordinator.markAndScheduleNeighbors(chunkX, chunkZ, 0, 0,
                        meshPipeline::scheduleConditionalMeshBuild);
            }
        }
    }

    /**
     * Triggers a mesh rebuild for all loaded chunks.
     * Use this when global visual settings change that affect block rendering.
     * This method requires a player position to determine which chunks are currently loaded.
     */
    public void rebuildAllLoadedChunks(int playerChunkX, int playerChunkZ) {
        if (meshPipeline == null) return; // Test mode - no rendering

        try {
            // Get all chunks currently loaded around the player
            Map<ChunkPosition, Chunk> loadedChunks = getChunksAroundPlayer(playerChunkX, playerChunkZ);

            // Mark all loaded chunks for mesh rebuild
            for (Chunk chunk : loadedChunks.values()) {
                if (chunk != null) {
                    markChunkForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
                }
            }

            System.out.println("Marked " + loadedChunks.size() + " chunks for mesh rebuild due to settings change");
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

    /** Returns the furnace registry, or {@code null} if Game isn't fully initialised yet. */
    private static com.stonebreak.blocks.furnace.FurnaceStateRegistry furnaceRegistryOrNull() {
        Game g = Game.getInstance();
        return g == null ? null : g.getFurnaceRegistry();
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
