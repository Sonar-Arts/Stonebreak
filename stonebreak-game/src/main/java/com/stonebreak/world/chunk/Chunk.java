package com.stonebreak.world.chunk;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.time.LocalDateTime;
import java.util.logging.Logger;
import java.util.logging.Level;

import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.chunk.mesh.geometry.ChunkMeshOperations;
import com.stonebreak.world.chunk.operations.ChunkCoordinateUtils;
import com.stonebreak.world.chunk.operations.ChunkInternalStateManager;
import com.stonebreak.world.chunk.operations.ChunkState;
import com.stonebreak.world.chunk.operations.ChunkDataBuffer;
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
    private final ChunkDataBuffer chunkData;
    private final ChunkDataOperations dataOperations;
    
    // Buffer operations and state
    private final ChunkBufferOperations bufferOperations = new ChunkBufferOperations();
    private ChunkBufferState bufferState = ChunkBufferState.empty();
    private int indexCount;
    private boolean meshGenerated;
    
    // State management
    private final ChunkInternalStateManager stateManager = new ChunkInternalStateManager();
    
    // Resource management
    private final ChunkResourceManager resourceManager;
    
    // Save/load tracking fields
    private boolean isDirty = false;
    private LocalDateTime lastModified = LocalDateTime.now();
    private boolean generatedByPlayer = false;
    
    /**
     * Creates a new chunk at the specified position.
     */
    public Chunk(int x, int z) {
        this.x = x;
        this.z = z;
        this.meshGenerated = false;
        
        // Initialize data and operations
        this.chunkData = new ChunkDataBuffer(x, z);
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
        boolean changed = dataOperations.setBlock(x, y, z, blockType);
        if (changed) {
            setDirty(true); // Mark chunk as dirty for saving when block data changes
        }
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

        // Case 1: Chunk has become empty or has no renderable data
        if (vertexData == null || vertexData.length == 0 || indexData == null || indexData.length == 0 || indexCount == 0) {
            if (this.meshGenerated) { // If there was an old mesh
                bufferOperations.deleteBuffers(bufferState);
                bufferState = ChunkBufferState.empty();
                this.meshGenerated = false;
            }
            stateManager.removeState(ChunkState.MESH_CPU_READY); // Data processed
            return;
        }

        // Case 2: We have new mesh data to apply.
        final boolean hasExistingMesh = this.meshGenerated && bufferState.isValid();

        try {
            // Create ChunkMeshData from current arrays
            ChunkMeshData meshData = new ChunkMeshData(vertexData, textureData, normalData, 
                                                      isWaterData, isAlphaTestedData, indexData, indexCount);
            
            if (hasExistingMesh) {
                // Fast path: update buffer contents in-place
                bufferOperations.updateBuffers(bufferState, meshData);
            } else {
                // First-time creation path: allocate VAO/VBOs and upload data
                bufferState = bufferOperations.createBuffers(meshData);
                this.meshGenerated = true; // New mesh is now active and ready for rendering.
            }
            
            // Mark as uploaded to GPU
            stateManager.markMeshGpuUploaded();
            
            // CRITICAL FIX: Free mesh data arrays after successful GL upload
            // The data is now safely stored in GPU buffers and no longer needed in RAM
            freeMeshDataArrays();
        } catch (Exception e) {
            logger.log(Level.SEVERE, () ->
                "CRITICAL: Error during " + (hasExistingMesh ? "updateBuffers" : "createBuffers") +
                " for chunk (" + x + ", " + z + ")\n" +
                "Time=" + java.time.LocalDateTime.now() +
                " Thread=" + Thread.currentThread().getName() +
                " BufferState=" + bufferState +
                " IndexCount=" + indexCount +
                " MemMB=" + ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024));
            
            if (!hasExistingMesh) {
                // Creation failed; ensure we don't hold partially created GL objects
                bufferState = resourceManager.handleBufferUploadFailure(bufferState);
                this.meshGenerated = false;
            }
       } finally {
            stateManager.removeState(ChunkState.MESH_CPU_READY); // Data has been processed (or attempt failed)
        }
    }
    /**
     * Updates mesh data from ChunkMeshData result
     */
    private void updateMeshDataFromResult(ChunkMeshData meshData) {
        // Free old mesh data arrays before assigning new ones
        freeMeshDataArrays();
        
        vertexData = meshData.getVertexData();
        textureData = meshData.getTextureData();
        normalData = meshData.getNormalData();
        isWaterData = meshData.getIsWaterData();
        isAlphaTestedData = meshData.getIsAlphaTestedData();
        indexData = meshData.getIndexData();
        indexCount = meshData.getIndexCount();
    }
    
    // Mesh data
    private float[] vertexData;
    private float[] textureData;
    private float[] normalData;
    private float[] isWaterData;
    private float[] isAlphaTestedData; // New array for isAlphaTested flags
    private int[] indexData;
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

    public int getX() {
        return this.x;
    }

    public int getZ() {
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
    public ChunkDataBuffer getChunkData() {
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
        if (vertexData == null || indexData == null || indexCount == 0) {
            return ChunkMeshData.empty();
        }
        return new ChunkMeshData(vertexData, textureData, normalData, 
                               isWaterData, isAlphaTestedData, indexData, indexCount);
    }
     /**
      * Cleans up CPU-side resources. Safe to call from any thread.
      */
     public void cleanupCpuResources() {
         ChunkMeshData meshData = getCurrentMeshData();
         resourceManager.cleanupCpuResources(meshData);
         freeMeshDataArrays();
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
    /**
     * Frees mesh data arrays to reduce memory usage.
     * This is critical for preventing memory leaks when mesh data is regenerated.
     */
    private void freeMeshDataArrays() {
        vertexData = null;
        textureData = null;
        normalData = null;
        isWaterData = null;
        isAlphaTestedData = null;
        indexData = null;
    }
    
    // ===== Save/Load Methods =====
    
    /**
     * Gets whether this chunk has been modified and needs saving.
     */
    public boolean isDirty() {
        return isDirty;
    }
    
    /**
     * Sets whether this chunk has been modified and needs saving.
     */
    public void setDirty(boolean isDirty) {
        this.isDirty = isDirty;
        if (isDirty) {
            this.lastModified = LocalDateTime.now();
        }
    }
    
    /**
     * Marks this chunk as clean (not needing to be saved).
     */
    public void markClean() {
        this.isDirty = false;
        this.lastModified = LocalDateTime.now();
    }
    
    /**
     * Gets the last modification timestamp of this chunk.
     */
    public LocalDateTime getLastModified() {
        return lastModified;
    }
    
    /**
     * Sets the last modification timestamp of this chunk.
     */
    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }
    
    /**
     * Gets whether this chunk was generated by player actions.
     */
    public boolean isGeneratedByPlayer() {
        return generatedByPlayer;
    }
    
    /**
     * Sets whether this chunk was generated by player actions.
     */
    public void setGeneratedByPlayer(boolean generatedByPlayer) {
        this.generatedByPlayer = generatedByPlayer;
    }
    
    /**
     * Gets whether features have been populated in this chunk.
     * Renamed from areFeaturesPopulated for consistency with ChunkData.
     */
    public boolean isFeaturesPopulated() {
        return stateManager.hasState(ChunkState.FEATURES_POPULATED);
    }

    /**
     * Gets the block array for this chunk.
     * Used by binary save/load system.
     * @return The 3D block array
     */
    public BlockType[][][] getBlocks() {
        return chunkData.getBlocks();
    }

    /**
     * Sets the block array for this chunk.
     * Used by binary save/load system.
     * @param blocks The 3D block array to set
     */
    public void setBlocks(BlockType[][][] blocks) {
        chunkData.setBlocks(blocks);
    }

    /**
     * Marks this chunk as dirty.
     * Alias for setDirty(true) used by binary save/load system.
     */
    public void markDirty() {
        setDirty(true);
    }
}
