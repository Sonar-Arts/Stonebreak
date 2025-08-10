package com.openmason.texture;

import com.stonebreak.textures.CowTextureDefinition;
import com.stonebreak.textures.CowTextureLoader;
// Removed JavaFX Platform dependency - using standard threading
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Unified Texture Resource Manager for Open Mason - Phase 5 Optimization
 * 
 * Provides centralized, high-performance texture resource management with:
 * - Multi-tier caching (Hot/Warm/Cold) with intelligent prefetching
 * - Object pooling for memory efficiency
 * - Predictive performance optimization
 * - Thread-safe resource management
 * - Comprehensive performance monitoring
 * 
 * Key Performance Targets:
 * - <100ms texture switching for cached variants
 * - 90%+ cache hit rate through intelligent prefetching
 * - <50MB baseline memory usage
 * - Predictive optimization to prevent performance degradation
 */
public class UnifiedTextureResourceManager {
    
    private static final Logger logger = LoggerFactory.getLogger(UnifiedTextureResourceManager.class);
    
    // Singleton instance for unified resource management
    private static volatile UnifiedTextureResourceManager INSTANCE;
    private static final Object LOCK = new Object();
    
    // Core resource management
    private final AdvancedTextureCache textureCache;
    private final TextureObjectPool objectPool;
    private final PerformancePredictor performancePredictor;
    
    // Unified threading architecture
    private final ExecutorService resourceExecutor;
    private final ScheduledExecutorService scheduledExecutor;
    private final CompletionService<TextureResourceInfo> completionService;
    
    // Performance monitoring
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong prefetchHits = new AtomicLong(0);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    
    // Configuration
    private static final int CORE_THREAD_POOL_SIZE = 4;
    private static final int MAX_THREAD_POOL_SIZE = 8;
    private static final long THREAD_KEEP_ALIVE_TIME = 60L;
    private static final int PREFETCH_BATCH_SIZE = 3;
    private static final long PERFORMANCE_MONITORING_INTERVAL = 5000L; // 5 seconds
    
    /**
     * Enhanced texture resource information with performance metadata
     */
    public static class TextureResourceInfo {
        private final String variantName;
        private final CowTextureDefinition.CowVariant variant;
        private final long loadTime;
        private final long memoryFootprint;
        private final CacheLevel cacheLevel;
        private final AtomicLong accessCount;
        private volatile long lastAccessed;
        private volatile boolean prefetched;
        
        public TextureResourceInfo(String variantName, CowTextureDefinition.CowVariant variant, 
                                 long loadTime, CacheLevel cacheLevel) {
            this.variantName = variantName;
            this.variant = variant;
            this.loadTime = loadTime;
            this.cacheLevel = cacheLevel;
            this.accessCount = new AtomicLong(0);
            this.lastAccessed = System.currentTimeMillis();
            this.prefetched = false;
            
            // Estimate memory footprint
            this.memoryFootprint = estimateMemoryFootprint(variant);
        }
        
        private long estimateMemoryFootprint(CowTextureDefinition.CowVariant variant) {
            if (variant == null) return 0L;
            
            long baseSize = 1024L; // Base object overhead
            
            // Face mappings
            if (variant.getFaceMappings() != null) {
                baseSize += variant.getFaceMappings().size() * 128L; // Estimated per mapping
            }
            
            // Drawing instructions
            if (variant.getDrawingInstructions() != null) {
                baseSize += variant.getDrawingInstructions().size() * 512L; // Estimated per instruction
            }
            
            return baseSize;
        }
        
        public void recordAccess() {
            accessCount.incrementAndGet();
            lastAccessed = System.currentTimeMillis();
        }
        
