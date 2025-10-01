package com.stonebreak.world.chunk;

import java.util.logging.Logger;
import java.util.logging.Level;

import com.stonebreak.world.World;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.chunk.mesh.geometry.ChunkMeshOperations;
import com.stonebreak.world.chunk.operations.ChunkCoordinateUtils;
import com.stonebreak.world.chunk.operations.ChunkInternalStateManager;
import com.stonebreak.world.chunk.operations.ChunkState;
import com.stonebreak.world.chunk.operations.ChunkData;
import com.stonebreak.world.chunk.operations.ChunkDataOperations;
import com.stonebreak.world.chunk.operations.ChunkMeshData;
import com.stonebreak.world.chunk.operations.ChunkBufferState;
import com.stonebreak.world.chunk.operations.ChunkBufferOperations;
import com.stonebreak.world.chunk.operations.ChunkResourceManager;

/**
 * Represents a chunk of the world, storing block data and mesh information.
 */
public class Chunk {
    
    private static final Logger logger = Logger.getLogger(Chunk.class.getName());
    
    private final int x;
    private final int z;
    
    // Block data storage and operations
    private final ChunkData chunkData;
    private final ChunkDataOperations dataOperations;
    
    // Buffer operations and state
    private final ChunkBufferOperations bufferOperations = new ChunkBufferOperations();
    private ChunkBufferState bufferState = ChunkBufferState.empty();
    private int indexCount;
    private boolean meshGenerated;
    private ChunkMeshData pendingMeshData;
    
    // State management
    private final ChunkInternalStateManager stateManager = new ChunkInternalStateManager();
    
    // Resource management
    private final ChunkResourceManager resourceManager;
    
    /**
     * Creates a new chunk at the specified position.
     */
    public Chunk(int x, int z) {
        this.x = x;
        this.z = z;
        this.meshGenerated = false;
        
        // Initialize data and operations
        this.chunkData = new ChunkData(x, z);
        this.dataOperations = new ChunkDataOperations(chunkData, stateManager);
        
        // Initialize resource management
        this.resourceManager = new ChunkResourceManager(stateManager, bufferOperations);
        
        // Initialize state - blocks populated after creation
        stateManager.addState(ChunkState.BLOCKS_POPULATED);
    }
    
    /**
     * Gets the block type at the specified local position.
     */
    public BlockType getBlock(int x, int y, int z) {
        return dataOperations.getBlock(x, y, z);
    }
    
    /**
     * Sets the block type at the specified local position.
     */
    public void setBlock(int x, int y, int z, BlockType blockType) {
        dataOperations.setBlock(x, y, z, blockType);
    }
    
    // Chunk mesh operations instance for generating mesh data
    private final ChunkMeshOperations chunkMeshOperations = new ChunkMeshOperations();
    
    /**
     * Builds the mesh data for this chunk. This is CPU-intensive and can be run on a worker thread.
     */
    public void buildAndPrepareMeshData(World world) {
        // State should already be MESH_GENERATING, set by caller
        try {
            // Update loading progress
            Game game = Game.getInstance();
            if (game != null && game.getLoadingScreen() != null && game.getLoadingScreen().isVisible()) {
                game.getLoadingScreen().updateProgress("Meshing Chunk");
            }
            
            // Generate mesh data directly within the chunk
            ChunkMeshOperations.MeshData oldMeshData = chunkMeshOperations.generateMeshData(chunkData.getBlocks(), x, z, world);
            
            // Convert to new ChunkMeshData format
            ChunkMeshData meshData = new ChunkMeshData(
                oldMeshData.vertexData,
                oldMeshData.textureData, 
                oldMeshData.normalData,
                oldMeshData.isWaterData,
                oldMeshData.isAlphaTestedData,
                oldMeshData.indexData,
                oldMeshData.indexCount
            );
            
            // Store the generated mesh data
            updateMeshDataFromResult(meshData);
            stateManager.markMeshCpuReady(); // Mark data as ready for GL upload ONLY on success
        } catch (Exception e) {
            logger.log(Level.SEVERE, "CRITICAL: Exception during generateMeshData for chunk (" + x + ", " + z + "): " + e.getMessage()
                + "\nTime: " + java.time.LocalDateTime.now()
                + "\nThread: " + Thread.currentThread().getName()
                + "\nMemory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used", e);
            resourceManager.handleMeshGenerationFailure();
        }
    }

