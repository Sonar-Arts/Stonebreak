package com.openmason.texture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Advanced Multi-Tier Texture Cache for Open Mason - Phase 5 Optimization
 * 
 * Implements intelligent three-tier caching strategy:
 * - HOT Cache: Frequently accessed textures (<10 items, <5MB)
 * - WARM Cache: Moderately accessed textures (<20 items, <15MB) 
 * - COLD Cache: Rarely accessed textures (<50 items, <30MB)
 * 
 * Features:
 * - Access pattern analysis with LRU/LFU hybrid eviction
 * - Intelligent prefetching based on usage patterns
 * - Memory-aware cache management with automatic resizing
 * - Performance monitoring and optimization
 * - Thread-safe operations with optimized locking
 */
public class AdvancedTextureCache {
    
    private static final Logger logger = LoggerFactory.getLogger(AdvancedTextureCache.class);
    
    // Cache tier configurations
    private static final int HOT_CACHE_MAX_SIZE = 10;
    private static final int WARM_CACHE_MAX_SIZE = 20;
    private static final int COLD_CACHE_MAX_SIZE = 50;
    
    private static final long HOT_CACHE_MAX_MEMORY = 5 * 1024 * 1024L;   // 5MB
    private static final long WARM_CACHE_MAX_MEMORY = 15 * 1024 * 1024L; // 15MB
    private static final long COLD_CACHE_MAX_MEMORY = 30 * 1024 * 1024L; // 30MB
    
    // Cache promotion/demotion thresholds
    private static final long HOT_ACCESS_THRESHOLD = 5;
    private static final long WARM_ACCESS_THRESHOLD = 2;
    private static final long COLD_TIME_THRESHOLD = 10 * 60 * 1000L; // 10 minutes
    
    // Cache tiers with access pattern tracking
    private final CacheLevel hotCache;
    private final CacheLevel warmCache;
    private final CacheLevel coldCache;
    
    // Global access pattern analysis
    private final Map<String, AccessPattern> accessPatterns = new ConcurrentHashMap<>();
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    
    // Performance monitoring
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong promotions = new AtomicLong(0);
    private final AtomicLong demotions = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    
    /**
     * Cache level implementation with intelligent eviction
     */
    private static class CacheLevel {
        private final String name;
        private final int maxSize;
        private final long maxMemory;
        private final Map<String, UnifiedTextureResourceManager.TextureResourceInfo> cache;
        private final LinkedList<String> accessOrder; // For LRU tracking
        private volatile long currentMemoryUsage = 0L;
        
        public CacheLevel(String name, int maxSize, long maxMemory) {
            this.name = name;
            this.maxSize = maxSize;
            this.maxMemory = maxMemory;
            this.cache = new ConcurrentHashMap<>();
            this.accessOrder = new LinkedList<>();
        }
        
        public synchronized UnifiedTextureResourceManager.TextureResourceInfo get(String key) {
            UnifiedTextureResourceManager.TextureResourceInfo resource = cache.get(key);
            if (resource != null) {
                // Update LRU order
                accessOrder.remove(key);
                accessOrder.addFirst(key);
                resource.recordAccess();
            }
            return resource;
        }
        
        public synchronized boolean put(String key, UnifiedTextureResourceManager.TextureResourceInfo resource) {
            // Check if resource fits
            long resourceMemory = resource.getMemoryFootprint();
            if (resourceMemory > maxMemory) {
                return false; // Resource too large for this cache level
            }
            
            // Remove existing entry if present
            UnifiedTextureResourceManager.TextureResourceInfo existing = cache.remove(key);
            accessOrder.remove(key);
            if (existing != null) {
                currentMemoryUsage -= existing.getMemoryFootprint();
            }
            
            // Ensure capacity
            ensureCapacity(resourceMemory);
            
            // Add new resource
            cache.put(key, resource);
            accessOrder.addFirst(key);
            currentMemoryUsage += resourceMemory;
            
            return true;
        }
        
