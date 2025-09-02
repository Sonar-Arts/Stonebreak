package com.stonebreak.textures;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory cache for texture atlas metadata providing fast runtime texture coordinate lookups.
 * Thread-safe implementation with LRU eviction and performance monitoring.
 */
public class AtlasMetadataCache {
    
    // Default cache configuration
    private static final int DEFAULT_MAX_ENTRIES = 1000;
    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000; // 5 minutes
    
    // Cache storage
    private final Map<String, CacheEntry> cache;
    private final Map<String, Long> accessOrder;
    private final int maxEntries;
    private final long ttlMs;
    
    // Performance monitoring
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);
    private final AtomicLong insertCount = new AtomicLong(0);
    
    /**
     * Represents texture coordinates and metadata for a single texture.
     */
    public static class TextureCoordinates {
        public final float u1, v1, u2, v2;  // UV coordinates
        public final int atlasX, atlasY;     // Pixel coordinates in atlas
        public final int width, height;     // Texture dimensions
        public final String textureName;    // Name/ID of texture
        public final TextureResourceLoader.TextureType type;      // Type of texture
        
        public TextureCoordinates(String textureName, float u1, float v1, float u2, float v2,
                                 int atlasX, int atlasY, int width, int height, TextureResourceLoader.TextureType type) {
            this.textureName = textureName;
            this.u1 = u1;
            this.v1 = v1;
            this.u2 = u2;
            this.v2 = v2;
            this.atlasX = atlasX;
            this.atlasY = atlasY;
            this.width = width;
            this.height = height;
            this.type = type;
        }
        
        public float[] getUVArray() {
            return new float[]{u1, v1, u2, v2};
        }
        
        @Override
        public String toString() {
            return String.format("TextureCoordinates{%s, UV=(%.3f,%.3f)-(%.3f,%.3f), pos=(%d,%d), size=%dx%d}", 
                               textureName, u1, v1, u2, v2, atlasX, atlasY, width, height);
        }
    }
    
    // TextureType enum is now defined in TextureResourceLoader
    
    /**
     * Internal cache entry with timestamp for TTL management.
     */
    private static class CacheEntry {
        public final TextureCoordinates coordinates;
        public final long createdTime;
        public volatile long lastAccessTime;
        
        public CacheEntry(TextureCoordinates coordinates) {
            this.coordinates = coordinates;
            this.createdTime = System.currentTimeMillis();
            this.lastAccessTime = this.createdTime;
        }
        
        public boolean isExpired(long currentTime, long ttl) {
            return (currentTime - createdTime) > ttl;
        }
    }
    
    /**
     * Cache statistics for monitoring and debugging.
     */
    public static class CacheStats {
        public final long hitCount;
        public final long missCount;
        public final long insertCount;
        public final long evictionCount;
        public final int currentSize;
        public final int maxSize;
        public final double hitRate;
        
        public CacheStats(long hitCount, long missCount, long insertCount, long evictionCount, 
                         int currentSize, int maxSize) {
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.insertCount = insertCount;
            this.evictionCount = evictionCount;
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.hitRate = (hitCount + missCount > 0) ? (double) hitCount / (hitCount + missCount) : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("CacheStats{hits=%d, misses=%d, inserts=%d, evictions=%d, size=%d/%d, hitRate=%.2f%%}", 
                               hitCount, missCount, insertCount, evictionCount, currentSize, maxSize, hitRate * 100);
        }
    }
    
    /**
     * Creates a new cache with default configuration.
     */
    public AtlasMetadataCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_MS);
    }
    
    /**
     * Creates a new cache with custom configuration.
     * @param maxEntries Maximum number of entries to cache
     * @param ttlMs Time-to-live for cache entries in milliseconds
     */
    public AtlasMetadataCache(int maxEntries, long ttlMs) {
        this.maxEntries = maxEntries;
        this.ttlMs = ttlMs;
        this.cache = new ConcurrentHashMap<>(maxEntries);
        this.accessOrder = new ConcurrentHashMap<>(maxEntries);
    }
    
    /**
     * Retrieves texture coordinates from cache.
     * @param textureKey The texture identifier key
     * @return TextureCoordinates if found, null if not cached or expired
     */
    public TextureCoordinates get(String textureKey) {
        CacheEntry entry = cache.get(textureKey);
        
        if (entry == null) {
            missCount.incrementAndGet();
            return null;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // Check if entry is expired
        if (entry.isExpired(currentTime, ttlMs)) {
            cache.remove(textureKey);
            accessOrder.remove(textureKey);
            missCount.incrementAndGet();
            evictionCount.incrementAndGet();
            return null;
        }
        
        // Update access time for LRU
        entry.lastAccessTime = currentTime;
        accessOrder.put(textureKey, currentTime);
        
        hitCount.incrementAndGet();
        return entry.coordinates;
    }
    
    /**
     * Stores texture coordinates in cache.
     * @param textureKey The texture identifier key
     * @param coordinates The texture coordinates to cache
     */
    public void put(String textureKey, TextureCoordinates coordinates) {
        // Check if we need to evict entries
        evictExpiredEntries();
        
        if (cache.size() >= maxEntries) {
            evictLRUEntry();
        }
        
        CacheEntry entry = new CacheEntry(coordinates);
        cache.put(textureKey, entry);
        accessOrder.put(textureKey, entry.createdTime);
        
        insertCount.incrementAndGet();
    }
    
    /**
     * Evicts expired entries from the cache.
     */
    private void evictExpiredEntries() {
        long currentTime = System.currentTimeMillis();
        List<String> expiredKeys = new ArrayList<>();
        
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (entry.getValue().isExpired(currentTime, ttlMs)) {
                expiredKeys.add(entry.getKey());
            }
        }
        
        for (String key : expiredKeys) {
            cache.remove(key);
            accessOrder.remove(key);
            evictionCount.incrementAndGet();
        }
    }
    
    /**
     * Evicts the least recently used entry.
     */
    private void evictLRUEntry() {
        if (accessOrder.isEmpty()) {
            return;
        }
        
        // Find the entry with the oldest access time
        String lruKey = null;
        long oldestTime = Long.MAX_VALUE;
        
        for (Map.Entry<String, Long> entry : accessOrder.entrySet()) {
            if (entry.getValue() < oldestTime) {
                oldestTime = entry.getValue();
                lruKey = entry.getKey();
            }
        }
        
        if (lruKey != null) {
            cache.remove(lruKey);
            accessOrder.remove(lruKey);
            evictionCount.incrementAndGet();
        }
    }
    
    /**
     * Checks if a texture is cached.
     * @param textureKey The texture identifier key
     * @return true if cached and not expired, false otherwise
     */
    public boolean contains(String textureKey) {
        return get(textureKey) != null;
    }
    
    /**
     * Removes a specific entry from cache.
     * @param textureKey The texture identifier key
     * @return true if entry was removed, false if not present
     */
    public boolean remove(String textureKey) {
        boolean removed = cache.remove(textureKey) != null;
        if (removed) {
            accessOrder.remove(textureKey);
        }
        return removed;
    }
    
    /**
     * Clears all entries from the cache.
     */
    public void clear() {
        cache.clear();
        accessOrder.clear();
        System.out.println("AtlasMetadataCache: Cache cleared");
    }
    
    /**
     * Invalidates the entire cache, typically called when atlas is rebuilt.
     */
    public void invalidateAll() {
        clear();
        System.out.println("AtlasMetadataCache: All entries invalidated due to atlas rebuild");
    }
    
    /**
     * Gets current cache statistics.
     * @return CacheStats object with performance metrics
     */
    public CacheStats getStats() {
        return new CacheStats(
            hitCount.get(),
            missCount.get(),
            insertCount.get(),
            evictionCount.get(),
            cache.size(),
            maxEntries
        );
    }
    
    /**
     * Preloads cache with commonly used textures.
     * This can be called after atlas generation to warm up the cache.
     * @param commonTextures Map of commonly used texture coordinates
     */
    public void preload(Map<String, TextureCoordinates> commonTextures) {
        int preloadedCount = 0;
        
        for (Map.Entry<String, TextureCoordinates> entry : commonTextures.entrySet()) {
            put(entry.getKey(), entry.getValue());
            preloadedCount++;
        }
        
        System.out.println("AtlasMetadataCache: Preloaded " + preloadedCount + " texture coordinates");
    }
    
    /**
     * Generates cache keys for different texture lookup patterns.
     */
    public static class KeyGenerator {
        
        /**
         * Generates key for block texture lookup.
         * @param blockName The block name (e.g., "stonebreak:grass")
         * @return Cache key string
         */
        public static String blockKey(String blockName) {
            return "block:" + blockName;
        }
        
        /**
         * Generates key for block face texture lookup.
         * @param blockName The block name
         * @param face The face name (e.g., "top", "bottom", "north", "south", "east", "west")
         * @return Cache key string
         */
        public static String blockFaceKey(String blockName, String face) {
            return "block:" + blockName + ":" + face;
        }
        
        /**
         * Generates key for item texture lookup.
         * @param itemName The item name (e.g., "stonebreak:stick")
         * @return Cache key string
         */
        public static String itemKey(String itemName) {
            return "item:" + itemName;
        }
        
        /**
         * Generates key for item by ID lookup.
         * @param itemId The item ID
         * @return Cache key string
         */
        public static String itemIdKey(int itemId) {
            return "item:id:" + itemId;
        }
        
        /**
         * Generates key for error texture lookup.
         * @return Cache key string for error texture
         */
        public static String errorKey() {
            return "error:texture";
        }
    }
    
    /**
     * Performs maintenance operations like cleanup of expired entries.
     * Should be called periodically (e.g., once per frame or every few seconds).
     */
    public void performMaintenance() {
        evictExpiredEntries();
        
        // Log statistics periodically for monitoring
        CacheStats stats = getStats();
        if ((stats.hitCount + stats.missCount) > 0 && (stats.hitCount + stats.missCount) % 1000 == 0) {
            System.out.println("AtlasMetadataCache: " + stats);
        }
    }
    
    /**
     * Gets the current cache size.
     * @return Number of entries currently cached
     */
    public int size() {
        return cache.size();
    }
    
    /**
     * Checks if cache is empty.
     * @return true if no entries are cached
     */
    public boolean isEmpty() {
        return cache.isEmpty();
    }
    
    /**
     * Gets all cached texture keys for debugging.
     * @return Set of all cached keys
     */
    public Set<String> getCachedKeys() {
        return new HashSet<>(cache.keySet());
    }
    
    /**
     * Prints cache status for debugging.
     */
    public void printStatus() {
        CacheStats stats = getStats();
        System.out.println("=== Atlas Metadata Cache Status ===");
        System.out.println(stats);
        System.out.println("TTL: " + ttlMs + "ms");
        
        if (!cache.isEmpty()) {
            System.out.println("Sample cached entries:");
            int count = 0;
            for (String key : cache.keySet()) {
                if (count >= 5) break; // Show first 5 entries
                TextureCoordinates coords = get(key);
                if (coords != null) {
                    System.out.println("  " + key + " -> " + coords);
                }
                count++;
            }
        }
        
        System.out.println("=================================");
    }
    
    /**
     * Convenience method for invalidating cache (alias for invalidateAll).
     */
    public void invalidateCache() {
        invalidateAll();
    }
    
    /**
     * Get texture metadata as a map (for backward compatibility).
     * @param textureName The texture name to look up
     * @return Map containing texture metadata, or null if not found
     */
    public Map<String, Object> getTextureMetadata(String textureName) {
        TextureCoordinates coords = get(textureName);
        if (coords == null) {
            return null;
        }
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("x", coords.atlasX);
        metadata.put("y", coords.atlasY);
        metadata.put("width", coords.width);
        metadata.put("height", coords.height);
        metadata.put("u1", coords.u1);
        metadata.put("v1", coords.v1);
        metadata.put("u2", coords.u2);
        metadata.put("v2", coords.v2);
        metadata.put("type", coords.type.toString());
        
        return metadata;
    }
}