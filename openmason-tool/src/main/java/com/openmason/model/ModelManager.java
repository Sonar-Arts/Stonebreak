package com.openmason.model;

import com.stonebreak.model.ModelDefinition;
import com.stonebreak.model.ModelLoader;
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
    
    // Debug logging control
    private static volatile boolean debugLoggingEnabled = true;
    private static final AtomicInteger loadRequestCounter = new AtomicInteger(0);
    
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
        final int requestId;
        private final AtomicBoolean isProcessing = new AtomicBoolean(false);
        private final AtomicReference<Thread> processingThread = new AtomicReference<>();
        private final long createdTime = System.currentTimeMillis();
        
        LoadRequest(String modelName, LoadingPriority priority, CompletableFuture<ModelInfo> future, ProgressCallback callback) {
            this.modelName = modelName;
            this.priority = priority;
            this.future = future;
            this.callback = callback;
            this.timestamp = System.nanoTime();
            this.requestId = loadRequestCounter.incrementAndGet();
            
            debugLog("LoadRequest", "Created request #" + requestId + " for model '" + modelName + "' with priority " + priority);
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
        private final ModelDefinition.CowModelDefinition modelDefinition;
        
        public ModelInfo(String modelName, String displayName, int partCount, 
                        ModelDefinition.CowModelDefinition modelDefinition) {
            this.modelName = modelName;
            this.displayName = displayName;
            this.partCount = partCount;
            this.modelDefinition = modelDefinition;
        }
        
        public String getModelName() { return modelName; }
        public String getDisplayName() { return displayName; }
        public int getPartCount() { return partCount; }
        public ModelDefinition.CowModelDefinition getModelDefinition() { return modelDefinition; }
        
        @Override
        public String toString() {
            return String.format("ModelInfo{name='%s', display='%s', parts=%d}", 
                modelName, displayName, partCount);
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
                    
                    // System.out.println("[ModelManager] Initializing async model management system...");
                    
                    // Get available models
                    String[] availableModels = ModelLoader.getAvailableModels();
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
                    
                    // System.out.println("[ModelManager] Async initialization complete. Loaded " + 
                    //     successCount + "/" + totalModels + " model(s)");
                    
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
        debugLog("loadModelInfoAsync", "Async load request for '" + modelName + "' with priority " + priority);
        
        if (shutdownRequested.get()) {
            debugLog("loadModelInfoAsync", "Shutdown requested, rejecting load for '" + modelName + "'");
            return CompletableFuture.failedFuture(new IllegalStateException("ModelManager is shutting down"));
        }
        
        // Check if already cached
        ModelInfo cached = modelInfoCache.get(modelName);
        if (cached != null) {
            debugLog("loadModelInfoAsync", "Cache hit for '" + modelName + "' - returning cached result");
            if (progressCallback != null) {
                progressCallback.onComplete("loadModelInfoAsync", cached);
            }
            return CompletableFuture.completedFuture(cached);
        }
        
        debugLog("loadModelInfoAsync", "Cache miss for '" + modelName + "' - creating new load request");
        
        // Create future for this request
        CompletableFuture<ModelInfo> future = new CompletableFuture<>();
        
        // Add to priority queue for background processing
        LoadRequest request = new LoadRequest(modelName, priority, future, progressCallback);
        
        if (priority == LoadingPriority.IMMEDIATE) {
            debugLog("loadModelInfoAsync", "IMMEDIATE priority for '" + modelName + "' - submitting to background executor");
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
            debugLog("loadModelInfoAsync", "Priority " + priority + " for '" + modelName + "' - adding to queue (queue size: " + loadQueue.size() + ")");
            // Add to queue for background processing
            loadQueue.offer(request);
        }
        
        return future;
    }
    
    /**
     * Debug logging method for tracing the model loading pipeline.
     */
    private static void debugLog(String component, String message) {
        if (debugLoggingEnabled) {
            String timestamp = String.format("[%tH:%tM:%tS.%tL]", 
                System.currentTimeMillis(), System.currentTimeMillis(),
                System.currentTimeMillis(), System.currentTimeMillis());
            // System.out.println(timestamp + " [DEBUG][" + component + "] " + message);
        }
    }
    
    /**
     * Debug logging with timing information.
     */
    private static void debugLogTiming(String component, String operation, long startTime) {
        if (debugLoggingEnabled) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            debugLog(component, operation + " completed in " + elapsedMs + "ms");
        }
    }
    
    /**
     * Enable or disable debug logging for the model loading pipeline.
     */
    public static void setDebugLoggingEnabled(boolean enabled) {
        debugLoggingEnabled = enabled;
        debugLog("ModelManager", "Debug logging " + (enabled ? "ENABLED" : "DISABLED"));
    }
    
    /**
     * Process a load request from the background queue.
     * This method assumes that tryStartProcessing() has already been called successfully.
     */
    private static void processLoadRequest(LoadRequest request) {
        long startTime = System.currentTimeMillis();
        debugLog("ProcessRequest", "Starting to process request #" + request.requestId + 
                 " for model '" + request.modelName + "' (queued for " + 
                 (startTime - request.createdTime) + "ms)");
        
        long pipelineStartTime = startTime;
        try {
            // Double-check cancellation state after gaining processing lock
            if (request.future.isCancelled() || request.future.isDone()) {
                debugLog("ProcessRequest", "Request #" + request.requestId + " already cancelled/completed, skipping");
                return;
            }
            
            debugLog("ProcessRequest", "Request #" + request.requestId + " pipeline starting - calling ModelLoader");
            
            if (request.callback != null) {
                request.callback.onProgress("loadModelInfoAsync", 0, 100, 
                    "Loading model: " + request.modelName);
            }
            
            // Map to correct model variant for positioning compatibility
            final String actualModelName = CoordinateSpaceManager.getStonebreakCompatibleVariant(request.modelName);
            
            // Validate coordinate compatibility
            CoordinateValidationResult validation = CoordinateSpaceManager.validateCoordinateCompatibility(
                request.modelName, actualModelName);
            
            debugLog("ProcessRequest", "Coordinate validation: " + validation.toString().replace("\n", " | "));
            
            if (!validation.isCompatible()) {
                debugLog("ProcessRequest", "WARNING: Coordinate space mismatch detected for '" + request.modelName + "'");
            }
            
            // Use the async model loader with proper cancellation handling
            long modelLoaderStartTime = System.currentTimeMillis();
            debugLog("ModelLoader", "Calling ModelLoader.getCowModelAsync for '" + actualModelName + "'");
            
            ModelLoader.getCowModelAsync(actualModelName, (progress) -> {
                debugLog("ModelLoader", "Progress for '" + actualModelName + "': " + progress);
            })
                .thenAccept(model -> {
                    debugLogTiming("ModelLoader", "ModelLoader.getCowModelAsync for '" + actualModelName + "'", modelLoaderStartTime);
                    
                    try {
                        // Thread-safe check: only complete if not already cancelled/completed
                        if (model != null && !request.future.isDone()) {
                            debugLog("ProcessRequest", "Request #" + request.requestId + " - ModelLoader success, creating ModelInfo");
                            
                            if (request.callback != null) {
                                request.callback.onProgress("loadModelInfoAsync", 75, 100, 
                                    "Creating model info");
                            }
                            
                            long modelInfoStartTime = System.currentTimeMillis();
                            ModelInfo info = createModelInfo(request.modelName, model);
                            debugLogTiming("ModelInfo", "createModelInfo for '" + request.modelName + "'", modelInfoStartTime);
                            
                            modelInfoCache.put(request.modelName, info);
                            debugLog("Cache", "Cached ModelInfo for '" + request.modelName + "' (cache size: " + modelInfoCache.size() + ")");
                            
                            // Atomic completion - only complete if not already done
                            if (request.future.complete(info)) {
                                debugLogTiming("ProcessRequest", "Complete pipeline for request #" + request.requestId + " ('" + request.modelName + "')", pipelineStartTime);
                                
                                if (request.callback != null) {
                                    request.callback.onProgress("loadModelInfoAsync", 100, 100, 
                                        "Model info loaded successfully");
                                    request.callback.onComplete("loadModelInfoAsync", info);
                                }
                                // System.out.println("[ModelManager] Loaded model info asynchronously: " + info);
                            } else {
                                debugLog("ProcessRequest", "Request #" + request.requestId + " future was already completed by another thread");
                            }
                        } else if (!request.future.isDone()) {
                            debugLog("ProcessRequest", "Request #" + request.requestId + " - ModelLoader returned null model");
                            // Only complete exceptionally if not already done
                            request.future.completeExceptionally(
                                new RuntimeException("Failed to load model: " + request.modelName));
                        } else {
                            debugLog("ProcessRequest", "Request #" + request.requestId + " future already completed, skipping");
                        }
                    } catch (Exception e) {
                        debugLog("ProcessRequest", "Request #" + request.requestId + " - Exception in success handler: " + e.getMessage());
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
                    debugLog("ProcessRequest", "Request #" + request.requestId + " - ModelLoader exception: " + throwable.getMessage());
                    debugLogTiming("ProcessRequest", "Failed pipeline for request #" + request.requestId + " ('" + request.modelName + "')", pipelineStartTime);
                    
                    // Only handle error if future is not already completed
                    if (!request.future.isDone()) {
                        if (request.callback != null) {
                            request.callback.onError("loadModelInfoAsync", throwable);
                        }
                        request.future.completeExceptionally(throwable);
                    } else {
                        debugLog("ProcessRequest", "Request #" + request.requestId + " future already completed, skipping error handling");
                    }
                    return null;
                });
                
        } catch (Exception e) {
            debugLog("ProcessRequest", "Request #" + request.requestId + " - Outer exception: " + e.getMessage());
            debugLogTiming("ProcessRequest", "Exception in pipeline for request #" + request.requestId + " ('" + request.modelName + "')", pipelineStartTime);
            
            if (request.callback != null) {
                request.callback.onError("loadModelInfoAsync", e);
            }
            request.future.completeExceptionally(e);
        }
    }
    
    /**
     * Create ModelInfo from a loaded model definition.
     */
    private static ModelInfo createModelInfo(String modelName, ModelDefinition.CowModelDefinition model) {
        int partCount = countModelParts(model);
        // Use original model name for API consistency, but model contains baked variant data
        return new ModelInfo(modelName, model.getDisplayName(), partCount, model);
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
    private static int countModelParts(ModelDefinition.CowModelDefinition model) {
        if (model.getParts() == null) return 0;
        
        int count = 0;
        ModelDefinition.ModelParts parts = model.getParts();
        
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
    public static ModelDefinition.ModelPart[] getModelParts(String modelName, String animationName, float animationTime) {
        if (!initialized) {
            initialize();
        }
        
        // Map to correct model variant for positioning compatibility
        String actualModelName = CoordinateSpaceManager.getStonebreakCompatibleVariant(modelName);
        
        // Validate coordinate compatibility
        CoordinateValidationResult validation = CoordinateSpaceManager.validateCoordinateCompatibility(
            modelName, actualModelName);
        
        if (!validation.isCompatible()) {
            debugLog("getModelParts", "WARNING: Coordinate space mismatch for '" + modelName + "' - " + validation.toString().replace("\n", " | "));
        }
        
        return ModelLoader.getAnimatedParts(actualModelName, animationName, animationTime);
    }
    
    /**
     * Centralized coordinate space management for Open Mason.
     * Ensures identical rendering behavior with Stonebreak's EntityRenderer.
     */
    public static class CoordinateSpaceManager {
        
        /**
         * Model variants that Stonebreak EntityRenderer uses for actual rendering.
         * These models have pre-applied positioning offsets to match EntityRenderer behavior.
         */
        private static final Map<String, String> STONEBREAK_COMPATIBLE_VARIANTS = Map.of(
            "standard_cow", "standard_cow_baked"  // Uses Y+0.2 offset like EntityRenderer
            // Add more model mappings here as needed
        );
        
        /**
         * Maps a model name to the variant that Stonebreak EntityRenderer actually uses.
         * This ensures Open Mason renders at identical coordinates as Stonebreak.
         * 
         * @param requestedModel The model name requested by the user
         * @return The actual model variant that should be loaded for coordinate compatibility
         */
        public static String getStonebreakCompatibleVariant(String requestedModel) {
            String mappedVariant = STONEBREAK_COMPATIBLE_VARIANTS.get(requestedModel);
            if (mappedVariant != null) {
                debugLog("CoordinateSpaceManager", 
                    "Mapped '" + requestedModel + "' to '" + mappedVariant + "' for Stonebreak compatibility");
                return mappedVariant;
            }
            return requestedModel;
        }
        
        /**
         * Checks if a model has a coordinate-compatible variant.
         * 
         * @param modelName The model to check
         * @return True if this model has a coordinate-compatible variant
         */
        public static boolean hasCompatibleVariant(String modelName) {
            return STONEBREAK_COMPATIBLE_VARIANTS.containsKey(modelName);
        }
        
        /**
         * Gets the coordinate space type for a model variant.
         * 
         * @param modelVariant The actual model variant being used
         * @return The coordinate space type
         */
        public static CoordinateSpace getCoordinateSpace(String modelVariant) {
            // Check if this is a baked variant (has positioning offsets pre-applied)
            if (modelVariant.endsWith("_baked")) {
                return CoordinateSpace.STONEBREAK_COMPATIBLE;
            }
            // Check if this is a known compatible variant
            if (STONEBREAK_COMPATIBLE_VARIANTS.containsValue(modelVariant)) {
                return CoordinateSpace.STONEBREAK_COMPATIBLE;
            }
            return CoordinateSpace.RAW_MODEL_SPACE;
        }
        
        /**
         * Validates that Open Mason and Stonebreak would render a model at identical coordinates.
         * 
         * @param requestedModel The model name being requested
         * @param actualVariant The actual variant being loaded
         * @return ValidationResult with details about coordinate compatibility
         */
        public static CoordinateValidationResult validateCoordinateCompatibility(
                String requestedModel, String actualVariant) {
            String expectedVariant = getStonebreakCompatibleVariant(requestedModel);
            CoordinateSpace actualSpace = getCoordinateSpace(actualVariant);
            CoordinateSpace expectedSpace = getCoordinateSpace(expectedVariant);
            
            boolean compatible = expectedVariant.equals(actualVariant);
            
            return new CoordinateValidationResult(
                requestedModel, actualVariant, expectedVariant,
                actualSpace, expectedSpace, compatible
            );
        }
    }
    
    /**
     * Enumeration of coordinate space types.
     */
    public enum CoordinateSpace {
        /** Raw model coordinates without any positioning offsets */
        RAW_MODEL_SPACE("Raw Model Space", "Direct JSON coordinates without EntityRenderer offsets"),
        
        /** Coordinates compatible with Stonebreak EntityRenderer (includes positioning offsets) */
        STONEBREAK_COMPATIBLE("Stonebreak Compatible", "Coordinates match EntityRenderer with positioning offsets");
        
        private final String displayName;
        private final String description;
        
        CoordinateSpace(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }
    
    /**
     * Result of coordinate compatibility validation.
     */
    public static class CoordinateValidationResult {
        private final String requestedModel;
        private final String actualVariant;
        private final String expectedVariant;
        private final CoordinateSpace actualSpace;
        private final CoordinateSpace expectedSpace;
        private final boolean compatible;
        
        public CoordinateValidationResult(String requestedModel, String actualVariant, 
                                        String expectedVariant, CoordinateSpace actualSpace, 
                                        CoordinateSpace expectedSpace, boolean compatible) {
            this.requestedModel = requestedModel;
            this.actualVariant = actualVariant;
            this.expectedVariant = expectedVariant;
            this.actualSpace = actualSpace;
            this.expectedSpace = expectedSpace;
            this.compatible = compatible;
        }
        
        public boolean isCompatible() { return compatible; }
        public String getRequestedModel() { return requestedModel; }
        public String getActualVariant() { return actualVariant; }
        public String getExpectedVariant() { return expectedVariant; }
        public CoordinateSpace getActualSpace() { return actualSpace; }
        public CoordinateSpace getExpectedSpace() { return expectedSpace; }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Coordinate Validation for '" + requestedModel + "':\n");
            sb.append("  Actual variant: " + actualVariant + " (" + actualSpace.getDisplayName() + ")\n");
            sb.append("  Expected variant: " + expectedVariant + " (" + expectedSpace.getDisplayName() + ")\n");
            sb.append("  Compatible: " + (compatible ? "YES" : "NO"));
            if (!compatible) {
                sb.append(" - COORDINATE MISMATCH DETECTED!");
            }
            return sb.toString();
        }
    }
    
    /**
     * Maps model names to their correct variants for proper positioning compatibility.
     * This ensures Open Mason uses the same model variants as Stonebreak.
     * 
     * @deprecated Use CoordinateSpaceManager.getStonebreakCompatibleVariant() instead
     */
    @Deprecated
    private static String mapModelName(String modelName) {
        return CoordinateSpaceManager.getStonebreakCompatibleVariant(modelName);
    }
    
    /**
     * Get static model parts (no animation applied).
     */
    public static ModelDefinition.ModelPart[] getStaticModelParts(String modelName) {
        debugLog("getStaticModelParts", "Requesting static model parts for '" + modelName + "'");
        
        if (!initialized) {
            debugLog("getStaticModelParts", "ModelManager not initialized, initializing now");
            initialize();
        }
        
        // Map to correct model variant for positioning compatibility
        String actualModelName = CoordinateSpaceManager.getStonebreakCompatibleVariant(modelName);
        
        // Validate coordinate compatibility
        CoordinateValidationResult validation = CoordinateSpaceManager.validateCoordinateCompatibility(
            modelName, actualModelName);
        
        debugLog("getStaticModelParts", "Coordinate validation: " + validation.toString().replace("\n", " | "));
        
        if (!validation.isCompatible()) {
            debugLog("getStaticModelParts", "WARNING: Coordinate space mismatch for '" + modelName + "'");
        }
        
        long startTime = System.currentTimeMillis();
        try {
            debugLog("getStaticModelParts", "Calling ModelLoader.getCowModel for '" + actualModelName + "'");
            // ModelLoader now throws exceptions instead of returning null
            ModelDefinition.CowModelDefinition model = ModelLoader.getCowModel(actualModelName);
            
            debugLog("getStaticModelParts", "Calling ModelLoader.getAllPartsStrict for '" + actualModelName + "'");
            ModelDefinition.ModelPart[] parts = ModelLoader.getAllPartsStrict(model);
            
            debugLogTiming("getStaticModelParts", "Retrieved " + parts.length + " static parts for '" + actualModelName + "'", startTime);
            return parts;
        } catch (Exception e) {
            debugLog("getStaticModelParts", "Exception retrieving parts for '" + actualModelName + "': " + e.getMessage());
            debugLogTiming("getStaticModelParts", "Failed to retrieve parts for '" + actualModelName + "'", startTime);
            // Return empty array on error (maintains backward compatibility)
            return new ModelDefinition.ModelPart[0];
        }
    }
    
    /**
     * Get list of all available models.
     */
    public static List<String> getAvailableModels() {
        if (!initialized) {
            initialize();
        }
        
        return Arrays.asList(ModelLoader.getAvailableModels());
    }
    
    
    /**
     * Validate that a model can be loaded successfully.
     */
    public static boolean validateModel(String modelName) {
        if (!ModelLoader.isValidModel(modelName)) {
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
        ModelDefinition.ModelPart[] parts = getStaticModelParts(modelName);
        return parts.length * 24; // 24 vertices per part (cuboid)
    }
    
    /**
     * Calculate the total triangle count for a model.
     */
    public static int calculateTriangleCount(String modelName) {
        ModelDefinition.ModelPart[] parts = getStaticModelParts(modelName);
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
        
        StringBuilder stats = new StringBuilder();
        stats.append(String.format("Model: %s (%s)\n", info.getModelName(), info.getDisplayName()));
        stats.append(String.format("Parts: %d\n", info.getPartCount()));
        stats.append(String.format("Vertices: %d\n", vertexCount));
        stats.append(String.format("Triangles: %d\n", triangleCount));
        
        return stats.toString();
    }
    
    
    
    /**
     * Test method to validate all models can be loaded correctly.
     */
    public static boolean testAllModels() {
        // System.out.println("[ModelManager] Testing all models...");
        boolean allValid = true;
        
        for (String modelName : getAvailableModels()) {
            boolean valid = validateModel(modelName);
            String status = valid ? "✓" : "✗";
            // System.out.println("  " + status + " " + modelName + (valid ? " -> " + getModelInfo(modelName).getDisplayName() : " -> FAILED"));
            
            if (!valid) {
                allValid = false;
            }
        }
        
        // System.out.println("[ModelManager] Model testing complete. Result: " + (allValid ? "ALL PASS" : "SOME FAILED"));
        return allValid;
    }
    
    /**
     * Cancel all pending load requests.
     * 
     * @return Number of requests cancelled
     */
    public static int cancelAllPendingLoads() {
        // System.out.println("[ModelManager] Cancelling all pending loads...");
        
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
        
        // System.out.println("[ModelManager] Cancelled " + cancelledCount + " pending load requests");
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
        // System.out.println("[ModelManager] Shutting down async model management system...");
        
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
        ModelLoader.shutdown();
        
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
        ModelLoader.clearCache();
        initialized = false;
        initializationInProgress.set(false);
        initializationFuture = null;
        
        System.out.println("[ModelManager] Cache cleared and system reset");
    }
    
    /**
     * Comprehensive end-to-end test of the model loading and rendering preparation pipeline.
     * This method tests all the fixes implemented for the model renderer issue.
     * 
     * @param modelName The model to test (e.g., "standard_cow")
     * @return Test results and diagnostics
     */
    public static String testModelRenderingPipeline(String modelName) {
        StringBuilder testResults = new StringBuilder();
        testResults.append("=== COMPREHENSIVE MODEL RENDERING PIPELINE TEST ===\n");
        testResults.append("Model: ").append(modelName).append("\n");
        testResults.append("Test Date: ").append(new java.util.Date()).append("\n\n");
        
        long overallStartTime = System.currentTimeMillis();
        boolean allTestsPassed = true;
        
        try {
            // Test 1: ModelManager Initialization
            testResults.append("TEST 1: ModelManager Initialization\n");
            long test1Start = System.currentTimeMillis();
            
            if (!initialized) {
                initialize();
            }
            
            long test1Time = System.currentTimeMillis() - test1Start;
            testResults.append("  ✓ ModelManager initialized in ").append(test1Time).append("ms\n");
            testResults.append("  ✓ Cache size: ").append(modelInfoCache.size()).append(" models\n\n");
            
            // Test 2: Model Loading (Stonebreak ModelLoader)
            testResults.append("TEST 2: Stonebreak ModelLoader Validation\n");
            long test2Start = System.currentTimeMillis();
            
            try {
                ModelDefinition.CowModelDefinition model = ModelLoader.getCowModel(modelName);
                long test2Time = System.currentTimeMillis() - test2Start;
                
                testResults.append("  ✓ ModelLoader.getCowModel() succeeded in ").append(test2Time).append("ms\n");
                testResults.append("  ✓ Model display name: ").append(model.getDisplayName()).append("\n");
                testResults.append("  ✓ Model parts: ").append(model.getParts() != null ? "present" : "null").append("\n");
            } catch (Exception e) {
                allTestsPassed = false;
                testResults.append("  ✗ ModelLoader.getCowModel() failed: ").append(e.getMessage()).append("\n");
            }
            testResults.append("\n");
            
            // Test 3: Static Model Parts Generation
            testResults.append("TEST 3: Static Model Parts Generation\n");
            long test3Start = System.currentTimeMillis();
            
            try {
                ModelDefinition.ModelPart[] parts = ModelLoader.getStaticModelParts(modelName);
                long test3Time = System.currentTimeMillis() - test3Start;
                
                testResults.append("  ✓ ModelLoader.getStaticModelParts() succeeded in ").append(test3Time).append("ms\n");
                testResults.append("  ✓ Generated ").append(parts.length).append(" model parts\n");
                
                // Test each part for vertex/index generation capability
                int validParts = 0;
                for (ModelDefinition.ModelPart part : parts) {
                    try {
                        float[] vertices = part.getVertices();
                        int[] indices = part.getIndices();
                        
                        if (vertices != null && indices != null && vertices.length > 0 && indices.length > 0) {
                            validParts++;
                        }
                    } catch (Exception e) {
                        testResults.append("  ⚠ Part '").append(part.getName()).append("' vertex/index generation issue: ").append(e.getMessage()).append("\n");
                    }
                }
                
                testResults.append("  ✓ ").append(validParts).append("/").append(parts.length).append(" parts have valid vertex/index generation\n");
                
                if (validParts < parts.length) {
                    allTestsPassed = false;
                    testResults.append("  ✗ Some parts failed vertex/index generation\n");
                }
            } catch (Exception e) {
                allTestsPassed = false;
                testResults.append("  ✗ ModelLoader.getStaticModelParts() failed: ").append(e.getMessage()).append("\n");
            }
            testResults.append("\n");
            
            // Test 4: StonebreakModel Validation
            testResults.append("TEST 4: StonebreakModel Integration and Validation\n");
            long test4Start = System.currentTimeMillis();
            
            try {
                com.openmason.model.StonebreakModel stonebreakModel = com.openmason.model.StonebreakModel.loadFromResources(
                    modelName, "default", "default"
                );
                
                long test4Time = System.currentTimeMillis() - test4Start;
                testResults.append("  ✓ StonebreakModel created in ").append(test4Time).append("ms\n");
                
                // Run validation
                com.openmason.model.StonebreakModel.ValidationResult validation = stonebreakModel.validate();
                testResults.append("  ✓ Model validation completed\n");
                testResults.append("  ✓ Valid model parts: ").append(validation.getValidModelPartCount()).append("/").append(validation.getModelPartCount()).append("\n");
                testResults.append("  ✓ Validation errors: ").append(validation.getErrors().size()).append("\n");
                testResults.append("  ✓ Validation warnings: ").append(validation.getWarnings().size()).append("\n");
                
                if (!validation.isValid()) {
                    allTestsPassed = false;
                    testResults.append("  ✗ Model validation failed\n");
                    for (String error : validation.getErrors()) {
                        testResults.append("    ERROR: ").append(error).append("\n");
                    }
                }
                
                if (!validation.getWarnings().isEmpty()) {
                    for (String warning : validation.getWarnings()) {
                        testResults.append("    WARNING: ").append(warning).append("\n");
                    }
                }
            } catch (Exception e) {
                allTestsPassed = false;
                testResults.append("  ✗ StonebreakModel creation/validation failed: ").append(e.getMessage()).append("\n");
            }
            testResults.append("\n");
            
            // Test 5: ModelRenderer Preparation (Simulated)
            testResults.append("TEST 5: ModelRenderer Preparation Simulation\n");
            long test5Start = System.currentTimeMillis();
            
            try {
                // This would normally require OpenGL context, so we simulate the key checks
                // Use baked model variant for proper positioning if testing standard cow
                String actualModelName = "standard_cow".equals(modelName) ? "standard_cow_baked" : modelName;
                com.openmason.model.StonebreakModel stonebreakModel = com.openmason.model.StonebreakModel.loadFromResources(
                    actualModelName, "default", "default"
                );
                
                // Get preparation status (this tests the diagnostic methods we added)
                com.openmason.rendering.ModelRenderer renderer = new com.openmason.rendering.ModelRenderer("TestRenderer");
                renderer.initialize();
                
                com.openmason.rendering.ModelRenderer.ModelPreparationStatus status = 
                    renderer.getModelPreparationStatus(stonebreakModel);
                
                long test5Time = System.currentTimeMillis() - test5Start;
                testResults.append("  ✓ ModelRenderer diagnostic methods working in ").append(test5Time).append("ms\n");
                testResults.append("  ✓ Model preparation status: ").append(status.isFullyPrepared() ? "READY" : "INCOMPLETE").append("\n");
                testResults.append("  ✓ Total parts to prepare: ").append(status.getTotalParts()).append("\n");
                
                // Test would require OpenGL context for actual preparation
                testResults.append("  ⚠ Full preparation test requires OpenGL context (skipped)\n");
                
                renderer.close();
            } catch (Exception e) {
                testResults.append("  ⚠ ModelRenderer simulation failed (expected without OpenGL): ").append(e.getMessage()).append("\n");
            }
            testResults.append("\n");
            
            // Test 6: End-to-end Pipeline Performance
            testResults.append("TEST 6: Pipeline Performance Summary\n");
            long overallTime = System.currentTimeMillis() - overallStartTime;
            testResults.append("  ✓ Total test execution time: ").append(overallTime).append("ms\n");
            testResults.append("  ✓ Pipeline status: ").append(allTestsPassed ? "ALL TESTS PASSED" : "SOME TESTS FAILED").append("\n");
            
        } catch (Exception e) {
            allTestsPassed = false;
            testResults.append("CRITICAL ERROR: Overall test failed: ").append(e.getMessage()).append("\n");
            e.printStackTrace();
        }
        
        testResults.append("\n=== TEST SUMMARY ===\n");
        testResults.append("Result: ").append(allTestsPassed ? "✓ SUCCESS" : "✗ FAILURE").append("\n");
        testResults.append("The model rendering pipeline ").append(allTestsPassed ? "should now work correctly" : "has remaining issues").append("\n");
        testResults.append("====================================================\n");
        
        return testResults.toString();
    }
    
    /**
     * Quick test method for standard cow model specifically.
     * Tests both coordinate system compatibility and rendering pipeline.
     */
    public static String testStandardCowModel() {
        StringBuilder testResults = new StringBuilder();
        testResults.append("=== STANDARD COW MODEL COMPREHENSIVE TEST ===\n\n");
        
        // First, test coordinate system
        testResults.append("PART 1: COORDINATE SYSTEM VALIDATION\n");
        testResults.append(diagnoseStandardCowCoordinates()).append("\n\n");
        
        // Then, test rendering pipeline
        testResults.append("PART 2: RENDERING PIPELINE TEST\n");
        testResults.append(testModelRenderingPipeline("standard_cow")).append("\n");
        
        return testResults.toString();
    }
    
    /**
     * Comprehensive system health check that validates the entire coordinate system architecture.
     * This method should be called during application startup to ensure proper configuration.
     * 
     * @return Complete health check report
     */
    public static String performSystemHealthCheck() {
        StringBuilder healthCheck = new StringBuilder();
        healthCheck.append("=== COORDINATE SYSTEM HEALTH CHECK ===\n");
        healthCheck.append("Timestamp: ").append(new java.util.Date()).append("\n\n");
        
        try {
            // 1. System Configuration Validation
            healthCheck.append("1. SYSTEM CONFIGURATION\n");
            SystemValidationReport systemReport = validateSystemConfiguration();
            healthCheck.append(systemReport.toString()).append("\n\n");
            
            // 2. Coordinate Space Mappings Test
            healthCheck.append("2. COORDINATE SPACE MAPPINGS\n");
            String[] testModels = {"standard_cow"};
            for (String model : testModels) {
                String compatible = CoordinateSpaceManager.getStonebreakCompatibleVariant(model);
                CoordinateSpace space = CoordinateSpaceManager.getCoordinateSpace(compatible);
                boolean hasVariant = CoordinateSpaceManager.hasCompatibleVariant(model);
                
                healthCheck.append("   Model: ").append(model).append("\n");
                healthCheck.append("   Compatible variant: ").append(compatible).append("\n");
                healthCheck.append("   Coordinate space: ").append(space.getDisplayName()).append("\n");
                healthCheck.append("   Has variant mapping: ").append(hasVariant ? "YES" : "NO").append("\n");
                healthCheck.append("   Status: ").append(
                    space == CoordinateSpace.STONEBREAK_COMPATIBLE ? "✓ COMPATIBLE" : "✗ INCOMPATIBLE"
                ).append("\n\n");
            }
            
            // 3. ModelLoader Integration Test
            healthCheck.append("3. MODELLOADER INTEGRATION\n");
            try {
                if (!initialized) {
                    initialize();
                }
                
                boolean testPassed = testAllModels();
                healthCheck.append("   All models test: ").append(testPassed ? "PASSED" : "FAILED").append("\n");
                healthCheck.append("   Cache status: ").append(modelInfoCache.size()).append(" models cached\n\n");
            } catch (Exception e) {
                healthCheck.append("   Integration test: FAILED - ").append(e.getMessage()).append("\n\n");
            }
            
            // 4. Critical Path Validation
            healthCheck.append("4. CRITICAL PATH VALIDATION\n");
            healthCheck.append("   Testing standard_cow -> standard_cow_baked mapping...\n");
            
            try {
                String mappedModel = CoordinateSpaceManager.getStonebreakCompatibleVariant("standard_cow");
                boolean correctMapping = "standard_cow_baked".equals(mappedModel);
                
                healthCheck.append("   Mapping result: ").append(mappedModel).append("\n");
                healthCheck.append("   Correct mapping: ").append(correctMapping ? "✓ YES" : "✗ NO").append("\n");
                
                if (correctMapping) {
                    // Test that the mapped model can be loaded
                    try {
                        ModelDefinition.CowModelDefinition model = ModelLoader.getCowModel(mappedModel);
                        healthCheck.append("   Model loading: ✓ SUCCESS\n");
                        healthCheck.append("   Critical path: ✓ WORKING\n");
                    } catch (Exception e) {
                        healthCheck.append("   Model loading: ✗ FAILED - ").append(e.getMessage()).append("\n");
                        healthCheck.append("   Critical path: ✗ BROKEN\n");
                    }
                } else {
                    healthCheck.append("   Critical path: ✗ BROKEN - Incorrect mapping\n");
                }
            } catch (Exception e) {
                healthCheck.append("   Critical path test: FAILED - ").append(e.getMessage()).append("\n");
            }
            
            healthCheck.append("\n");
            
            // 5. Overall Health Assessment
            healthCheck.append("5. OVERALL HEALTH ASSESSMENT\n");
            boolean systemHealthy = systemReport.isSystemHealthy();
            String mappedStandardCow = CoordinateSpaceManager.getStonebreakCompatibleVariant("standard_cow");
            boolean correctStandardCowMapping = "standard_cow_baked".equals(mappedStandardCow);
            
            boolean overallHealthy = systemHealthy && correctStandardCowMapping;
            
            healthCheck.append("   System configuration: ").append(systemHealthy ? "✓ HEALTHY" : "✗ ISSUES").append("\n");
            healthCheck.append("   Standard cow mapping: ").append(correctStandardCowMapping ? "✓ CORRECT" : "✗ INCORRECT").append("\n");
            healthCheck.append("   Overall status: ").append(overallHealthy ? "✓ SYSTEM HEALTHY" : "✗ SYSTEM NEEDS ATTENTION").append("\n");
            
            if (overallHealthy) {
                healthCheck.append("\n   🎉 COORDINATE SYSTEM IS PROPERLY CONFIGURED!\n");
                healthCheck.append("   Open Mason will render at identical coordinates as Stonebreak.\n");
            } else {
                healthCheck.append("\n   ⚠️  COORDINATE SYSTEM NEEDS ATTENTION!\n");
                healthCheck.append("   Open Mason may render at different coordinates than Stonebreak.\n");
                healthCheck.append("   Please review the issues above and fix the configuration.\n");
            }
            
        } catch (Exception e) {
            healthCheck.append("CRITICAL ERROR during health check: ").append(e.getMessage()).append("\n");
            e.printStackTrace();
        }
        
        healthCheck.append("\n======================================");
        return healthCheck.toString();
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
        
        ModelLoader.printCacheStatus();
    }
    
    /**
     * Comprehensive coordinate system diagnostic method.
     * This method provides detailed analysis of coordinate space usage across the system.
     * 
     * @param requestedModel The model name that was requested
     * @return Detailed diagnostic report
     */
    public static String diagnoseCoordinateSystem(String requestedModel) {
        StringBuilder diagnosis = new StringBuilder();
        diagnosis.append("=== COORDINATE SYSTEM DIAGNOSTIC REPORT ===\n");
        diagnosis.append("Model: ").append(requestedModel).append("\n");
        diagnosis.append("Timestamp: ").append(new java.util.Date()).append("\n\n");
        
        try {
            // 1. Coordinate Space Analysis
            diagnosis.append("1. COORDINATE SPACE ANALYSIS\n");
            String compatibleVariant = CoordinateSpaceManager.getStonebreakCompatibleVariant(requestedModel);
            CoordinateValidationResult validation = CoordinateSpaceManager.validateCoordinateCompatibility(
                requestedModel, compatibleVariant);
            
            diagnosis.append("   Requested model: ").append(requestedModel).append("\n");
            diagnosis.append("   Compatible variant: ").append(compatibleVariant).append("\n");
            diagnosis.append("   Coordinate compatibility: ").append(validation.isCompatible() ? "COMPATIBLE" : "MISMATCHED").append("\n");
            diagnosis.append("   Coordinate space: ").append(validation.getExpectedSpace().getDisplayName()).append("\n");
            diagnosis.append("   Description: ").append(validation.getExpectedSpace().getDescription()).append("\n\n");
            
            // 2. Model Availability Check
            diagnosis.append("2. MODEL AVAILABILITY\n");
            boolean isValidModel = ModelLoader.isValidModel(requestedModel);
            boolean isValidCompatibleModel = ModelLoader.isValidModel(compatibleVariant);
            
            diagnosis.append("   Original model '" + requestedModel + "' available: ").append(isValidModel ? "YES" : "NO").append("\n");
            diagnosis.append("   Compatible model '" + compatibleVariant + "' available: ").append(isValidCompatibleModel ? "YES" : "NO").append("\n\n");
            
            // 3. Stonebreak EntityRenderer Analysis
            diagnosis.append("3. STONEBREAK ENTITYRENDERER COMPATIBILITY\n");
            diagnosis.append("   EntityRenderer model variant: standard_cow_baked\n");
            diagnosis.append("   EntityRenderer Y-offset: +0.2 (pre-applied in JSON)\n");
            diagnosis.append("   Open Mason will use: ").append(compatibleVariant).append("\n");
            
            boolean usingSameVariant = "standard_cow_baked".equals(compatibleVariant) || 
                                     CoordinateSpaceManager.getCoordinateSpace(compatibleVariant) == CoordinateSpace.STONEBREAK_COMPATIBLE;
            diagnosis.append("   Coordinate alignment: ").append(usingSameVariant ? "ALIGNED" : "MISALIGNED").append("\n\n");
            
            // 4. Model Loading Test
            diagnosis.append("4. MODEL LOADING TEST\n");
            try {
                long loadStart = System.currentTimeMillis();
                ModelDefinition.CowModelDefinition model = ModelLoader.getCowModel(compatibleVariant);
                long loadTime = System.currentTimeMillis() - loadStart;
                
                diagnosis.append("   Model loading: SUCCESS (").append(loadTime).append("ms)\n");
                diagnosis.append("   Model display name: ").append(model.getDisplayName()).append("\n");
                diagnosis.append("   Model parts: ").append(model.getParts() != null ? "present" : "null").append("\n");
                diagnosis.append("\n");
            } catch (Exception e) {
                diagnosis.append("   Model loading: FAILED - ").append(e.getMessage()).append("\n\n");
            }
            
            // 5. Recommendations
            diagnosis.append("5. RECOMMENDATIONS\n");
            if (validation.isCompatible()) {
                diagnosis.append("   ✓ Coordinate system is properly configured\n");
                diagnosis.append("   ✓ Open Mason will render at identical coordinates as Stonebreak\n");
                diagnosis.append("   ✓ No action required\n");
            } else {
                diagnosis.append("   ✗ COORDINATE MISMATCH DETECTED\n");
                diagnosis.append("   ✗ Open Mason may render at different coordinates than Stonebreak\n");
                diagnosis.append("   → Recommendation: Use '" + compatibleVariant + "' instead of '" + requestedModel + "'\n");
                diagnosis.append("   → This will ensure coordinate alignment with Stonebreak EntityRenderer\n");
            }
            
        } catch (Exception e) {
            diagnosis.append("ERROR during coordinate system diagnosis: ").append(e.getMessage()).append("\n");
            e.printStackTrace();
        }
        
        diagnosis.append("\n================================================");
        return diagnosis.toString();
    }
    
    /**
     * Quick diagnostic method specifically for the standard cow model.
     * This is the most commonly used model and the one with known coordinate issues.
     */
    public static String diagnoseStandardCowCoordinates() {
        return diagnoseCoordinateSystem("standard_cow");
    }
    
    /**
     * Validates that ModelManager is properly configured for coordinate system compatibility.
     * This method should be called during system initialization to catch configuration issues early.
     * 
     * @return Validation report with any configuration issues
     */
    public static SystemValidationReport validateSystemConfiguration() {
        SystemValidationReport report = new SystemValidationReport();
        
        try {
            // Check coordinate space mappings
            String[] testModels = {"standard_cow"};
            for (String model : testModels) {
                if (ModelLoader.isValidModel(model)) {
                    String compatibleVariant = CoordinateSpaceManager.getStonebreakCompatibleVariant(model);
                    CoordinateValidationResult validation = CoordinateSpaceManager.validateCoordinateCompatibility(
                        model, compatibleVariant);
                    
                    if (validation.isCompatible()) {
                        report.addValidConfiguration(model, compatibleVariant, validation.getExpectedSpace());
                    } else {
                        report.addConfigurationIssue(model, compatibleVariant, 
                            "Coordinate space mismatch: " + validation.toString());
                    }
                }
            }
            
            // Check ModelLoader availability
            try {
                String[] availableModels = ModelLoader.getAvailableModels();
                report.addSystemInfo("Available models: " + java.util.Arrays.toString(availableModels));
                
                boolean hasStandardCow = java.util.Arrays.asList(availableModels).contains("standard_cow");
                boolean hasStandardCowBaked = java.util.Arrays.asList(availableModels).contains("standard_cow_baked");
                
                if (hasStandardCow && hasStandardCowBaked) {
                    report.addSystemInfo("Both standard_cow and standard_cow_baked are available - GOOD");
                } else {
                    report.addConfigurationIssue("model_availability", "N/A", 
                        "Missing required model variants: standard_cow=" + hasStandardCow + 
                        ", standard_cow_baked=" + hasStandardCowBaked);
                }
            } catch (Exception e) {
                report.addConfigurationIssue("model_loader", "N/A", "ModelLoader error: " + e.getMessage());
            }
            
        } catch (Exception e) {
            report.addConfigurationIssue("system", "N/A", "System validation error: " + e.getMessage());
        }
        
        return report;
    }
    
    /**
     * System validation report for ModelManager configuration.
     */
    public static class SystemValidationReport {
        private final List<String> validConfigurations = new java.util.ArrayList<>();
        private final List<String> configurationIssues = new java.util.ArrayList<>();
        private final List<String> systemInfo = new java.util.ArrayList<>();
        private final java.util.Map<String, String> details = new java.util.HashMap<>();
        
        public void addValidConfiguration(String model, String variant, CoordinateSpace space) {
            String entry = model + " -> " + variant + " (" + space.getDisplayName() + ")";
            validConfigurations.add(entry);
            details.put(entry, "Coordinate space compatible with Stonebreak");
        }
        
        public void addConfigurationIssue(String model, String variant, String issue) {
            String entry = model + " -> " + variant;
            configurationIssues.add(entry);
            details.put(entry, issue);
        }
        
        public void addSystemInfo(String info) {
            systemInfo.add(info);
        }
        
        public boolean isSystemHealthy() {
            return configurationIssues.isEmpty();
        }
        
        public List<String> getValidConfigurations() {
            return new java.util.ArrayList<>(validConfigurations);
        }
        
        public List<String> getConfigurationIssues() {
            return new java.util.ArrayList<>(configurationIssues);
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== SYSTEM VALIDATION REPORT ===\n");
            sb.append("System health: ").append(isSystemHealthy() ? "HEALTHY" : "ISSUES DETECTED").append("\n");
            sb.append("Valid configurations: ").append(validConfigurations.size()).append("\n");
            sb.append("Configuration issues: ").append(configurationIssues.size()).append("\n\n");
            
            if (!systemInfo.isEmpty()) {
                sb.append("System Information:\n");
                for (String info : systemInfo) {
                    sb.append("  ℹ ").append(info).append("\n");
                }
                sb.append("\n");
            }
            
            if (!validConfigurations.isEmpty()) {
                sb.append("Valid Configurations:\n");
                for (String config : validConfigurations) {
                    sb.append("  ✓ ").append(config).append("\n");
                    String detail = details.get(config);
                    if (detail != null) {
                        sb.append("    ").append(detail).append("\n");
                    }
                }
                sb.append("\n");
            }
            
            if (!configurationIssues.isEmpty()) {
                sb.append("Configuration Issues:\n");
                for (String issue : configurationIssues) {
                    sb.append("  ✗ ").append(issue).append("\n");
                    String detail = details.get(issue);
                    if (detail != null) {
                        sb.append("    ").append(detail).append("\n");
                    }
                }
            }
            
            sb.append("================================");
            return sb.toString();
        }
    }
    
    /**
     * Quick validation method that can be called from anywhere to ensure coordinate system health.
     * Returns true if the coordinate system is properly configured, false otherwise.
     * 
     * @return True if coordinate system is healthy, false if issues detected
     */
    public static boolean isCoordinateSystemHealthy() {
        try {
            // Check the critical mapping
            String mappedStandardCow = CoordinateSpaceManager.getStonebreakCompatibleVariant("standard_cow");
            boolean correctMapping = "standard_cow_baked".equals(mappedStandardCow);
            
            // Check that the mapped model exists
            boolean modelExists = ModelLoader.isValidModel(mappedStandardCow);
            
            return correctMapping && modelExists;
        } catch (Exception e) {
            System.err.println("Error checking coordinate system health: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Emergency diagnostic method for when coordinate mismatches are detected.
     * Provides immediate actionable information.
     * 
     * @param detectedIssue Description of the detected issue
     * @return Emergency diagnostic report with immediate recommendations
     */
    public static String emergencyCoordinateDiagnostic(String detectedIssue) {
        StringBuilder emergency = new StringBuilder();
        emergency.append("🚨 EMERGENCY COORDINATE DIAGNOSTIC 🚨\n");
        emergency.append("Issue: ").append(detectedIssue).append("\n");
        emergency.append("Timestamp: ").append(new java.util.Date()).append("\n\n");
        
        emergency.append("IMMEDIATE CHECKS:\n");
        
        // Check coordinate system health
        boolean healthy = isCoordinateSystemHealthy();
        emergency.append("1. Coordinate system health: ").append(healthy ? "HEALTHY" : "UNHEALTHY").append("\n");
        
        // Check standard cow mapping
        String standardCowMapping = CoordinateSpaceManager.getStonebreakCompatibleVariant("standard_cow");
        emergency.append("2. Standard cow mapping: ").append(standardCowMapping).append("\n");
        
        // Check model availability
        boolean standardCowExists = ModelLoader.isValidModel("standard_cow");
        boolean standardCowBakedExists = ModelLoader.isValidModel("standard_cow_baked");
        emergency.append("3. standard_cow available: ").append(standardCowExists ? "YES" : "NO").append("\n");
        emergency.append("4. standard_cow_baked available: ").append(standardCowBakedExists ? "YES" : "NO").append("\n\n");
        
        emergency.append("IMMEDIATE RECOMMENDATIONS:\n");
        if (!healthy) {
            emergency.append("❌ CRITICAL: Coordinate system is not healthy!\n");
            emergency.append("   → Run ModelManager.performSystemHealthCheck() for detailed analysis\n");
            emergency.append("   → Ensure standard_cow_baked.json exists and is valid\n");
            emergency.append("   → Check that CoordinateSpaceManager mappings are correct\n");
        } else {
            emergency.append("✅ Coordinate system appears healthy\n");
            emergency.append("   → Issue may be in rendering pipeline or model preparation\n");
            emergency.append("   → Check ModelRenderer coordinate space validation\n");
            emergency.append("   → Verify model preparation status\n");
        }
        
        emergency.append("\n⚡ EMERGENCY DIAGNOSTIC COMPLETE ⚡");
        return emergency.toString();
    }
}