        public synchronized UnifiedTextureResourceManager.TextureResourceInfo remove(String key) {
            UnifiedTextureResourceManager.TextureResourceInfo resource = cache.remove(key);
            accessOrder.remove(key);
            if (resource != null) {
                currentMemoryUsage -= resource.getMemoryFootprint();
            }
            return resource;
        }
        
        private void ensureCapacity(long newResourceMemory) {
            // Evict based on size limit
            while (cache.size() >= maxSize && !accessOrder.isEmpty()) {
                evictLRU();
            }
            
            // Evict based on memory limit
            while (currentMemoryUsage + newResourceMemory > maxMemory && !accessOrder.isEmpty()) {
                evictLRU();
            }
        }
        
        private void evictLRU() {
            if (accessOrder.isEmpty()) return;
            
            String lruKey = accessOrder.removeLast();
            UnifiedTextureResourceManager.TextureResourceInfo evicted = cache.remove(lruKey);
            if (evicted != null) {
                currentMemoryUsage -= evicted.getMemoryFootprint();
                logger.debug("Evicted {} from {} cache", lruKey, name);
            }
        }
        
        public synchronized List<String> getLRUCandidates(int count) {
            return new ArrayList<>(accessOrder.subList(
                Math.max(0, accessOrder.size() - count), 
                accessOrder.size()
            ));
        }
        
        public synchronized List<Map.Entry<String, UnifiedTextureResourceManager.TextureResourceInfo>> getAllEntries() {
            return new ArrayList<>(cache.entrySet());
        }
        
        public int size() { return cache.size(); }
        public long getMemoryUsage() { return currentMemoryUsage; }
        public boolean isEmpty() { return cache.isEmpty(); }
        
        public void clear() {
            cache.clear();
            accessOrder.clear();
            currentMemoryUsage = 0L;
        }
    }
    
    /**
     * Access pattern tracking for intelligent cache management
     */
    private static class AccessPattern {
        private final AtomicLong accessCount = new AtomicLong(0);
        private volatile long lastAccessTime = System.currentTimeMillis();
        private volatile long firstAccessTime = System.currentTimeMillis();
        private final List<Long> recentAccesses = Collections.synchronizedList(new ArrayList<>());
        
        public void recordAccess() {
            long now = System.currentTimeMillis();
            accessCount.incrementAndGet();
            lastAccessTime = now;
            
            // Keep only recent accesses for frequency analysis
            synchronized (recentAccesses) {
                recentAccesses.add(now);
                // Keep only last 10 accesses
                if (recentAccesses.size() > 10) {
                    recentAccesses.remove(0);
                }
            }
        }
        
        public long getAccessCount() { return accessCount.get(); }
        public long getLastAccessTime() { return lastAccessTime; }
        public long getTimeSinceLastAccess() { return System.currentTimeMillis() - lastAccessTime; }
        
        public double getAccessFrequency() {
            long timeSpan = System.currentTimeMillis() - firstAccessTime;
            return timeSpan > 0 ? (double) accessCount.get() / timeSpan * 1000 : 0; // accesses per second
        }
        
        public boolean isHotCandidate() {
            return accessCount.get() >= HOT_ACCESS_THRESHOLD && 
                   getTimeSinceLastAccess() < COLD_TIME_THRESHOLD;
        }
        
        public boolean isWarmCandidate() {
            return accessCount.get() >= WARM_ACCESS_THRESHOLD && 
                   getTimeSinceLastAccess() < COLD_TIME_THRESHOLD;
        }
        
        public boolean isColdCandidate() {
            return getTimeSinceLastAccess() > COLD_TIME_THRESHOLD;
        }
    }
    
    public AdvancedTextureCache() {
        this.hotCache = new CacheLevel("HOT", HOT_CACHE_MAX_SIZE, HOT_CACHE_MAX_MEMORY);
        this.warmCache = new CacheLevel("WARM", WARM_CACHE_MAX_SIZE, WARM_CACHE_MAX_MEMORY);
        this.coldCache = new CacheLevel("COLD", COLD_CACHE_MAX_SIZE, COLD_CACHE_MAX_MEMORY);
        
        logger.info("Advanced Texture Cache initialized with intelligent multi-tier caching");
    }
    
