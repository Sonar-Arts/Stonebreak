package com.openmason.model;

import com.openmason.model.stonebreak.StonebreakModelDefinition;
import com.openmason.model.stonebreak.StonebreakModelLoader;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous high-level model management system for Open Mason Phase 2.
 * Provides both synchronous (legacy) and asynchronous APIs with progress reporting,
 * background loading queues, and comprehensive thread safety for UI responsiveness.
 * Wraps the Stonebreak model system with caching and validation.
 */
public class ModelManager {
    
    private static final Map<String, ModelInfo> modelInfoCache = new ConcurrentHashMap<>();
    private static boolean initialized = false;
    private static final AtomicBoolean initializationInProgress = new AtomicBoolean(false);
    private static CompletableFuture<Void> initializationFuture = null;
    
    // Background loading queue system
    private static final ExecutorService backgroundLoader = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ModelManager-Background-" + System.currentTimeMillis());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1); // Lower priority for background loading
        return t;
    });
    
    private static final PriorityBlockingQueue<LoadRequest> loadQueue = new PriorityBlockingQueue<>();
    private static final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    
    /**
     * Progress callback interface for async operations.
     */
    public interface ProgressCallback {
        void onProgress(String operation, int current, int total, String details);
        void onError(String operation, Throwable error);
        void onComplete(String operation, Object result);
    }
    
    /**
     * Loading priority levels for background queue management.
     */
    public enum LoadingPriority {
        IMMEDIATE(0),   // Load immediately, highest priority
        HIGH(1),        // Load as soon as possible
        NORMAL(2),      // Standard background loading
        LOW(3);         // Load when system is idle
        
        private final int priority;
        LoadingPriority(int priority) { this.priority = priority; }
        public int getPriority() { return priority; }
    }
    
    /**
     * Internal load request for priority queue with thread-safe cancellation handling.
     */
    private static class LoadRequest implements Comparable<LoadRequest> {
        final String modelName;
        final LoadingPriority priority;
        final CompletableFuture<ModelInfo> future;
        final ProgressCallback callback;
        final long timestamp;
        private final AtomicBoolean isProcessing = new AtomicBoolean(false);
        private final AtomicReference<Thread> processingThread = new AtomicReference<>();
        
        LoadRequest(String modelName, LoadingPriority priority, CompletableFuture<ModelInfo> future, ProgressCallback callback) {
            this.modelName = modelName;
            this.priority = priority;
            this.future = future;
            this.callback = callback;
            this.timestamp = System.nanoTime();
        }
        
        /**
         * Atomically try to start processing this request.
         * @return true if this thread should process the request, false if already being processed or cancelled
         */
        boolean tryStartProcessing() {
            if (future.isCancelled() || future.isDone()) {
                return false;
            }
            if (isProcessing.compareAndSet(false, true)) {
                processingThread.set(Thread.currentThread());
                return true;
            }
            return false;
        }
        
        /**
         * Mark processing as complete and clear the processing thread.
         */
        void finishProcessing() {
            processingThread.set(null);
            isProcessing.set(false);
        }
        
        /**
         * Check if this request is currently being processed.
         */
        boolean isBeingProcessed() {
            return isProcessing.get();
        }
        
        /**
         * Get the thread currently processing this request, if any.
         */
        Thread getProcessingThread() {
            return processingThread.get();
        }
        
        @Override
        public int compareTo(LoadRequest other) {
            // Lower priority number = higher actual priority
            int priorityCompare = Integer.compare(this.priority.getPriority(), other.priority.getPriority());
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            // If same priority, FIFO by timestamp
            return Long.compare(this.timestamp, other.timestamp);
        }
    }
    
    // Start background processing thread
    static {
        Thread backgroundProcessor = new Thread(() -> {
            while (!shutdownRequested.get()) {
                try {
                    LoadRequest request = loadQueue.poll(1, TimeUnit.SECONDS);
                    if (request != null) {
                        // Use atomic operation to check if we should process this request
                        if (request.tryStartProcessing()) {
                            try {
                                processLoadRequest(request);
                            } finally {
                                request.finishProcessing();
                            }
                        }
                        // If tryStartProcessing() returns false, the request was already
                        // cancelled, completed, or being processed by another thread
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (CancellationException e) {
                    // Normal cancellation, not an error condition
                    // Continue processing other requests
                } catch (Exception e) {
                    System.err.println("[ModelManager] Background processor error: " + e.getMessage());
                    // Log full stack trace for debugging in development
                    e.printStackTrace();
                }
            }
        }, "ModelManager-BackgroundProcessor");
        backgroundProcessor.setDaemon(true);
        backgroundProcessor.start();
    }
    
    /**
     * Information about a loaded model.
     */
    public static class ModelInfo {
        private final String modelName;
        private final String displayName;
        private final int partCount;
        private final int animationCount;
        private final StonebreakModelDefinition.CowModelDefinition modelDefinition;
        
        public ModelInfo(String modelName, String displayName, int partCount, int animationCount, 
                        StonebreakModelDefinition.CowModelDefinition modelDefinition) {
            this.modelName = modelName;
            this.displayName = displayName;
            this.partCount = partCount;
            this.animationCount = animationCount;
            this.modelDefinition = modelDefinition;
        }
        
        public String getModelName() { return modelName; }
        public String getDisplayName() { return displayName; }
        public int getPartCount() { return partCount; }
        public int getAnimationCount() { return animationCount; }
        public StonebreakModelDefinition.CowModelDefinition getModelDefinition() { return modelDefinition; }
        
        @Override
        public String toString() {
            return String.format("ModelInfo{name='%s', display='%s', parts=%d, animations=%d}", 
                modelName, displayName, partCount, animationCount);
        }
    }
    
    /**
     * ASYNC: Initialize the ModelManager system asynchronously with progress reporting.
     * 
     * @param progressCallback Optional progress callback
     * @return CompletableFuture that completes when initialization is done
     */
    public static CompletableFuture<Void> initializeAsync(ProgressCallback progressCallback) {
        if (initialized) {
            if (progressCallback != null) {
                progressCallback.onComplete("initializeAsync", "Already initialized");
            }
            return CompletableFuture.completedFuture(null);
        }
        
        // Check if initialization is already in progress
        if (initializationInProgress.compareAndSet(false, true)) {
            // We're the first to start initialization
            initializationFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    if (progressCallback != null) {
                        progressCallback.onProgress("initializeAsync", 0, 100, "Starting ModelManager initialization");
                    }
                    
                    System.out.println("[ModelManager] Initializing async model management system...");
                    
                    // Get available models
                    String[] availableModels = StonebreakModelLoader.getAvailableModels();
                    int totalModels = availableModels.length;
                    
                    if (progressCallback != null) {
                        progressCallback.onProgress("initializeAsync", 10, 100, 
                            "Found " + totalModels + " models to load");
                    }
                    
                    // Load all models in parallel
                    List<CompletableFuture<ModelInfo>> modelFutures = new ArrayList<>();
                    AtomicInteger completed = new AtomicInteger(0);
                    
                    for (String modelName : availableModels) {
                        CompletableFuture<ModelInfo> modelFuture = loadModelInfoAsync(modelName, 
                            LoadingPriority.HIGH, null)
                            .thenApply(info -> {
                                int currentCompleted = completed.incrementAndGet();
                                if (progressCallback != null) {
                                    int progress = 10 + (currentCompleted * 80 / totalModels);
                                    progressCallback.onProgress("initializeAsync", progress, 100, 
                                        "Loaded model " + currentCompleted + "/" + totalModels + ": " + modelName);
                                }
                                return info;
                            })
                            .exceptionally(throwable -> {
                                System.err.println("[ModelManager] Failed to load model '" + modelName + "': " + throwable.getMessage());
                                if (progressCallback != null) {
                                    progressCallback.onError("initializeAsync", throwable);
                                }
                                return null; // Continue with other models
                            });
                        modelFutures.add(modelFuture);
                    }
                    
                    // Wait for all models to complete
                    CompletableFuture.allOf(modelFutures.toArray(new CompletableFuture[0])).join();
                    
                    // Count successful loads
                    long successCount = modelFutures.stream()
                        .mapToLong(future -> {
                            try {
                                return future.get() != null ? 1 : 0;
                            } catch (Exception e) {
                                return 0;
                            }
                        })
                        .sum();
                    
                    initialized = true;
                    
                    if (progressCallback != null) {
                        progressCallback.onProgress("initializeAsync", 100, 100, 
                            "Initialization complete: " + successCount + "/" + totalModels + " models loaded");
                        progressCallback.onComplete("initializeAsync", 
                            "ModelManager initialized with " + successCount + " models");
                    }
                    
                    System.out.println("[ModelManager] Async initialization complete. Loaded " + 
                        successCount + "/" + totalModels + " model(s)");
                    
                    return null;
                    
                } catch (Exception e) {
                    if (progressCallback != null) {
                        progressCallback.onError("initializeAsync", e);
                    }
                    throw new RuntimeException("ModelManager initialization failed: " + e.getMessage(), e);
                } finally {
                    initializationInProgress.set(false);
                }
            }, backgroundLoader);
            
            return initializationFuture;
        } else {
            // Initialization already in progress, return existing future
            return initializationFuture != null ? initializationFuture : CompletableFuture.completedFuture(null);
        }
    }
    
    /**
     * LEGACY: Initialize the ModelManager system synchronously.
     * Preserved for backward compatibility but now uses async system internally.
     */
    public static synchronized void initialize() {
        try {
            initializeAsync(null).get(); // Block until async initialization completes
        } catch (Exception e) {
            System.err.println("[ModelManager] Synchronous initialization failed: " + e.getMessage());
            throw new RuntimeException("Failed to initialize ModelManager", e);
        }
    }
    
    /**
     * ASYNC: Load model information asynchronously with priority support.
     * 
     * @param modelName The model to load info for
     * @param priority Loading priority
     * @param progressCallback Optional progress callback
     * @return CompletableFuture that resolves to ModelInfo
     */
    public static CompletableFuture<ModelInfo> loadModelInfoAsync(String modelName, 
                                                                  LoadingPriority priority, 
                                                                  ProgressCallback progressCallback) {
        if (shutdownRequested.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("ModelManager is shutting down"));
        }
        
        // Check if already cached
        ModelInfo cached = modelInfoCache.get(modelName);
        if (cached != null) {
            if (progressCallback != null) {
                progressCallback.onComplete("loadModelInfoAsync", cached);
            }
            return CompletableFuture.completedFuture(cached);
        }
        
        // Create future for this request
        CompletableFuture<ModelInfo> future = new CompletableFuture<>();
        
        // Add to priority queue for background processing
        LoadRequest request = new LoadRequest(modelName, priority, future, progressCallback);
        
        if (priority == LoadingPriority.IMMEDIATE) {
            // Process immediately with thread-safe handling
            backgroundLoader.submit(() -> {
                if (request.tryStartProcessing()) {
                    try {
                        processLoadRequest(request);
                    } finally {
                        request.finishProcessing();
                    }
                }
            });
        } else {
            // Add to queue for background processing
            loadQueue.offer(request);
        }
        
        return future;
    }
    
    /**
     * Process a load request from the background queue.
     * This method assumes that tryStartProcessing() has already been called successfully.
     */
    private static void processLoadRequest(LoadRequest request) {
        try {
            // Double-check cancellation state after gaining processing lock
            if (request.future.isCancelled() || request.future.isDone()) {
                return;
            }
            
            if (request.callback != null) {
                request.callback.onProgress("loadModelInfoAsync", 0, 100, 
                    "Loading model: " + request.modelName);
            }
            
            // Use the async model loader with proper cancellation handling
            StonebreakModelLoader.getCowModelAsync(request.modelName, 
                StonebreakModelLoader.LoadingPriority.NORMAL, null)
                .thenAccept(model -> {
                    try {
                        // Thread-safe check: only complete if not already cancelled/completed
                        if (model != null && !request.future.isDone()) {
                            if (request.callback != null) {
                                request.callback.onProgress("loadModelInfoAsync", 75, 100, 
                                    "Creating model info");
                            }
                            
                            ModelInfo info = createModelInfo(request.modelName, model);
                            modelInfoCache.put(request.modelName, info);
                            
                            // Atomic completion - only complete if not already done
                            if (request.future.complete(info)) {
                                if (request.callback != null) {
                                    request.callback.onProgress("loadModelInfoAsync", 100, 100, 
                                        "Model info loaded successfully");
                                    request.callback.onComplete("loadModelInfoAsync", info);
                                }
                                System.out.println("[ModelManager] Loaded model info asynchronously: " + info);
                            }
                        } else if (!request.future.isDone()) {
                            // Only complete exceptionally if not already done
                            request.future.completeExceptionally(
                                new RuntimeException("Failed to load model: " + request.modelName));
                        }
                    } catch (Exception e) {
                        // Only handle error if future is not already completed
                        if (!request.future.isDone()) {
                            if (request.callback != null) {
                                request.callback.onError("loadModelInfoAsync", e);
                            }
                            request.future.completeExceptionally(e);
                        }
                    }
                })
                .exceptionally(throwable -> {
                    // Only handle error if future is not already completed
                    if (!request.future.isDone()) {
                        if (request.callback != null) {
                            request.callback.onError("loadModelInfoAsync", throwable);
                        }
                        request.future.completeExceptionally(throwable);
                    }
                    return null;
                });
                
        } catch (Exception e) {
            if (request.callback != null) {
                request.callback.onError("loadModelInfoAsync", e);
            }
            request.future.completeExceptionally(e);
        }
    }
    
    /**
     * Create ModelInfo from a loaded model definition.
     */
    private static ModelInfo createModelInfo(String modelName, StonebreakModelDefinition.CowModelDefinition model) {
        int partCount = countModelParts(model);
        int animationCount = model.getAnimations() != null ? model.getAnimations().size() : 0;
        return new ModelInfo(modelName, model.getDisplayName(), partCount, animationCount, model);
    }
    
    /**
     * LEGACY: Get information about a specific model.
     * Now uses async system with blocking for backward compatibility.
     */
    public static ModelInfo getModelInfo(String modelName) {
        try {
            // Use async loading with IMMEDIATE priority for legacy compatibility
            return loadModelInfoAsync(modelName, LoadingPriority.IMMEDIATE, null).get();
        } catch (CancellationException e) {
            System.err.println("[ModelManager] Model loading was cancelled for '" + modelName + "'");
            return null;
        } catch (Exception e) {
            System.err.println("[ModelManager] Failed to get model info for '" + modelName + "': " + e.getMessage());
            return null;
        }
    }
    
    // Removed legacy loadModelInfo method - now handled by async system
    
    /**
     * Count the total number of parts in a model.
     */
    private static int countModelParts(StonebreakModelDefinition.CowModelDefinition model) {
        if (model.getParts() == null) return 0;
        
        int count = 0;
        StonebreakModelDefinition.ModelParts parts = model.getParts();
        
        if (parts.getBody() != null) count++;
        if (parts.getHead() != null) count++;
        if (parts.getLegs() != null) count += parts.getLegs().size();
        if (parts.getHorns() != null) count += parts.getHorns().size();
        if (parts.getUdder() != null) count++;
        if (parts.getTail() != null) count++;
        
        return count;
    }
    
    /**
     * Get all model parts for a specific model and animation.
     */
    public static StonebreakModelDefinition.ModelPart[] getModelParts(String modelName, String animationName, float animationTime) {
        if (!initialized) {
            initialize();
        }
        
        return StonebreakModelLoader.getAnimatedParts(modelName, animationName, animationTime);
    }
    
    /**
     * Get static model parts (no animation applied).
     */
    public static StonebreakModelDefinition.ModelPart[] getStaticModelParts(String modelName) {
        if (!initialized) {
            initialize();
        }
        
        StonebreakModelDefinition.CowModelDefinition model = StonebreakModelLoader.getCowModel(modelName);
        if (model == null) {
            return new StonebreakModelDefinition.ModelPart[0];
        }
        
        return StonebreakModelLoader.getAllParts(model);
    }
    
    /**
     * Get list of all available models.
     */
    public static List<String> getAvailableModels() {
        if (!initialized) {
            initialize();
        }
        
        return Arrays.asList(StonebreakModelLoader.getAvailableModels());
    }
    
    /**
     * Get list of all available animations for a model.
     */
    public static List<String> getAvailableAnimations(String modelName) {
        ModelInfo info = getModelInfo(modelName);
        if (info == null || info.getModelDefinition().getAnimations() == null) {
            return List.of();
        }
        
        return List.copyOf(info.getModelDefinition().getAnimations().keySet());
    }
    
    /**
     * Validate that a model can be loaded successfully.
     */
    public static boolean validateModel(String modelName) {
        if (!StonebreakModelLoader.isValidModel(modelName)) {
            return false;
        }
        
        try {
            ModelInfo info = getModelInfo(modelName);
            return info != null;
        } catch (Exception e) {
            System.err.println("[ModelManager] Model validation failed for '" + modelName + "': " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Calculate the total vertex count for a model.
     */
    public static int calculateVertexCount(String modelName) {
        StonebreakModelDefinition.ModelPart[] parts = getStaticModelParts(modelName);
        return parts.length * 24; // 24 vertices per part (cuboid)
    }
    
    /**
     * Calculate the total triangle count for a model.
     */
    public static int calculateTriangleCount(String modelName) {
        StonebreakModelDefinition.ModelPart[] parts = getStaticModelParts(modelName);
        return parts.length * 12; // 12 triangles per part (6 faces × 2 triangles)
    }
    
    /**
     * Get detailed statistics about a model.
     */
    public static String getModelStatistics(String modelName) {
        ModelInfo info = getModelInfo(modelName);
        if (info == null) {
            return "Model not found: " + modelName;
        }
        
        int vertexCount = calculateVertexCount(modelName);
        int triangleCount = calculateTriangleCount(modelName);
        List<String> animations = getAvailableAnimations(modelName);
        
        StringBuilder stats = new StringBuilder();
        stats.append(String.format("Model: %s (%s)\n", info.getModelName(), info.getDisplayName()));
        stats.append(String.format("Parts: %d\n", info.getPartCount()));
        stats.append(String.format("Vertices: %d\n", vertexCount));
        stats.append(String.format("Triangles: %d\n", triangleCount));
        stats.append(String.format("Animations: %d [%s]\n", info.getAnimationCount(), String.join(", ", animations)));
        
        return stats.toString();
    }
    
    
    
    /**
     * Test method to validate all models can be loaded correctly.
     */
    public static boolean testAllModels() {
        System.out.println("[ModelManager] Testing all models...");
        boolean allValid = true;
        
        for (String modelName : getAvailableModels()) {
            boolean valid = validateModel(modelName);
            String status = valid ? "✓" : "✗";
            System.out.println("  " + status + " " + modelName + (valid ? " -> " + getModelInfo(modelName).getDisplayName() : " -> FAILED"));
            
            if (!valid) {
                allValid = false;
            }
        }
        
        System.out.println("[ModelManager] Model testing complete. Result: " + (allValid ? "ALL PASS" : "SOME FAILED"));
        return allValid;
    }
    
    /**
     * Cancel all pending load requests.
     * 
     * @return Number of requests cancelled
     */
    public static int cancelAllPendingLoads() {
        System.out.println("[ModelManager] Cancelling all pending loads...");
        
        int cancelledCount = 0;
        LoadRequest request;
        while ((request = loadQueue.poll()) != null) {
            // Cancel the future first
            if (request.future.cancel(false)) {
                cancelledCount++;
            }
            // If the request is currently being processed, interrupt the processing thread
            // Note: We don't interrupt here as it could cause issues with the underlying model loader
            // The processing thread will check cancellation status and exit gracefully
        }
        
        System.out.println("[ModelManager] Cancelled " + cancelledCount + " pending load requests");
        return cancelledCount;
    }
    
    /**
     * Get the number of pending load requests in the queue.
     * 
     * @return Number of pending requests
     */
    public static int getPendingLoadCount() {
        return loadQueue.size();
    }
    
    /**
     * Get the number of requests currently being processed.
     * 
     * @return Number of requests currently being processed
     */
    public static int getProcessingCount() {
        return (int) loadQueue.stream()
            .filter(LoadRequest::isBeingProcessed)
            .count();
    }
    
    /**
     * Get detailed information about current load queue status.
     * 
     * @return Status string with queue and processing information
     */
    public static String getLoadQueueStatus() {
        int pending = getPendingLoadCount();
        int processing = getProcessingCount();
        StringBuilder status = new StringBuilder();
        status.append(String.format("Load Queue Status: %d pending, %d processing\n", pending, processing));
        
        if (pending > 0 || processing > 0) {
            status.append("Current requests:\n");
            loadQueue.forEach(request -> {
                String state = request.isBeingProcessed() ? "PROCESSING" : "PENDING";
                Thread processingThread = request.getProcessingThread();
                String threadInfo = processingThread != null ? " (" + processingThread.getName() + ")" : "";
                status.append(String.format("  - %s [%s] %s%s\n", 
                    request.modelName, request.priority, state, threadInfo));
            });
        }
        
        return status.toString();
    }
    
    /**
     * Check if ModelManager is currently initializing.
     * 
     * @return true if initialization is in progress
     */
    public static boolean isInitializing() {
        return initializationInProgress.get();
    }
    
    /**
     * Shutdown the ModelManager system gracefully.
     * This should be called when the application is shutting down.
     */
    public static void shutdown() {
        System.out.println("[ModelManager] Shutting down async model management system...");
        
        shutdownRequested.set(true);
        
        // Cancel pending loads
        int cancelledPending = cancelAllPendingLoads();
        System.out.println("[ModelManager] Cancelled " + cancelledPending + " pending loads");
        
        // Shutdown background executor
        backgroundLoader.shutdown();
        try {
            if (!backgroundLoader.awaitTermination(5, TimeUnit.SECONDS)) {
                backgroundLoader.shutdownNow();
                System.out.println("[ModelManager] Forced shutdown of background executor");
            } else {
                System.out.println("[ModelManager] Background executor shutdown gracefully");
            }
        } catch (InterruptedException e) {
            backgroundLoader.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Shutdown underlying model loader
        StonebreakModelLoader.shutdown();
        
        System.out.println("[ModelManager] Shutdown complete");
    }
    
    /**
     * Clear all cached model data and reset initialization state.
     */
    public static synchronized void clearCache() {
        System.out.println("[ModelManager] Clearing model cache...");
        
        // Cancel any pending operations
        cancelAllPendingLoads();
        
        modelInfoCache.clear();
        StonebreakModelLoader.clearCache();
        initialized = false;
        initializationInProgress.set(false);
        initializationFuture = null;
        
        System.out.println("[ModelManager] Cache cleared and system reset");
    }
    
    /**
     * Enhanced status printing with async information.
     */
    public static void printAllModelInfo() {
        if (!initialized && !isInitializing()) {
            System.out.println("[ModelManager] System not initialized. Call initializeAsync() first.");
            return;
        }
        
        System.out.println("[ModelManager] === Async Model Management System Status ===");
        System.out.println("  Initialized: " + initialized);
        System.out.println("  Initializing: " + isInitializing());
        System.out.println("  Pending loads: " + getPendingLoadCount());
        System.out.println("  Processing loads: " + getProcessingCount());
        System.out.println("  Cached models: " + modelInfoCache.size());
        System.out.println("  Shutdown requested: " + shutdownRequested.get());
        System.out.println();
        
        // Print detailed queue status if there are active operations
        if (getPendingLoadCount() > 0 || getProcessingCount() > 0) {
            System.out.println(getLoadQueueStatus());
        }
        
        if (initialized) {
            for (ModelInfo info : modelInfoCache.values()) {
                System.out.println(getModelStatistics(info.getModelName()));
                System.out.println("---");
            }
        }
        
        StonebreakModelLoader.printCacheStatus();
    }
}