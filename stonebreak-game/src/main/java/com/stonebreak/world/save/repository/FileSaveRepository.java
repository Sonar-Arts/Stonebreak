package com.stonebreak.world.save.repository;

import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.serialization.JsonWorldSerializer;
import com.stonebreak.world.save.serialization.JsonPlayerSerializer;
import com.stonebreak.world.save.serialization.BinaryChunkSerializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * File-based repository for save/load operations.
 * Follows Single Responsibility - only handles file I/O.
 */
public class FileSaveRepository {
    private final String worldPath;
    private final JsonWorldSerializer worldSerializer;
    private final JsonPlayerSerializer playerSerializer;
    private final RegionRepository regionRepository;

    public FileSaveRepository(String worldPath) {
        this.worldPath = worldPath;
        this.worldSerializer = new JsonWorldSerializer();
        this.playerSerializer = new JsonPlayerSerializer();
        this.regionRepository = new RegionRepository(worldPath, new BinaryChunkSerializer());

        // Create world directory synchronously - fail fast if there's an issue
        try {
            Files.createDirectories(Paths.get(worldPath));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create world directory: " + worldPath, e);
        }
    }

    public CompletableFuture<Void> saveWorld(WorldData world) {
        return CompletableFuture.runAsync(() -> {
            try {
                byte[] data = worldSerializer.serialize(world);
                Path metadataPath = Paths.get(worldPath, "metadata.json");

                // Atomic write using temporary file
                Path tempPath = Paths.get(metadataPath.toString() + ".tmp");
                Files.write(tempPath, data);
                Files.move(tempPath, metadataPath, StandardCopyOption.REPLACE_EXISTING);

            } catch (Exception e) {
                throw new RuntimeException("Failed to save world metadata", e);
            }
        });
    }

    public CompletableFuture<Optional<WorldData>> loadWorld() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path metadataPath = Paths.get(worldPath, "metadata.json");

                if (!Files.exists(metadataPath)) {
                    return Optional.empty();
                }

                byte[] data = Files.readAllBytes(metadataPath);
                WorldData world = worldSerializer.deserialize(data);
                return Optional.of(world);

            } catch (Exception e) {
                throw new RuntimeException("Failed to load world metadata", e);
            }
        });
    }

    public CompletableFuture<Void> savePlayer(PlayerData player) {
        return CompletableFuture.runAsync(() -> {
            try {
                byte[] data = playerSerializer.serialize(player);
                Path playerPath = Paths.get(worldPath, "player.json");

                // Atomic write using temporary file
                Path tempPath = Paths.get(playerPath.toString() + ".tmp");
                Files.write(tempPath, data);
                Files.move(tempPath, playerPath, StandardCopyOption.REPLACE_EXISTING);

            } catch (Exception e) {
                throw new RuntimeException("Failed to save player data", e);
            }
        });
    }

    public CompletableFuture<Optional<PlayerData>> loadPlayer() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path playerPath = Paths.get(worldPath, "player.json");

                if (!Files.exists(playerPath)) {
                    return Optional.empty();
                }

                byte[] data = Files.readAllBytes(playerPath);
                PlayerData player = playerSerializer.deserialize(data);
                return Optional.of(player);

            } catch (Exception e) {
                throw new RuntimeException("Failed to load player data", e);
            }
        });
    }

    public CompletableFuture<Void> saveChunk(ChunkData chunk) {
        return regionRepository.saveChunk(chunk);
    }

    public CompletableFuture<Optional<ChunkData>> loadChunk(int chunkX, int chunkZ) {
        return regionRepository.loadChunk(chunkX, chunkZ);
    }

    public CompletableFuture<Boolean> chunkExists(int chunkX, int chunkZ) {
        return regionRepository.chunkExists(chunkX, chunkZ);
    }

    public CompletableFuture<Boolean> worldExists() {
        return CompletableFuture.supplyAsync(() -> {
            Path metadataPath = Paths.get(worldPath, "metadata.json");
            return Files.exists(metadataPath) && Files.isReadable(metadataPath);
        });
    }

    public void close() {
        regionRepository.close();
    }
}
