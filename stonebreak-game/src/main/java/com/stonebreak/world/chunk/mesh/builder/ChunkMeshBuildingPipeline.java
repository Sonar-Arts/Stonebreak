package com.stonebreak.world.chunk.mesh.builder;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.ChunkManager;
import com.stonebreak.world.chunk.mesh.util.ChunkErrorReporter;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.core.Game;

import java.util.*;
import java.util.concurrent.*;

public class ChunkMeshBuildingPipeline {
    private final ExecutorService chunkBuildExecutor;
    private final Set<Chunk> chunksToBuildMesh;
    private final Queue<Chunk> chunksReadyForGLUpload;
    private final Queue<Chunk> chunksFailedToBuildMesh;
    private final Queue<Chunk> chunksPendingGpuCleanup;
    private final Map<Chunk, Integer> chunkRetryCount;
    private final ChunkErrorReporter errorReporter;
    private final WorldConfiguration config;

    public ChunkMeshBuildingPipeline(WorldConfiguration config, ChunkErrorReporter errorReporter) {
        this.config = config;
        this.errorReporter = errorReporter;
        
        this.chunkBuildExecutor = Executors.newFixedThreadPool(config.getChunkBuildThreads());
        this.chunksToBuildMesh = ConcurrentHashMap.newKeySet();
        this.chunksReadyForGLUpload = new ConcurrentLinkedQueue<>();
        this.chunksFailedToBuildMesh = new ConcurrentLinkedQueue<>();
        this.chunksPendingGpuCleanup = new ConcurrentLinkedQueue<>();
        this.chunkRetryCount = new ConcurrentHashMap<>();
        
        System.out.println("Created mesh building pipeline with " + config.getChunkBuildThreads() + " threads.");
    }
    
    public void scheduleConditionalMeshBuild(Chunk chunk) {
        if (chunk == null) return;

        synchronized (chunk) {
            if (!isReadyForMeshGeneration(chunk)) {
                return;
            }

            if (chunksToBuildMesh.contains(chunk) || chunksReadyForGLUpload.contains(chunk)) {
                return;
            }

            markMeshGenerationInProgress(chunk);
            chunksToBuildMesh.add(chunk);
        }
    }
    
    public void processChunkMeshBuildRequests(World world) {
        if (chunksToBuildMesh.isEmpty()) {
            return;
        }
        
        Set<Chunk> batchToProcess = new HashSet<>(chunksToBuildMesh);
        chunksToBuildMesh.clear();
        
        for (Chunk chunkToProcess : batchToProcess) {
            chunkBuildExecutor.submit(() -> processMeshBuildTask(world, chunkToProcess));
        }
    }
    
    private void processMeshBuildTask(World world, Chunk chunkToProcess) {
        boolean buildSuccess = false;
        try {
            chunkToProcess.buildAndPrepareMeshData(world);
            buildSuccess = chunkToProcess.isDataReadyForGL();
            
            if (buildSuccess) {
                chunksReadyForGLUpload.offer(chunkToProcess);
            }
        } catch (Exception e) {
            errorReporter.reportMeshBuildError(chunkToProcess, e, "Outer error during mesh build task");
            buildSuccess = false;
        } finally {
            markMeshGenerationComplete(chunkToProcess);
            handleBuildResult(chunkToProcess, buildSuccess);
        }
    }
    
    private void handleBuildResult(Chunk chunk, boolean buildSuccess) {
        if (!buildSuccess) {
            int retryCount = chunkRetryCount.getOrDefault(chunk, 0);
            if (retryCount < WorldConfiguration.MAX_FAILED_CHUNK_RETRIES) {
                chunkRetryCount.put(chunk, retryCount + 1);
                chunksFailedToBuildMesh.offer(chunk);
            } else {
                chunkRetryCount.remove(chunk);
                errorReporter.reportMaxRetriesReached(chunk);
            }
        } else {
            chunkRetryCount.remove(chunk);
        }
    }
    
    public void requeueFailedChunks() {
        Chunk failedChunk;
        while ((failedChunk = chunksFailedToBuildMesh.poll()) != null) {
            resetMeshGenerationState(failedChunk);
            scheduleConditionalMeshBuild(failedChunk);
        }
    }
    
