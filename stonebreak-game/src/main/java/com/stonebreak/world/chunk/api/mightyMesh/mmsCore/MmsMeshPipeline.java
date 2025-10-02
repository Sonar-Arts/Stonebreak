package com.stonebreak.world.chunk.api.mightyMesh.mmsCore;

import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.ChunkManager;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.chunk.api.mightyMesh.MmsAPI;
import com.stonebreak.world.chunk.utils.ChunkErrorReporter;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.core.Game;

import java.util.*;
import java.util.concurrent.*;

/**
 * Mighty Mesh System - Complete mesh building pipeline with orchestration.
 *
 * Provides multi-threaded mesh generation, GPU upload batching, retry logic,
 * and resource cleanup in a unified pipeline architecture.
 *
 * Design Philosophy:
 * - Pipeline Architecture: Separate stages for generation, upload, cleanup
 * - Multi-threaded: Parallel mesh generation on worker threads
 * - Batch Processing: GPU uploads limited by frame budget
 * - Resilient: Retry logic for failed operations
 * - RAII: Automatic resource cleanup
 *
 * Pipeline Stages:
 * 1. Mesh Generation Queue → Worker threads generate meshes
 * 2. GL Upload Queue → Render thread uploads to GPU
 * 3. Cleanup Queue → GPU resource disposal
 * 4. Retry Queue → Failed meshes for retry
 *
 * Performance:
 * - Multi-threaded mesh generation (configurable thread pool)
 * - Batched GL uploads (10-16 per frame typical)
 * - Automatic retry with exponential backoff
 * - Memory-conscious cleanup scheduling
 *
 * @since MMS 1.1
 */
public final class MmsMeshPipeline {

    // Pipeline queues
    private final ExecutorService meshGenerationExecutor;
    private final Set<Chunk> chunksToGenerateMesh;
    private final Queue<MeshUploadTask> meshesReadyForGLUpload;
    private final Queue<Chunk> chunksFailedToGenerateMesh;
    private final Queue<MmsRenderableHandle> handlesPendingGpuCleanup;
    private final Map<Chunk, Integer> chunkRetryCount;

    // Dependencies
    private final ChunkErrorReporter errorReporter;
    private final WorldConfiguration config;
    private final World world;

    // State
    private volatile boolean shutdown = false;

    /**
     * Creates a new mesh pipeline.
     *
     * @param world World instance for mesh generation
     * @param config World configuration
     * @param errorReporter Error reporter for diagnostics
     */
    public MmsMeshPipeline(World world, WorldConfiguration config, ChunkErrorReporter errorReporter) {
        if (world == null) {
            throw new IllegalArgumentException("World cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }
        if (errorReporter == null) {
            throw new IllegalArgumentException("Error reporter cannot be null");
        }

        this.world = world;
        this.config = config;
        this.errorReporter = errorReporter;

        // Initialize pipeline queues
        this.meshGenerationExecutor = Executors.newFixedThreadPool(
            config.getChunkBuildThreads(),
            new ThreadFactory() {
                private int threadId = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "MMS-MeshGen-" + threadId++);
                    thread.setDaemon(true);
                    return thread;
                }
            }
        );
        this.chunksToGenerateMesh = ConcurrentHashMap.newKeySet();
        this.meshesReadyForGLUpload = new ConcurrentLinkedQueue<>();
        this.chunksFailedToGenerateMesh = new ConcurrentLinkedQueue<>();
        this.handlesPendingGpuCleanup = new ConcurrentLinkedQueue<>();
        this.chunkRetryCount = new ConcurrentHashMap<>();

        // System.out.println("[MmsMeshPipeline] Created mesh pipeline with " +
        //     config.getChunkBuildThreads() + " threads");
    }

    // === Pipeline Stage 1: Mesh Generation Scheduling ===

    /**
     * Schedules a chunk for mesh generation if it's ready.
     * Thread-safe, can be called from any thread.
     *
     * @param chunk Chunk to schedule
     */
    public void scheduleConditionalMeshBuild(Chunk chunk) {
        if (chunk == null || shutdown) {
            return;
        }

        synchronized (chunk) {
            // Check if chunk is ready for mesh generation
            if (!isReadyForMeshGeneration(chunk)) {
                return;
            }

            // Skip if already scheduled
            if (chunksToGenerateMesh.contains(chunk) ||
                containsMeshForChunk(chunk)) {
                return;
            }

            // Mark as generating and schedule
            markMeshGenerationInProgress(chunk);
            chunksToGenerateMesh.add(chunk);
        }
    }

