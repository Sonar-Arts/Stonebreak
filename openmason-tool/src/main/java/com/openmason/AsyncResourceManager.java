package com.openmason;

import com.openmason.model.ModelManager;
import com.stonebreak.model.ModelLoader;
import com.openmason.texture.TextureManager;
import com.stonebreak.textures.CowTextureLoader;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central coordinator for asynchronous resource loading across all Open Mason systems.
 * 
 * Provides:
 * - Non-blocking initialization of model and texture systems
 * - Progress reporting for UI integration
 * - Centralized operation tracking and cancellation
 * - Error handling and recovery coordination
 * - Memory management across all systems
 * 
 * This class ensures that Open Mason's UI remains responsive during resource loading
 * while providing comprehensive progress feedback and error handling.
 */
public class AsyncResourceManager {
    
    // Initialization state management
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean initializationInProgress = new AtomicBoolean(false);
    private static final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    
    // Operation tracking
    private static final Map<String, CompletableFuture<?>> activeOperations = new ConcurrentHashMap<>();
    private static volatile CompletableFuture<Void> initializationFuture = null;
    
    // Configuration
    private static final ExecutorService coordinatorExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "AsyncResourceManager-Coordinator");
        t.setDaemon(true);
        return t;
    });
    
    /**
     * Progress callback interface for initialization operations.
     */
    public interface ProgressCallback {
        void onProgress(String operation, int current, int total, String details);
        void onError(String operation, Throwable error);
        void onComplete(String operation, Object result);
    }
    
    /**
     * Configuration options for resource initialization.
     */
    public static class InitializationOptions {
        public boolean initializeModels = true;
        public boolean initializeTextures = true;
        public boolean enableParallelLoading = true;
        public long timeoutMillis = 30000; // 30 seconds
        
        public static InitializationOptions defaults() {
            return new InitializationOptions();
        }
        
        public InitializationOptions withModels(boolean enable) {
            this.initializeModels = enable;
            return this;
        }
        
        public InitializationOptions withTextures(boolean enable) {
            this.initializeTextures = enable;
            return this;
        }
        
        public InitializationOptions withParallelLoading(boolean enable) {
            this.enableParallelLoading = enable;
            return this;
        }
        
        public InitializationOptions withTimeout(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }
    }
    
    /**
     * Initialize all resource systems asynchronously with comprehensive progress reporting.
     * 
     * @param options Configuration for initialization behavior
     * @param progressCallback Optional progress callback for UI updates
     * @return CompletableFuture that completes when all systems are initialized
     */
    public static CompletableFuture<Void> initializeAsync(InitializationOptions options, ProgressCallback progressCallback) {
        if (shutdownRequested.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("AsyncResourceManager is shutting down"));
        }
        
        // Check if already initialized
        if (initialized.get()) {
            if (progressCallback != null) {
                progressCallback.onComplete("initializeAsync", "Already initialized");
            }
            return CompletableFuture.completedFuture(null);
        }
        
        // Check if initialization is in progress
        if (initializationInProgress.compareAndSet(false, true)) {
            // We're the first to start initialization
            String operationId = "AsyncResourceManager-Init-" + System.currentTimeMillis();
            
            initializationFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    if (progressCallback != null) {
                        progressCallback.onProgress(operationId, 0, 100, "Starting AsyncResourceManager initialization");
                    }
                    
                    List<CompletableFuture<Void>> systemFutures = new ArrayList<>();
                    final AtomicInteger completedSystems = new AtomicInteger(0);
                    final int[] totalSystems = {0};
                    
                    // Initialize model management system
                    if (options.initializeModels) {
                        totalSystems[0]++;
                        CompletableFuture<Void> modelFuture = ModelManager.initializeAsync(
                            new ModelManager.ProgressCallback() {
                                @Override
                                public void onProgress(String operation, int current, int total, String details) {
                                    if (progressCallback != null) {
                                        int overallProgress = 10 + (completedSystems.get() * 40) + (current * 40 / 100 / (totalSystems[0] > 0 ? totalSystems[0] : 1));
                                        progressCallback.onProgress(operationId, overallProgress, 100, 
                                            "Models: " + details);
                                    }
                                }
                                
                                @Override
                                public void onError(String operation, Throwable error) {
                                    if (progressCallback != null) {
                                        progressCallback.onError(operationId, error);
                                    }
                                }
                                
                                @Override
                                public void onComplete(String operation, Object result) {
                                    completedSystems.incrementAndGet();
                                    if (progressCallback != null) {
                                        progressCallback.onProgress(operationId, 10 + completedSystems.get() * 40, 100, 
                                            "Model system initialized");
                                    }
                                }
                            }
                        );
                        systemFutures.add(modelFuture);
                    }
                    
                    // Initialize texture management system
                    if (options.initializeTextures) {
                        totalSystems[0]++;
                        CompletableFuture<Void> textureFuture = TextureManager.initializeAsync(
                            new TextureManager.ProgressCallback() {
                                @Override
                                public void onProgress(String operation, int current, int total, String details) {
                                    if (progressCallback != null) {
                                        int overallProgress = 10 + (completedSystems.get() * 40) + (current * 40 / 100 / (totalSystems[0] > 0 ? totalSystems[0] : 1));
                                        progressCallback.onProgress(operationId, overallProgress, 100, 
                                            "Textures: " + details);
                                    }
                                }
                                
                                @Override
                                public void onError(String operation, Throwable error) {
                                    if (progressCallback != null) {
                                        progressCallback.onError(operationId, error);
                                    }
                                }
                                
                                @Override
                                public void onComplete(String operation, Object result) {
                                    completedSystems.incrementAndGet();
                                    if (progressCallback != null) {
                                        progressCallback.onProgress(operationId, 10 + completedSystems.get() * 40, 100, 
                                            "Texture system initialized");
                                    }
                                }
                            }
                        );
                        systemFutures.add(textureFuture);
                    }
                    
                    if (progressCallback != null) {
                        progressCallback.onProgress(operationId, 10, 100, 
                            "Initializing " + totalSystems + " resource systems");
                    }
                    
                    // Wait for all systems to complete
                    if (options.enableParallelLoading && systemFutures.size() > 1) {
                        // Parallel initialization
                        CompletableFuture.allOf(systemFutures.toArray(new CompletableFuture[0])).get(
                            options.timeoutMillis, TimeUnit.MILLISECONDS);
                    } else {
                        // Sequential initialization
                        for (CompletableFuture<Void> future : systemFutures) {
                            future.get(options.timeoutMillis, TimeUnit.MILLISECONDS);
                        }
                    }
                    
                    initialized.set(true);
                    
                    if (progressCallback != null) {
                        progressCallback.onProgress(operationId, 100, 100, 
                            "All resource systems initialized successfully");
                        progressCallback.onComplete(operationId, 
                            "AsyncResourceManager initialized with " + totalSystems + " systems");
                    }
                    
                    System.out.println("[AsyncResourceManager] Initialization complete. " + 
                        totalSystems + " system(s) initialized successfully.");
                    
                    return null;
                    
                } catch (Exception e) {
                    if (progressCallback != null) {
                        progressCallback.onError(operationId, e);
                    }
                    throw new RuntimeException("AsyncResourceManager initialization failed: " + e.getMessage(), e);
                } finally {
                    initializationInProgress.set(false);
                    activeOperations.remove(operationId);
                }
            }, coordinatorExecutor);
            
            activeOperations.put(operationId, initializationFuture);
            return initializationFuture;
        } else {
            // Initialization already in progress, return existing future
            return initializationFuture != null ? initializationFuture : CompletableFuture.completedFuture(null);
        }
    }
    
    /**
     * Initialize with default options.
     */
    public static CompletableFuture<Void> initializeAsync(ProgressCallback progressCallback) {
        return initializeAsync(InitializationOptions.defaults(), progressCallback);
    }
    
    /**
     * Initialize synchronously (blocking) for backward compatibility.
     */
    public static void initialize() {
        try {
            initializeAsync(null).get();
        } catch (Exception e) {
            System.err.println("[AsyncResourceManager] Synchronous initialization failed: " + e.getMessage());
            throw new RuntimeException("Failed to initialize AsyncResourceManager", e);
        }
    }
    
    /**
     * Check if the resource manager is fully initialized.
     */
    public static boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * Check if initialization is currently in progress.
     */
    public static boolean isInitializing() {
        return initializationInProgress.get();
    }
    
    /**
     * Get the number of active operations across all systems.
     */
    public static int getActiveOperationCount() {
        return activeOperations.size() + 
               TextureManager.getPendingLoadCount() +
               ModelManager.getPendingLoadCount() +
               TextureManager.getPendingLoadCount();
    }
    
    /**
     * Cancel all active operations across all resource systems.
     * 
     * @param mayInterruptIfRunning Whether to interrupt running operations
     * @return Total number of operations cancelled
     */
    public static int cancelAllOperations(boolean mayInterruptIfRunning) {
        System.out.println("[AsyncResourceManager] Cancelling all active operations...");
        
        int totalCancelled = 0;
        
        // Cancel local operations
        for (Map.Entry<String, CompletableFuture<?>> entry : activeOperations.entrySet()) {
            if (entry.getValue().cancel(mayInterruptIfRunning)) {
                totalCancelled++;
                System.out.println("[AsyncResourceManager] Cancelled operation: " + entry.getKey());
            }
        }
        activeOperations.clear();
        
        // Cancel operations in subsystems
        // Note: ModelLoader no longer supports cancellation tracking
        totalCancelled += TextureManager.cancelAllPendingLoads();
        totalCancelled += ModelManager.cancelAllPendingLoads();
        totalCancelled += TextureManager.cancelAllPendingLoads();
        
        System.out.println("[AsyncResourceManager] Cancelled " + totalCancelled + " total operations");
        return totalCancelled;
    }
    
    /**
     * Shutdown all resource management systems gracefully.
     * This should be called when the application is shutting down.
     */
    public static void shutdown() {
        System.out.println("[AsyncResourceManager] Starting graceful shutdown of all resource systems...");
        
        shutdownRequested.set(true);
        
        // Cancel all active operations
        int cancelled = cancelAllOperations(true);
        System.out.println("[AsyncResourceManager] Cancelled " + cancelled + " active operations");
        
        // Shutdown subsystems in dependency order
        try {
            ModelManager.shutdown();
            TextureManager.shutdown();
            
            System.out.println("[AsyncResourceManager] All resource systems shut down successfully");
        } catch (Exception e) {
            System.err.println("[AsyncResourceManager] Error during shutdown: " + e.getMessage());
        }
        
        // Reset state
        initialized.set(false);
        initializationInProgress.set(false);
        initializationFuture = null;
        activeOperations.clear();
        
        System.out.println("[AsyncResourceManager] Shutdown complete");
    }
    
    /**
     * Clear all caches and reset all systems.
     */
    public static void clearAllCaches() {
        System.out.println("[AsyncResourceManager] Clearing all resource caches...");
        
        // Cancel any active operations first
        cancelAllOperations(false);
        
        // Clear caches in all systems
        ModelManager.clearCache();
        TextureManager.clearCache();
        
        // Reset initialization state
        initialized.set(false);
        initializationInProgress.set(false);
        initializationFuture = null;
        
        System.out.println("[AsyncResourceManager] All caches cleared and systems reset");
    }
    
    /**
     * Get comprehensive status of all resource systems.
     */
    public static String getSystemStatus() {
        StringBuilder status = new StringBuilder();
        status.append("[AsyncResourceManager] === Complete System Status ===\n");
        status.append("  Initialized: ").append(initialized.get()).append("\n");
        status.append("  Initializing: ").append(initializationInProgress.get()).append("\n");
        status.append("  Shutdown Requested: ").append(shutdownRequested.get()).append("\n");
        status.append("  Active Operations: ").append(getActiveOperationCount()).append("\n");
        status.append("\n");
        
        status.append("  Model System:\n");
        status.append("    Initialized: ").append(ModelManager.isInitializing()).append("\n");
        status.append("    Pending Loads: ").append(ModelManager.getPendingLoadCount()).append("\n");
        status.append("    Active Loads: N/A (not tracked)\n");
        status.append("\n");
        
        status.append("  Texture System:\n");
        status.append("    Initialized: ").append(TextureManager.isInitializing()).append("\n");
        status.append("    Pending Loads: ").append(TextureManager.getPendingLoadCount()).append("\n");
        status.append("    Active Loads: ").append(TextureManager.getPendingLoadCount()).append("\n");
        
        return status.toString();
    }
    
    /**
     * Print comprehensive status to console.
     */
    public static void printSystemStatus() {
        System.out.println(getSystemStatus());
        
        if (initialized.get()) {
            System.out.println("\n=== Detailed System Information ===");
            ModelManager.printAllModelInfo();
            System.out.println();
            TextureManager.printAllVariantInfo();
        }
    }
}