        // Getters
        public String getVariantName() { return variantName; }
        public CowTextureDefinition.CowVariant getVariant() { return variant; }
        public long getLoadTime() { return loadTime; }
        public long getMemoryFootprint() { return memoryFootprint; }
        public CacheLevel getCacheLevel() { return cacheLevel; }
        public long getAccessCount() { return accessCount.get(); }
        public long getLastAccessed() { return lastAccessed; }
        public boolean isPrefetched() { return prefetched; }
        public void setPrefetched(boolean prefetched) { this.prefetched = prefetched; }
    }
    
    /**
     * Cache levels for multi-tier caching strategy
     */
    public enum CacheLevel {
        HOT,    // Frequently accessed, highest priority
        WARM,   // Moderately accessed, medium priority
        COLD    // Rarely accessed, lowest priority
    }
    
    /**
     * Resource loading priority with intelligent scheduling
     */
    public enum LoadingPriority {
        IMMEDIATE(0),   // UI-blocking operations
        HIGH(1),        // User-initiated actions
        MEDIUM(2),      // Background prefetching
        LOW(3);         // Idle-time optimization
        
        private final int priority;
        LoadingPriority(int priority) { this.priority = priority; }
        public int getPriority() { return priority; }
    }
    
    /**
     * Callback interface for resource operations
     */
    public interface ResourceCallback {
        void onSuccess(TextureResourceInfo resource);
        void onError(String variantName, Throwable error);
        void onProgress(String operation, int current, int total, String details);
    }
    