    /**
     * Processes all scheduled mesh generation requests.
     * Submits tasks to worker thread pool.
     *
     * @param world World instance for mesh generation
     */
    public void processChunkMeshBuildRequests(World world) {
        if (chunksToGenerateMesh.isEmpty() || shutdown) {
            // Debug: Log if empty
            if (debugProcessCallCount < 3 && chunksToGenerateMesh.isEmpty()) {
                System.out.println("[MmsMeshPipeline.processChunkMeshBuildRequests] Queue is empty");
                debugProcessCallCount++;
            }
            return;
        }

        // Debug: Log processing
        if (debugProcessCallCount < 3) {
            System.out.println("[MmsMeshPipeline.processChunkMeshBuildRequests] Processing " +
                chunksToGenerateMesh.size() + " chunks");
            debugProcessCallCount++;
        }

        // Batch all pending chunks
        Set<Chunk> batchToProcess = new HashSet<>(chunksToGenerateMesh);
        chunksToGenerateMesh.clear();

        // Submit to worker threads
        for (Chunk chunk : batchToProcess) {
            meshGenerationExecutor.submit(() -> processMeshGenerationTask(world, chunk));
        }
    }

    private static int debugProcessCallCount = 0;
    private static int debugGLUploadCount = 0;
    private static int debugGLUploadSuccessCount = 0;

    /**
     * Mesh generation worker task.
     * Runs on worker thread pool.
     *
     * @param world World instance
     * @param chunk Chunk to process
     */
    private void processMeshGenerationTask(World world, Chunk chunk) {
        boolean success = false;
        MmsMeshData meshData = null;

        try {
            // Use MMS API to generate mesh
            if (!MmsAPI.isInitialized()) {
                throw new IllegalStateException("MMS API not initialized");
            }

            meshData = MmsAPI.getInstance().generateChunkMesh(chunk);
            success = meshData != null && !meshData.isEmpty();

            if (success) {
                // Queue for GL upload
                meshesReadyForGLUpload.offer(new MeshUploadTask(chunk, meshData));

                // Mark chunk state as CPU ready
                synchronized (chunk) {
                    chunk.getCcoStateManager().addState(CcoChunkState.MESH_CPU_READY);
                    chunk.getCcoDirtyTracker().clearMeshDirty();
                }
            }

        } catch (Exception e) {
            errorReporter.reportMeshBuildError(chunk, e,
                "MMS mesh generation failed for chunk (" +
                chunk.getChunkX() + ", " + chunk.getChunkZ() + ")");
            success = false;

        } finally {
            markMeshGenerationComplete(chunk);
            handleGenerationResult(chunk, success);
        }
    }

    /**
     * Handles mesh generation result (success or failure).
     *
     * @param chunk Chunk that was processed
     * @param success Whether generation succeeded
     */
    private void handleGenerationResult(Chunk chunk, boolean success) {
        if (!success) {
            int retryCount = chunkRetryCount.getOrDefault(chunk, 0);
            if (retryCount < WorldConfiguration.MAX_FAILED_CHUNK_RETRIES) {
                chunkRetryCount.put(chunk, retryCount + 1);
                chunksFailedToGenerateMesh.offer(chunk);
            } else {
                // Max retries reached
                chunkRetryCount.remove(chunk);
                errorReporter.reportMaxRetriesReached(chunk);
            }
        } else {
            // Success - clear retry count
            chunkRetryCount.remove(chunk);
        }
    }

    /**
     * Requeues failed chunks for retry.
     * Should be called periodically to retry failed generations.
     */
    public void requeueFailedChunks() {
        if (shutdown) {
            return;
        }

        Chunk failedChunk;
        while ((failedChunk = chunksFailedToGenerateMesh.poll()) != null) {
            resetMeshGenerationState(failedChunk);
            scheduleConditionalMeshBuild(failedChunk);
        }
    }

    // === Pipeline Stage 2: GPU Upload ===