    /**
     * Applies the prepared mesh data to OpenGL. This must be called on the main GL thread.
     *
     * Optimization: if a mesh already exists (VAO/VBOs allocated), update buffer data in place
     * instead of destroying and recreating GL objects. This avoids VAO/VBO churn.
     */
    public void applyPreparedDataToGL() {
        if (!stateManager.isMeshReadyForUpload()) {
            return; // Data not ready yet or already processed
        }

        final ChunkMeshData meshData = pendingMeshData != null ? pendingMeshData : ChunkMeshData.empty();
        final boolean hasExistingMesh = this.meshGenerated && bufferState.isValid();

        try {
            if (meshData.isEmpty()) {
                if (this.meshGenerated) {
                    bufferOperations.deleteBuffers(bufferState);
                    bufferState = ChunkBufferState.empty();
                    this.meshGenerated = false;
                }
                this.indexCount = 0;
                stateManager.removeState(ChunkState.MESH_GPU_UPLOADED);
                return;
            }

            if (hasExistingMesh) {
                // Fast path: update buffer contents in-place
                bufferOperations.updateBuffers(bufferState, meshData);
            } else {
                // First-time creation path: allocate VAO/VBOs and upload data
                bufferState = bufferOperations.createBuffers(meshData);
                this.meshGenerated = true; // New mesh is now active and ready for rendering.
            }

            this.indexCount = meshData.getIndexCount();
            stateManager.markMeshGpuUploaded();
        } catch (Exception e) {
            logger.log(Level.SEVERE, () ->
                "CRITICAL: Error during " + (hasExistingMesh ? "updateBuffers" : "createBuffers") +
                " for chunk (" + x + ", " + z + ")\n" +
                "Time=" + java.time.LocalDateTime.now() +
                " Thread=" + Thread.currentThread().getName() +
                " BufferState=" + bufferState +
                " PendingIndexCount=" + meshData.getIndexCount() +
                " MemMB=" + ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024));

            if (!hasExistingMesh) {
                // Creation failed; ensure we don't hold partially created GL objects
                bufferState = resourceManager.handleBufferUploadFailure(bufferState);
                this.meshGenerated = false;
            }
        } finally {
            pendingMeshData = null;
            stateManager.removeState(ChunkState.MESH_CPU_READY); // Data has been processed (or attempt failed)
        }
    }
    /**
     * Updates mesh data from ChunkMeshData result
     */
    private void updateMeshDataFromResult(ChunkMeshData meshData) {
        // Stage the mesh for GPU upload without disturbing the currently rendered data
        pendingMeshData = meshData;
    }
      /**
     * Renders the chunk.
     */
    public void render() {
        if (!stateManager.isRenderable() || indexCount == 0) {
            return;
        }
        
        bufferOperations.render(bufferState, indexCount);
    }
    
    /**
     * Converts a local X coordinate to a world X coordinate.
     */
    public int getWorldX(int localX) {
        return ChunkCoordinateUtils.localToWorldX(x, localX);
    }
    
    /**
     * Converts a local Z coordinate to a world Z coordinate.
     */
    public int getWorldZ(int localZ) {
        return ChunkCoordinateUtils.localToWorldZ(z, localZ);
    }

    public int getChunkX() {
        return this.x;
    }

    public int getChunkZ() {
        return this.z;
    }

    public boolean areFeaturesPopulated() {
        return stateManager.hasState(ChunkState.FEATURES_POPULATED);
    }

    public void setFeaturesPopulated(boolean featuresPopulated) {
        if (featuresPopulated) {
            stateManager.addState(ChunkState.FEATURES_POPULATED);
        } else {
            stateManager.removeState(ChunkState.FEATURES_POPULATED);
        }
    }

    public boolean isMeshGenerated() {
        return meshGenerated;
    }

    public boolean isDataReadyForGL() {
        return stateManager.hasState(ChunkState.MESH_CPU_READY);
    }

    // Getter for mesh generation status
    public boolean isMeshDataGenerationScheduledOrInProgress() {
        return stateManager.hasState(ChunkState.MESH_GENERATING);
    }

    // Package-private setter for mesh generation status, called by World
    void setMeshDataGenerationScheduledOrInProgress(boolean status) {
        if (status) {
            stateManager.markMeshGenerating();
        } else {
            stateManager.removeState(ChunkState.MESH_GENERATING);
        }
    }
    
    /**
     * Gets the state manager for this chunk.
     * @return The chunk's state manager
     */
    public ChunkInternalStateManager getStateManager() {
        return stateManager;
    }
    
    /**
     * Gets the data operations for this chunk.
     * @return The chunk's data operations
     */
    public ChunkDataOperations getDataOperations() {
        return dataOperations;
    }
    
    /**
     * Gets the chunk data for this chunk.
     * @return The chunk's data
     */
    public ChunkData getChunkData() {
        return chunkData;
    }
    
    /**
     * Gets the resource manager for this chunk.
     * @return The chunk's resource manager
     */
    public ChunkResourceManager getResourceManager() {
        return resourceManager;
    }
    
    /**
     * Gets the current mesh data as a ChunkMeshData object.
     * @return Current mesh data, or empty mesh data if no mesh exists
     */
    private ChunkMeshData getCurrentMeshData() {
        return pendingMeshData != null ? pendingMeshData : ChunkMeshData.empty();
    }
     /**
      * Cleans up CPU-side resources. Safe to call from any thread.
      */
     public void cleanupCpuResources() {
         ChunkMeshData meshData = getCurrentMeshData();
         resourceManager.cleanupCpuResources(meshData);
         pendingMeshData = null;
         indexCount = 0;
     }
 
     /**
      * Cleans up GPU resources. MUST be called from the main OpenGL thread.
      */
     public void cleanupGpuResources() {
         meshGenerated = resourceManager.cleanupGpuResources(bufferState, meshGenerated);
         bufferState = ChunkBufferState.empty();
         
         // Clean up buffer operations resources
         bufferOperations.cleanup();
     }
}
