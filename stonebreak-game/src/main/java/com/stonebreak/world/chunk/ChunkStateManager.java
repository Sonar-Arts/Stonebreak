package com.stonebreak.world.chunk;

import java.util.function.Consumer;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;

/**
 * Higher-level chunk state management that orchestrates operations across chunks.
 * Updated to use CCO (Common Chunk Operations) API.
 */
public class ChunkStateManager {

    public void markForMeshRebuild(Chunk chunk) {
        if (chunk == null) return;

        synchronized (chunk) {
            chunk.getCcoStateManager().transitionTo(CcoChunkState.MESH_DIRTY);
        }
    }

    public void markForMeshRebuildWithScheduling(Chunk chunk, Consumer<Chunk> meshBuildScheduler) {
        markForMeshRebuild(chunk);
        meshBuildScheduler.accept(chunk);
    }

    public void resetMeshGenerationState(Chunk chunk) {
        if (chunk == null) return;

        synchronized (chunk) {
            chunk.getCcoStateManager().transitionTo(CcoChunkState.MESH_DIRTY);
        }
    }

    public void markMeshGenerationInProgress(Chunk chunk) {
        if (chunk == null) return;

        synchronized (chunk) {
            chunk.getCcoStateManager().transitionTo(CcoChunkState.MESH_GENERATING);
        }
    }

    public void markMeshGenerationComplete(Chunk chunk) {
        if (chunk == null) return;

        synchronized (chunk) {
            // Transition from MESH_GENERATING to appropriate next state
            CcoChunkState currentState = chunk.getCcoStateManager().getState();
            if (currentState == CcoChunkState.MESH_GENERATING) {
                // If generation completed successfully, it should be in MESH_CPU_READY
                // If not, mark as dirty to retry
                chunk.getCcoStateManager().transitionTo(CcoChunkState.MESH_DIRTY);
            }
        }
    }

    public boolean isReadyForMeshGeneration(Chunk chunk) {
        if (chunk == null) return false;

        synchronized (chunk) {
            CcoChunkState state = chunk.getCcoStateManager().getState();
            // Ready if dirty and not already generating
            return state == CcoChunkState.MESH_DIRTY;
        }
    }

    public void markChunkForPopulation(Chunk chunk) {
        markForMeshRebuild(chunk);
    }
}
