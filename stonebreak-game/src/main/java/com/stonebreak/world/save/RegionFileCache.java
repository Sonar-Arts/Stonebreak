package com.stonebreak.world.save;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LRU cache for region files to limit memory usage and file handle consumption.
 * Automatically closes least recently used region files when cache is full.
 */
public class RegionFileCache implements AutoCloseable {
    
    private final int maxSize;
    private final LinkedHashMap<RegionCoordinate, RegionFile> cache;
    private final ReentrantReadWriteLock lock;
    
    // Statistics
    private volatile long hits = 0;
    private volatile long misses = 0;
    private volatile long evictions = 0;
    
    /**
     * Create a region file cache with the specified maximum size.
     * @param maxSize Maximum number of region files to keep open
     */
    public RegionFileCache(int maxSize) {
        this.maxSize = maxSize;
        this.lock = new ReentrantReadWriteLock();
        
        // LinkedHashMap with access-order for LRU behavior
        this.cache = new LinkedHashMap<RegionCoordinate, RegionFile>(
                maxSize + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<RegionCoordinate, RegionFile> eldest) {
                if (size() > RegionFileCache.this.maxSize) {
                    try {
                        eldest.getValue().close();
                        evictions++;
                    } catch (IOException e) {
                        System.err.println("Failed to close evicted region file: " + e.getMessage());
                    }
                    return true;
                }
                return false;
            }
        };
    }
    
    /**
     * Get a region file from the cache.
     * @param regionCoord Region coordinate
     * @return Region file if cached, null otherwise
     */
    public RegionFile get(RegionCoordinate regionCoord) {
        lock.readLock().lock();
        try {
            RegionFile regionFile = cache.get(regionCoord);
            if (regionFile != null) {
                hits++;
                return regionFile;
            } else {
                misses++;
                return null;
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Put a region file in the cache.
     * May cause eviction of least recently used entries.
     * @param regionCoord Region coordinate
     * @param regionFile Region file to cache
     */
    public void put(RegionCoordinate regionCoord, RegionFile regionFile) {
        if (regionFile == null) {
            return;
        }
        
        lock.writeLock().lock();
        try {
            // Check if we already have this region cached
            RegionFile existing = cache.get(regionCoord);
            if (existing != null && existing != regionFile) {
                try {
                    existing.close();
                } catch (IOException e) {
                    System.err.println("Failed to close replaced region file: " + e.getMessage());
                }
            }
            
            cache.put(regionCoord, regionFile);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Remove a region file from the cache and close it.
     * @param regionCoord Region coordinate
     * @return True if the region was in the cache
     */
    public boolean remove(RegionCoordinate regionCoord) {
        lock.writeLock().lock();
        try {
            RegionFile regionFile = cache.remove(regionCoord);
            if (regionFile != null) {
                try {
                    regionFile.close();
                } catch (IOException e) {
                    System.err.println("Failed to close removed region file: " + e.getMessage());
                }
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Check if a region is cached.
     * @param regionCoord Region coordinate
     * @return True if region is in cache
     */
    public boolean contains(RegionCoordinate regionCoord) {
        lock.readLock().lock();
        try {
            return cache.containsKey(regionCoord);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get the number of cached region files.
     * @return Number of cached regions
     */
    public int size() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Check if the cache is empty.
     * @return True if cache is empty
     */
    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return cache.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Clear the cache, closing all region files.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            for (RegionFile regionFile : cache.values()) {
                try {
                    regionFile.close();
                } catch (IOException e) {
                    System.err.println("Failed to close region file during clear: " + e.getMessage());
                }
            }
            cache.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Sync all cached region files to disk.
     * @throws IOException if any sync operations fail
     */
    public void syncAll() throws IOException {
        lock.readLock().lock();
        try {
            IOException lastException = null;
            for (RegionFile regionFile : cache.values()) {
                try {
                    regionFile.sync();
                } catch (IOException e) {
                    lastException = e;
                    System.err.println("Failed to sync region file: " + e.getMessage());
                }
            }
            
            if (lastException != null) {
                throw lastException;
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Perform maintenance on the cache.
     * Checks for closed region files and removes them from the cache.
     */
    public void maintenance() {
        lock.writeLock().lock();
        try {
            cache.entrySet().removeIf(entry -> {
                RegionFile regionFile = entry.getValue();
                if (regionFile.isClosed()) {
                    return true; // Remove from cache
                }
                return false;
            });
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get cache statistics.
     * @return Cache statistics
     */
    public CacheStats getStats() {
        lock.readLock().lock();
        try {
            return new CacheStats(
                cache.size(), maxSize,
                hits, misses, evictions,
                calculateHitRate()
            );
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Calculate cache hit rate.
     * @return Hit rate as percentage (0-100)
     */
    private double calculateHitRate() {
        long totalRequests = hits + misses;
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) hits / totalRequests * 100.0;
    }
    
    /**
     * Reset cache statistics.
     */
    public void resetStats() {
        hits = 0;
        misses = 0;
        evictions = 0;
    }
    
    @Override
    public void close() throws IOException {
        clear();
    }
    
    /**
     * Cache statistics.
     */
    public static class CacheStats {
        public final int currentSize;
        public final int maxSize;
        public final long hits;
        public final long misses;
        public final long evictions;
        public final double hitRate;
        
        public CacheStats(int currentSize, int maxSize, long hits, long misses, long evictions, double hitRate) {
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.hitRate = hitRate;
        }
        
        public long getTotalRequests() {
            return hits + misses;
        }
        
        public double getUtilization() {
            return maxSize > 0 ? (double) currentSize / maxSize * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "Cache[%d/%d regions (%.1f%%), %.1f%% hit rate, %d evictions]",
                currentSize, maxSize, getUtilization(), hitRate, evictions
            );
        }
    }
}