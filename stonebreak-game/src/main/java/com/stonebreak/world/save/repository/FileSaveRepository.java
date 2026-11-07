package com.stonebreak.world.save.repository;

import com.stonebreak.world.save.io.AtomicFiles;
import com.stonebreak.world.save.io.ChunkStorage;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.serialization.JsonPlayerSerializer;
import com.stonebreak.world.save.serialization.JsonWorldSerializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Optional;

/**
 * Thin file I/O abstraction used by {@link com.stonebreak.world.save.SaveService}.
 * All methods are synchronous; callers decide threading policy.
 */
public class FileSaveRepository {

    private final Path worldRoot;
    private final Path metadataFile;
    /** world.json: written once at creation (seed, generator, format) and never rewritten. */
    private final Path creationRecordFile;
    private final Path playerFile;
    private final ChunkStorage chunkStorage;
    private final JsonWorldSerializer worldSerializer = new JsonWorldSerializer();
    private final JsonPlayerSerializer playerSerializer = new JsonPlayerSerializer();

    public FileSaveRepository(String worldPath) {
        this.worldRoot = Paths.get(worldPath);
        this.metadataFile = worldRoot.resolve("metadata.json");
        this.creationRecordFile = worldRoot.resolve("world.json");
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
        AtomicFiles.write(metadataFile, worldSerializer.serialize(world));
    }

    /**
     * Reads {@code metadata.json}. Empty only when the file does not exist; a file that cannot be
     * read throws {@link IOException}, and one that reads but does not parse throws
     * {@link UnreadableSaveFileException} — never empty, which would make the world look new.
     */
    public Optional<WorldData> loadWorld() throws IOException {
        return readWorldFile(metadataFile);
    }

    /**
     * Reads {@code world.json}, the record written when the world was created. It carries what
     * the terrain depends on (seed, generator, format version), so a lost {@code metadata.json}
     * can be rebuilt from it without changing the world. Same contract as {@link #loadWorld()}.
     */
    public Optional<WorldData> loadWorldCreationRecord() throws IOException {
        return readWorldFile(creationRecordFile);
    }

    private Optional<WorldData> readWorldFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        byte[] payload = Files.readAllBytes(file);
        try {
            return Optional.of(worldSerializer.deserialize(payload));
        } catch (RuntimeException e) {
            throw new UnreadableSaveFileException(file, e);
        }
    }

    /**
     * Moves an unreadable {@code metadata.json} aside, exactly like {@link #setPlayerFileAside()}
     * does for {@code player.json}. Returns where it went.
     */
    public Path setWorldFileAside() throws IOException {
        return setAside(metadataFile);
    }

    /**
     * Whether this world has anything saved besides its metadata: a chunk, a player record, or
     * a metadata file set aside earlier. A missing {@code metadata.json} only means "new world"
     * when this is false — otherwise booting it as new would overwrite the real world.
     */
    public boolean hasSavedWorldContent() throws IOException {
        if (Files.exists(playerFile) || Files.isDirectory(playersDir())) {
            return true;
        }
        try (var entries = Files.list(worldRoot)) {
            if (entries.anyMatch(p -> p.getFileName().toString().startsWith("metadata.json.corrupt"))) {
                return true;
            }
        }
        Path chunks = worldRoot.resolve("chunks");
        if (!Files.isDirectory(chunks)) {
            return false;
        }
        try (var files = Files.walk(chunks, 2)) {
            return files.anyMatch(Files::isRegularFile);
        }
    }

    public void savePlayer(PlayerData player) throws IOException {
        ensureWorldDirectory();
        AtomicFiles.write(playerFile, playerSerializer.serialize(player));
    }

    public Optional<PlayerData> loadPlayer() throws IOException {
        if (!Files.exists(playerFile)) {
            return Optional.empty();
        }
        byte[] payload = Files.readAllBytes(playerFile);
        return Optional.of(playerSerializer.deserialize(payload));
    }

    /**
     * Moves an unreadable {@code player.json} to {@code player.json.corrupt} (or
     * {@code player.json.corrupt.N} when earlier copies exist) so the next save writes a fresh
     * file instead of overwriting the player's data (issue #358). Returns where it went.
     */
    public Path setPlayerFileAside() throws IOException {
        return setAside(playerFile);
    }

    /** Moves {@code file} to {@code <name>.corrupt}, or {@code <name>.corrupt.N} past earlier copies. */
    private static Path setAside(Path file) throws IOException {
        Path aside = file.resolveSibling(file.getFileName() + ".corrupt");
        for (int n = 1; Files.exists(aside); n++) {
            aside = file.resolveSibling(file.getFileName() + ".corrupt." + n);
        }
        Files.move(file, aside);
        return aside;
    }

    // ── Per-username player blobs (remote multiplayer players) ──────────────────
    // The host persists every connected player's serialized PlayerData under
    // players/<stem>.json, the stem a reversible, case-safe encoding of the name
    // (PlayerSaveNames, issue #335). Saves made before it used a lossy sanitized name; the first
    // load of such a player renames its file to the new name. The in-process local player keeps
    // using player.json.

    public void saveNamedPlayerBytes(String username, byte[] json) throws IOException {
        ensureWorldDirectory();
        AtomicFiles.write(namedPlayerFile(username), json);
    }

    public Optional<byte[]> loadNamedPlayerBytes(String username) throws IOException {
        Optional<Path> found = existingNamedPlayerFile(username);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Path file = found.get();
        Path current = namedPlayerFile(username);
        if (!file.equals(current)) {
            // Migrate, so no old-name copy is left to come back after a later set-aside.
            Files.move(file, current);
            file = current;
        }
        return Optional.of(Files.readAllBytes(file));
    }

    /**
     * Moves an unreadable {@code players/<username>.json} aside, exactly like
     * {@link #setPlayerFileAside()} does for {@code player.json} (issue #358, applied to
     * remote players by #334). Returns where it went.
     */
    public Path setNamedPlayerFileAside(String username) throws IOException {
        return setAside(existingNamedPlayerFile(username).orElse(namedPlayerFile(username)));
    }

    private Path playersDir() {
        return worldRoot.resolve("players");
    }

    /** Where {@code username}'s save is written. */
    private Path namedPlayerFile(String username) {
        return playersDir().resolve(PlayerSaveNames.stem(username) + ".json");
    }

    /**
     * The file {@code username}'s save is read from: the current one, else one written before
     * #335 under the old sanitized name. The old name is matched against the directory listing
     * with its exact case, so on a case-insensitive file system {@code bob} never reads
     * {@code Bob.json}.
     */
    private Optional<Path> existingNamedPlayerFile(String username) throws IOException {
        Path current = namedPlayerFile(username);
        if (Files.exists(current)) {
            return Optional.of(current);
        }
        Path dir = playersDir();
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String legacy = PlayerSaveNames.legacyStem(username) + ".json";
        try (var entries = Files.list(dir)) {
            return entries.filter(p -> p.getFileName().toString().equals(legacy))
                    .filter(Files::isRegularFile)
                    .findFirst();
        }
    }

    public void saveChunks(Collection<ChunkData> chunks) throws IOException {
        ensureWorldDirectory();
        chunkStorage.saveChunks(chunks);
    }

    /** Single-chunk save — the parallel save pool fans batches out through this. */
    public void saveChunk(ChunkData chunk) throws IOException {
        ensureWorldDirectory();
        chunkStorage.saveChunk(chunk);
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
}
