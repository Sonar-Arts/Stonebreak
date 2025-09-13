package com.stonebreak.world;

import java.util.Map;
import java.util.List;

import org.joml.Vector3f;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.chunk.*;
import com.stonebreak.world.chunk.mesh.builder.ChunkMeshBuildingPipeline;
import com.stonebreak.world.chunk.mesh.util.ChunkErrorReporter;
import com.stonebreak.world.chunk.mesh.data.ChunkNeighborCoordinator;
import com.stonebreak.world.chunk.mesh.data.WorldChunkStore;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.memory.WorldMemoryManager;
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
    private final ChunkStateManager stateManager;
    private final ChunkNeighborCoordinator neighborCoordinator;
    private final WorldMemoryManager memoryManager;
    private final ChunkMeshBuildingPipeline meshPipeline;
    private final ChunkErrorReporter errorReporter;

    public World() {
        this(new WorldConfiguration());
    }
    
    public World(WorldConfiguration config) {
        this.config = config;
        
        long seed = System.currentTimeMillis();
        this.terrainSystem = new TerrainGenerationSystem(seed);
        this.snowLayerManager = new SnowLayerManager();
        
        // Initialize modular components
        this.stateManager = new ChunkStateManager();
        this.errorReporter = new ChunkErrorReporter();
        this.meshPipeline = new ChunkMeshBuildingPipeline(config, stateManager, errorReporter);
        this.chunkStore = new WorldChunkStore(terrainSystem, config, meshPipeline);
        this.neighborCoordinator = new ChunkNeighborCoordinator(chunkStore, stateManager, config);
        this.memoryManager = new WorldMemoryManager(config, chunkStore);
        
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
        meshPipeline.requeueFailedChunks();
        chunkManager.update(Game.getPlayer());
        memoryManager.performMemoryManagement();
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
            stateManager.markChunkForPopulation(chunk);
            meshPipeline.scheduleConditionalMeshBuild(chunk);
        }

        boolean isMeshReady = chunk.isMeshGenerated() && chunk.isDataReadyForGL();
        if (!isMeshReady) {
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
        
        chunk.setBlock(localX, y, localZ, blockType);

        stateManager.markForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
        neighborCoordinator.rebuildNeighborChunksScheduled(chunkX, chunkZ, localX, localZ, meshPipeline::scheduleConditionalMeshBuild);
        
        return true;
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
                stateManager.markChunkForPopulation(chunk);
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

        // Reset world state using available methods
        if (stateManager != null) {
            // Reset mesh generation states for all chunks
            for (Chunk chunk : chunkStore.getAllChunks()) {
                stateManager.resetMeshGenerationState(chunk);
            }
        }

        // Clear memory management caches using available methods
        if (memoryManager != null) {
            // Perform aggressive memory cleanup by unloading distant chunks
            memoryManager.forceUnloadDistantChunks(400);
        }

        // Process any pending GPU cleanup without shutting down the pipeline
        if (meshPipeline != null) {
            meshPipeline.processGpuCleanupQueue();
        }

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
            stateManager.markForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
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
