package com.stonebreak.world.save.repository;

import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.serialization.BinaryChunkSerializer;
import com.stonebreak.world.save.storage.binary.RegionFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository for region-based chunk storage.
 * Follows Single Responsibility - manages region files and chunk I/O.
 */
public class RegionRepository {
    private static final String REGION_FILE_EXTENSION = ".mcr";

    private final Path regionsDirectory;
    private final BinaryChunkSerializer chunkSerializer;
    private final ConcurrentHashMap<RegionCoordinate, RegionFile> openRegions;
    private final ExecutorService ioExecutor;

    public RegionRepository(String worldPath, BinaryChunkSerializer chunkSerializer) {
        this.regionsDirectory = Paths.get(worldPath, "regions");
        this.chunkSerializer = chunkSerializer;
        this.openRegions = new ConcurrentHashMap<>();

        // Create regions directory synchronously - fail fast
        try {
            Files.createDirectories(regionsDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create regions directory: " + regionsDirectory, e);
        }

        // Create I/O executor
        this.ioExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "RegionIO-" + regionsDirectory.getFileName());
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<Void> saveChunk(ChunkData chunk) {
        return CompletableFuture.runAsync(() -> {
            try {
                int chunkX = chunk.getChunkX();
                int chunkZ = chunk.getChunkZ();

                // CRITICAL VALIDATION: Ensure chunk coordinates are reasonable
                if (Math.abs(chunkX) > 1000000 || Math.abs(chunkZ) > 1000000) {
                    throw new IllegalArgumentException(String.format(
                        "CRITICAL: Refusing to save chunk with corrupted coordinates: (%d,%d)",
                        chunkX, chunkZ
                    ));
                }

                RegionCoordinate regionCoord = getRegionCoordinate(chunkX, chunkZ);
                RegionFile regionFile = getOrCreateRegion(regionCoord);

                byte[] chunkData = chunkSerializer.serialize(chunk);

                int localX = Math.floorMod(chunkX, 32);
                int localZ = Math.floorMod(chunkZ, 32);

                regionFile.writeChunk(localX, localZ, chunkData);

            } catch (Exception e) {
                throw new RuntimeException("Failed to save chunk at " + chunk.getChunkX() + "," + chunk.getChunkZ(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<Optional<ChunkData>> loadChunk(int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> {
            RegionCoordinate regionCoord = getRegionCoordinate(chunkX, chunkZ);
            RegionFile regionFile;
            try {
                regionFile = getOrOpenRegion(regionCoord);
            } catch (IOException e) {
                System.err.println("[CORRUPTION-RECOVERY] Failed to open region file for chunk (" + chunkX + "," + chunkZ + "): " + e.getMessage());
                return Optional.empty(); // Region corrupted, chunk will regenerate
            }

            if (regionFile == null) {
                return Optional.empty(); // Region file doesn't exist
            }

            int localX = Math.floorMod(chunkX, 32);
            int localZ = Math.floorMod(chunkZ, 32);

            byte[] chunkData;
            try {
                chunkData = regionFile.readChunk(localX, localZ);
            } catch (IOException e) {
                System.err.println("[CORRUPTION-RECOVERY] Failed to read chunk data at (" + chunkX + "," + chunkZ + "): " + e.getMessage());
                System.err.println("[CORRUPTION-RECOVERY] Chunk will be regenerated from scratch");
                // Try to delete corrupted chunk from region
                try {
                    regionFile.deleteChunk(localX, localZ);
                    System.err.println("[CORRUPTION-RECOVERY] Deleted corrupted chunk from region file");
                } catch (IOException deleteError) {
                    System.err.println("[CORRUPTION-RECOVERY] Failed to delete corrupted chunk: " + deleteError.getMessage());
                }
                return Optional.empty(); // Corrupted chunk, will regenerate
            }

            if (chunkData == null) {
                return Optional.empty(); // Chunk not saved
            }

            // Try to deserialize chunk data
            try {
                ChunkData chunk = chunkSerializer.deserialize(chunkData);
                return Optional.of(chunk);
            } catch (Exception e) {
                // CORRUPTION RECOVERY: If deserialization fails, delete corrupted chunk and regenerate
                System.err.println("[CORRUPTION-RECOVERY] ========================================");
                System.err.println("[CORRUPTION-RECOVERY] Corrupted chunk detected at (" + chunkX + "," + chunkZ + ")");
                System.err.println("[CORRUPTION-RECOVERY] Error: " + e.getMessage());
                System.err.println("[CORRUPTION-RECOVERY] This chunk will be deleted and regenerated");
                System.err.println("[CORRUPTION-RECOVERY] ========================================");

                // Delete corrupted chunk from region file
                try {
                    regionFile.deleteChunk(localX, localZ);
                    System.err.println("[CORRUPTION-RECOVERY] ✓ Corrupted chunk deleted from region file");
                } catch (IOException deleteError) {
                    System.err.println("[CORRUPTION-RECOVERY] ✗ Failed to delete corrupted chunk: " + deleteError.getMessage());
                }

                // Return empty so chunk regenerates
                return Optional.empty();
            }
        }, ioExecutor);
    }

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
        }, ioExecutor);
    }

    /**
     * Flushes all pending writes to disk across all open region files.
     * OPTIMIZATION: Ensures data durability during shutdown without closing files.
     */
    public void flush() {
        for (RegionFile region : openRegions.values()) {
            try {
                region.flush();
            } catch (IOException e) {
                System.err.println("Error flushing region file: " + e.getMessage());
            }
        }
    }

    public void close() {
        // Flush all pending writes before closing
        flush();

        // Close all open region files
        for (RegionFile region : openRegions.values()) {
            try {
                region.close();
            } catch (IOException e) {
                System.err.println("Error closing region file: " + e.getMessage());
            }
        }

        openRegions.clear();
        ioExecutor.shutdown();
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
        String filename = "r." + coord.x + "." + coord.z + REGION_FILE_EXTENSION;
        return regionsDirectory.resolve(filename);
    }

    /**
     * Coordinate for a region file.
     */
    private static class RegionCoordinate {
        private final int x;
        private final int z;

        RegionCoordinate(int x, int z) {
            this.x = x;
            this.z = z;
        }

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
    }
}
