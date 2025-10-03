package com.stonebreak.world.chunk.api.mightyMesh.mmsCore;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mighty Mesh System - Asynchronous GPU upload system.
 *
 * Provides non-blocking mesh uploads with priority-based queue management.
 * Prevents frame stalls by batching uploads and limiting per-frame work.
 *
 * Design Philosophy:
 * - Non-blocking: Never block the render thread
 * - Priority-based: Upload nearby chunks first
 * - Budget-aware: Limit GPU work per frame
 * - Thread-safe: Concurrent submissions from multiple threads
 *
 * Architecture:
 * - Worker threads prepare mesh data
 * - Render thread polls and uploads in batches
 * - Priority queue ensures important uploads first
 *
 * Performance:
 * - Target: <2ms GPU upload time per frame
 * - Typical: 5-10 uploads per frame
 * - Max queue size: 256 pending uploads
 *
 * @since MMS 1.1
 */
public final class MmsAsyncUploader {

    // Upload configuration
    private static final int MAX_QUEUE_SIZE = 256;
    private static final int MAX_UPLOADS_PER_FRAME = 10;
    private static final long MAX_UPLOAD_TIME_MS = 2; // 2ms budget

    // Upload queue (priority-based)
    private final PriorityBlockingQueue<UploadTask> uploadQueue;

    // Statistics
    private final AtomicInteger totalSubmissions = new AtomicInteger(0);
    private final AtomicInteger totalUploads = new AtomicInteger(0);
    private final AtomicInteger totalDropped = new AtomicInteger(0);

    // State
    private volatile boolean shutdown = false;

    // Singleton instance
    private static volatile MmsAsyncUploader instance;
    private static final Object LOCK = new Object();

    /**
     * Creates a new async uploader.
     */
    private MmsAsyncUploader() {
        this.uploadQueue = new PriorityBlockingQueue<>(MAX_QUEUE_SIZE);
    }

    /**
     * Gets the async uploader singleton instance.
     *
     * @return Async uploader instance
     */
    public static MmsAsyncUploader getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new MmsAsyncUploader();
                }
            }
        }
        return instance;
    }

    /**
     * Submits a mesh for asynchronous upload.
     * Can be called from any thread.
     *
     * @param meshData Mesh data to upload
     * @param priority Upload priority (higher = more urgent)
     * @return Upload future for tracking completion
     */
    public CompletableFuture<MmsRenderableHandle> submit(MmsMeshData meshData, int priority) {
        if (shutdown) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Uploader is shut down")
            );
        }

        if (meshData == null || meshData.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // Check queue size
        if (uploadQueue.size() >= MAX_QUEUE_SIZE) {
            totalDropped.incrementAndGet();
            return CompletableFuture.failedFuture(
                new RejectedExecutionException("Upload queue full")
            );
        }

        // Create upload task
        CompletableFuture<MmsRenderableHandle> future = new CompletableFuture<>();
        UploadTask task = new UploadTask(meshData, priority, future);

        uploadQueue.offer(task);
        totalSubmissions.incrementAndGet();

        return future;
    }

    /**
     * Submits a mesh for upload with default priority.
     * Can be called from any thread.
     *
     * @param meshData Mesh data to upload
     * @return Upload future
     */
    public CompletableFuture<MmsRenderableHandle> submit(MmsMeshData meshData) {
        return submit(meshData, 0);
    }

    /**
     * Processes pending uploads for current frame.
     * MUST be called from OpenGL thread once per frame.
     *
     * @return Number of uploads processed
     */
    public int processUploads() {
        if (shutdown || uploadQueue.isEmpty()) {
            return 0;
        }

        long frameStartTime = System.currentTimeMillis();
        int uploadCount = 0;

        // Process uploads within time budget
        while (uploadCount < MAX_UPLOADS_PER_FRAME &&
               !uploadQueue.isEmpty() &&
               (System.currentTimeMillis() - frameStartTime) < MAX_UPLOAD_TIME_MS) {

            UploadTask task = uploadQueue.poll();
            if (task == null) {
                break;
            }

            try {
                // Perform GPU upload
                MmsRenderableHandle handle = MmsRenderableHandle.upload(task.meshData);
                task.future.complete(handle);
                totalUploads.incrementAndGet();
                uploadCount++;

            } catch (Exception e) {
                task.future.completeExceptionally(e);
            }
        }

        return uploadCount;
    }

    /**
     * Gets the number of pending uploads.
     *
     * @return Pending upload count
     */
    public int getPendingCount() {
        return uploadQueue.size();
    }

    /**
     * Clears all pending uploads.
     */
    public void clear() {
        while (!uploadQueue.isEmpty()) {
            UploadTask task = uploadQueue.poll();
            if (task != null) {
                task.future.cancel(false);
            }
        }
    }

    /**
     * Shuts down the uploader and cancels pending uploads.
     */
    public void shutdown() {
        shutdown = true;
        clear();
    }

    /**
     * Gets uploader statistics.
     *
     * @return Statistics string
     */
    public String getStatistics() {
        int pending = uploadQueue.size();
        double dropRate = totalSubmissions.get() > 0 ?
            (double) totalDropped.get() / totalSubmissions.get() * 100.0 : 0.0;

        return String.format(
            "MmsAsyncUploader{pending=%d, uploads=%d, submissions=%d, dropped=%d (%.1f%%)}",
            pending, totalUploads.get(), totalSubmissions.get(),
            totalDropped.get(), dropRate
        );
    }

    /**
     * Resets uploader statistics.
     */
    public void resetStatistics() {
        totalSubmissions.set(0);
        totalUploads.set(0);
        totalDropped.set(0);
    }

    /**
     * Upload task with priority.
     */
    private static class UploadTask implements Comparable<UploadTask> {
        final MmsMeshData meshData;
        final int priority;
        final CompletableFuture<MmsRenderableHandle> future;
        final long timestamp;

        UploadTask(MmsMeshData meshData, int priority,
                   CompletableFuture<MmsRenderableHandle> future) {
            this.meshData = meshData;
            this.priority = priority;
            this.future = future;
            this.timestamp = System.nanoTime();
        }

        @Override
        public int compareTo(UploadTask other) {
            // Higher priority first
            int priorityCompare = Integer.compare(other.priority, this.priority);
            if (priorityCompare != 0) {
                return priorityCompare;
            }

            // Earlier timestamp first (FIFO for same priority)
            return Long.compare(this.timestamp, other.timestamp);
        }
    }

    /**
     * Priority level constants.
     */
    public static final class Priority {
        /** Immediate priority - upload ASAP */
        public static final int IMMEDIATE = 1000;

        /** High priority - nearby chunks */
        public static final int HIGH = 100;

        /** Normal priority - visible chunks */
        public static final int NORMAL = 50;

        /** Low priority - distant chunks */
        public static final int LOW = 10;

        /** Background priority - off-screen chunks */
        public static final int BACKGROUND = 1;

        private Priority() {}
    }
}
