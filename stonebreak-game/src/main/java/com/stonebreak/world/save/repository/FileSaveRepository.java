package com.stonebreak.world.save.repository;

import com.stonebreak.world.save.io.ChunkStorage;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.serialization.JsonPlayerSerializer;
import com.stonebreak.world.save.serialization.JsonWorldSerializer;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Optional;

/**
 * Thin file I/O abstraction used by {@link com.stonebreak.world.save.SaveService}.
 * All methods are synchronous; callers decide threading policy.
 */
public class FileSaveRepository {

    private final Path worldRoot;
    private final Path metadataFile;
    private final Path playerFile;
    private final ChunkStorage chunkStorage;
    private final JsonWorldSerializer worldSerializer = new JsonWorldSerializer();
    private final JsonPlayerSerializer playerSerializer = new JsonPlayerSerializer();

    public FileSaveRepository(String worldPath) {
        this.worldRoot = Paths.get(worldPath);
        this.metadataFile = worldRoot.resolve("metadata.json");
        this.playerFile = worldRoot.resolve("player.json");
        this.chunkStorage = new ChunkStorage(worldRoot.resolve("chunks"));
    }

    public void ensureWorldDirectory() throws IOException {
        if (!Files.exists(worldRoot)) {
            Files.createDirectories(worldRoot);
        }
    }

    public void saveWorld(WorldData world) throws IOException {
        ensureWorldDirectory();
        writeAtomic(metadataFile, worldSerializer.serialize(world));
    }

    public Optional<WorldData> loadWorld() throws IOException {
        if (!Files.exists(metadataFile)) {
            return Optional.empty();
        }
        byte[] payload = Files.readAllBytes(metadataFile);
        return Optional.of(worldSerializer.deserialize(payload));
    }

    public void savePlayer(PlayerData player) throws IOException {
        ensureWorldDirectory();
        writeAtomic(playerFile, playerSerializer.serialize(player));
    }

    public Optional<PlayerData> loadPlayer() throws IOException {
        if (!Files.exists(playerFile)) {
            return Optional.empty();
        }
        byte[] payload = Files.readAllBytes(playerFile);
        return Optional.of(playerSerializer.deserialize(payload));
    }

    public void saveChunks(Collection<ChunkData> chunks) throws IOException {
        ensureWorldDirectory();
        chunkStorage.saveChunks(chunks);
    }

    public Optional<ChunkData> loadChunk(int chunkX, int chunkZ) throws IOException {
        return chunkStorage.loadChunk(chunkX, chunkZ);
    }

    public boolean chunkExists(int chunkX, int chunkZ) {
        return chunkStorage.chunkExists(chunkX, chunkZ);
    }

    public boolean worldExists() {
        return Files.exists(metadataFile) && Files.isReadable(metadataFile);
    }

    public void deleteChunk(int chunkX, int chunkZ) throws IOException {
        chunkStorage.deleteChunk(chunkX, chunkZ);
    }

    public void close() {
        // no-op: kept for API parity with legacy implementation
    }

    private void writeAtomic(Path target, byte[] payload) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        if (target.getParent() != null && !Files.exists(target.getParent())) {
            Files.createDirectories(target.getParent());
        }
        Files.write(temp, payload);
        try {
            Files.move(temp, target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
