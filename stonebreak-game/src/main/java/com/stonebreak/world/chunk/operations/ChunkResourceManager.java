package com.stonebreak.world.chunk.operations;

import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Orchestrates cleanup and lifecycle management of chunk resources.
 * Coordinates CPU and GPU resource cleanup across all chunk operations.
 */
public class ChunkResourceManager {
    
    private static final Logger logger = Logger.getLogger(ChunkResourceManager.class.getName());
    
    private final ChunkInternalStateManager stateManager;
    private final ChunkBufferOperations bufferOperations;
    
    /**
     * Creates a resource manager for the given chunk operations.
     * @param stateManager The chunk's state manager
     * @param bufferOperations The chunk's buffer operations
     */
    public ChunkResourceManager(ChunkInternalStateManager stateManager, ChunkBufferOperations bufferOperations) {
        this.stateManager = stateManager;
        this.bufferOperations = bufferOperations;
    }
    
    /**
     * Cleans up CPU-side resources. Safe to call from any thread.
     * @param meshData Current CPU mesh data arrays to free
     */
    public void cleanupCpuResources(ChunkMeshData meshData) {
        try {
            // Mark CPU resources as no longer ready
            stateManager.removeState(ChunkState.MESH_CPU_READY);
            
            // Note: meshData arrays are handled by garbage collector
            // No explicit freeing needed for regular arrays
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during CPU resource cleanup", e);
        }
    }
    
    /**
     * Cleans up GPU resources. MUST be called from the main OpenGL thread.
     * @param bufferState Current buffer state containing OpenGL resource IDs
     * @param isMeshGenerated Whether the chunk currently has generated mesh
     * @return Updated mesh generation status (will be false after cleanup)
     */
    public boolean cleanupGpuResources(ChunkBufferState bufferState, boolean isMeshGenerated) {
        try {
            if (isMeshGenerated && bufferState.isValid()) {
                bufferOperations.deleteBuffers(bufferState);
                stateManager.removeState(ChunkState.MESH_GPU_UPLOADED);
                return false; // Mesh no longer generated
            }
            return isMeshGenerated; // No change if nothing to clean up
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during GPU resource cleanup: " + bufferState, e);
            // Even if cleanup failed, consider mesh no longer generated to prevent further issues
            return false;
        }
    }
    
    /**
     * Performs complete cleanup of all chunk resources.
     * MUST be called from the main OpenGL thread due to GPU resource cleanup.
     * @param meshData Current CPU mesh data to free
     * @param bufferState Current GPU buffer state to clean up
     * @param isMeshGenerated Current mesh generation status
     * @return Updated mesh generation status (will be false after cleanup)
     */
    public boolean cleanupAllResources(ChunkMeshData meshData, ChunkBufferState bufferState, boolean isMeshGenerated) {
        // Clean up CPU resources first (thread-safe)
        cleanupCpuResources(meshData);
        
        // Clean up GPU resources (main thread only)
        return cleanupGpuResources(bufferState, isMeshGenerated);
    }
    
    /**
     * Prepares the chunk for unloading by cleaning up resources and marking state.
     * @param meshData Current CPU mesh data to free
     * @param bufferState Current GPU buffer state to clean up  
     * @param isMeshGenerated Current mesh generation status
     * @return Updated mesh generation status (will be false after cleanup)
     */
    public boolean prepareForUnload(ChunkMeshData meshData, ChunkBufferState bufferState, boolean isMeshGenerated) {
        // Mark chunk as unloading to prevent new operations
        stateManager.addState(ChunkState.UNLOADING);
        
        // Perform complete cleanup
        return cleanupAllResources(meshData, bufferState, isMeshGenerated);
    }
    
    /**
     * Handles mesh generation failure by cleaning up partial resources.
     * Safe to call from any thread.
     */
    public void handleMeshGenerationFailure() {
        try {
            stateManager.removeState(ChunkState.MESH_GENERATING);
            stateManager.removeState(ChunkState.MESH_CPU_READY); 
            stateManager.markMeshDirty(); // Mark as dirty so it can be retried
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling mesh generation failure", e);
        }
    }
    
    /**
     * Handles buffer upload failure by cleaning up partial GPU resources.
     * MUST be called from the main OpenGL thread.
     * @param bufferState Buffer state that may contain partial resources
     * @return Empty buffer state
     */
    public ChunkBufferState handleBufferUploadFailure(ChunkBufferState bufferState) {
        try {
            if (bufferState.isValid()) {
                bufferOperations.deleteBuffers(bufferState);
            }
            stateManager.removeState(ChunkState.MESH_GPU_UPLOADED);
            return ChunkBufferState.empty();
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling buffer upload failure: " + bufferState, e);
            return ChunkBufferState.empty(); // Return empty state even if cleanup failed
        }
    }
    
    /**
     * Checks if the chunk is in a state where it's safe to perform resource operations.
     * @return true if the chunk is not being unloaded
     */
    public boolean isSafeForOperations() {
        return !stateManager.hasState(ChunkState.UNLOADING);
    }
    
    /**
     * Gets statistics about the current resource state.
     * @param bufferState Current buffer state
     * @param meshData Current mesh data (can be null)
     * @return Resource statistics string
     */
    public String getResourceStats(ChunkBufferState bufferState, ChunkMeshData meshData) {
        StringBuilder stats = new StringBuilder();
        stats.append("ResourceState{");
        stats.append("buffer=").append(bufferState.isValid() ? "allocated" : "empty");
        if (meshData != null) {
            stats.append(", mesh=").append(meshData.isEmpty() ? "empty" : meshData.toString());
        }
        stats.append(", states=").append(stateManager.getCurrentStates());
        stats.append("}");
        return stats.toString();
    }
}