    private UnifiedTextureResourceManager() {
        logger.info("Initializing Unified Texture Resource Manager with performance optimizations");
        
        // Initialize advanced caching system
        this.textureCache = new AdvancedTextureCache();
        
        // Initialize object pooling
        this.objectPool = new TextureObjectPool();
        
        // Initialize performance predictor
        this.performancePredictor = new PerformancePredictor();
        
        // Initialize unified thread pool with optimal configuration
        this.resourceExecutor = new ThreadPoolExecutor(
            CORE_THREAD_POOL_SIZE,
            MAX_THREAD_POOL_SIZE,
            THREAD_KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            new PriorityBlockingQueue<>(),
            new ThreadFactory() {
                private int counter = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "UnifiedTextureResource-" + (++counter));
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY);
                    return t;
                }
            }
        );
        
        this.completionService = new ExecutorCompletionService<>(resourceExecutor);
        
        // Initialize scheduled executor for background tasks
        this.scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "UnifiedTextureScheduled-" + System.currentTimeMillis());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        
        // Start background optimization tasks
        startBackgroundOptimization();
        
        logger.info("Unified Texture Resource Manager initialized successfully");
    }
    
    /**
     * Get the singleton instance with thread-safe lazy initialization
     */
    public static UnifiedTextureResourceManager getInstance() {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = new UnifiedTextureResourceManager();
                }
            }
        }
        return INSTANCE;
    }
    
    /**
     * Initialize the resource manager with intelligent prefetching
     */
    public CompletableFuture<Void> initializeAsync(ResourceCallback callback) {
        if (initialized.get()) {
            if (callback != null) {
                callback.onSuccess(null);
            }
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Starting unified texture resource initialization");
                
                if (callback != null) {
                    callback.onProgress("initialize", 0, 100, "Initializing core systems");
                }
                
                // Initialize underlying texture managers
                TextureManager.initializeAsync(null).get();
                
                if (callback != null) {
                    callback.onProgress("initialize", 25, 100, "Loading common texture variants");
                }
                
                // Prefetch common variants for optimal performance
                List<String> commonVariants = Arrays.asList("default", "angus", "highland", "jersey");
                prefetchVariantsAsync(commonVariants, LoadingPriority.HIGH).get();
                
                if (callback != null) {
                    callback.onProgress("initialize", 75, 100, "Optimizing cache distribution");
                }
                
                // Optimize initial cache distribution
                optimizeCacheDistribution();
                
                if (callback != null) {
                    callback.onProgress("initialize", 100, 100, "Initialization complete");
                    callback.onSuccess(null);
                }
                
                initialized.set(true);
                logger.info("Unified texture resource initialization completed successfully");
                
            } catch (Exception e) {
                logger.error("Failed to initialize unified texture resource manager", e);
                if (callback != null) {
                    callback.onError("initialization", e);
                }
                throw new RuntimeException("Initialization failed", e);
            }
        }, resourceExecutor);
    }
    
    /**
     * Get texture resource with intelligent caching and prefetching
     */
    public CompletableFuture<TextureResourceInfo> getResourceAsync(String variantName, 
                                                                  LoadingPriority priority, 
                                                                  ResourceCallback callback) {
        totalRequests.incrementAndGet();
        
        // Check hot cache first
        TextureResourceInfo hotResource = textureCache.getFromHotCache(variantName);
        if (hotResource != null) {
            cacheHits.incrementAndGet();
            hotResource.recordAccess();
            
            // Trigger predictive prefetching
            triggerPredictivePrefetch(variantName);
            
            if (callback != null) {
                callback.onSuccess(hotResource);
            }
            return CompletableFuture.completedFuture(hotResource);
        }
        
        // Check warm cache
        TextureResourceInfo warmResource = textureCache.getFromWarmCache(variantName);
        if (warmResource != null) {
            cacheHits.incrementAndGet();
            warmResource.recordAccess();
            
            // Promote to hot cache
            textureCache.promoteToHot(variantName, warmResource);
            
            if (callback != null) {
                callback.onSuccess(warmResource);
            }
            return CompletableFuture.completedFuture(warmResource);
        }
        
        // Check cold cache
        TextureResourceInfo coldResource = textureCache.getFromColdCache(variantName);
        if (coldResource != null) {
            cacheHits.incrementAndGet();
            coldResource.recordAccess();
            
            // Promote to warm cache
            textureCache.promoteToWarm(variantName, coldResource);
            
            if (callback != null) {
                callback.onSuccess(coldResource);
            }
            return CompletableFuture.completedFuture(coldResource);
        }
        
        // Cache miss - load asynchronously
        cacheMisses.incrementAndGet();
        return loadResourceAsync(variantName, priority, callback);
    }
    
    /**
     * Load texture resource with performance optimization
     */
    private CompletableFuture<TextureResourceInfo> loadResourceAsync(String variantName, 
                                                                   LoadingPriority priority, 
                                                                   ResourceCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                if (callback != null) {
                    callback.onProgress("loadResource", 0, 100, "Loading variant: " + variantName);
                }
                
                // Load using existing texture system
                CowTextureDefinition.CowVariant variant = CowTextureLoader
                    .getCowVariant(variantName);
                
                if (variant == null) {
                    throw new RuntimeException("Failed to load variant: " + variantName);
                }
                
                long loadTime = System.currentTimeMillis() - startTime;
                
                // Create resource info with performance metadata
                TextureResourceInfo resourceInfo = new TextureResourceInfo(
                    variantName, variant, loadTime, CacheLevel.HOT
                );
                
                // Add to cache with intelligent placement
                textureCache.addToCache(variantName, resourceInfo, determineCacheLevel(resourceInfo));
                
                // Update performance predictor
                performancePredictor.recordLoadTime(variantName, loadTime);
                
                if (callback != null) {
                    callback.onProgress("loadResource", 100, 100, "Resource loaded successfully");
                    callback.onSuccess(resourceInfo);
                }
                
                logger.debug("Loaded texture resource '{}' in {}ms", variantName, loadTime);
                return resourceInfo;
                
            } catch (Exception e) {
                logger.error("Failed to load texture resource '{}'", variantName, e);
                if (callback != null) {
                    callback.onError(variantName, e);
                }
                throw new RuntimeException("Resource loading failed", e);
            }
        }, resourceExecutor);
    }
    
    /**
     * Prefetch multiple variants with intelligent prioritization
     */
    public CompletableFuture<Map<String, TextureResourceInfo>> prefetchVariantsAsync(
            List<String> variantNames, LoadingPriority priority) {
        
        if (variantNames == null || variantNames.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        
        logger.debug("Prefetching {} variants with priority {}", variantNames.size(), priority);
        
        List<CompletableFuture<TextureResourceInfo>> futures = new ArrayList<>();
        
        for (String variantName : variantNames) {
            // Skip if already cached in hot cache
            if (textureCache.getFromHotCache(variantName) == null) {
                CompletableFuture<TextureResourceInfo> future = getResourceAsync(variantName, priority, null)
                    .thenApply(resource -> {
                        resource.setPrefetched(true);
                        prefetchHits.incrementAndGet();
                        return resource;
                    });
                futures.add(future);
            }
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                Map<String, TextureResourceInfo> results = new ConcurrentHashMap<>();
                for (CompletableFuture<TextureResourceInfo> future : futures) {
                    try {
                        TextureResourceInfo resource = future.get();
                        results.put(resource.getVariantName(), resource);
                    } catch (Exception e) {
                        logger.warn("Failed to prefetch variant", e);
                    }
                }
                return results;
            });
    }
    
    /**
     * Get comprehensive performance statistics
     */
    public PerformanceStatistics getPerformanceStatistics() {
        return new PerformanceStatistics(
            totalRequests.get(),
            cacheHits.get(),
            cacheMisses.get(),
            prefetchHits.get(),
            textureCache.getStatistics(),
            performancePredictor.getStatistics(),
            getMemoryUsage()
        );
    }
    
    /**
     * Performance statistics class
     */
    public static class PerformanceStatistics {
        private final long totalRequests;
        private final long cacheHits;
        private final long cacheMisses;
        private final long prefetchHits;
        private final Map<String, Object> cacheStats;
        private final Map<String, Object> predictorStats;
        private final long memoryUsage;
        
        public PerformanceStatistics(long totalRequests, long cacheHits, long cacheMisses, 
                                   long prefetchHits, Map<String, Object> cacheStats, 
                                   Map<String, Object> predictorStats, long memoryUsage) {
            this.totalRequests = totalRequests;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.prefetchHits = prefetchHits;
            this.cacheStats = cacheStats;
            this.predictorStats = predictorStats;
            this.memoryUsage = memoryUsage;
        }
        
        public double getHitRate() {
            return totalRequests > 0 ? (double) cacheHits / totalRequests * 100 : 0;
        }
        
        public double getPrefetchEfficiency() {
            return cacheHits > 0 ? (double) prefetchHits / cacheHits * 100 : 0;
        }
        
        // Getters
        public long getTotalRequests() { return totalRequests; }
        public long getCacheHits() { return cacheHits; }
        public long getCacheMisses() { return cacheMisses; }
        public long getPrefetchHits() { return prefetchHits; }
        public Map<String, Object> getCacheStats() { return cacheStats; }
        public Map<String, Object> getPredictorStats() { return predictorStats; }
        public long getMemoryUsage() { return memoryUsage; }
    }
    
    // Helper methods
    
    private void startBackgroundOptimization() {
        // Start cache optimization task
        scheduledExecutor.scheduleAtFixedRate(
            this::optimizeCacheDistribution,
            PERFORMANCE_MONITORING_INTERVAL,
            PERFORMANCE_MONITORING_INTERVAL,
            TimeUnit.MILLISECONDS
        );
        
        // Start performance monitoring task
        scheduledExecutor.scheduleAtFixedRate(
            this::performanceMonitoringTask,
            PERFORMANCE_MONITORING_INTERVAL,
            PERFORMANCE_MONITORING_INTERVAL,
            TimeUnit.MILLISECONDS
        );
    }
    
    private void optimizeCacheDistribution() {
        try {
            textureCache.optimizeDistribution();
        } catch (Exception e) {
            logger.warn("Error during cache optimization", e);
        }
    }
    
    private void performanceMonitoringTask() {
        try {
            PerformanceStatistics stats = getPerformanceStatistics();
            
            // Log performance metrics
            if (logger.isDebugEnabled()) {
                logger.debug("Performance: Hit rate {:.1f}%, Prefetch efficiency {:.1f}%, Memory usage {}MB",
                    stats.getHitRate(), stats.getPrefetchEfficiency(), stats.getMemoryUsage() / 1024 / 1024);
            }
            
            // Trigger optimizations if needed
            if (stats.getHitRate() < 80.0) {
                triggerCacheOptimization();
            }
            
        } catch (Exception e) {
            logger.warn("Error during performance monitoring", e);
        }
    }
    
    private void triggerPredictivePrefetch(String variantName) {
        List<String> predictedVariants = performancePredictor.predictNextVariants(variantName, PREFETCH_BATCH_SIZE);
        if (!predictedVariants.isEmpty()) {
            prefetchVariantsAsync(predictedVariants, LoadingPriority.LOW);
        }
    }
    
    private void triggerCacheOptimization() {
        resourceExecutor.submit(() -> {
            logger.debug("Triggering cache optimization due to low hit rate");
            optimizeCacheDistribution();
        });
    }
    
    private CacheLevel determineCacheLevel(TextureResourceInfo resourceInfo) {
        // Intelligent cache level determination based on usage patterns
        if (resourceInfo.getLoadTime() < 100) {
            return CacheLevel.HOT;
        } else if (resourceInfo.getLoadTime() < 500) {
            return CacheLevel.WARM;
        } else {
            return CacheLevel.COLD;
        }
    }
    
    private TextureManager.LoadingPriority convertPriority(LoadingPriority priority) {
        return switch (priority) {
            case IMMEDIATE -> TextureManager.LoadingPriority.IMMEDIATE;
            case HIGH -> TextureManager.LoadingPriority.HIGH;
            case MEDIUM -> TextureManager.LoadingPriority.NORMAL;
            case LOW -> TextureManager.LoadingPriority.LOW;
        };
    }
    
    private long getMemoryUsage() {
        return textureCache.getMemoryUsage() + objectPool.getMemoryUsage();
    }
    
    /**
     * Shutdown the resource manager gracefully
     */
    public void shutdown() {
        logger.info("Shutting down Unified Texture Resource Manager");
        
        resourceExecutor.shutdown();
        scheduledExecutor.shutdown();
        
        try {
            if (!resourceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                resourceExecutor.shutdownNow();
            }
            if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            resourceExecutor.shutdownNow();
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        textureCache.clear();
        objectPool.clear();
        initialized.set(false);
        
        logger.info("Unified Texture Resource Manager shutdown complete");
    }
    
    private static class PerformancePredictor {
        private final Map<String, List<String>> accessPatterns = new ConcurrentHashMap<>();
        private final Map<String, Long> loadTimes = new ConcurrentHashMap<>();
        
        void recordLoadTime(String variantName, long loadTime) {
            loadTimes.put(variantName, loadTime);
        }
        
        List<String> predictNextVariants(String variantName, int count) {
            // Simple prediction based on common texture variant patterns
            List<String> predictions = new ArrayList<>();
            
            // Common cow variant sequences
            switch (variantName.toLowerCase()) {
                case "default" -> {
                    predictions.add("angus");
                    predictions.add("highland");
                }
                case "angus" -> {
                    predictions.add("jersey");
                    predictions.add("default");
                }
                case "highland" -> {
                    predictions.add("jersey");
                    predictions.add("default");
                }
                case "jersey" -> {
                    predictions.add("angus");
                    predictions.add("highland");
                }
            }
            
            return predictions.stream().limit(count).toList();
        }
        
        Map<String, Object> getStatistics() {
            Map<String, Object> stats = new HashMap<>();
            stats.put("recordedLoadTimes", loadTimes.size());
            stats.put("averageLoadTime", loadTimes.values().stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0));
            return stats;
        }
    }
}