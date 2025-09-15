package com.stonebreak.world.save.storage.providers;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.core.WorldMetadata;
import com.stonebreak.world.save.storage.binary.RegionFileManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.Collection;
import java.nio.charset.StandardCharsets;

/**
 * Provides access to terrain data (chunks and world metadata).
 * Single responsibility: abstract terrain data access from storage details.
 * Implements dependency inversion - depends on abstractions, not concrete implementations.
 */
public class TerrainDataProvider implements AutoCloseable {

    private static final String WORLD_METADATA_FILE = "metadata.json";

    private final String worldPath;
    private final RegionFileManager regionManager;
    private final ObjectMapper objectMapper;

    public TerrainDataProvider(String worldPath) {
        this.worldPath = worldPath;
        this.regionManager = new RegionFileManager(worldPath);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        // Ensure world directory exists
        try {
            Files.createDirectories(Paths.get(worldPath));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create world directory: " + worldPath, e);
        }
    }

    /**
     * Loads world metadata from storage.
     */
    public CompletableFuture<WorldMetadata> loadWorldMetadata() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path metadataPath = getMetadataPath();

                if (!Files.exists(metadataPath)) {
                    return null; // World doesn't exist
                }

                String jsonContent = Files.readString(metadataPath, StandardCharsets.UTF_8);
                return objectMapper.readValue(jsonContent, WorldMetadata.class);

            } catch (Exception e) {
                throw new RuntimeException("Failed to load world metadata", e);
            }
        });
    }

    /**
     * Saves world metadata to storage.
     */
    public CompletableFuture<Void> saveWorldMetadata(WorldMetadata metadata) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (metadata == null) {
                    throw new IllegalArgumentException("WorldMetadata cannot be null");
                }

                Path metadataPath = getMetadataPath();

                // Ensure parent directory exists
                Path parentDir = metadataPath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }

                String jsonContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
                if (jsonContent == null || jsonContent.trim().isEmpty()) {
                    throw new RuntimeException("Serialized metadata is empty");
                }

                // Atomic write using temporary file
                Path tempPath = Paths.get(metadataPath.toString() + ".tmp");
                Files.writeString(tempPath, jsonContent, StandardCharsets.UTF_8);
                Files.move(tempPath, metadataPath, StandardCopyOption.REPLACE_EXISTING);

                System.out.println("[SAVE] Successfully saved world metadata to: " + metadataPath);

            } catch (Exception e) {
                System.err.println("[SAVE-ERROR] Failed to save world metadata: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to save world metadata", e);
            }
        });
    }

    /**
     * Loads a chunk from terrain storage.
     */
    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        return regionManager.loadChunk(chunkX, chunkZ);
    }

    /**
     * Saves a chunk to terrain storage.
     */
    public CompletableFuture<Void> saveChunk(Chunk chunk) {
        return regionManager.saveChunk(chunk);
    }

    /**
     * Checks if a chunk exists in storage.
     */
    public CompletableFuture<Boolean> chunkExists(int chunkX, int chunkZ) {
        return regionManager.chunkExists(chunkX, chunkZ);
    }

    /**
     * Saves multiple dirty chunks efficiently.
     */
    public CompletableFuture<Void> saveDirtyChunks(Collection<Chunk> chunks) {
        return regionManager.saveDirtyChunks(chunks);
    }

    /**
     * Deletes a chunk from storage.
     */
    public CompletableFuture<Void> deleteChunk(int chunkX, int chunkZ) {
        return regionManager.deleteChunk(chunkX, chunkZ);
    }

    /**
     * Validates that the world exists and has valid metadata.
     */
    public CompletableFuture<Boolean> validateWorldExists() {
        return CompletableFuture.supplyAsync(() -> {
            Path metadataPath = getMetadataPath();
            return Files.exists(metadataPath) && Files.isReadable(metadataPath);
        });
    }

    /**
     * Gets storage statistics for the terrain data.
     */
    public RegionFileManager.RegionStats getStorageStats() {
        return regionManager.getRegionStats();
    }

    /**
     * Loads chunks in a radius around a center point.
     * Useful for initial world loading.
     */
    public CompletableFuture<Void> loadChunksInRadius(int centerX, int centerZ, int radius) {
        return CompletableFuture.runAsync(() -> {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    try {
                        loadChunk(x, z).get(); // Load synchronously within radius
                    } catch (Exception e) {
                        // Continue loading other chunks even if one fails
                        System.err.println("Failed to load chunk at " + x + "," + z + ": " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Gets the world directory path.
     */
    public String getWorldPath() {
        return worldPath;
    }

    /**
     * Gets the world name from the path.
     */
    public String getWorldName() {
        return Paths.get(worldPath).getFileName().toString();
    }

    @Override
    public void close() {
        try {
            regionManager.close();
        } catch (Exception e) {
            System.err.println("Error closing terrain data provider: " + e.getMessage());
        }
    }

    private Path getMetadataPath() {
        return Paths.get(worldPath, WORLD_METADATA_FILE);
    }
}