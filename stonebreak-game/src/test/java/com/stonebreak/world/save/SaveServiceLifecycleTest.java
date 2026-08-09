package com.stonebreak.world.save;

import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.repository.FileSaveRepository;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@Tag("regression")
@Timeout(30)
class SaveServiceLifecycleTest {

    @TempDir
    Path tempDir;

    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2024, 3, 15, 10, 30, 45);

    @Test
    void constructorCreatesWorldDirectoryAndEchoesPath() throws IOException {
        String worldPath = tempDir.resolve("w1").toString();
        try (SaveService svc = new SaveService(worldPath)) {
            assertTrue(Files.isDirectory(Path.of(worldPath)));
            assertEquals(worldPath, svc.getWorldPath());
            assertNull(svc.getWorldData());
        }
    }

    @Test
    void saveAllBeforeInitializeFailsWithIllegalState() throws IOException {
        String worldPath = tempDir.resolve("w2").toString();
        try (SaveService svc = new SaveService(worldPath)) {
            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> svc.saveAll().get(10, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(IllegalStateException.class, ex.getCause());
            assertEquals("Save service not initialized", ((IllegalStateException) ex.getCause()).getMessage());
        }
    }

    @Test
    void saveDirtyChunksWithoutWorldCompletesWithNull() throws Exception {
        String worldPath = tempDir.resolve("w3").toString();
        try (SaveService svc = new SaveService(worldPath)) {
            assertNull(svc.saveDirtyChunks().get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void loadWorldReportsMissingMetadataThenSucceedsAfterSave() throws Exception {
        String worldPath = tempDir.resolve("w4").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        WorldData metadata = WorldData.builder()
            .seed(42L)
            .worldName("TestWorld")
            .createdTime(FIXED_TIME)
            .lastPlayed(FIXED_TIME)
            .totalPlayTimeMillis(1000L)
            .build();

        try (SaveService svc = new SaveService(worldPath)) {
            // Phase 1: empty directory — metadata not found
            SaveService.LoadResult result1 = svc.loadWorld().get(10, TimeUnit.SECONDS);
            assertFalse(result1.isSuccess());
            assertEquals("World metadata not found", result1.getError());

            // Phase 2: plant metadata via a SEPARATE FileSaveRepository
            repo.saveWorld(metadata);

            SaveService.LoadResult result2 = svc.loadWorld().get(10, TimeUnit.SECONDS);
            assertTrue(result2.isSuccess());
            assertNotNull(result2.getWorldData());
            assertEquals(42L, result2.getWorldData().getSeed());
            assertEquals("TestWorld", result2.getWorldData().getWorldName());
            assertNull(result2.getPlayerData());
        } finally {
            repo.close();
        }
    }

    @Test
    void chunkExistsReflectsFilesystem() throws Exception {
        String worldPath = tempDir.resolve("w5").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);

        ChunkData chunk = ChunkData.builder()
            .chunkX(3)
            .chunkZ(4)
            .blocks(CcoPalettedChunkStorage.createEmpty(16, WorldConfiguration.WORLD_HEIGHT, 16, BlockType.AIR))
            .lastModified(FIXED_TIME)
            .build();

        try (SaveService svc = new SaveService(worldPath)) {
            // Before planting
            Boolean exists1 = svc.chunkExists(3, 4).get(10, TimeUnit.SECONDS);
            assertFalse(exists1);

            // Plant chunk via FileSaveRepository
            repo.saveChunk(chunk);

            Boolean exists2 = svc.chunkExists(3, 4).get(10, TimeUnit.SECONDS);
            assertTrue(exists2);
        } finally {
            repo.close();
        }
    }

    @Test
    void loadChunkForMissingChunkCompletesWithNull() throws Exception {
        String worldPath = tempDir.resolve("w6").toString();
        try (SaveService svc = new SaveService(worldPath)) {
            CompletableFuture<?> future = (CompletableFuture<?>) svc.loadChunk(0, 0);
            assertNull(future.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void namedPlayerRoundTrip() throws Exception {
        String worldPath = tempDir.resolve("w7").toString();
        byte[] aliceData = "Alice player data".getBytes();

        try (SaveService svc = new SaveService(worldPath)) {
            svc.saveNamedPlayer("Alice", aliceData).get(10, TimeUnit.SECONDS);

            byte[] loaded = svc.loadNamedPlayer("Alice").get(10, TimeUnit.SECONDS);
            assertArrayEquals(aliceData, loaded);

            byte[] nobody = svc.loadNamedPlayer("Nobody").get(10, TimeUnit.SECONDS);
            assertNull(nobody);
        }
    }

    @Test
    void flushSavesBlockingOnUninitializedServiceIsANoOp() throws IOException {
        String worldPath = tempDir.resolve("w8").toString();
        try (SaveService svc = new SaveService(worldPath)) {
            // Should return immediately without throwing
            svc.flushSavesBlocking("test");
        }
    }

    @Test
    void autoSaveStartStopIsIdempotent() throws IOException {
        String worldPath = tempDir.resolve("w9").toString();
        try (SaveService svc = new SaveService(worldPath)) {
            svc.startAutoSave();
            svc.startAutoSave();  // idempotent — no exception
            svc.stopAutoSave();
        }
    }

    @Test
    void concurrentSameChunkSavesLeaveOneValidPayload() throws Exception {
        String worldPath = tempDir.resolve("w10").toString();
        FileSaveRepository repo = new FileSaveRepository(worldPath);
        BlockType[] types = {BlockType.STONE, BlockType.DIRT, BlockType.GRASS, BlockType.SAND};

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            Future<?>[] futures = new Future[4];
            for (int i = 0; i < 4; i++) {
                final BlockType type = types[i];
                final int idx = i;

                futures[idx] = executor.submit(() -> {
                    try {
                        CcoPalettedChunkStorage blocks =
                            CcoPalettedChunkStorage.createEmpty(16, WorldConfiguration.WORLD_HEIGHT, 16, BlockType.AIR);
                        blocks.set(0, 0, 0, type);

                        ChunkData chunk = ChunkData.builder()
                            .chunkX(7)
                            .chunkZ(7)
                            .blocks(blocks)
                            .lastModified(FIXED_TIME)
                            .build();

                        repo.saveChunk(chunk);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            // Wait for all with timeouts
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        }

        // Load and verify exactly one valid chunk remains
        java.util.Optional<ChunkData> loaded = repo.loadChunk(7, 7);
        assertTrue(loaded.isPresent(), "Chunk (7,7) should still exist after concurrent saves");

        BlockType survivor = (BlockType) loaded.get().getBlockStorage().get(0, 0, 0);
        boolean matchesOneOfTheFour = false;
        for (BlockType t : types) {
            if (t == survivor || t.equals(survivor)) {
                matchesOneOfTheFour = true;
                break;
            }
        }
        assertTrue(matchesOneOfTheFour,
            "Surviving block (0,0,0) was " + survivor + " — expected one of STONE, DIRT, GRASS, SAND");

        // No .tmp residue anywhere under world root
        Path worldRoot = Path.of(worldPath);
        java.util.stream.Stream<Path> walk = Files.walk(worldRoot);
        try {
            boolean tmpFound = walk.anyMatch(p -> p.toString().endsWith(".tmp"));
            walk.close();
            assertFalse(tmpFound, "No .tmp residue should remain after concurrent saves");
        } finally {
            walk.close();
        }

        repo.close();
    }
}