package com.stonebreak.world.save.repository;

import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("regression")
class FileSaveRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureWorldDirectoryCreatesAndIsIdempotent() throws IOException {
        String worldPath = tempDir.resolve("world").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        assertFalse(Files.exists(Path.of(worldPath)));

        repo.ensureWorldDirectory();
        assertTrue(Files.exists(Path.of(worldPath)));

        repo.ensureWorldDirectory();
        assertTrue(Files.exists(Path.of(worldPath)));

        repo.close();
    }

    @Test
    void worldExistsFlipsAfterSaveWorld() throws IOException {
        String worldPath = tempDir.resolve("world").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        assertFalse(repo.worldExists());
        assertTrue(repo.loadWorld().isEmpty());

        WorldData wd = WorldData.builder()
            .seed(42L)
            .worldName("TestWorld")
            .worldTimeTicks(12345L)
            .cheatsEnabled(true)
            .build();
        repo.saveWorld(wd);

        assertTrue(repo.worldExists());

        Optional<WorldData> loaded = repo.loadWorld();
        assertTrue(loaded.isPresent());
        assertEquals(42L, loaded.get().getSeed());
        assertEquals("TestWorld", loaded.get().getWorldName());
        assertEquals(12345L, loaded.get().getWorldTimeTicks());
        assertTrue(loaded.get().isCheatsEnabled());

        repo.close();
    }

    @Test
    void saveWorldOverwriteLastWins() throws IOException {
        String worldPath = tempDir.resolve("world").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        WorldData first = WorldData.builder()
            .seed(1L)
            .worldName("FirstWorld")
            .worldTimeTicks(100L)
            .cheatsEnabled(false)
            .build();
        repo.saveWorld(first);

        WorldData second = WorldData.builder()
            .seed(999L)
            .worldName("SecondWorld")
            .worldTimeTicks(200L)
            .cheatsEnabled(true)
            .build();
        repo.saveWorld(second);

        Optional<WorldData> loaded = repo.loadWorld();
        assertTrue(loaded.isPresent());
        assertEquals(999L, loaded.get().getSeed());
        assertEquals("SecondWorld", loaded.get().getWorldName());
        assertEquals(200L, loaded.get().getWorldTimeTicks());
        assertTrue(loaded.get().isCheatsEnabled());

        repo.close();
    }

    @Test
    void playerRoundTrip() throws IOException {
        String worldPath = tempDir.resolve("world").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        assertTrue(repo.loadPlayer().isEmpty());

        PlayerData player = PlayerData.createDefault("w");
        repo.savePlayer(player);

        Optional<PlayerData> loaded = repo.loadPlayer();
        assertTrue(loaded.isPresent());
        assertEquals(20.0f, loaded.get().getHealth(), 0.0f);
        assertEquals("w", loaded.get().getWorldName());

        repo.close();
    }

    @Test
    void chunkPathsFollowRegionFormula() throws IOException {
        String worldPath = tempDir.resolve("world").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        ChunkData c00 = smallChunk(0, 0);
        ChunkData c3131 = smallChunk(31, 31);
        ChunkData c320 = smallChunk(32, 0);
        ChunkData cm1m1 = smallChunk(-1, -1);
        ChunkData cm33m33 = smallChunk(-33, -33);

        repo.saveChunk(c00);
        repo.saveChunk(c3131);
        repo.saveChunk(c320);
        repo.saveChunk(cm1m1);
        repo.saveChunk(cm33m33);

        Path worldRoot = Path.of(worldPath);

        assertTrue(Files.exists(worldRoot.resolve("chunks/r.0.0/c.0.0.sbc")));
        assertTrue(Files.exists(worldRoot.resolve("chunks/r.0.0/c.31.31.sbc")));
        assertTrue(Files.exists(worldRoot.resolve("chunks/r.1.0/c.32.0.sbc")));
        assertTrue(Files.exists(worldRoot.resolve("chunks/r.-1.-1/c.-1.-1.sbc")));
        assertTrue(Files.exists(worldRoot.resolve("chunks/r.-2.-2/c.-33.-33.sbc")));

        assertTrue(repo.chunkExists(0, 0));
        assertTrue(repo.chunkExists(31, 31));
        assertTrue(repo.chunkExists(32, 0));
        assertTrue(repo.chunkExists(-1, -1));
        assertTrue(repo.chunkExists(-33, -33));

        Optional<ChunkData> loaded = repo.loadChunk(0, 0);
        assertTrue(loaded.isPresent());
        assertEquals(0, loaded.get().getChunkX());
        assertEquals(0, loaded.get().getChunkZ());
        assertEquals(BlockType.STONE, loaded.get().getBlockStorage().get(0, 0, 0));

        repo.close();
    }

    @Test
    void deleteChunkRemovesFileAndExistsGoesFalse() throws IOException {
        String worldPath = tempDir.resolve("world").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        repo.saveChunk(smallChunk(7, 7));
        assertTrue(repo.chunkExists(7, 7));

        repo.deleteChunk(7, 7);
        assertFalse(repo.chunkExists(7, 7));
        assertTrue(repo.loadChunk(7, 7).isEmpty());

        repo.close();
    }

    @Test
    void saveChunksCollectionSavesAll() throws IOException {
        String worldPath = tempDir.resolve("world").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        ChunkData chunk1 = smallChunk(10, 20);
        ChunkData chunk2 = smallChunk(-5, -5);

        repo.saveChunks(List.of(chunk1, chunk2));

        assertTrue(repo.chunkExists(10, 20));
        assertTrue(repo.chunkExists(-5, -5));

        repo.close();
    }

    @Test
    void namedPlayerSanitization() throws IOException {
        String worldPath = tempDir.resolve("world").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        byte[] bytes = "hello".getBytes();
        repo.saveNamedPlayerBytes("Bob/../x", bytes);

        Path worldRoot = Path.of(worldPath);
        assertTrue(Files.exists(worldRoot.resolve("players/Bob_.._x.json")));

        Optional<byte[]> loaded = repo.loadNamedPlayerBytes("Bob/../x");
        assertTrue(loaded.isPresent());
        assertArrayEquals(bytes, loaded.get());

        repo.saveNamedPlayerBytes(null, bytes);
        assertTrue(Files.exists(worldRoot.resolve("players/_anonymous.json")));

        repo.saveNamedPlayerBytes("   ", bytes);
        assertTrue(Files.exists(worldRoot.resolve("players/_anonymous.json")));

        assertTrue(repo.loadNamedPlayerBytes("Nobody").isEmpty());

        repo.close();
    }

    @Test
    void noTmpFilesRemainAfterSaves() throws IOException {
        String worldPath = tempDir.resolve("world").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        WorldData wd = WorldData.builder()
            .seed(1L)
            .worldName("TmpTestWorld")
            .worldTimeTicks(100L)
            .build();
        repo.saveWorld(wd);
        repo.savePlayer(PlayerData.createDefault("tmp"));
        repo.saveChunk(smallChunk(0, 0));
        repo.saveNamedPlayerBytes("TestUser", "data".getBytes());

        List<Path> tmpFiles;
        try (Stream<Path> walk = Files.walk(Path.of(worldPath))) {
            tmpFiles = walk.filter(p -> p.toString().endsWith(".tmp")).toList();
        }
        assertTrue(tmpFiles.isEmpty(), "No .tmp files should remain: " + tmpFiles);

        repo.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static ChunkData smallChunk(int chunkX, int chunkZ) {
        CcoPalettedChunkStorage blocks =
            CcoPalettedChunkStorage.createEmpty(16, WorldConfiguration.WORLD_HEIGHT, 16, BlockType.AIR);
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