    public void applyPendingGLUpdates() {
        Chunk chunkToUpdate;
        int updatesThisFrame = 0;
        int maxUpdatesPerFrame = ChunkManager.getOptimizedGLBatchSize();
        
        while ((chunkToUpdate = chunksReadyForGLUpload.poll()) != null && updatesThisFrame < maxUpdatesPerFrame) {
            try {
                chunkToUpdate.applyPreparedDataToGL();
                updatesThisFrame++;
            } catch (Exception e) {
                errorReporter.reportGLUpdateError(chunkToUpdate, e, updatesThisFrame, maxUpdatesPerFrame, chunksReadyForGLUpload.size());
            }
        }
        
        if (updatesThisFrame >= 16 || ChunkManager.isHighMemoryPressure()) {
            Game.logDetailedMemoryInfo("After processing " + updatesThisFrame + " GL updates (optimized batch size: " + maxUpdatesPerFrame + ")");
        }
    }
    
    public void processGpuCleanupQueue() {
        Chunk chunk;
        int cleaned = 0;
        while ((chunk = chunksPendingGpuCleanup.poll()) != null) {
            chunk.cleanupGpuResources();
            cleaned++;
        }
        
        if (cleaned > WorldConfiguration.GPU_CLEANUP_LOG_THRESHOLD) {
            System.out.println("Cleaned up GPU resources for " + cleaned + " chunks");
        }
    }
    
    public void addChunkForGpuCleanup(Chunk chunk) {
        chunksPendingGpuCleanup.offer(chunk);
    }
    
    public void removeChunkFromQueues(Chunk chunk) {
        chunksToBuildMesh.remove(chunk);
        chunksReadyForGLUpload.remove(chunk);
        chunksFailedToBuildMesh.remove(chunk);
        chunkRetryCount.remove(chunk);
    }
    
    public void shutdown() {
        chunkBuildExecutor.shutdown();
        try {
            if (!chunkBuildExecutor.awaitTermination(WorldConfiguration.CHUNK_BUILD_EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                System.err.println("Chunk build executor did not terminate in " + WorldConfiguration.CHUNK_BUILD_EXECUTOR_TIMEOUT_SECONDS + " seconds. Forcing shutdown...");
                chunkBuildExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            System.err.println("Error while shutting down chunk build executor: " + e.getMessage());
            chunkBuildExecutor.shutdownNow();
        }
        
        clearQueues();
    }
    
    private void clearQueues() {
        chunksToBuildMesh.clear();
        chunksReadyForGLUpload.clear();
        chunksFailedToBuildMesh.clear();
        chunkRetryCount.clear();
    }
    
    // CCO-based state management helpers

    private boolean isReadyForMeshGeneration(Chunk chunk) {
        if (chunk == null) return false;

        synchronized (chunk) {
            // Ready if dirty and not already generating
            return chunk.getCcoStateManager().hasState(CcoChunkState.MESH_DIRTY) &&
                   !chunk.getCcoStateManager().hasState(CcoChunkState.MESH_GENERATING);
        }
    }

    private void markMeshGenerationInProgress(Chunk chunk) {
        if (chunk == null) return;

        synchronized (chunk) {
            chunk.getCcoStateManager().addState(CcoChunkState.MESH_GENERATING);
        }
    }

    private void markMeshGenerationComplete(Chunk chunk) {
        if (chunk == null) return;

        synchronized (chunk) {
            chunk.getCcoStateManager().removeState(CcoChunkState.MESH_GENERATING);
            // If generation completed successfully, it should transition to CPU_READY
            // If not, mark as dirty to retry
            if (!chunk.getCcoStateManager().hasState(CcoChunkState.MESH_CPU_READY)) {
                chunk.getCcoStateManager().addState(CcoChunkState.MESH_DIRTY);
            }
        }
    }

    private void resetMeshGenerationState(Chunk chunk) {
        if (chunk == null) return;

        synchronized (chunk) {
            chunk.getCcoStateManager().addState(CcoChunkState.MESH_DIRTY);
        }
    }

    // Debug methods
    public int getPendingMeshBuildCount() {
        return chunksToBuildMesh.size();
    }

    public int getPendingGLUploadCount() {
        return chunksReadyForGLUpload.size();
    }
}