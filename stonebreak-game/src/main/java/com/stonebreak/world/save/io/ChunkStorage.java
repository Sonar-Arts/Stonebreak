package com.stonebreak.world.save.io;

import com.stonebreak.world.save.model.ChunkData;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Optional;

/**
 * Filesystem backed chunk store.
 * Uses a simple per-chunk file layout:
 *
 * <pre>
 *   chunks/
 *     r.&lt;regionX&gt;.&lt;regionZ&gt;/
 *       c.&lt;chunkX&gt;.&lt;chunkZ&gt;.sbc
 * </pre>
 *
 * Region folders keep directory sizes manageable while maintaining atomic writes via
 * temporary files and {@link Files#move(Path, Path, java.nio.file.CopyOption...)}.
 */
public final class ChunkStorage {

    private static final String REGION_PREFIX = "r.";
    private static final String CHUNK_PREFIX = "c.";
    private static final String CHUNK_SUFFIX = ".sbc";
    private static final int REGION_SIZE = 32;

    private final Path rootDirectory;

    public ChunkStorage(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    public void saveChunks(Collection<ChunkData> chunks) throws IOException {
        for (ChunkData chunk : chunks) {
            saveChunk(chunk);
        }
    }

    public void saveChunk(ChunkData chunk) throws IOException {
        Path target = chunkFile(chunk.getChunkX(), chunk.getChunkZ());
        ensureParentExists(target);

        byte[] payload = ChunkCodec.encode(chunk);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        Files.write(temp, payload);
        try {
            Files.move(temp, target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Optional<ChunkData> loadChunk(int chunkX, int chunkZ) throws IOException {
        Path file = chunkFile(chunkX, chunkZ);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        byte[] payload = Files.readAllBytes(file);
        return Optional.of(ChunkCodec.decode(payload));
    }

    public boolean chunkExists(int chunkX, int chunkZ) {
        return Files.exists(chunkFile(chunkX, chunkZ));
    }

    public void deleteChunk(int chunkX, int chunkZ) throws IOException {
        Path file = chunkFile(chunkX, chunkZ);
        Files.deleteIfExists(file);
    }

    private Path chunkFile(int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, REGION_SIZE);
        int regionZ = Math.floorDiv(chunkZ, REGION_SIZE);
        Path regionDir = rootDirectory.resolve(REGION_PREFIX + regionX + "." + regionZ);
        return regionDir.resolve(CHUNK_PREFIX + chunkX + "." + chunkZ + CHUNK_SUFFIX);
    }

    private void ensureParentExists(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(rootDirectory)) {
            Files.createDirectories(rootDirectory);
        }
    }
}
