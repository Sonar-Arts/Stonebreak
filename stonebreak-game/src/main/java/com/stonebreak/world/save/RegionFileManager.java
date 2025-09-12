package com.stonebreak.world.save;

import com.stonebreak.world.Chunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages multiple region files for binary chunk storage.
 * Coordinates chunk loading/saving across region files and handles caching of open regions.
 * 
 * This class provides the main interface for chunk I/O operations in the binary save system.
 */
public class RegionFileManager implements AutoCloseable {
    
    private final String worldPath;
    private final Path regionsDirectory;
    private final BinaryChunkCodec codec;
    private final RegionFileCache regionCache;
    private final ExecutorService ioExecutor;
    private final ReentrantReadWriteLock lock;
    private volatile boolean closed = false;
    
    /** Statistics tracking */
    private volatile long chunksLoaded = 0;
    private volatile long chunksSaved = 0;
    private volatile long totalBytesLoaded = 0;
    private volatile long totalBytesSaved = 0;
    
    /**
     * Create a region file manager for the specified world.
     * @param worldPath Path to the world directory
     * @throws IOException if directory creation fails
     */
    public RegionFileManager(String worldPath) throws IOException {
        this.worldPath = worldPath;
        this.regionsDirectory = Paths.get(worldPath, "regions");
        this.codec = new BinaryChunkCodec();
        this.regionCache = new RegionFileCache(32); // Cache up to 32 region files
        this.ioExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "RegionFile-IO");
            t.setDaemon(true);
            return t;
        });
        this.lock = new ReentrantReadWriteLock();
        
        // Ensure regions directory exists
        Files.createDirectories(regionsDirectory);
    }
    
    /**
     * Load a chunk asynchronously.
     * @param chunkX Global chunk X coordinate
     * @param chunkZ Global chunk Z coordinate
     * @return CompletableFuture with the loaded chunk, or null if not found
     */
    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loadChunkSync(chunkX, chunkZ);
            } catch (Exception e) {
                System.err.println("Failed to load chunk [" + chunkX + ", " + chunkZ + "]: " + e.getMessage());
                return null;
            }
        }, ioExecutor);
    }
    
    /**
     * Load a chunk synchronously.
     * @param chunkX Global chunk X coordinate
     * @param chunkZ Global chunk Z coordinate
     * @return Loaded chunk, or null if not found
     * @throws IOException if loading fails
     */
    public Chunk loadChunkSync(int chunkX, int chunkZ) throws IOException {
        if (closed) {
            return null;
        }
        
        RegionCoordinate regionCoord = RegionCoordinate.fromChunk(chunkX, chunkZ);
        
        lock.readLock().lock();
        try {
            RegionFile regionFile = getOrOpenRegionFile(regionCoord, false);
            if (regionFile == null) {
                return null; // Region file doesn't exist
            }
            
            int localX = RegionCoordinate.getLocalChunkX(chunkX);
            int localZ = RegionCoordinate.getLocalChunkZ(chunkZ);
            
            byte[] chunkData = regionFile.readChunk(localX, localZ);
            if (chunkData == null) {
                return null; // Chunk not saved
            }
            
            Chunk chunk = codec.decodeChunk(chunkData);
            
            // Update statistics
            chunksLoaded++;
            totalBytesLoaded += chunkData.length;
            
            return chunk;
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Save a chunk asynchronously.
     * @param chunk Chunk to save
     * @return CompletableFuture that completes when save is finished
     */
    public CompletableFuture<Void> saveChunk(Chunk chunk) {
        if (closed || chunk == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                saveChunkSync(chunk);
            } catch (Exception e) {
                System.err.println("Failed to save chunk [" + chunk.getX() + ", " + chunk.getZ() + "]: " + e.getMessage());
            }
        }, ioExecutor);
    }
    
    /**
     * Save a chunk synchronously.
     * @param chunk Chunk to save
     * @throws IOException if saving fails
     */
    public void saveChunkSync(Chunk chunk) throws IOException {
        if (closed || chunk == null) {
            return;
        }
        
        RegionCoordinate regionCoord = RegionCoordinate.fromChunk(chunk.getX(), chunk.getZ());
        
        lock.writeLock().lock();
        try {
            RegionFile regionFile = getOrOpenRegionFile(regionCoord, true);
            
            int localX = RegionCoordinate.getLocalChunkX(chunk.getX());
            int localZ = RegionCoordinate.getLocalChunkZ(chunk.getZ());
            
            byte[] chunkData = codec.encodeChunk(chunk);
            regionFile.writeChunk(localX, localZ, chunkData);
            
            // Update statistics
            chunksSaved++;
            totalBytesSaved += chunkData.length;
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Check if a chunk exists.
     * @param chunkX Global chunk X coordinate
     * @param chunkZ Global chunk Z coordinate
     * @return True if chunk exists
     */
    public boolean hasChunk(int chunkX, int chunkZ) {
        if (closed) {
            return false;
        }
        
        RegionCoordinate regionCoord = RegionCoordinate.fromChunk(chunkX, chunkZ);
        
        lock.readLock().lock();
        try {
            RegionFile regionFile = getOrOpenRegionFile(regionCoord, false);
            if (regionFile == null) {
                return false;
            }
            
            int localX = RegionCoordinate.getLocalChunkX(chunkX);
            int localZ = RegionCoordinate.getLocalChunkZ(chunkZ);
            
            return regionFile.hasChunk(localX, localZ);
            
        } catch (IOException e) {
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Delete a chunk.
     * @param chunkX Global chunk X coordinate
     * @param chunkZ Global chunk Z coordinate
     * @return CompletableFuture that completes when deletion is finished
     */
    public CompletableFuture<Void> deleteChunk(int chunkX, int chunkZ) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                deleteChunkSync(chunkX, chunkZ);
            } catch (Exception e) {
                System.err.println("Failed to delete chunk [" + chunkX + ", " + chunkZ + "]: " + e.getMessage());
            }
        }, ioExecutor);
    }
    
    /**
     * Delete a chunk synchronously.
     * @param chunkX Global chunk X coordinate
     * @param chunkZ Global chunk Z coordinate
     * @throws IOException if deletion fails
     */
    public void deleteChunkSync(int chunkX, int chunkZ) throws IOException {
        if (closed) {
            return;
        }
        
        RegionCoordinate regionCoord = RegionCoordinate.fromChunk(chunkX, chunkZ);
        
        lock.writeLock().lock();
        try {
            RegionFile regionFile = getOrOpenRegionFile(regionCoord, false);
            if (regionFile == null) {
                return; // Region doesn't exist
            }
            
            int localX = RegionCoordinate.getLocalChunkX(chunkX);
            int localZ = RegionCoordinate.getLocalChunkZ(chunkZ);
            
            regionFile.removeChunk(localX, localZ);
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Force all data to disk.
     * @return CompletableFuture that completes when sync is finished
     */
    public CompletableFuture<Void> syncAll() {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                syncAllSync();
            } catch (Exception e) {
                System.err.println("Failed to sync region files: " + e.getMessage());
            }
        }, ioExecutor);
    }
    
    /**
     * Force all data to disk synchronously.
     * @throws IOException if sync fails
     */
    public void syncAllSync() throws IOException {
        if (closed) {
            return;
        }
        
        lock.readLock().lock();
        try {
            regionCache.syncAll();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get or open a region file.
     * @param regionCoord Region coordinate
     * @param createIfMissing Whether to create the file if it doesn't exist
     * @return Region file, or null if not found and createIfMissing is false
     * @throws IOException if file operations fail
     */
    private RegionFile getOrOpenRegionFile(RegionCoordinate regionCoord, boolean createIfMissing) throws IOException {
        RegionFile cached = regionCache.get(regionCoord);
        if (cached != null) {
            return cached;
        }
        
        Path regionFilePath = regionsDirectory.resolve(regionCoord.getFileName());
        
        if (!Files.exists(regionFilePath) && !createIfMissing) {
            return null;
        }
        
        RegionFile regionFile = new RegionFile(regionFilePath);
        regionCache.put(regionCoord, regionFile);
        return regionFile;
    }
    
    /**
     * Get performance statistics.
     * @return Performance statistics
     */
    public PerformanceStats getStats() {
        return new PerformanceStats(
            chunksLoaded, chunksSaved,
            totalBytesLoaded, totalBytesSaved,
            regionCache.getStats()
        );
    }
    
    /**
     * Get the world path.
     * @return World path
     */
    public String getWorldPath() {
        return worldPath;
    }
    
    /**
     * Get the regions directory path.
     * @return Regions directory path
     */
    public Path getRegionsDirectory() {
        return regionsDirectory;
    }
    
    /**
     * Check if this manager is closed.
     * @return True if closed
     */
    public boolean isClosed() {
        return closed;
    }
    
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        
        lock.writeLock().lock();
        try {
            if (!closed) {
                closed = true;
                
                // Close all cached region files
                regionCache.close();
                
                // Shutdown executor
                ioExecutor.shutdown();
                try {
                    if (!ioExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        ioExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    ioExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Performance statistics for the region file manager.
     */
    public static class PerformanceStats {
        public final long chunksLoaded;
        public final long chunksSaved;
        public final long totalBytesLoaded;
        public final long totalBytesSaved;
        public final RegionFileCache.CacheStats cacheStats;
        
        public PerformanceStats(long chunksLoaded, long chunksSaved, 
                              long totalBytesLoaded, long totalBytesSaved,
                              RegionFileCache.CacheStats cacheStats) {
            this.chunksLoaded = chunksLoaded;
            this.chunksSaved = chunksSaved;
            this.totalBytesLoaded = totalBytesLoaded;
            this.totalBytesSaved = totalBytesSaved;
            this.cacheStats = cacheStats;
        }
        
        @Override
        public String toString() {
            return String.format(
                "RegionFileManager[loaded=%d chunks (%.1f MB), saved=%d chunks (%.1f MB), cache=%s]",
                chunksLoaded, totalBytesLoaded / 1024.0 / 1024.0,
                chunksSaved, totalBytesSaved / 1024.0 / 1024.0,
                cacheStats
            );
        }
    }
}