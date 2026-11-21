package com.stonebreak.ui.worldSelect.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the world size / saved-cube count walk and the byte formatting shown on the card.
 *
 * <p>The size has to be the whole directory, not just the chunk files: the fastlod cache can
 * outweigh the saved chunks, and a number that quietly omits it would tell a player their
 * 1 GB world is 80 MB.
 */
class WorldStatsServiceTest {

    @TempDir
    Path tempDir;

    private WorldStatsService service;

    @BeforeEach
    void setUp() {
        service = new WorldStatsService(tempDir::resolve);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private WorldStatsService.Stats await(String world) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        WorldStatsService.Stats stats = service.getStats(world);
        while (stats == null && System.nanoTime() < deadline) {
            Thread.sleep(10L);
            stats = service.getStats(world);
        }
        return stats;
    }

    @Test
    void firstCallIsPendingWhileTheWalkRuns() {
        assertNull(service.getStats("Anything"), "the walk must not block the caller");
        assertEquals(WorldStatsService.PENDING, new WorldStatsService(tempDir::resolve).getSizeBytes("Anything"));
    }

    @Test
    void sizeCountsEveryFileUnderTheWorldNotJustChunks() throws Exception {
        Path world = Files.createDirectories(tempDir.resolve("My World"));
        Files.write(world.resolve("world.json"), new byte[100]);
        Files.createDirectories(world.resolve("chunks/r.0.0"));
        Files.write(world.resolve("chunks/r.0.0/c.0.0.sbc"), new byte[1000]);
        Files.createDirectories(world.resolve("fastlod"));
        Files.write(world.resolve("fastlod/cache.sqlite"), new byte[4000]);

        WorldStatsService.Stats stats = await("My World");
        assertNotNull(stats, "stats never arrived");
        assertEquals(5100L, stats.bytes(), "the fastlod cache counts toward a world's size");
    }

    @Test
    void chunkCountCountsSavedCubesFromTheCubeStoreHeaders() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            com.openmason.engine.voxel.world.CendaVoxelWorld.isAvailable(), "needs the Cenda voxel library");
        Path world = Files.createDirectories(tempDir.resolve("My World"));
        Files.write(world.resolve("world.json"), new byte[1]);
        // Cubes in three regions (8^3 cubes each), negative coordinates included, plus a column record.
        try (var store = com.openmason.engine.voxel.world.CubeStore.open(world.resolve("cubes"))) {
            store.writeCube(0, 0, 0, new byte[]{1, 2, 3});
            store.writeCube(7, -1, 7, new byte[]{4});
            store.writeCube(-9, 3, 100, new byte[40_000]);
            store.writeCube(1, 0, 0, new byte[]{5});
            store.deleteCube(1, 0, 0);
            store.writeColumn(0, 0, new byte[]{9});
            store.flush();
        }
        Files.createDirectories(world.resolve("chunks/r.0.0"));
        Files.write(world.resolve("chunks/r.0.0/c.0.0.sbc"), new byte[1]); // old column files are not cubes

        WorldStatsService.Stats stats = await("My World");
        assertNotNull(stats);
        assertEquals(3, stats.chunkCount(), "saved cube records (a deleted one and column records excluded)");
    }

    @Test
    void missingWorldMeasuresAsEmptyRatherThanFailing() throws Exception {
        WorldStatsService.Stats stats = await("Never Existed");
        assertNotNull(stats, "a missing directory must still produce a result");
        assertEquals(0L, stats.bytes());
        assertEquals(0, stats.chunkCount());
    }

    @Test
    void invalidateAllForcesARewalk() throws Exception {
        Path world = Files.createDirectories(tempDir.resolve("My World"));
        Files.write(world.resolve("world.json"), new byte[100]);
        assertEquals(100L, await("My World").bytes());

        Files.write(world.resolve("player.json"), new byte[50]);
        assertEquals(100L, await("My World").bytes(), "cached until invalidated");

        service.invalidateAll();
        assertEquals(150L, await("My World").bytes(), "refresh must pick up new files");
    }

    @Test
    void blankWorldNameIsRejectedWithoutSchedulingAWalk() {
        assertNull(service.getStats(null));
        assertNull(service.getStats("  "));
    }

    // ===== FORMATTING =====

    @Test
    void formatsBytesBelowAKilobyteVerbatim() {
        assertEquals("0 B", WorldStatsService.formatSize(0L));
        assertEquals("512 B", WorldStatsService.formatSize(512L));
    }

    @Test
    void formatsUsingBinaryUnits() {
        assertEquals("1.0 KB", WorldStatsService.formatSize(1024L));
        assertEquals("1.0 MB", WorldStatsService.formatSize(1024L * 1024L));
        assertEquals("1.0 GB", WorldStatsService.formatSize(1024L * 1024L * 1024L));
    }

    @Test
    void dropsTheDecimalOnceTheNumberIsLarge() {
        assertEquals("142 MB", WorldStatsService.formatSize(142L * 1024L * 1024L));
        assertEquals("1.4 GB", WorldStatsService.formatSize((long) (1.4 * 1024 * 1024 * 1024)));
    }

    @Test
    void pendingFormatsAsNothingRatherThanMinusOne() {
        assertEquals("", WorldStatsService.formatSize(WorldStatsService.PENDING),
                "a pending size must not render as '-1 B'");
    }
}
