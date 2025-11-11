package com.stonebreak.world;

import java.util.Map;
import java.util.List;
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
    
    // World spawn position
    private Vector3f spawnPosition = new Vector3f(0, 100, 0);
    
    // Modular components
    private final WorldChunkStore chunkStore;
    private final CcoNeighborCoordinator neighborCoordinator;
    private final MmsMeshPipeline meshPipeline;
    private final ChunkErrorReporter errorReporter;
    private final WaterSystem waterSystem;

    public World() {
        this(new WorldConfiguration());
    }

    public World(WorldConfiguration config) {
        this(config, System.currentTimeMillis());
    }

    public World(WorldConfiguration config, long seed) {
        this(config, seed, "LEGACY", false);
    }

    /**
     * Constructor with explicit terrain generator type.
     *
     * @param config World configuration
     * @param seed World generation seed
     * @param generatorType Terrain generator type (e.g., "LEGACY", "SPLINE")
     */
    public World(WorldConfiguration config, long seed, String generatorType) {
        this(config, seed, generatorType, false);
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
        this(config, seed, "LEGACY", testMode);
    }

    /**
     * Protected constructor with explicit terrain generator type for testing.
     * WARNING: Only use this for unit tests that don't require rendering!
     *
     * @param config World configuration
     * @param seed World generation seed
     * @param generatorType Terrain generator type (e.g., "LEGACY", "SPLINE")
     * @param testMode If true, skips MmsAPI/rendering initialization (for tests only)
     */
    protected World(WorldConfiguration config, long seed, String generatorType, boolean testMode) {
        this.config = config;

        this.terrainSystem = new TerrainGenerationSystem(seed, com.stonebreak.world.generation.config.TerrainGenerationConfig.defaultConfig(), generatorType, com.stonebreak.world.generation.LoadingProgressReporter.NULL);
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
        com.stonebreak.world.generation.features.FeatureQueue featureQueue = new com.stonebreak.world.generation.features.FeatureQueue();

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
            this.chunkStore.setChunkListeners(waterSystem::onChunkLoaded, waterSystem::onChunkUnloaded);

            this.chunkManager = new ChunkManager(this, config.getRenderDistance());

            System.out.println("Creating world with seed: " + terrainSystem.getSeed() + ", using " + config.getChunkBuildThreads() + " mesh builder threads.");
        }
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
     */
    public Chunk getChunkAt(int x, int z) {
        if (chunkStore == null) return null; // Test mode - no chunk store

        return chunkStore.getOrCreateChunk(x, z);
    }

    /**
     * Checks if a chunk exists at the specified position.
     */
    public boolean hasChunkAt(int x, int z) {
        if (chunkStore == null) return false; // Test mode - no chunk store

        return chunkStore.hasChunk(x, z);
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

        Chunk chunk = getChunkAt(chunkX, chunkZ);

        if (chunk == null) {
            return BlockType.AIR;
        }

        int localX = Math.floorMod(x, WorldConfiguration.CHUNK_SIZE);
        int localZ = Math.floorMod(z, WorldConfiguration.CHUNK_SIZE);

        return chunk.getBlock(localX, y, localZ);
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

        Chunk chunk = getChunkAt(chunkX, chunkZ);

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

        return true;
    }

    public WaterSystem getWaterSystem() {
        return waterSystem;
    }
    
    
    
    /**
     * Gets the continentalness value at the specified world position.
     */
    public float getContinentalnessAt(int x, int z) {
        return terrainSystem.getContinentalnessAt(x, z);
    }

    /**
     * Gets the biome at the specified world position.
     */
    public BiomeType getBiomeAt(int x, int z) {
        return terrainSystem.getBiomeType(x, z);
    }

    /**
     * Gets the moisture value at the specified world position.
     */
    public float getMoistureAt(int x, int z) {
        return terrainSystem.generateMoisture(x, z);
    }

    /**
     * Gets the temperature value at the specified world position.
     */
    public float getTemperatureAt(int x, int z) {
        return terrainSystem.generateTemperature(x, z);
    }

    /**
     * Gets the erosion noise value at the specified world position.
     * Phase 1: Erosion adds subtle terrain variation (weathering effects).
     */
    public float getErosionNoiseAt(int x, int z) {
        return terrainSystem.getErosionNoiseAt(x, z);
    }

    /**
     * Gets the base terrain height before biome modifiers and erosion.
     * This is just the continentalness-based height.
     */
    public int getBaseHeight(int x, int z) {
        return terrainSystem.getBaseHeightBeforeErosion(x, z);
    }

    /**
     * Gets the base terrain height from continentalness only.
     * This is before erosion, PV, and weirdness are applied.
     */
    public int getHeightBeforeErosion(int x, int z) {
        return terrainSystem.getBaseHeightBeforeErosion(x, z);
    }

    /**
     * Gets the actual final terrain height as used in world generation.
     * This is the final height including biome modifiers and erosion.
     */
    public int getFinalTerrainHeight(int x, int z) {
        return terrainSystem.getFinalTerrainHeight(x, z);
    }

    /**
     * Gets the peaks & valleys noise value at the specified world position.
     * This parameter amplifies height extremes for dramatic terrain features.
     */
    public float getPeaksValleysAt(int x, int z) {
        return terrainSystem.getPeaksValleysAt(x, z);
    }

    /**
     * Gets the weirdness noise value at the specified world position.
     * This parameter creates plateaus and mesas with terracing effects.
     */
    public float getWeirdnessAt(int x, int z) {
        return terrainSystem.getWeirdnessAt(x, z);
    }

    /**
     * Gets the terrain generator type used by this world.
     */
    public com.stonebreak.world.generation.TerrainGeneratorType getGeneratorType() {
        return terrainSystem.getGeneratorType();
    }

    /**
     * Gets comprehensive height calculation debug information at the specified position.
     * Returns generator-specific debug data for F3 visualization.
     */
    public com.stonebreak.world.generation.debug.HeightCalculationDebugInfo getHeightCalculationDebugInfo(int x, int z) {
        return terrainSystem.getHeightCalculationDebugInfo(x, z);
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

        // Ensure border chunks exist for meshing purposes (triggers generation cascade)
        if (neighborCoordinator != null) {
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

        if (meshPipeline != null) {
            meshPipeline.shutdown();
            meshPipeline.processGpuCleanupQueue();
        }
        chunkStore.cleanup();
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
}
