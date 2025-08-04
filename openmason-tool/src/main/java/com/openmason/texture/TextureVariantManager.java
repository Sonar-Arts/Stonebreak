package com.openmason.texture;

import com.openmason.texture.stonebreak.StonebreakTextureDefinition;
import com.openmason.texture.stonebreak.StonebreakTextureLoader;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * UI-focused texture variant management system for OpenMason Phase 3.
 * 
 * Acts as a bridge between the backend async texture system (TextureManager/StonebreakTextureLoader)
 * and UI components, providing HashMap-based caching for fast variant switching and property panel integration.
 * 
 * Key Features:
 * - HashMap-based variant caching with <200ms switching performance
 * - Integration with existing async TextureManager infrastructure
 * - UI-friendly methods for property panel integration
 * - Real-time variant switching with immediate feedback
 * - Batch texture loading and validation operations
 * - JavaFX property bindings for reactive UI updates
 * - Performance monitoring and optimization
 * 
 * Architecture:
 * - Wraps existing TextureManager with UI-focused caching layer
 * - Uses CompletableFuture for async operations while maintaining sync UI methods
 * - Provides both blocking and non-blocking APIs for different use cases
 * - Integrates seamlessly with existing StonebreakTextureLoader async system
 */
public class TextureVariantManager {
    
    private static final Logger logger = LoggerFactory.getLogger(TextureVariantManager.class);
    
    // Core caching infrastructure
    private final Map<String, CachedVariantInfo> variantCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CachedVariantInfo>> activeLoads = new ConcurrentHashMap<>();
    
    // UI integration properties
    private final ObservableList<String> availableVariants = FXCollections.observableArrayList();
    private final StringProperty currentVariant = new SimpleStringProperty();
    private final BooleanProperty loadingInProgress = new SimpleBooleanProperty(false);
    private final StringProperty loadingStatus = new SimpleStringProperty("");
    private final IntegerProperty loadingProgress = new SimpleIntegerProperty(0);
    
    // Performance tracking
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong switchingTimes = new AtomicLong(0);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    
    // Configuration
    private static final long CACHE_EXPIRY_MS = 30 * 60 * 1000; // 30 minutes
    private static final int MAX_CONCURRENT_LOADS = 4;
    private static final int PERFORMANCE_TARGET_MS = 200;
    
