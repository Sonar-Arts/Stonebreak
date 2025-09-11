package com.stonebreak.world;

import java.util.function.Consumer;

public class ChunkStateManager {
    
    public void markForMeshRebuild(Chunk chunk) {
        if (chunk == null) return;
        
        synchronized (chunk) {
            chunk.setDataReadyForGL(false);
            chunk.setMeshDataGenerationScheduledOrInProgress(false);
        }
    }
    
    public void markForMeshRebuildWithScheduling(Chunk chunk, Consumer<Chunk> meshBuildScheduler) {
        markForMeshRebuild(chunk);
        meshBuildScheduler.accept(chunk);
    }
    
    public void resetMeshGenerationState(Chunk chunk) {
        if (chunk == null) return;
        
        synchronized (chunk) {
            chunk.setDataReadyForGL(false);
            chunk.setMeshDataGenerationScheduledOrInProgress(false);
        }
    }
    
    public void markMeshGenerationInProgress(Chunk chunk) {
        if (chunk == null) return;
        
        synchronized (chunk) {
            chunk.setMeshDataGenerationScheduledOrInProgress(true);
        }
    }
    
    public void markMeshGenerationComplete(Chunk chunk) {
        if (chunk == null) return;
        
        synchronized (chunk) {
            chunk.setMeshDataGenerationScheduledOrInProgress(false);
        }
    }
    
    public boolean isReadyForMeshGeneration(Chunk chunk) {
        if (chunk == null) return false;
        
        synchronized (chunk) {
            if (chunk.isMeshGenerated() && chunk.isDataReadyForGL()) {
                return false; // Already fully meshed and data applied
            }
            if (chunk.isDataReadyForGL() && !chunk.isMeshGenerated()) {
                return false; // Data ready for GL, waiting for main thread
            }
            if (chunk.isMeshDataGenerationScheduledOrInProgress()) {
                return false; // Already scheduled or worker is on it
            }
            return true;
        }
    }
    
    public void markChunkForPopulation(Chunk chunk) {
        markForMeshRebuild(chunk);
    }
}