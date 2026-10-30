package com.stonebreak.world.save.repository;

import com.stonebreak.world.save.io.ChunkStorage;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.serialization.JsonPlayerSerializer;
import com.stonebreak.world.save.serialization.JsonWorldSerializer;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
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

    /**
     * Moves an unreadable {@code player.json} to {@code player.json.corrupt} (or
     * {@code player.json.corrupt.N} when earlier copies exist) so the next save writes a fresh
     * file instead of overwriting the player's data (issue #358). Returns where it went.
     */
    public Path setPlayerFileAside() throws IOException {
        Path aside = playerFile.resolveSibling(playerFile.getFileName() + ".corrupt");
        for (int n = 1; Files.exists(aside); n++) {
            aside = playerFile.resolveSibling(playerFile.getFileName() + ".corrupt." + n);
        }
        Files.move(playerFile, aside);
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
        writeAtomic(namedPlayerFile(username), json);
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
        Path file = existingNamedPlayerFile(username).orElse(namedPlayerFile(username));
        Path aside = file.resolveSibling(file.getFileName() + ".corrupt");
        for (int n = 1; Files.exists(aside); n++) {
            aside = file.resolveSibling(file.getFileName() + ".corrupt." + n);
        }
        Files.move(file, aside);
        return aside;
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

    private void writeAtomic(Path target, byte[] payload) throws IOException {
        // Unique temp name per write — a fixed sibling name races when two
        // threads write the same target concurrently (autosave + manual flush
        // both writing metadata.json, or two saves of one named player).
        Path temp = target.resolveSibling(target.getFileName() + "."
            + Long.toUnsignedString(System.nanoTime(), 36)
            + "-" + Thread.currentThread().threadId() + ".tmp");
        if (target.getParent() != null && !Files.exists(target.getParent())) {
            Files.createDirectories(target.getParent());
        }
        Files.write(temp, payload);
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    replace(temp, target);
                    return;
                } catch (AccessDeniedException e) {
                    // Windows refuses to rename over a file another thread has open — a load
                    // of the same player reading it. The read is brief, so wait it out.
                    if (attempt >= REPLACE_ATTEMPTS) {
                        throw e;
                    }
                    sleepBeforeRetry(attempt);
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** Tries for about a second in all before a write fails. */
    private static final int REPLACE_ATTEMPTS = 20;

    private static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void sleepBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(Math.min(attempt * 5L, 100L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted while retrying a save");
        }
    }
}
