package com.openmason.model;

import com.stonebreak.model.ModelDefinition;
import com.stonebreak.model.ModelLoader;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous cow model management system for Open Mason.
 *
 * <p>This manager specifically handles cow mob models (standard_cow, standard_cow_baked, etc.)
 * from the Stonebreak model system. It provides both synchronous (legacy) and asynchronous APIs
 * with progress reporting, background loading queues, and comprehensive thread safety for UI
 * responsiveness.</p>
 *
 * <p><b>Scope:</b> This class only manages cow models ({@link ModelDefinition.CowModelDefinition}).
 * For other mob types or model types, a separate manager would be needed.</p>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li>Async cow model loading with priority-based queue</li>
 *   <li>Coordinate space management for Stonebreak compatibility</li>
 *   <li>Model caching and validation</li>
 *   <li>Thread-safe operations with cancellation support</li>
 * </ul>
 *
 * @deprecated This cow-specific model manager is deprecated due to its overly narrow scope.
 *             It only handles cow models and cannot be extended to other mob types without significant
 *             refactoring. Direct use of {@link com.stonebreak.model.ModelLoader} is recommended until
 *             a replacement general-purpose model management system is implemented.
 */
@Deprecated
public class LegacyCowModelManager {
    
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
     * Information about a loaded cow model.
     *
     * <p>Contains metadata and the actual {@link ModelDefinition.CowModelDefinition} for
     * a specific cow model variant (e.g., standard_cow, standard_cow_baked).</p>
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
     * ASYNC: Initialize the cow model manager asynchronously with progress reporting.
     *
     * <p>Loads all available cow models from the Stonebreak model system in parallel.
     * This should be called during application startup to pre-cache cow model data.</p>
     *
     * @param progressCallback Optional progress callback for tracking load progress
     * @return CompletableFuture that completes when all cow models are initialized
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
     * LEGACY: Initialize the cow model manager synchronously.
     *
     * <p>Preserved for backward compatibility but now uses async system internally.
     * Blocks until all cow models are loaded.</p>
     *
     * @deprecated Use {@link #initializeAsync(ProgressCallback)} for better UI responsiveness
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
     * ASYNC: Load cow model information asynchronously with priority support.
     *
     * <p>Loads a specific cow model variant (e.g., "standard_cow", "standard_cow_baked")
     * from the Stonebreak model system with priority-based queue management.</p>
     *
     * @param modelName The cow model variant to load (e.g., "standard_cow")
     * @param priority Loading priority in the background queue
     * @param progressCallback Optional progress callback for tracking load progress
     * @return CompletableFuture that resolves to cow model information
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
     * LEGACY: Get information about a specific cow model synchronously.
     *
     * <p>Now uses async system with blocking for backward compatibility.
     * Prefer {@link #loadModelInfoAsync} for better performance.</p>
     *
     * @param modelName The cow model variant name (e.g., "standard_cow")
     * @return ModelInfo for the cow model, or null if loading fails
     * @deprecated Use {@link #loadModelInfoAsync(String, LoadingPriority, ProgressCallback)} instead
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
     * Centralized coordinate space management for cow models in Open Mason.
     *
     * <p>Ensures identical rendering behavior with Stonebreak's EntityRenderer by mapping
     * cow model names to their coordinate-compatible variants. For example, "standard_cow"
     * is mapped to "standard_cow_baked" which has pre-applied Y+0.2 positioning offsets.</p>
     *
     * <p><b>Purpose:</b> Prevents coordinate mismatches where Open Mason would render cows
     * at different positions than Stonebreak's in-game renderer.</p>
     */
    public static class CoordinateSpaceManager {

        /**
         * Cow model variants that Stonebreak EntityRenderer uses for actual rendering.
         * These cow models have pre-applied positioning offsets to match EntityRenderer behavior.
         *
         * <p>Example: standard_cow → standard_cow_baked (includes Y+0.2 offset)</p>
         */
        private static final Map<String, String> STONEBREAK_COMPATIBLE_VARIANTS = Map.of(
            "standard_cow", "standard_cow_baked"  // Uses Y+0.2 offset like EntityRenderer
            // Add more cow model variant mappings here as needed
        );
        
        /**
         * Maps a cow model name to the variant that Stonebreak EntityRenderer actually uses.
         *
         * <p>This ensures Open Mason renders cows at identical coordinates as Stonebreak.
         * For example, "standard_cow" maps to "standard_cow_baked".</p>
         *
         * @param requestedModel The cow model name requested (e.g., "standard_cow")
         * @return The actual cow model variant that should be loaded for coordinate compatibility
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
         * Checks if a cow model has a coordinate-compatible variant.
         *
         * @param modelName The cow model to check (e.g., "standard_cow")
         * @return True if this cow model has a coordinate-compatible variant
         */
        public static boolean hasCompatibleVariant(String modelName) {
            return STONEBREAK_COMPATIBLE_VARIANTS.containsKey(modelName);
        }
        
        /**
         * Gets the coordinate space type for a cow model variant.
         *
         * @param modelVariant The actual cow model variant being used (e.g., "standard_cow_baked")
         * @return The coordinate space type (RAW_MODEL_SPACE or STONEBREAK_COMPATIBLE)
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
         * Validates that Open Mason and Stonebreak would render a cow at identical coordinates.
         *
         * @param requestedModel The cow model name being requested (e.g., "standard_cow")
         * @param actualVariant The actual cow variant being loaded (e.g., "standard_cow_baked")
         * @return ValidationResult with details about cow coordinate compatibility
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
     * Result of cow model coordinate compatibility validation.
     *
     * <p>Reports whether a requested cow model variant matches the expected Stonebreak-compatible
     * variant, ensuring identical rendering coordinates between Open Mason and Stonebreak.</p>
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
     * Get static cow model parts (no animation applied).
     *
     * <p>Retrieves all parts for a cow model in their default (non-animated) positions.
     * Automatically uses the coordinate-compatible cow model variant for Stonebreak alignment.</p>
     *
     * @param modelName The cow model variant name (e.g., "standard_cow")
     * @return Array of static cow model parts, or empty array on error
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
     * Check if the cow model manager is currently initializing.
     *
     * @return true if cow model initialization is in progress
     */
    public static boolean isInitializing() {
        return initializationInProgress.get();
    }

}