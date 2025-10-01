package com.stonebreak.world.save.storage.binary;

import com.stonebreak.world.chunk.Chunk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages multiple region files and coordinates chunk I/O operations.
 * Single responsibility: coordinate region-based chunk storage operations.
 * Thread-safe with concurrent region file management.
 */
public class RegionFileManager implements AutoCloseable {

    private static final String REGION_FILE_EXTENSION = ".mcr";

    private final Path regionsDirectory;
    private final BinaryChunkCodec chunkCodec;
    private final ConcurrentHashMap<RegionCoordinate, RegionFile> openRegions;
    private volatile ExecutorService ioExecutor; // Lazy-initialized
    private final Object executorLock = new Object();

    public RegionFileManager(String worldPath) {
        this.regionsDirectory = Paths.get(worldPath, "regions");
        this.chunkCodec = new BinaryChunkCodec();
        this.openRegions = new ConcurrentHashMap<>();

        // Async directory creation to avoid blocking initialization
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(regionsDirectory);
            } catch (IOException e) {
                System.err.println("[INIT-WARNING] Failed to create regions directory: " + regionsDirectory + " - " + e.getMessage());
            }
        });
    }

    /**
     * Gets or creates the I/O executor with lazy initialization.
     * Avoids thread pool creation overhead during world initialization.
     */
    private ExecutorService getIOExecutor() {
        if (ioExecutor == null) {
            synchronized (executorLock) {
                if (ioExecutor == null) {
                    ioExecutor = Executors.newFixedThreadPool(2, r -> {
                        Thread t = new Thread(r, "RegionFileIO-" + regionsDirectory.getFileName());
                        t.setDaemon(true);
                        return t;
                    });
                    System.out.println("[PERFORMANCE] Lazy-initialized RegionFileManager executor for: " + regionsDirectory.getFileName());
                }
            }
        }
        return ioExecutor;
    }

    /**
     * Loads a chunk asynchronously from region storage.
     */
    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RegionCoordinate regionCoord = getRegionCoordinate(chunkX, chunkZ);
                RegionFile regionFile = getOrOpenRegion(regionCoord);

                if (regionFile == null) {
                    return null; // Region file doesn't exist
                }

                int localX = Math.floorMod(chunkX, 32);
                int localZ = Math.floorMod(chunkZ, 32);

                byte[] chunkData = regionFile.readChunk(localX, localZ);
                if (chunkData == null) {
                    return null; // Chunk not saved
                }

                return chunkCodec.decodeChunk(chunkData);

            } catch (Exception e) {
                throw new RuntimeException("Failed to load chunk at " + chunkX + "," + chunkZ, e);
            }
        }, getIOExecutor());
    }

    /**
     * Saves a chunk asynchronously to region storage.
     */
    public CompletableFuture<Void> saveChunk(Chunk chunk) {
        return CompletableFuture.runAsync(() -> {
            try {
                RegionCoordinate regionCoord = getRegionCoordinate(chunk.getX(), chunk.getZ());
                RegionFile regionFile = getOrCreateRegion(regionCoord);

                byte[] chunkData = chunkCodec.encodeChunk(chunk);

                int localX = Math.floorMod(chunk.getX(), 32);
                int localZ = Math.floorMod(chunk.getZ(), 32);

                regionFile.writeChunk(localX, localZ, chunkData);

                // Mark chunk as clean after successful save
                chunk.markClean();

            } catch (Exception e) {
                throw new RuntimeException("Failed to save chunk at " + chunk.getX() + "," + chunk.getZ(), e);
            }
        }, getIOExecutor());
    }

    /**
     * Checks if a chunk exists in storage.
     */
    public CompletableFuture<Boolean> chunkExists(int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RegionCoordinate regionCoord = getRegionCoordinate(chunkX, chunkZ);
                RegionFile regionFile = getOrOpenRegion(regionCoord);

                if (regionFile == null) {
                    return false;
                }

                int localX = Math.floorMod(chunkX, 32);
                int localZ = Math.floorMod(chunkZ, 32);

                return regionFile.hasChunk(localX, localZ);

            } catch (Exception e) {
                return false;
            }
        }, getIOExecutor());
    }

    /**
     * Deletes a chunk from storage.
     */
    public CompletableFuture<Void> deleteChunk(int chunkX, int chunkZ) {
        return CompletableFuture.runAsync(() -> {
            try {
                RegionCoordinate regionCoord = getRegionCoordinate(chunkX, chunkZ);
                RegionFile regionFile = getOrOpenRegion(regionCoord);

                if (regionFile != null) {
                    int localX = Math.floorMod(chunkX, 32);
                    int localZ = Math.floorMod(chunkZ, 32);
                    regionFile.deleteChunk(localX, localZ);
                }

            } catch (Exception e) {
                throw new RuntimeException("Failed to delete chunk at " + chunkX + "," + chunkZ, e);
            }
        }, getIOExecutor());
    }

    /**
     * Saves multiple chunks synchronously for auto-save operations.
     */
    public CompletableFuture<Void> saveDirtyChunks(Iterable<Chunk> chunks) {
        return CompletableFuture.runAsync(() -> {
            for (Chunk chunk : chunks) {
                if (chunk.isDirty()) {
                    try {
                        saveChunk(chunk).get(); // Wait for each chunk to complete
                    } catch (Exception e) {
                        System.err.println("Failed to save dirty chunk at " + chunk.getX() + "," + chunk.getZ() + ": " + e.getMessage());
                    }
                }
            }
        }, getIOExecutor());
    }

    /**
     * Gets statistics about region file usage.
     */
    public RegionStats getRegionStats() {
        RegionStats stats = new RegionStats();

        for (RegionFile region : openRegions.values()) {
            try {
                stats.totalRegions++;
                stats.totalChunks += region.getChunkCount();
                stats.totalSize += region.getFileSize();
            } catch (IOException e) {
                // Continue with other regions
            }
        }

        return stats;
    }

    @Override
    public void close() {
        // Close all open region files
        for (RegionFile region : openRegions.values()) {
            try {
                region.close();
            } catch (IOException e) {
                System.err.println("Error closing region file: " + e.getMessage());
            }
        }

        openRegions.clear();

        // Shutdown executor if it was initialized
        if (ioExecutor != null) {
            ioExecutor.shutdown();
        }
    }

    private RegionCoordinate getRegionCoordinate(int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        return new RegionCoordinate(regionX, regionZ);
    }

    private RegionFile getOrOpenRegion(RegionCoordinate coord) throws IOException {
        return openRegions.computeIfAbsent(coord, this::openRegion);
    }

    private RegionFile getOrCreateRegion(RegionCoordinate coord) throws IOException {
        return openRegions.computeIfAbsent(coord, c -> {
            try {
                Path regionPath = getRegionPath(c);
                return new RegionFile(regionPath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create region file", e);
            }
        });
    }

    private RegionFile openRegion(RegionCoordinate coord) {
        try {
            Path regionPath = getRegionPath(coord);
            if (!Files.exists(regionPath)) {
                return null; // Region doesn't exist yet
            }
            return new RegionFile(regionPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open region file", e);
        }
    }

    private Path getRegionPath(RegionCoordinate coord) {
        String filename = "r." + coord.getX() + "." + coord.getZ() + REGION_FILE_EXTENSION;
        return regionsDirectory.resolve(filename);
    }

    /**
     * Coordinate for a region file.
     */
    public static class RegionCoordinate {
        private final int x;
        private final int z;

        public RegionCoordinate(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public int getX() { return x; }
        public int getZ() { return z; }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RegionCoordinate)) return false;
            RegionCoordinate other = (RegionCoordinate) obj;
            return x == other.x && z == other.z;
        }

        @Override
        public int hashCode() {
            return x * 31 + z;
        }

        @Override
        public String toString() {
            return "(" + x + "," + z + ")";
        }
    }

    /**
     * Statistics about region file usage.
     */
    public static class RegionStats {
        public int totalRegions = 0;
        public int totalChunks = 0;
        public long totalSize = 0;

        @Override
        public String toString() {
            return String.format("Regions: %d, Chunks: %d, Size: %.2f MB",
                totalRegions, totalChunks, totalSize / (1024.0 * 1024.0));
        }
    }
}