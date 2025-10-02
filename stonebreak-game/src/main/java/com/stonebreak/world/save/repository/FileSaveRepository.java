package com.stonebreak.world.save.repository;

import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.serialization.Serializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * File-based implementation of SaveRepository.
 * Follows Single Responsibility - only handles file I/O.
 * Uses dependency injection for serializers - follows Dependency Inversion.
 */
public class FileSaveRepository implements SaveRepository {
    private final String worldPath;
    private final Serializer<WorldData> worldSerializer;
    private final Serializer<PlayerData> playerSerializer;
    private final RegionRepository regionRepository;

    public FileSaveRepository(String worldPath,
                              Serializer<WorldData> worldSerializer,
                              Serializer<PlayerData> playerSerializer,
                              Serializer<ChunkData> chunkSerializer) {
        this.worldPath = worldPath;
        this.worldSerializer = worldSerializer;
        this.playerSerializer = playerSerializer;
        this.regionRepository = new RegionRepository(worldPath, chunkSerializer);

        // Create world directory synchronously - fail fast if there's an issue
        try {
            Files.createDirectories(Paths.get(worldPath));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create world directory: " + worldPath, e);
        }
    }

    @Override
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

    @Override
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

    @Override
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

    @Override
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

    @Override
    public CompletableFuture<Void> saveChunk(ChunkData chunk) {
        return regionRepository.saveChunk(chunk);
    }

    @Override
    public CompletableFuture<Optional<ChunkData>> loadChunk(int chunkX, int chunkZ) {
        return regionRepository.loadChunk(chunkX, chunkZ);
    }

    @Override
    public CompletableFuture<Boolean> chunkExists(int chunkX, int chunkZ) {
        return regionRepository.chunkExists(chunkX, chunkZ);
    }

    @Override
    public CompletableFuture<Boolean> worldExists() {
        return CompletableFuture.supplyAsync(() -> {
            Path metadataPath = Paths.get(worldPath, "metadata.json");
            return Files.exists(metadataPath) && Files.isReadable(metadataPath);
        });
    }

    @Override
    public void close() {
        regionRepository.close();
    }
}
