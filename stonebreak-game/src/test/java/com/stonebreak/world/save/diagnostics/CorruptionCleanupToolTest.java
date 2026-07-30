package com.stonebreak.world.save.diagnostics;

import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.repository.FileSaveRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

@Tag("regression")
class CorruptionCleanupToolTest {

    @TempDir
    Path tempDir;

    @Test
    void cleanupDeletesOnlyUndecodableSbcFiles() throws IOException {
        String worldPath = tempDir.resolve("world").toString();

        Path chunksRoot = tempDir.resolve("world/chunks");
        Files.createDirectories(chunksRoot);

        FileSaveRepository repo = new FileSaveRepository(worldPath);
        repo.saveChunk(smallChunk(0, 0));
        repo.saveChunk(smallChunk(1, 1));
        repo.close();

        Path garbageFile = chunksRoot.resolve("r.0.0/c.9.9.sbc");
        Files.write(garbageFile, "garbage".getBytes());

        Path notesFile = chunksRoot.resolve("r.0.0/notes.txt");
        Files.write(notesFile, "some notes".getBytes());

        Path c00 = chunksRoot.resolve("r.0.0/c.0.0.sbc");
        Path c11 = chunksRoot.resolve("r.0.0/c.1.1.sbc");

        assertTrue(Files.exists(c00));
        assertTrue(Files.exists(c11));
        assertTrue(Files.exists(garbageFile));
        assertTrue(Files.exists(notesFile));

        CorruptionCleanupTool tool = new CorruptionCleanupTool();
        tool.scanAndCleanWorld(worldPath);

        assertTrue(Files.exists(c00), "Valid c.0.0.sbc should remain");
        assertTrue(Files.exists(c11), "Valid c.1.1.sbc should remain");
        assertFalse(Files.exists(garbageFile), "Garbage .sbc should be deleted");
        assertTrue(Files.exists(notesFile), "notes.txt should be untouched");
    }

    @Test
    void missingChunksDirectoryReturnsQuietly() throws IOException {
        String worldPath = tempDir.resolve("emptyWorld").toString();
        Files.createDirectories(Path.of(worldPath));

        CorruptionCleanupTool tool = new CorruptionCleanupTool();
        assertDoesNotThrow(() -> tool.scanAndCleanWorld(worldPath));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static ChunkData smallChunk(int chunkX, int chunkZ) {
        CcoPalettedChunkStorage blocks =
            CcoPalettedChunkStorage.createEmpty(16, 256, 16, BlockType.AIR);
        blocks.set(0, 0, 0, BlockType.STONE);
        blocks.set(5, 10, 5, BlockType.DIRT);

        return ChunkData.builder()
            .chunkX(chunkX)
            .chunkZ(chunkZ)
            .blocks(blocks)
            .lastModified(LocalDateTime.of(2024, 3, 15, 10, 30, 45))
            .featuresPopulated(true)
            .hasEntitiesGenerated(false)
            .waterMetadata(new HashMap<>())
            .entities(new ArrayList<>())
            .snowLayers(new HashMap<>())
            .build();
    }
}