    /**
     * Get resource from hot cache
     */
    public UnifiedTextureResourceManager.TextureResourceInfo getFromHotCache(String variantName) {
        totalRequests.incrementAndGet();
        UnifiedTextureResourceManager.TextureResourceInfo resource = hotCache.get(variantName);
        if (resource != null) {
            cacheHits.incrementAndGet();
            recordAccess(variantName);
        }
        return resource;
    }
    
    /**
     * Get resource from warm cache
     */
    public UnifiedTextureResourceManager.TextureResourceInfo getFromWarmCache(String variantName) {
        totalRequests.incrementAndGet();
        UnifiedTextureResourceManager.TextureResourceInfo resource = warmCache.get(variantName);
        if (resource != null) {
            cacheHits.incrementAndGet();
            recordAccess(variantName);
        }
        return resource;
    }
    
    /**
     * Get resource from cold cache
     */
    public UnifiedTextureResourceManager.TextureResourceInfo getFromColdCache(String variantName) {
        totalRequests.incrementAndGet();
        UnifiedTextureResourceManager.TextureResourceInfo resource = coldCache.get(variantName);
        if (resource != null) {
            cacheHits.incrementAndGet();
            recordAccess(variantName);
        }
        return resource;
    }
    
    /**
     * Add resource to cache with intelligent tier placement
     */
    public void addToCache(String variantName, 
                          UnifiedTextureResourceManager.TextureResourceInfo resource, 
                          UnifiedTextureResourceManager.CacheLevel targetLevel) {
        cacheLock.writeLock().lock();
        try {
            // Remove from other caches first
            hotCache.remove(variantName);
            warmCache.remove(variantName);
            coldCache.remove(variantName);
            
            // Add to target cache level
            boolean added = switch (targetLevel) {
                case HOT -> hotCache.put(variantName, resource);
                case WARM -> warmCache.put(variantName, resource);
                case COLD -> coldCache.put(variantName, resource);
            };
            
            if (added) {
                recordAccess(variantName);
                logger.debug("Added '{}' to {} cache", variantName, targetLevel);
            } else {
                logger.warn("Failed to add '{}' to {} cache (capacity/memory limits)", variantName, targetLevel);
                // Fallback to cold cache
                if (targetLevel != UnifiedTextureResourceManager.CacheLevel.COLD) {
                    coldCache.put(variantName, resource);
                }
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Promote resource to hot cache
     */
    public void promoteToHot(String variantName, UnifiedTextureResourceManager.TextureResourceInfo resource) {
        cacheLock.writeLock().lock();
        try {
            // Remove from lower tiers
            warmCache.remove(variantName);
            coldCache.remove(variantName);
            
            // Add to hot cache
            if (hotCache.put(variantName, resource)) {
                promotions.incrementAndGet();
                logger.debug("Promoted '{}' to HOT cache", variantName);
            } else {
                // Fallback to warm cache if hot cache is full
                warmCache.put(variantName, resource);
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Promote resource to warm cache
     */
    public void promoteToWarm(String variantName, UnifiedTextureResourceManager.TextureResourceInfo resource) {
        cacheLock.writeLock().lock();
        try {
            coldCache.remove(variantName);
            
            if (warmCache.put(variantName, resource)) {
                promotions.incrementAndGet();
                logger.debug("Promoted '{}' to WARM cache", variantName);
            } else {
                // Fallback to cold cache if warm cache is full
                coldCache.put(variantName, resource);
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Optimize cache distribution based on access patterns
     */
    public void optimizeDistribution() {
        cacheLock.writeLock().lock();
        try {
            logger.debug("Starting cache distribution optimization");
            
            // Analyze access patterns and optimize tier placement
            optimizeHotCache();
            optimizeWarmCache();
            optimizeColdCache();
            
            logger.debug("Cache optimization completed - Hot: {}, Warm: {}, Cold: {}", 
                hotCache.size(), warmCache.size(), coldCache.size());
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    private void optimizeHotCache() {
        // Move frequently accessed items from warm to hot
        List<Map.Entry<String, UnifiedTextureResourceManager.TextureResourceInfo>> warmEntries = 
            warmCache.getAllEntries();
        
        for (Map.Entry<String, UnifiedTextureResourceManager.TextureResourceInfo> entry : warmEntries) {
            String variantName = entry.getKey();
            AccessPattern pattern = accessPatterns.get(variantName);
            
            if (pattern != null && pattern.isHotCandidate()) {
                UnifiedTextureResourceManager.TextureResourceInfo resource = warmCache.remove(variantName);
                if (resource != null && hotCache.put(variantName, resource)) {
                    promotions.incrementAndGet();
                    logger.debug("Optimized: Promoted '{}' to HOT cache", variantName);
                } else if (resource != null) {
                    // Put it back if hot cache is full
                    warmCache.put(variantName, resource);
                }
            }
        }
        
        // Demote old items from hot to warm
        List<String> lruCandidates = hotCache.getLRUCandidates(3);
        for (String variantName : lruCandidates) {
            AccessPattern pattern = accessPatterns.get(variantName);
            if (pattern != null && !pattern.isHotCandidate()) {
                UnifiedTextureResourceManager.TextureResourceInfo resource = hotCache.remove(variantName);
                if (resource != null) {
                    warmCache.put(variantName, resource);
                    demotions.incrementAndGet();
                    logger.debug("Optimized: Demoted '{}' from HOT to WARM cache", variantName);
                }
            }
        }
    }
    
    private void optimizeWarmCache() {
        // Move moderately accessed items from cold to warm
        List<Map.Entry<String, UnifiedTextureResourceManager.TextureResourceInfo>> coldEntries = 
            coldCache.getAllEntries();
        
        for (Map.Entry<String, UnifiedTextureResourceManager.TextureResourceInfo> entry : coldEntries) {
            String variantName = entry.getKey();
            AccessPattern pattern = accessPatterns.get(variantName);
            
            if (pattern != null && pattern.isWarmCandidate()) {
                UnifiedTextureResourceManager.TextureResourceInfo resource = coldCache.remove(variantName);
                if (resource != null && warmCache.put(variantName, resource)) {
                    promotions.incrementAndGet();
                    logger.debug("Optimized: Promoted '{}' to WARM cache", variantName);
                } else if (resource != null) {
                    coldCache.put(variantName, resource);
                }
            }
        }
        
        // Demote old items from warm to cold
        List<String> lruCandidates = warmCache.getLRUCandidates(5);
        for (String variantName : lruCandidates) {
            AccessPattern pattern = accessPatterns.get(variantName);
            if (pattern != null && pattern.isColdCandidate()) {
                UnifiedTextureResourceManager.TextureResourceInfo resource = warmCache.remove(variantName);
                if (resource != null) {
                    coldCache.put(variantName, resource);
                    demotions.incrementAndGet();
                    logger.debug("Optimized: Demoted '{}' from WARM to COLD cache", variantName);
                }
            }
        }
    }
    
    private void optimizeColdCache() {
        // Remove very old items from cold cache
        List<String> lruCandidates = coldCache.getLRUCandidates(10);
        for (String variantName : lruCandidates) {
            AccessPattern pattern = accessPatterns.get(variantName);
            if (pattern != null && pattern.getTimeSinceLastAccess() > COLD_TIME_THRESHOLD * 2) {
                UnifiedTextureResourceManager.TextureResourceInfo resource = coldCache.remove(variantName);
                if (resource != null) {
                    accessPatterns.remove(variantName);
                    evictions.incrementAndGet();
                    logger.debug("Optimized: Evicted '{}' from COLD cache", variantName);
                }
            }
        }
    }
    
    private void recordAccess(String variantName) {
        accessPatterns.computeIfAbsent(variantName, k -> new AccessPattern()).recordAccess();
    }
    
    /**
     * Get comprehensive cache statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        // Basic stats
        stats.put("totalRequests", totalRequests.get());
        stats.put("cacheHits", cacheHits.get());
        stats.put("hitRate", totalRequests.get() > 0 ? 
            (double) cacheHits.get() / totalRequests.get() * 100 : 0);
        
        // Cache tier stats
        stats.put("hotCacheSize", hotCache.size());
        stats.put("warmCacheSize", warmCache.size());
        stats.put("coldCacheSize", coldCache.size());
        stats.put("totalCacheSize", hotCache.size() + warmCache.size() + coldCache.size());
        
        // Memory usage
        stats.put("hotCacheMemory", hotCache.getMemoryUsage());
        stats.put("warmCacheMemory", warmCache.getMemoryUsage());
        stats.put("coldCacheMemory", coldCache.getMemoryUsage());
        stats.put("totalMemoryUsage", getMemoryUsage());
        
        // Optimization stats
        stats.put("promotions", promotions.get());
        stats.put("demotions", demotions.get());
        stats.put("evictions", evictions.get());
        
        return stats;
    }
    
    /**
     * Get total memory usage across all cache tiers
     */
    public long getMemoryUsage() {
        return hotCache.getMemoryUsage() + warmCache.getMemoryUsage() + coldCache.getMemoryUsage();
    }
    
    /**
     * Clear all cache tiers
     */
    public void clear() {
        cacheLock.writeLock().lock();
        try {
            hotCache.clear();
            warmCache.clear();
            coldCache.clear();
            accessPatterns.clear();
            
            // Reset counters
            totalRequests.set(0);
            cacheHits.set(0);
            promotions.set(0);
            demotions.set(0);
            evictions.set(0);
            
            logger.info("Advanced texture cache cleared");
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Get cache efficiency metrics
     */
    public CacheEfficiencyMetrics getEfficiencyMetrics() {
        return new CacheEfficiencyMetrics(
            totalRequests.get(),
            cacheHits.get(),
            hotCache.size(),
            warmCache.size(),
            coldCache.size(),
            getMemoryUsage(),
            promotions.get(),
            demotions.get(),
            evictions.get()
        );
    }
    
    /**
     * Cache efficiency metrics class
     */
    public static class CacheEfficiencyMetrics {
        private final long totalRequests;
        private final long cacheHits;
        private final int hotCacheSize;
        private final int warmCacheSize;
        private final int coldCacheSize;
        private final long memoryUsage;
        private final long promotions;
        private final long demotions;
        private final long evictions;
        
        public CacheEfficiencyMetrics(long totalRequests, long cacheHits, 
                                    int hotCacheSize, int warmCacheSize, int coldCacheSize,
                                    long memoryUsage, long promotions, long demotions, long evictions) {
            this.totalRequests = totalRequests;
            this.cacheHits = cacheHits;
            this.hotCacheSize = hotCacheSize;
            this.warmCacheSize = warmCacheSize;
            this.coldCacheSize = coldCacheSize;
            this.memoryUsage = memoryUsage;
            this.promotions = promotions;
            this.demotions = demotions;
            this.evictions = evictions;
        }
        
        public double getHitRate() {
            return totalRequests > 0 ? (double) cacheHits / totalRequests * 100 : 0;
        }
        
        public double getMemoryEfficiency() {
            long maxMemory = HOT_CACHE_MAX_MEMORY + WARM_CACHE_MAX_MEMORY + COLD_CACHE_MAX_MEMORY;
            return maxMemory > 0 ? (double) memoryUsage / maxMemory * 100 : 0;
        }
        
        public int getTotalItems() {
            return hotCacheSize + warmCacheSize + coldCacheSize;
        }
        
        // Getters
        public long getTotalRequests() { return totalRequests; }
        public long getCacheHits() { return cacheHits; }
        public int getHotCacheSize() { return hotCacheSize; }
        public int getWarmCacheSize() { return warmCacheSize; }
        public int getColdCacheSize() { return coldCacheSize; }
        public long getMemoryUsage() { return memoryUsage; }
        public long getPromotions() { return promotions; }
        public long getDemotions() { return demotions; }
        public long getEvictions() { return evictions; }
    }
}