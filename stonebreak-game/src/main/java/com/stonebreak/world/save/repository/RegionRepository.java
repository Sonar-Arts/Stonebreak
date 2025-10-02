package com.stonebreak.world.save.repository;

import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.serialization.Serializer;
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
    private final Serializer<ChunkData> chunkSerializer;
    private final ConcurrentHashMap<RegionCoordinate, RegionFile> openRegions;
    private final ExecutorService ioExecutor;

    public RegionRepository(String worldPath, Serializer<ChunkData> chunkSerializer) {
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
                RegionCoordinate regionCoord = getRegionCoordinate(chunk.getChunkX(), chunk.getChunkZ());
                RegionFile regionFile = getOrCreateRegion(regionCoord);

                byte[] chunkData = chunkSerializer.serialize(chunk);

                int localX = Math.floorMod(chunk.getChunkX(), 32);
                int localZ = Math.floorMod(chunk.getChunkZ(), 32);

                regionFile.writeChunk(localX, localZ, chunkData);

            } catch (Exception e) {
                throw new RuntimeException("Failed to save chunk at " + chunk.getChunkX() + "," + chunk.getChunkZ(), e);
            }
        }, ioExecutor);
    }

    public CompletableFuture<Optional<ChunkData>> loadChunk(int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RegionCoordinate regionCoord = getRegionCoordinate(chunkX, chunkZ);
                RegionFile regionFile = getOrOpenRegion(regionCoord);

                if (regionFile == null) {
                    return Optional.empty(); // Region file doesn't exist
                }

                int localX = Math.floorMod(chunkX, 32);
                int localZ = Math.floorMod(chunkZ, 32);

                byte[] chunkData = regionFile.readChunk(localX, localZ);
                if (chunkData == null) {
                    return Optional.empty(); // Chunk not saved
                }

                ChunkData chunk = chunkSerializer.deserialize(chunkData);
                return Optional.of(chunk);

            } catch (Exception e) {
                System.err.println("Failed to load chunk at (" + chunkX + "," + chunkZ + "): " + e.getMessage());
                return Optional.empty(); // Return empty on error to allow regeneration
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