    /**
     * Applies pending GL uploads for current frame.
     * MUST be called from OpenGL thread.
     */
    public void applyPendingGLUpdates() {
        if (shutdown) {
            return;
        }

        // Debug: Log first few calls
        if (debugGLUploadCount < 3) {
            System.out.println("[MmsMeshPipeline.applyPendingGLUpdates] Called with " +
                meshesReadyForGLUpload.size() + " meshes ready for upload");
            debugGLUploadCount++;
        }

        MeshUploadTask task;
        int updatesThisFrame = 0;
        int maxUpdatesPerFrame = ChunkManager.getOptimizedGLBatchSize();

        while ((task = meshesReadyForGLUpload.poll()) != null &&
               updatesThisFrame < maxUpdatesPerFrame) {

            try {
                // Upload mesh to GPU using MMS API
                MmsRenderableHandle handle = MmsAPI.getInstance().uploadMeshToGPU(task.meshData);

                // Debug: Log first few uploads
                // if (debugGLUploadSuccessCount < 3) {
                //     System.out.println("[MmsMeshPipeline] Uploaded chunk (" + task.chunk.getChunkX() + "," +
                //         task.chunk.getChunkZ() + ") with " + handle.getIndexCount() + " indices");
                //     debugGLUploadSuccessCount++;
                // }

                // Store handle in chunk for rendering
                synchronized (task.chunk) {
                    // Clean up old handle if exists
                    MmsRenderableHandle oldHandle = task.chunk.getMmsRenderableHandle();
                    if (oldHandle != null) {
                        handlesPendingGpuCleanup.offer(oldHandle);
                    }

                    // Set new handle and mark GPU ready
                    task.chunk.setMmsRenderableHandle(handle);
                    // CRITICAL: Remove MESH_CPU_READY first because mesh states are mutually exclusive!
                    task.chunk.getCcoStateManager().removeState(CcoChunkState.MESH_CPU_READY);
                    task.chunk.getCcoStateManager().addState(CcoChunkState.MESH_GPU_UPLOADED);
                    task.chunk.getCcoDirtyTracker().clearMeshDirty();

                    // Debug: Verify it was set
                    // if (debugGLUploadSuccessCount <= 3) {
                    //     System.out.println("[MmsMeshPipeline] Chunk (" + task.chunk.getChunkX() + "," +
                    //         task.chunk.getChunkZ() + ") now has handle=" + (task.chunk.getMmsRenderableHandle() != null) +
                    //         " meshGen=" + task.chunk.isMeshGenerated() +
                    //         " renderable=" + task.chunk.getCcoStateManager().isRenderable());
                    // }
                }

                updatesThisFrame++;

            } catch (Exception e) {
                errorReporter.reportGLUpdateError(task.chunk, e,
                    updatesThisFrame, maxUpdatesPerFrame,
                    meshesReadyForGLUpload.size());
            }
        }

        // Log if significant work done or high memory pressure
        if (updatesThisFrame >= 16 || ChunkManager.isHighMemoryPressure()) {
            Game.logDetailedMemoryInfo(
                "After processing " + updatesThisFrame +
                " MMS GL updates (batch size: " + maxUpdatesPerFrame + ")"
            );
        }
    }

    // === Pipeline Stage 3: GPU Cleanup ===

    /**
     * Processes pending GPU resource cleanup.
     * MUST be called from OpenGL thread.
     */
    public void processGpuCleanupQueue() {
        if (shutdown) {
            return;
        }

        MmsRenderableHandle handle;
        int cleaned = 0;

        while ((handle = handlesPendingGpuCleanup.poll()) != null) {
            try {
                handle.close(); // RAII cleanup
                cleaned++;
            } catch (Exception e) {
                System.err.println("[MmsMeshPipeline] Error cleaning GPU resource: " +
                    e.getMessage());
            }
        }

        // if (cleaned > WorldConfiguration.GPU_CLEANUP_LOG_THRESHOLD) {
        //     System.out.println("[MmsMeshPipeline] Cleaned up " + cleaned +
        //         " GPU mesh resources");
        // }
    }

    /**
     * Schedules a chunk's GPU resources for cleanup.
     * Thread-safe, can be called from any thread.
     *
     * @param chunk Chunk whose resources should be cleaned
     */
    public void addChunkForGpuCleanup(Chunk chunk) {
        if (chunk == null) {
            return;
        }

        synchronized (chunk) {
            MmsRenderableHandle handle = chunk.getMmsRenderableHandle();
            if (handle != null) {
                handlesPendingGpuCleanup.offer(handle);
                chunk.setMmsRenderableHandle(null);
                chunk.getCcoStateManager().removeState(CcoChunkState.MESH_GPU_UPLOADED);
            }
        }
    }

    // === Queue Management ===

    /**
     * Removes a chunk from all pipeline queues.
     * Used when chunk is unloaded.
     *
     * @param chunk Chunk to remove
     */
    public void removeChunkFromQueues(Chunk chunk) {
        if (chunk == null) {
            return;
        }

        chunksToGenerateMesh.remove(chunk);
        chunkRetryCount.remove(chunk);
        chunksFailedToGenerateMesh.remove(chunk);

        // Remove from upload queue
        meshesReadyForGLUpload.removeIf(task -> task.chunk == chunk);
    }

    // === CCO State Management ===

    /**
     * Checks if chunk is ready for mesh generation.
     *
     * @param chunk Chunk to check
     * @return true if ready
     */
    private boolean isReadyForMeshGeneration(Chunk chunk) {
        if (chunk == null) {
            return false;
        }

        synchronized (chunk) {
            // Ready if dirty and not already generating
            return chunk.getCcoDirtyTracker().isMeshDirty() &&
                   !chunk.getCcoStateManager().hasState(CcoChunkState.MESH_GENERATING);
        }
    }

