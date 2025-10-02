package com.stonebreak.world;

import java.util.Map;
import java.util.List;
import java.util.Collection;
import java.util.function.Consumer;

import org.joml.Vector3f;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterSystem;
import com.stonebreak.core.Game;
import com.stonebreak.world.chunk.*;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.chunk.api.commonChunkOperations.operations.CcoNeighborCoordinator;
import com.stonebreak.world.chunk.api.mightyMesh.MmsAPI;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshPipeline;
import com.stonebreak.world.chunk.mesh.util.ChunkErrorReporter;
import com.stonebreak.world.chunk.mesh.data.WorldChunkStore;
import com.stonebreak.world.generation.TerrainGenerationSystem;
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
        this.config = config;

        this.terrainSystem = new TerrainGenerationSystem(seed);
        this.snowLayerManager = new SnowLayerManager();

        // Initialize modular components
        this.errorReporter = new ChunkErrorReporter();

        // Create MMS mesh pipeline using MmsAPI
        // MmsAPI is initialized in Game.initCoreComponents() before any World is created
        if (!MmsAPI.isInitialized()) {
            throw new IllegalStateException("MmsAPI must be initialized before creating World");
        }
        this.meshPipeline = MmsAPI.getInstance().createMeshPipeline(config, errorReporter);

        this.chunkStore = new WorldChunkStore(terrainSystem, config, meshPipeline);

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
        waterSystem.tick(Game.getDeltaTime());
        meshPipeline.requeueFailedChunks();
        chunkManager.update(Game.getPlayer());
        meshPipeline.processChunkMeshBuildRequests(this);
    }

    public void updateMainThread() {
        meshPipeline.applyPendingGLUpdates();
        meshPipeline.processGpuCleanupQueue();
    }
    
    public void processGpuCleanupQueue() {
        meshPipeline.processGpuCleanupQueue();
    }
    public void ensureChunkIsReadyForRender(int cx, int cz) {
        Chunk chunk = chunkStore.getChunk(cx, cz);

        if (chunk == null) {
            chunk = getChunkAt(cx, cz);
            if (chunk == null) {
                return;
            }
        }

        if (!chunk.areFeaturesPopulated()) {
            terrainSystem.populateChunkWithFeatures(this, chunk, snowLayerManager);
            markChunkForMeshRebuild(chunk);
            meshPipeline.scheduleConditionalMeshBuild(chunk);
        }

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
        return chunkStore.getOrCreateChunk(x, z);
    }
    
    /**
     * Checks if a chunk exists at the specified position.
     */
    public boolean hasChunkAt(int x, int z) {
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

        markChunkForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
        neighborCoordinator.markAndScheduleNeighbors(chunkX, chunkZ, localX, localZ, meshPipeline::scheduleConditionalMeshBuild);
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
     * Gets a cached chunk position for coordinate lookup.
     */
    public ChunkPosition getCachedChunkPosition(int x, int z) {
        return chunkStore.getCachedChunkPosition(x, z);
    }
    

    

    /**
     * Returns chunks around the specified position within render distance.
     */
    public Map<ChunkPosition, Chunk> getChunksAroundPlayer(int playerChunkX, int playerChunkZ) {
        Map<ChunkPosition, Chunk> visibleChunks = chunkStore.getChunksInRenderDistance(playerChunkX, playerChunkZ);
        
        // Populate chunks that need features
        for (Chunk chunk : visibleChunks.values()) {
            if (!chunk.areFeaturesPopulated()) {
                terrainSystem.populateChunkWithFeatures(this, chunk, snowLayerManager);
                markChunkForMeshRebuild(chunk);
                meshPipeline.scheduleConditionalMeshBuild(chunk);
            }
        }
        
        // Ensure border chunks exist for meshing purposes
        neighborCoordinator.ensureBorderChunksExist(playerChunkX, playerChunkZ);
        
        return visibleChunks;
    }

    /**
     * Get all dirty chunks that need to be saved.
     * @return List of chunks that have been modified and need saving
     */
    public List<Chunk> getDirtyChunks() {
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
        
        meshPipeline.shutdown();
        meshPipeline.processGpuCleanupQueue();
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
        return meshPipeline.getPendingMeshBuildCount();
    }
    
    /**
     * Returns the number of chunks pending GL upload.
     * This is used for debugging purposes.
     */
    public int getPendingGLUploadCount() {
        return meshPipeline.getPendingGLUploadCount();
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
    
    
    /**
     * Represents a position of a chunk in the world.
     */
    public static class ChunkPosition {
        private final int x;
        private final int z;
        
        public ChunkPosition(int x, int z) {
            this.x = x;
            this.z = z;
        }
        
        public int getX() {
            return x;
        }
        
        public int getZ() {
            return z;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            
            ChunkPosition that = (ChunkPosition) o;
            return x == that.x && z == that.z;
        }
        
        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + z;
            return result;
        }
    }
}
