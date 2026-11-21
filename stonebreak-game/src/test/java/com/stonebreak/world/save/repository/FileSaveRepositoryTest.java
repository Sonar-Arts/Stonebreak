package com.stonebreak.world.save.repository;

import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("regression")
class FileSaveRepositoryTest {

    @TempDir
    Path tempDir;

    /** Direct repository writes of player.json from four threads (atomic + durable replace): one complete
     *  record survives and no temp file is left (Windows rename contention used to throw here). */
    @Test
    void concurrentPlayerSavesLeaveOneValidRecord() throws Exception {
        String worldPath = tempDir.resolve("w10").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);
        int[] slots = {0, 1, 2, 3};

        try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            java.util.concurrent.Future<?>[] futures = new java.util.concurrent.Future[4];
            for (int i = 0; i < 4; i++) {
                final int slot = slots[i];
                futures[i] = executor.submit(() -> {
                    try {
                        repo.savePlayer(PlayerData.builder().worldName("w").selectedHotbarSlot(slot).build());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        Optional<PlayerData> loaded = repo.loadPlayer();
        assertTrue(loaded.isPresent());
        int survivor = loaded.get().getSelectedHotbarSlot();
        assertTrue(survivor >= 0 && survivor <= 3, "survivor " + survivor);
        try (Stream<Path> walk = Files.walk(Path.of(worldPath))) {
            assertFalse(walk.anyMatch(p -> p.toString().endsWith(".tmp")), "No .tmp residue");
        }
        repo.close();
    }

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
    void setPlayerFileAsideNeverOverwritesEarlierCopies() throws IOException {
        String worldPath = tempDir.resolve("world").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);
        Path playerFile = Path.of(worldPath, "player.json");

        repo.savePlayer(PlayerData.createDefault("w"));
        assertEquals(Path.of(worldPath, "player.json.corrupt"), repo.setPlayerFileAside());
        assertFalse(Files.exists(playerFile));

        repo.savePlayer(PlayerData.createDefault("w"));
        assertEquals(Path.of(worldPath, "player.json.corrupt.1"), repo.setPlayerFileAside());
        assertTrue(Files.exists(Path.of(worldPath, "player.json.corrupt")));

        repo.close();
    }

    @Test
    void namedPlayerSanitization() throws IOException {
        String worldPath = tempDir.resolve("world").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        byte[] bytes = "hello".getBytes();
        repo.saveNamedPlayerBytes("Bob/../x", bytes);

        // Encoded, never a path (issue #335 replaced the lossy sanitizing; see PlayerSaveNamesTest).
        Path worldRoot = Path.of(worldPath);
        assertTrue(Files.exists(worldRoot.resolve("players/@_bob_.002f_.002e_.002e_.002fx.json")));

        Optional<byte[]> loaded = repo.loadNamedPlayerBytes("Bob/../x");
        assertTrue(loaded.isPresent());
        assertArrayEquals(bytes, loaded.get());

        repo.saveNamedPlayerBytes(null, bytes);
        assertTrue(Files.exists(worldRoot.resolve("players/@.json")));

        repo.saveNamedPlayerBytes("   ", bytes);
        assertTrue(Files.exists(worldRoot.resolve("players/@_.0020_.0020_.0020.json")));

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
        repo.saveNamedPlayerBytes("TestUser", "data".getBytes());

        List<Path> tmpFiles;
        try (Stream<Path> walk = Files.walk(Path.of(worldPath))) {
            tmpFiles = walk.filter(p -> p.toString().endsWith(".tmp")).toList();
        }
        assertTrue(tmpFiles.isEmpty(), "No .tmp files should remain: " + tmpFiles);

        repo.close();
    }
}