    /**
     * Marks chunk as mesh generation in progress.
     *
     * @param chunk Chunk to mark
     */
    private void markMeshGenerationInProgress(Chunk chunk) {
        if (chunk == null) {
            return;
        }

        synchronized (chunk) {
            chunk.getCcoStateManager().addState(CcoChunkState.MESH_GENERATING);
        }
    }

    /**
     * Marks chunk mesh generation as complete.
     *
     * @param chunk Chunk to mark
     */
    private void markMeshGenerationComplete(Chunk chunk) {
        if (chunk == null) {
            return;
        }

        synchronized (chunk) {
            chunk.getCcoStateManager().removeState(CcoChunkState.MESH_GENERATING);

            // If not CPU ready, mark as dirty for retry
            if (!chunk.getCcoStateManager().hasState(CcoChunkState.MESH_CPU_READY)) {
                chunk.getCcoDirtyTracker().markMeshDirtyOnly();
            }
        }
    }

    /**
     * Resets chunk mesh generation state for retry.
     *
     * @param chunk Chunk to reset
     */
    private void resetMeshGenerationState(Chunk chunk) {
        if (chunk == null) {
            return;
        }

        synchronized (chunk) {
            chunk.getCcoDirtyTracker().markMeshDirtyOnly();
            chunk.getCcoStateManager().removeState(CcoChunkState.MESH_GENERATING);
        }
    }

    /**
     * Checks if upload queue contains a mesh for the given chunk.
     *
     * @param chunk Chunk to check
     * @return true if mesh is in upload queue
     */
    private boolean containsMeshForChunk(Chunk chunk) {
        return meshesReadyForGLUpload.stream()
            .anyMatch(task -> task.chunk == chunk);
    }

    // === Lifecycle ===

    /**
     * Shuts down the mesh pipeline and releases resources.
     */
    public void shutdown() {
        if (shutdown) {
            return;
        }

        shutdown = true;

        // Shutdown thread pool
        meshGenerationExecutor.shutdown();
        try {
            if (!meshGenerationExecutor.awaitTermination(
                    WorldConfiguration.CHUNK_BUILD_EXECUTOR_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS)) {
                System.err.println("[MmsMeshPipeline] Executor did not terminate in " +
                    WorldConfiguration.CHUNK_BUILD_EXECUTOR_TIMEOUT_SECONDS +
                    " seconds. Forcing shutdown...");
                meshGenerationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            System.err.println("[MmsMeshPipeline] Error during shutdown: " +
                e.getMessage());
            meshGenerationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Clear all queues
        clearQueues();

        // System.out.println("[MmsMeshPipeline] Pipeline shut down");
    }

    /**
     * Clears all pipeline queues.
     */
    private void clearQueues() {
        chunksToGenerateMesh.clear();
        meshesReadyForGLUpload.clear();
        chunksFailedToGenerateMesh.clear();
        chunkRetryCount.clear();

        // Clean up pending GPU resources
        processGpuCleanupQueue();
    }

    // === Debug/Statistics ===

    /**
     * Gets the number of chunks pending mesh generation.
     *
     * @return Pending mesh build count
     */
    public int getPendingMeshBuildCount() {
        return chunksToGenerateMesh.size();
    }

    /**
     * Gets the number of meshes pending GL upload.
     *
     * @return Pending GL upload count
     */
    public int getPendingGLUploadCount() {
        return meshesReadyForGLUpload.size();
    }

    /**
     * Gets the number of handles pending GPU cleanup.
     *
     * @return Pending cleanup count
     */
    public int getPendingCleanupCount() {
        return handlesPendingGpuCleanup.size();
    }

    /**
     * Gets the number of failed chunks awaiting retry.
     *
     * @return Failed chunk count
     */
    public int getFailedChunkCount() {
        return chunksFailedToGenerateMesh.size();
    }

    @Override
    public String toString() {
        return String.format(
            "MmsMeshPipeline{pending=%d, uploads=%d, cleanup=%d, failed=%d}",
            getPendingMeshBuildCount(),
            getPendingGLUploadCount(),
            getPendingCleanupCount(),
            getFailedChunkCount()
        );
    }

    // === Internal Classes ===

    /**
     * Upload task containing chunk and generated mesh data.
     */
    private static class MeshUploadTask {
        final Chunk chunk;
        final MmsMeshData meshData;

        MeshUploadTask(Chunk chunk, MmsMeshData meshData) {
            this.chunk = chunk;
            this.meshData = meshData;
        }
    }
}
