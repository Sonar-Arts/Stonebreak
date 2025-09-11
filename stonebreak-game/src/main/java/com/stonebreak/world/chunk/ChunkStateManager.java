package com.stonebreak.world.chunk;

import java.util.function.Consumer;
import com.stonebreak.world.chunk.operations.ChunkState;

/**
 * Higher-level chunk state management that orchestrates operations across chunks.
 * Uses the new ChunkStateManager system internally.
 */
public class ChunkStateManager {
    
    public void markForMeshRebuild(Chunk chunk) {
        if (chunk == null) return;
        
        synchronized (chunk) {
            chunk.getStateManager().markMeshDirty();
            chunk.getStateManager().removeState(ChunkState.MESH_GENERATING);
        }
    }
    
    public void markForMeshRebuildWithScheduling(Chunk chunk, Consumer<Chunk> meshBuildScheduler) {
        markForMeshRebuild(chunk);
        meshBuildScheduler.accept(chunk);
    }
    
    public void resetMeshGenerationState(Chunk chunk) {
        if (chunk == null) return;
        
        synchronized (chunk) {
            chunk.getStateManager().removeState(ChunkState.MESH_CPU_READY);
            chunk.getStateManager().removeState(ChunkState.MESH_GENERATING);
            chunk.getStateManager().markMeshDirty();
        }
    }
    
    public void markMeshGenerationInProgress(Chunk chunk) {
        if (chunk == null) return;
        
        synchronized (chunk) {
            chunk.getStateManager().markMeshGenerating();
        }
    }
    
    public void markMeshGenerationComplete(Chunk chunk) {
        if (chunk == null) return;
        
        synchronized (chunk) {
            chunk.getStateManager().removeState(ChunkState.MESH_GENERATING);
        }
    }
    
    public boolean isReadyForMeshGeneration(Chunk chunk) {
        if (chunk == null) return false;
        
        synchronized (chunk) {
            return chunk.getStateManager().needsMeshGeneration();
        }
    }
    
    public void markChunkForPopulation(Chunk chunk) {
        markForMeshRebuild(chunk);
    }
}