    // Async executor for background operations
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "TextureVariantManager-" + System.currentTimeMillis());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    
    // Singleton instance for UI integration
    private static final TextureVariantManager INSTANCE = new TextureVariantManager();
    
    /**
     * Enhanced cached variant information with UI-specific metadata.
     */
    public static class CachedVariantInfo {
        private final String variantName;
        private final String displayName;
        private final TextureManager.TextureVariantInfo backendInfo;
        private final Map<String, String> uiMetadata;
        private final long cachedAt;
        private final boolean isValid;
        
        // Performance metrics
        private final AtomicLong accessCount = new AtomicLong(0);
        private volatile long lastAccessed;
        
        public CachedVariantInfo(String variantName, String displayName, 
                               TextureManager.TextureVariantInfo backendInfo,
                               Map<String, String> uiMetadata) {
            this.variantName = variantName;
            this.displayName = displayName;
            this.backendInfo = backendInfo;
            this.uiMetadata = uiMetadata != null ? Map.copyOf(uiMetadata) : Map.of();
            this.cachedAt = System.currentTimeMillis();
            this.isValid = backendInfo != null;
            this.lastAccessed = cachedAt;
        }
        
        // Getters with access tracking
        public String getVariantName() { 
            recordAccess();
            return variantName; 
        }
        
        public String getDisplayName() { 
            recordAccess();
            return displayName; 
        }
        
        public TextureManager.TextureVariantInfo getBackendInfo() { 
            recordAccess();
            return backendInfo; 
        }
        
        public Map<String, String> getUiMetadata() { 
            recordAccess();
            return uiMetadata; 
        }
        
        public boolean isValid() { return isValid; }
        public long getCachedAt() { return cachedAt; }
        public long getLastAccessed() { return lastAccessed; }
        public long getAccessCount() { return accessCount.get(); }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_EXPIRY_MS;
        }
        
        private void recordAccess() {
            accessCount.incrementAndGet();
            lastAccessed = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return String.format("CachedVariantInfo{name='%s', display='%s', valid=%s, accessed=%d}", 
                variantName, displayName, isValid, accessCount.get());
        }
    }
    
    /**
     * UI callback interface for variant operations.
     */
    public interface VariantCallback {
        void onSuccess(CachedVariantInfo variantInfo);
        void onError(String variantName, Throwable error);
        void onProgress(String operation, int current, int total, String details);
    }
    
    private TextureVariantManager() {
        // Initialize with known variants
        initializeAvailableVariants();
    }
    
    public static TextureVariantManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Initialize the variant manager system asynchronously.
     * 
     * @param callback Optional callback for initialization progress
     * @return CompletableFuture that completes when initialization is done
     */
    public CompletableFuture<Void> initializeAsync(VariantCallback callback) {
        if (initialized.get()) {
            if (callback != null) {
                Platform.runLater(() -> callback.onSuccess(null));
            }
            return CompletableFuture.completedFuture(null);
        }
        
        logger.info("Initializing TextureVariantManager...");
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Initialize backend TextureManager first
                TextureManager.initializeAsync(new TextureManager.ProgressCallback() {
                    @Override
                    public void onProgress(String operation, int current, int total, String details) {
                        if (callback != null && Platform.isFxApplicationThread()) {
                            callback.onProgress(operation, current, total, details);
                        } else if (callback != null) {
                            Platform.runLater(() -> callback.onProgress(operation, current, total, details));
                        }
                    }
                    
                    @Override
                    public void onError(String operation, Throwable error) {
                        if (callback != null) {
                            Platform.runLater(() -> callback.onError("Initialize", error));
                        }
                    }
                    
                    @Override
                    public void onComplete(String operation, Object result) {
                        // Initialization complete, no specific action needed
                    }
                }).get();
                
                // Pre-load common cow variants for optimal performance
                List<String> commonVariants = Arrays.asList("default", "angus", "highland", "jersey");
                logger.info("Pre-loading {} common variants for optimal performance: {}", 
                    commonVariants.size(), commonVariants);
                    
                loadMultipleVariantsAsync(commonVariants, false, callback).get();
                
                initialized.set(true);
                
                Platform.runLater(() -> {
                    loadingInProgress.set(false);
                    loadingStatus.set("Ready");
                    loadingProgress.set(100);
                });
                
                logger.info("TextureVariantManager initialization complete. Cached {} variants.", 
                    variantCache.size());
                
                return null;
                
            } catch (Exception e) {
                logger.error("TextureVariantManager initialization failed", e);
                if (callback != null) {
                    Platform.runLater(() -> callback.onError("initialization", e));
                }
                throw new RuntimeException("Failed to initialize TextureVariantManager", e);
            }
        }, backgroundExecutor);
    }
    
    /**
     * Get a texture variant with fast HashMap-based caching.
     * Guarantees <200ms performance for cached variants.
     * 
     * @param variantName The variant to retrieve
     * @return CachedVariantInfo or null if not found
     */
    public CachedVariantInfo getVariant(String variantName) {
        if (variantName == null) {
            return null;
        }
        
        long startTime = System.currentTimeMillis();
        
        // Check cache first
        CachedVariantInfo cached = variantCache.get(variantName);
        if (cached != null && !cached.isExpired()) {
            cacheHits.incrementAndGet();
            long switchTime = System.currentTimeMillis() - startTime;
            switchingTimes.addAndGet(switchTime);
            
            if (switchTime > PERFORMANCE_TARGET_MS) {
                logger.warn("Cache hit took {}ms for variant '{}' (target: {}ms)", 
                    switchTime, variantName, PERFORMANCE_TARGET_MS);
            }
            
            return cached;
        }
        
        // Cache miss - load synchronously with performance warning
        cacheMisses.incrementAndGet();
        logger.warn("Cache miss for variant '{}'. Loading synchronously (may impact performance).", variantName);
        
        try {
            // Use async loader but block for immediate result
            CachedVariantInfo result = loadVariantAsync(variantName, null).get(PERFORMANCE_TARGET_MS, TimeUnit.MILLISECONDS);
            
            long totalTime = System.currentTimeMillis() - startTime;
            switchingTimes.addAndGet(totalTime);
            
            if (totalTime > PERFORMANCE_TARGET_MS) {
                logger.warn("Synchronous load took {}ms for variant '{}' (target: {}ms)", 
                    totalTime, variantName, PERFORMANCE_TARGET_MS);
            }
            
            return result;
            
        } catch (Exception e) {
            logger.error("Failed to load variant '{}' synchronously", variantName, e);
            return null;
        }
    }
    
    /**
     * Load a texture variant asynchronously with caching.
     * 
     * @param variantName The variant to load
     * @param callback Optional callback for progress updates
     * @return CompletableFuture that resolves to CachedVariantInfo
     */
    public CompletableFuture<CachedVariantInfo> loadVariantAsync(String variantName, VariantCallback callback) {
        if (variantName == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Variant name cannot be null"));
        }
        
        // Check cache first
        CachedVariantInfo cached = variantCache.get(variantName);
        if (cached != null && !cached.isExpired()) {
            cacheHits.incrementAndGet();
            if (callback != null) {
                Platform.runLater(() -> callback.onSuccess(cached));
            }
            return CompletableFuture.completedFuture(cached);
        }
        
        // Check if already loading
        CompletableFuture<CachedVariantInfo> existingLoad = activeLoads.get(variantName);
        if (existingLoad != null) {
            logger.debug("Variant '{}' already loading, reusing future", variantName);
            return existingLoad;
        }
        
        // Create new load operation
        CompletableFuture<CachedVariantInfo> loadFuture = CompletableFuture.supplyAsync(() -> {
            try {
                if (callback != null) {
                    Platform.runLater(() -> callback.onProgress("loadVariant", 0, 100, 
                        "Loading variant: " + variantName));
                }
                
                // Use existing TextureManager to load variant info
                TextureManager.TextureVariantInfo backendInfo = TextureManager.loadVariantInfoAsync(
                    variantName, TextureManager.LoadingPriority.NORMAL, null).get();
                
                if (backendInfo == null) {
                    throw new RuntimeException("Backend failed to load variant: " + variantName);
                }
                
                if (callback != null) {
                    Platform.runLater(() -> callback.onProgress("loadVariant", 75, 100, 
                        "Creating UI metadata"));
                }
                
                // Create UI-specific metadata
                Map<String, String> uiMetadata = createUIMetadata(backendInfo);
                
                // Create cached info
                CachedVariantInfo cachedInfo = new CachedVariantInfo(
                    variantName, 
                    backendInfo.getDisplayName(),
                    backendInfo,
                    uiMetadata
                );
                
                // Cache the result
                variantCache.put(variantName, cachedInfo);
                
                if (callback != null) {
                    Platform.runLater(() -> {
                        callback.onProgress("loadVariant", 100, 100, "Variant loaded successfully");
                        callback.onSuccess(cachedInfo);
                    });
                }
                
                logger.debug("Successfully loaded and cached variant: {}", cachedInfo);
                return cachedInfo;
                
            } catch (Exception e) {
                logger.error("Failed to load variant '{}'", variantName, e);
                if (callback != null) {
                    Platform.runLater(() -> callback.onError(variantName, e));
                }
                throw new RuntimeException("Failed to load variant: " + variantName, e);
            }
        }, backgroundExecutor).whenComplete((result, throwable) -> {
            // Clean up active loads
            activeLoads.remove(variantName);
        });
        
        // Track active load
        activeLoads.put(variantName, loadFuture);
        return loadFuture;
    }
    
    /**
     * Load multiple texture variants in parallel with progress reporting.
     * 
     * @param variantNames List of variants to load
     * @param forceReload Whether to reload even if cached
     * @param callback Progress callback
     * @return CompletableFuture that resolves when all variants are loaded
     */
    public CompletableFuture<Map<String, CachedVariantInfo>> loadMultipleVariantsAsync(
            List<String> variantNames, boolean forceReload, VariantCallback callback) {
        
        if (variantNames == null || variantNames.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        
        Platform.runLater(() -> {
            loadingInProgress.set(true);
            loadingProgress.set(0);
            loadingStatus.set("Loading " + variantNames.size() + " variants...");
        });
        
        // Filter variants that need loading
        List<String> toLoad = variantNames.stream()
            .filter(name -> forceReload || !variantCache.containsKey(name) || variantCache.get(name).isExpired())
            .collect(Collectors.toList());
        
        if (toLoad.isEmpty()) {
            // All variants already cached
            Map<String, CachedVariantInfo> result = variantNames.stream()
                .collect(Collectors.toMap(name -> name, variantCache::get));
            
            Platform.runLater(() -> {
                loadingInProgress.set(false);
                loadingStatus.set("All variants cached");
                loadingProgress.set(100);
            });
            
            return CompletableFuture.completedFuture(result);
        }
        
        logger.info("Loading {} variants in parallel: {}", toLoad.size(), toLoad);
        
        // Create loading futures
        List<CompletableFuture<CachedVariantInfo>> loadFutures = toLoad.stream()
            .map(variantName -> loadVariantAsync(variantName, null))
            .collect(Collectors.toList());
        
        // Track progress
        AtomicLong completed = new AtomicLong(0);
        int total = toLoad.size();
        
        // Add progress tracking to each future
        for (int i = 0; i < loadFutures.size(); i++) {
            final String variantName = toLoad.get(i);
            loadFutures.get(i).whenComplete((result, throwable) -> {
                long currentCompleted = completed.incrementAndGet();
                int progress = (int) ((currentCompleted * 100) / total);
                
                Platform.runLater(() -> {
                    loadingProgress.set(progress);
                    loadingStatus.set("Loaded " + currentCompleted + "/" + total + " variants");
                    
                    if (callback != null) {
                        callback.onProgress("loadMultipleVariants", (int) currentCompleted, total, 
                            "Loaded: " + variantName);
                    }
                });
            });
        }
        
        // Combine all futures
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            loadFutures.toArray(new CompletableFuture[0]));
        
        return allFutures.thenApply(v -> {
            Map<String, CachedVariantInfo> results = new ConcurrentHashMap<>();
            
            // Collect all results (including previously cached ones)
            for (String variantName : variantNames) {
                CachedVariantInfo cached = variantCache.get(variantName);
                if (cached != null) {
                    results.put(variantName, cached);
                }
            }
            
            Platform.runLater(() -> {
                loadingInProgress.set(false);
                loadingStatus.set("Loaded " + results.size() + " variants");
                loadingProgress.set(100);
                
                if (callback != null) {
                    callback.onSuccess(null); // Signal completion
                }
            });
            
            logger.info("Batch loading complete. {} variants now cached.", results.size());
            return results;
        });
    }
    
    /**
     * Switch to a different texture variant with performance tracking.
     * Updates currentVariant property for UI binding.
     * 
     * @param variantName The variant to switch to
     * @return true if switch was successful
     */
    public boolean switchToVariant(String variantName) {
        if (variantName == null || variantName.equals(currentVariant.get())) {
            return false;
        }
        
        long startTime = System.currentTimeMillis();
        
        CachedVariantInfo variant = getVariant(variantName);
        if (variant != null && variant.isValid()) {
            Platform.runLater(() -> currentVariant.set(variantName));
            
            long switchTime = System.currentTimeMillis() - startTime;
            logger.debug("Switched to variant '{}' in {}ms", variantName, switchTime);
            
            if (switchTime > PERFORMANCE_TARGET_MS) {
                logger.warn("Variant switch took {}ms (target: {}ms)", switchTime, PERFORMANCE_TARGET_MS);
            }
            
            return true;
        }
        
        logger.error("Failed to switch to variant '{}' - not found or invalid", variantName);
        return false;
    }
    
    /**
     * Validate all cached texture variants.
     * 
     * @return Map of variant names to validation results
     */
    public Map<String, Boolean> validateAllVariants() {
        logger.info("Validating {} cached variants", variantCache.size());
        
        Map<String, Boolean> results = new ConcurrentHashMap<>();
        
        for (Map.Entry<String, CachedVariantInfo> entry : variantCache.entrySet()) {
            String variantName = entry.getKey();
            CachedVariantInfo cachedInfo = entry.getValue();
            
            boolean isValid = cachedInfo.isValid() && 
                             TextureManager.validateVariant(variantName) &&
                             TextureManager.validateCoordinates(variantName);
            
            results.put(variantName, isValid);
            
            if (!isValid) {
                logger.warn("Variant '{}' failed validation", variantName);
            }
        }
        
        long validCount = results.values().stream().mapToLong(valid -> valid ? 1 : 0).sum();
        logger.info("Validation complete: {}/{} variants valid", validCount, results.size());
        
        return results;
    }
    
    /**
     * Get performance statistics for monitoring and optimization.
     */
    public Map<String, Object> getPerformanceStats() {
        long totalSwitches = cacheHits.get() + cacheMisses.get();
        double hitRate = totalSwitches > 0 ? (double) cacheHits.get() / totalSwitches * 100 : 0;
        double avgSwitchTime = totalSwitches > 0 ? (double) switchingTimes.get() / totalSwitches : 0;
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("initialized", initialized.get());
        stats.put("cachedVariants", variantCache.size());
        stats.put("activeLoads", activeLoads.size());
        stats.put("cacheHits", cacheHits.get());
        stats.put("cacheMisses", cacheMisses.get());
        stats.put("hitRate", String.format("%.1f%%", hitRate));
        stats.put("averageSwitchTime", String.format("%.1fms", avgSwitchTime));
        stats.put("performanceTarget", PERFORMANCE_TARGET_MS + "ms");
        stats.put("cacheExpiryTime", CACHE_EXPIRY_MS / 1000 + "s");
        
        return stats;
    }
    
    /**
     * Clear expired entries from cache.
     * 
     * @return Number of entries removed
     */
    public int cleanupExpiredCache() {
        int removedCount = 0;
        Iterator<Map.Entry<String, CachedVariantInfo>> iterator = variantCache.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<String, CachedVariantInfo> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                removedCount++;
                logger.debug("Removed expired cache entry for variant: {}", entry.getKey());
            }
        }
        
        if (removedCount > 0) {
            logger.info("Cleaned up {} expired cache entries", removedCount);
        }
        
        return removedCount;
    }
    
    // UI-specific helper methods
    
    private void initializeAvailableVariants() {
        String[] variants = StonebreakTextureLoader.getAvailableVariants();
        Platform.runLater(() -> {
            availableVariants.clear();
            availableVariants.addAll(Arrays.asList(variants));
        });
    }
    
    private Map<String, String> createUIMetadata(TextureManager.TextureVariantInfo backendInfo) {
        Map<String, String> metadata = new HashMap<>();
        
        // Add UI-friendly information
        metadata.put("faceMappings", String.valueOf(backendInfo.getFaceMappingCount()));
        metadata.put("drawingInstructions", String.valueOf(backendInfo.getDrawingInstructionCount()));
        metadata.put("primaryColor", backendInfo.getBaseColors().get("primary"));
        metadata.put("secondaryColor", backendInfo.getBaseColors().get("secondary"));
        metadata.put("accentColor", backendInfo.getBaseColors().get("accent"));
        
        // Add texture quality indicators
        boolean hasAllMappings = backendInfo.getFaceMappingCount() >= 12; // HEAD + BODY faces
        boolean hasDrawingInstructions = backendInfo.getDrawingInstructionCount() > 0;
        
        metadata.put("quality", hasAllMappings && hasDrawingInstructions ? "High" : "Standard");
        metadata.put("completeness", String.format("%.0f%%", 
            Math.min(100, (backendInfo.getFaceMappingCount() / 12.0) * 100)));
        
        return metadata;
    }
    
    // JavaFX Property Accessors for UI Binding
    
    public ObservableList<String> getAvailableVariants() {
        return availableVariants;
    }
    
    public StringProperty currentVariantProperty() {
        return currentVariant;
    }
    
    public String getCurrentVariant() {
        return currentVariant.get();
    }
    
    public BooleanProperty loadingInProgressProperty() {
        return loadingInProgress;
    }
    
    public boolean isLoadingInProgress() {
        return loadingInProgress.get();
    }
    
    public StringProperty loadingStatusProperty() {
        return loadingStatus;
    }
    
    public String getLoadingStatus() {
        return loadingStatus.get();
    }
    
    public IntegerProperty loadingProgressProperty() {
        return loadingProgress;
    }
    
    public int getLoadingProgress() {
        return loadingProgress.get();
    }
    
    /**
     * Shutdown the TextureVariantManager gracefully.
     */
    public void shutdown() {
        logger.info("Shutting down TextureVariantManager...");
        
        // Cancel active loads
        activeLoads.values().forEach(future -> future.cancel(false));
        activeLoads.clear();
        
        // Clear cache
        variantCache.clear();
        
        // Shutdown executor
        backgroundExecutor.shutdown();
        try {
            if (!backgroundExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                backgroundExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            backgroundExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        initialized.set(false);
        logger.info("TextureVariantManager shutdown complete");
    }
    
    /**
     * Print detailed status information for debugging.
     */
    public void printStatus() {
        logger.info("=== TextureVariantManager Status ===");
        Map<String, Object> stats = getPerformanceStats();
        stats.forEach((key, value) -> logger.info("  {}: {}", key, value));
        
        logger.info("Cached Variants:");
        variantCache.forEach((name, info) -> {
            logger.info("  {} -> {} (accessed {} times, last: {}ms ago)", 
                name, info.getDisplayName(), info.getAccessCount(),
                System.currentTimeMillis() - info.getLastAccessed());
        });
        
        if (!activeLoads.isEmpty()) {
            logger.info("Active Loads: {}", activeLoads.keySet());
        }
    }
}