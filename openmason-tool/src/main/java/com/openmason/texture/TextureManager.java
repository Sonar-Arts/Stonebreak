package com.openmason.texture;

import com.stonebreak.textures.CowTextureDefinition;
import com.stonebreak.textures.CowTextureLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous high-level texture management system for Open Mason Phase 2.
 * Provides both synchronous (legacy) and asynchronous APIs with progress reporting,
 * background loading queues, and comprehensive thread safety for UI responsiveness.
 * Wraps the Stonebreak texture system with caching and validation.
 */
public class TextureManager {
    
    private static final Map<String, TextureVariantInfo> variantInfoCache = new ConcurrentHashMap<>();
    private static boolean initialized = false;
    private static final AtomicBoolean initializationInProgress = new AtomicBoolean(false);
    private static CompletableFuture<Void> initializationFuture = null;
    
    // Background loading queue system
    private static final ExecutorService backgroundLoader = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "TextureManager-Background-" + System.currentTimeMillis());
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
     * Internal load request for priority queue.
     */
    private static class LoadRequest implements Comparable<LoadRequest> {
        final String variantName;
        final LoadingPriority priority;
        final CompletableFuture<TextureVariantInfo> future;
        final ProgressCallback callback;
        final long timestamp;
        
        LoadRequest(String variantName, LoadingPriority priority, CompletableFuture<TextureVariantInfo> future, ProgressCallback callback) {
            this.variantName = variantName;
            this.priority = priority;
            this.future = future;
            this.callback = callback;
            this.timestamp = System.nanoTime();
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
                    if (request != null && !request.future.isCancelled()) {
                        processLoadRequest(request);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[TextureManager] Background processor error: " + e.getMessage());
                }
            }
        }, "TextureManager-BackgroundProcessor");
        backgroundProcessor.setDaemon(true);
        backgroundProcessor.start();
    }
    
    /**
     * Information about a texture variant.
     */
    public static class TextureVariantInfo {
        private final String variantName;
        private final String displayName;
        private final int faceMappingCount;
        private final int drawingInstructionCount;
        private final CowTextureDefinition.CowVariant variantDefinition;
        private final Map<String, String> baseColors;
        
        public TextureVariantInfo(String variantName, String displayName, int faceMappingCount, 
                                 int drawingInstructionCount, CowTextureDefinition.CowVariant variantDefinition,
                                 Map<String, String> baseColors) {
            this.variantName = variantName;
            this.displayName = displayName;
            this.faceMappingCount = faceMappingCount;
            this.drawingInstructionCount = drawingInstructionCount;
            this.variantDefinition = variantDefinition;
            this.baseColors = baseColors;
        }
        
        public String getVariantName() { return variantName; }
        public String getDisplayName() { return displayName; }
        public int getFaceMappingCount() { return faceMappingCount; }
        public int getDrawingInstructionCount() { return drawingInstructionCount; }
        public CowTextureDefinition.CowVariant getVariantDefinition() { return variantDefinition; }
        public Map<String, String> getBaseColors() { return baseColors; }
        
        @Override
        public String toString() {
            return String.format("TextureVariantInfo{name='%s', display='%s', faces=%d, instructions=%d}", 
                variantName, displayName, faceMappingCount, drawingInstructionCount);
        }
    }
    
    /**
     * ASYNC: Initialize the TextureManager system asynchronously with progress reporting.
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
                        progressCallback.onProgress("initializeAsync", 0, 100, "Starting TextureManager initialization");
                    }
                    
                    System.out.println("[TextureManager] Initializing async texture management system...");
                    
                    // Initialize texture loading system
                    if (progressCallback != null) {
                        progressCallback.onProgress("initializeAsync", 10, 100, "Initializing texture loading system");
                    }
                    
                    // Get available variants
                    String[] availableVariants = CowTextureLoader.getAvailableVariants();
                    int totalVariants = availableVariants.length;
                    
                    if (progressCallback != null) {
                        progressCallback.onProgress("initializeAsync", 20, 100, 
                            "Found " + totalVariants + " texture variants to load");
                    }
                    
                    // Load all variants in parallel
                    List<CompletableFuture<TextureVariantInfo>> variantFutures = new ArrayList<>();
                    AtomicInteger completed = new AtomicInteger(0);
                    
                    for (String variantName : availableVariants) {
                        CompletableFuture<TextureVariantInfo> variantFuture = loadVariantInfoAsync(variantName, 
                            LoadingPriority.HIGH, null)
                            .thenApply(info -> {
                                int currentCompleted = completed.incrementAndGet();
                                if (progressCallback != null) {
                                    int progress = 20 + (currentCompleted * 70 / totalVariants);
                                    progressCallback.onProgress("initializeAsync", progress, 100, 
                                        "Loaded variant " + currentCompleted + "/" + totalVariants + ": " + variantName);
                                }
                                return info;
                            })
                            .exceptionally(throwable -> {
                                System.err.println("[TextureManager] Failed to load variant '" + variantName + "': " + throwable.getMessage());
                                if (progressCallback != null) {
                                    progressCallback.onError("initializeAsync", throwable);
                                }
                                return null; // Continue with other variants
                            });
                        variantFutures.add(variantFuture);
                    }
                    
                    // Wait for all variants to complete
                    CompletableFuture.allOf(variantFutures.toArray(new CompletableFuture[0])).join();
                    
                    // Count successful loads
                    long successCount = variantFutures.stream()
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
                            "Initialization complete: " + successCount + "/" + totalVariants + " variants loaded");
                        progressCallback.onComplete("initializeAsync", 
                            "TextureManager initialized with " + successCount + " variants");
                    }
                    
                    System.out.println("[TextureManager] Async initialization complete. Loaded " + 
                        successCount + "/" + totalVariants + " variant(s)");
                    
                    return null;
                    
                } catch (Exception e) {
                    if (progressCallback != null) {
                        progressCallback.onError("initializeAsync", e);
                    }
                    throw new RuntimeException("TextureManager initialization failed: " + e.getMessage(), e);
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
     * LEGACY: Initialize the TextureManager system synchronously.
     * Preserved for backward compatibility but now uses async system internally.
     */
    public static synchronized void initialize() {
        try {
            initializeAsync(null).get(); // Block until async initialization completes
        } catch (Exception e) {
            System.err.println("[TextureManager] Synchronous initialization failed: " + e.getMessage());
            throw new RuntimeException("Failed to initialize TextureManager", e);
        }
    }
    
    /**
     * ASYNC: Load texture variant information asynchronously with priority support.
     * 
     * @param variantName The variant to load info for
     * @param priority Loading priority
     * @param progressCallback Optional progress callback
     * @return CompletableFuture that resolves to TextureVariantInfo
     */
    public static CompletableFuture<TextureVariantInfo> loadVariantInfoAsync(String variantName, 
                                                                            LoadingPriority priority, 
                                                                            ProgressCallback progressCallback) {
        if (shutdownRequested.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("TextureManager is shutting down"));
        }
        
        // Check if already cached
        TextureVariantInfo cached = variantInfoCache.get(variantName);
        if (cached != null) {
            if (progressCallback != null) {
                progressCallback.onComplete("loadVariantInfoAsync", cached);
            }
            return CompletableFuture.completedFuture(cached);
        }
        
        // Create future for this request
        CompletableFuture<TextureVariantInfo> future = new CompletableFuture<>();
        
        // Add to priority queue for background processing
        LoadRequest request = new LoadRequest(variantName, priority, future, progressCallback);
        
        if (priority == LoadingPriority.IMMEDIATE) {
            // Process immediately on current thread
            backgroundLoader.submit(() -> processLoadRequest(request));
        } else {
            // Add to queue for background processing
            loadQueue.offer(request);
        }
        
        return future;
    }
    
    /**
     * Process a load request from the background queue.
     */
    private static void processLoadRequest(LoadRequest request) {
        try {
            if (request.future.isCancelled()) {
                return;
            }
            
            if (request.callback != null) {
                request.callback.onProgress("loadVariantInfoAsync", 0, 100, 
                    "Loading texture variant: " + request.variantName);
            }
            
            // Use the synchronous texture loader wrapped in async
            CompletableFuture.supplyAsync(() -> {
                return CowTextureLoader.getCowVariant(request.variantName);
            }).thenAccept(variant -> {
                    try {
                        if (variant != null && !request.future.isCancelled()) {
                            if (request.callback != null) {
                                request.callback.onProgress("loadVariantInfoAsync", 75, 100, 
                                    "Creating variant info");
                            }
                            
                            TextureVariantInfo info = createVariantInfo(request.variantName, variant);
                            variantInfoCache.put(request.variantName, info);
                            
                            if (request.callback != null) {
                                request.callback.onProgress("loadVariantInfoAsync", 100, 100, 
                                    "Variant info loaded successfully");
                                request.callback.onComplete("loadVariantInfoAsync", info);
                            }
                            
                            request.future.complete(info);
                            System.out.println("[TextureManager] Loaded variant info asynchronously: " + info);
                        } else {
                            request.future.completeExceptionally(
                                new RuntimeException("Failed to load variant: " + request.variantName));
                        }
                    } catch (Exception e) {
                        if (request.callback != null) {
                            request.callback.onError("loadVariantInfoAsync", e);
                        }
                        request.future.completeExceptionally(e);
                    }
                })
                .exceptionally(throwable -> {
                    if (request.callback != null) {
                        request.callback.onError("loadVariantInfoAsync", throwable);
                    }
                    request.future.completeExceptionally(throwable);
                    return null;
                });
                
        } catch (Exception e) {
            if (request.callback != null) {
                request.callback.onError("loadVariantInfoAsync", e);
            }
            request.future.completeExceptionally(e);
        }
    }
    
    /**
     * Create TextureVariantInfo from a loaded variant definition.
     */
    private static TextureVariantInfo createVariantInfo(String variantName, CowTextureDefinition.CowVariant variant) {
        int faceMappingCount = variant.getFaceMappings() != null ? variant.getFaceMappings().size() : 0;
        int drawingInstructionCount = variant.getDrawingInstructions() != null ? variant.getDrawingInstructions().size() : 0;
        
        Map<String, String> baseColors = Map.of(
            "primary", variant.getBaseColors() != null ? variant.getBaseColors().getPrimary() : "#FFFFFF",
            "secondary", variant.getBaseColors() != null ? variant.getBaseColors().getSecondary() : "#FFFFFF",
            "accent", variant.getBaseColors() != null ? variant.getBaseColors().getAccent() : "#FFFFFF"
        );
        
        return new TextureVariantInfo(variantName, variant.getDisplayName(), 
            faceMappingCount, drawingInstructionCount, variant, baseColors);
    }
    
    /**
     * LEGACY: Get information about a specific texture variant.
     * Now uses async system with blocking for backward compatibility.
     */
    public static TextureVariantInfo getVariantInfo(String variantName) {
        try {
            // Use async loading with IMMEDIATE priority for legacy compatibility
            return loadVariantInfoAsync(variantName, LoadingPriority.IMMEDIATE, null).get();
        } catch (Exception e) {
            System.err.println("[TextureManager] Failed to get variant info for '" + variantName + "': " + e.getMessage());
            return null;
        }
    }
    
    // Removed legacy loadVariantInfo method - now handled by async system
    
    /**
     * Get UV coordinates for a specific face of a texture variant.
     */
    public static float[] getUVCoordinates(String variantName, String faceName) {
        if (!initialized) {
            initialize();
        }
        
        return CowTextureLoader.getNormalizedUVCoordinates(variantName, faceName, 16);
    }
    
    /**
     * Get normalized UV coordinates (0.0-1.0 range) for a specific face.
     */
    public static float[] getNormalizedUVCoordinates(String variantName, String faceName) {
        if (!initialized) {
            initialize();
        }
        
        return CowTextureLoader.getNormalizedUVCoordinates(variantName, faceName, 16);
    }
    
    /**
     * Get atlas coordinates for a specific face.
     */
    public static CowTextureDefinition.AtlasCoordinate getAtlasCoordinate(String variantName, String faceName) {
        if (!initialized) {
            initialize();
        }
        
        return CowTextureLoader.getAtlasCoordinate(variantName, faceName);
    }
    
    /**
     * Get base color for a texture variant.
     */
    public static String getBaseColor(String variantName, String colorType) {
        if (!initialized) {
            initialize();
        }
        
        return CowTextureLoader.getBaseColor(variantName, colorType);
    }
    
    /**
     * Get list of all available texture variants.
     */
    public static List<String> getAvailableVariants() {
        if (!initialized) {
            initialize();
        }
        
        return Arrays.asList(CowTextureLoader.getAvailableVariants());
    }
    
    /**
     * Get list of all face names for a texture variant.
     */
    public static List<String> getFaceNames(String variantName) {
        TextureVariantInfo info = getVariantInfo(variantName);
        if (info == null || info.getVariantDefinition().getFaceMappings() == null) {
            return List.of();
        }
        
        return List.copyOf(info.getVariantDefinition().getFaceMappings().keySet());
    }
    
    /**
     * Validate that a texture variant can be loaded successfully.
     */
    public static boolean validateVariant(String variantName) {
        if (!CowTextureLoader.isValidVariant(variantName)) {
            return false;
        }
        
        try {
            TextureVariantInfo info = getVariantInfo(variantName);
            return info != null && info.getFaceMappingCount() > 0;
        } catch (Exception e) {
            System.err.println("[TextureManager] Variant validation failed for '" + variantName + "': " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Validate that all face mappings have valid coordinates.
     */
    public static boolean validateCoordinates(String variantName) {
        TextureVariantInfo info = getVariantInfo(variantName);
        if (info == null) {
            return false;
        }
        
        Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings = 
            info.getVariantDefinition().getFaceMappings();
        
        if (faceMappings == null) {
            return false;
        }
        
        for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : faceMappings.entrySet()) {
            String faceName = entry.getKey();
            CowTextureDefinition.AtlasCoordinate coord = entry.getValue();
            
            if (coord == null) {
                System.err.println("[TextureManager] Null coordinate for face: " + faceName);
                return false;
            }
            
            if (coord.getAtlasX() < 0 || coord.getAtlasX() >= 16 || 
                coord.getAtlasY() < 0 || coord.getAtlasY() >= 16) {
                System.err.println("[TextureManager] Invalid coordinate for face " + faceName + 
                    ": (" + coord.getAtlasX() + "," + coord.getAtlasY() + ") - must be 0-15");
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Get detailed statistics about a texture variant.
     */
    public static String getVariantStatistics(String variantName) {
        TextureVariantInfo info = getVariantInfo(variantName);
        if (info == null) {
            return "Variant not found: " + variantName;
        }
        
        boolean coordinatesValid = validateCoordinates(variantName);
        List<String> faceNames = getFaceNames(variantName);
        
        StringBuilder stats = new StringBuilder();
        stats.append(String.format("Variant: %s (%s)\n", info.getVariantName(), info.getDisplayName()));
        stats.append(String.format("Face Mappings: %d\n", info.getFaceMappingCount()));
        stats.append(String.format("Drawing Instructions: %d\n", info.getDrawingInstructionCount()));
        stats.append(String.format("Coordinates Valid: %s\n", coordinatesValid ? "✓" : "✗"));
        stats.append(String.format("Base Colors: Primary=%s, Secondary=%s, Accent=%s\n", 
            info.getBaseColors().get("primary"),
            info.getBaseColors().get("secondary"), 
            info.getBaseColors().get("accent")));
        stats.append(String.format("Face Names: [%s]\n", String.join(", ", faceNames)));
        
        return stats.toString();
    }
    
    /**
     * Test UV coordinate generation for a specific variant and face.
     */
    public static void testUVGeneration(String variantName, String faceName) {
        System.out.println("[TextureManager] Testing UV generation for " + variantName + ":" + faceName);
        
        // Get atlas coordinate
        CowTextureDefinition.AtlasCoordinate coord = getAtlasCoordinate(variantName, faceName);
        if (coord == null) {
            System.err.println("  ✗ Atlas coordinate not found");
            return;
        }
        
        System.out.println("  Atlas Coordinate: (" + coord.getAtlasX() + "," + coord.getAtlasY() + ")");
        
        // Get UV coordinates
        float[] uv = getNormalizedUVCoordinates(variantName, faceName);
        System.out.println("  UV Coordinates: [" + uv[0] + ", " + uv[1] + ", " + uv[2] + ", " + uv[3] + "]");
        
        // Test mathematical consistency
        float expectedU1 = coord.getAtlasX() / 16.0f;
        float expectedV1 = coord.getAtlasY() / 16.0f;
        float expectedU2 = expectedU1 + (1.0f / 16.0f);
        float expectedV2 = expectedV1 + (1.0f / 16.0f);
        
        boolean uvValid = Math.abs(uv[0] - expectedU1) < 0.001f &&
                         Math.abs(uv[1] - expectedV1) < 0.001f &&
                         Math.abs(uv[2] - expectedU2) < 0.001f &&
                         Math.abs(uv[3] - expectedV2) < 0.001f;
        
        System.out.println("  Mathematical Consistency: " + (uvValid ? "✓" : "✗"));
        if (!uvValid) {
            System.out.println("    Expected: [" + expectedU1 + ", " + expectedV1 + ", " + expectedU2 + ", " + expectedV2 + "]");
        }
    }
    
    
    
    /**
     * Test method to validate all texture variants can be loaded correctly.
     */
    public static boolean testAllVariants() {
        System.out.println("[TextureManager] Testing all texture variants...");
        boolean allValid = true;
        
        for (String variantName : getAvailableVariants()) {
            boolean valid = validateVariant(variantName) && validateCoordinates(variantName);
            String status = valid ? "✓" : "✗";
            TextureVariantInfo info = getVariantInfo(variantName);
            System.out.println("  " + status + " " + variantName + (valid && info != null ? 
                " -> " + info.getDisplayName() + " (" + info.getFaceMappingCount() + " faces)" : " -> FAILED"));
            
            if (!valid) {
                allValid = false;
            }
        }
        
        System.out.println("[TextureManager] Texture variant testing complete. Result: " + (allValid ? "ALL PASS" : "SOME FAILED"));
        return allValid;
    }
    
    /**
     * Cancel all pending load requests.
     * 
     * @return Number of requests cancelled
     */
    public static int cancelAllPendingLoads() {
        System.out.println("[TextureManager] Cancelling all pending loads...");
        
        int cancelledCount = 0;
        LoadRequest request;
        while ((request = loadQueue.poll()) != null) {
            if (request.future.cancel(false)) {
                cancelledCount++;
            }
        }
        
        System.out.println("[TextureManager] Cancelled " + cancelledCount + " pending load requests");
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
     * Check if TextureManager is currently initializing.
     * 
     * @return true if initialization is in progress
     */
    public static boolean isInitializing() {
        return initializationInProgress.get();
    }
    
    /**
     * Shutdown the TextureManager system gracefully.
     * This should be called when the application is shutting down.
     */
    public static void shutdown() {
        System.out.println("[TextureManager] Shutting down async texture management system...");
        
        shutdownRequested.set(true);
        
        // Cancel pending loads
        int cancelledPending = cancelAllPendingLoads();
        System.out.println("[TextureManager] Cancelled " + cancelledPending + " pending loads");
        
        // Shutdown background executor
        backgroundLoader.shutdown();
        try {
            if (!backgroundLoader.awaitTermination(5, TimeUnit.SECONDS)) {
                backgroundLoader.shutdownNow();
                System.out.println("[TextureManager] Forced shutdown of background executor");
            } else {
                System.out.println("[TextureManager] Background executor shutdown gracefully");
            }
        } catch (InterruptedException e) {
            backgroundLoader.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Clear underlying texture cache
        CowTextureLoader.clearCache();
        
        System.out.println("[TextureManager] Shutdown complete");
    }
    
    /**
     * Clear all cached texture data and reset initialization state.
     */
    public static synchronized void clearCache() {
        System.out.println("[TextureManager] Clearing texture cache...");
        
        // Cancel any pending operations
        cancelAllPendingLoads();
        
        variantInfoCache.clear();
        CowTextureLoader.clearCache();
        initialized = false;
        initializationInProgress.set(false);
        initializationFuture = null;
        
        System.out.println("[TextureManager] Cache cleared and system reset");
    }
    
    /**
     * Enhanced status printing with async information.
     */
    public static void printAllVariantInfo() {
        if (!initialized && !isInitializing()) {
            System.out.println("[TextureManager] System not initialized. Call initializeAsync() first.");
            return;
        }
        
        System.out.println("[TextureManager] === Async Texture Management System Status ===");
        System.out.println("  Initialized: " + initialized);
        System.out.println("  Initializing: " + isInitializing());
        System.out.println("  Pending loads: " + getPendingLoadCount());
        System.out.println("  Cached variants: " + variantInfoCache.size());
        System.out.println("  Shutdown requested: " + shutdownRequested.get());
        System.out.println();
        
        if (initialized) {
            for (TextureVariantInfo info : variantInfoCache.values()) {
                System.out.println(getVariantStatistics(info.getVariantName()));
                System.out.println("---");
            }
        }
        
        CowTextureLoader.printCacheStatus();
    }
}