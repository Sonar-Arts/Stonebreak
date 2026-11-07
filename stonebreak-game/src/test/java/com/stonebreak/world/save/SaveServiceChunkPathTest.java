package com.stonebreak.world.save;

import com.openmason.engine.voxel.cco.coordinates.CcoBounds;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.weather.WeatherMode;
import com.stonebreak.world.weather.WeatherState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The chunk side of {@link SaveService} — real {@link Chunk}s through the real repository, which
 * the other SaveService tests (all with an empty dirty-chunk list) never reach: round-trips, the
 * same-chunk ordering gate that loads and saves chain on, re-dirtying a chunk whose write failed,
 * entity-only dirtiness, and the world-time/weather stamp.
 */
@Tag("regression")
@Timeout(30)
class SaveServiceChunkPathTest {

    private static final long WAIT = 10;

    @TempDir
    Path tempDir;

    /** The world dimensions the game installs at startup (GameBootstrap): the chunk codec reads them (#385). */
    @BeforeAll
    static void installWorldDimensions() {
        CcoBounds.configure(WorldConfiguration.voxelConfig());
    }

    private static World worldWith(Chunk... dirty) {
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of(dirty));
        return world;
    }

    private static WorldData worldData(String name) {
        return WorldData.builder().seed(11L).worldName(name).build();
    }

    /** Initialized service on the default pools; the injected clock keeps the Game singleton out. */
    private static SaveService open(String worldPath, World world) {
        return init(new SaveService(worldPath), world);
    }

    private static SaveService init(SaveService svc, World world) {
        svc.setWorldTimeSource(new TimeOfDay(0));
        svc.initialize(worldData("chunk-path"), null, world);
        return svc;
    }

    private static Chunk chunkWith(int cx, int cz, BlockType type) {
        Chunk chunk = new Chunk(cx, cz);
        chunk.setBlock(1, 5, 2, type);
        assertTrue(chunk.getCcoDirtyTracker().isDataDirty(), "precondition: an edit marks the chunk data-dirty");
        return chunk;
    }

    private static BlockType savedBlock(String worldPath, int cx, int cz) throws Exception {
        try (SaveService reloaded = open(worldPath, worldWith())) {
            Chunk loaded = reloaded.loadChunk(cx, cz).get(WAIT, TimeUnit.SECONDS);
            assertNotNull(loaded, "chunk (" + cx + "," + cz + ") was never written");
            return loaded.getBlock(1, 5, 2);
        }
    }

    /**
     * Save pool that parks the FIRST task on {@code release} — a chunk write in flight — and runs
     * every later task normally, so a test can submit more work against a known in-flight write.
     */
    private static ExecutorService parkFirstTask(CountDownLatch release, ExecutorService delegate) {
        AtomicBoolean armed = new AtomicBoolean(true);
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                if (armed.compareAndSet(true, false)) {
                    delegate.execute(() -> {
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        command.run();
                    });
                } else {
                    delegate.execute(command);
                }
            }

            @Override public void shutdown() { delegate.shutdown(); }
            @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
            @Override public boolean isShutdown() { return delegate.isShutdown(); }
            @Override public boolean isTerminated() { return delegate.isTerminated(); }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return delegate.awaitTermination(timeout, unit);
            }
        };
    }

    @Test
    void dirtyChunkRoundTripsThroughSaveAllAndIsCleanAfterward() throws Exception {
        String worldPath = tempDir.resolve("round-trip").toString();
        Chunk chunk = chunkWith(2, -3, BlockType.STONE);

        try (SaveService svc = open(worldPath, worldWith(chunk))) {
            svc.saveAll().get(WAIT, TimeUnit.SECONDS);
            assertFalse(chunk.getCcoDirtyTracker().isDataDirty(), "a saved chunk is no longer data-dirty");
        }

        assertEquals(BlockType.STONE, savedBlock(worldPath, 2, -3));
    }

    @Test
    void saveChunkSkipsAChunkThatIsNotDataDirty() throws Exception {
        String worldPath = tempDir.resolve("clean-skip").toString();
        Chunk chunk = chunkWith(9, 9, BlockType.DIRT);
        chunk.getCcoDirtyTracker().clearDataDirty();

        try (SaveService svc = open(worldPath, worldWith())) {
            svc.saveChunk(chunk).get(WAIT, TimeUnit.SECONDS);
            assertFalse(svc.chunkExists(9, 9).get(WAIT, TimeUnit.SECONDS), "a clean chunk is not written");
        }
    }

    @Test
    void aFailedChunkWriteMarksTheChunkDirtyAgainSoTheNextSaveRetriesIt() throws Exception {
        String worldPath = tempDir.resolve("redirty").toString();
        Chunk chunk = chunkWith(1, 1, BlockType.STONE);

        // A regular file where the chunk's region directory belongs: the write cannot land.
        Path regionBlocker = Path.of(worldPath, "chunks", "r.0.0");
        Files.createDirectories(regionBlocker.getParent());
        Files.writeString(regionBlocker, "not a directory");

        try (SaveService svc = open(worldPath, worldWith(chunk))) {
            assertThrows(ExecutionException.class, () -> svc.saveDirtyChunks().get(WAIT, TimeUnit.SECONDS));
            assertTrue(chunk.getCcoDirtyTracker().isDataDirty(),
                "a chunk whose write failed must be dirty again, or its edits are silently lost");

            Files.delete(regionBlocker);
            svc.saveDirtyChunks().get(WAIT, TimeUnit.SECONDS);
            assertFalse(chunk.getCcoDirtyTracker().isDataDirty());
        }

        assertEquals(BlockType.STONE, savedBlock(worldPath, 1, 1));
    }

    @Test
    void loadAndExistsProbeWaitForAnInFlightSaveOfTheSameChunk() throws Exception {
        // The unload-save -> immediate-reload race: a load issued while the chunk's save is still
        // in flight must read that save, not the (absent) file from before it.
        String worldPath = tempDir.resolve("load-gate").toString();
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService delegate = Executors.newFixedThreadPool(4);
        Chunk chunk = chunkWith(4, 4, BlockType.STONE);

        try (SaveService svc = init(new SaveService(worldPath, parkFirstTask(release, delegate),
                Executors.newFixedThreadPool(4)), worldWith());
             // Closes first (reverse order): a failed assertion must unpark the write before
             // svc.close() waits for the save pool to drain.
             AutoCloseable unpark = release::countDown) {
            CompletableFuture<Void> save = svc.saveChunk(chunk);     // parked mid-flight
            CompletableFuture<Boolean> exists = svc.chunkExists(4, 4);
            CompletableFuture<Chunk> load = svc.loadChunk(4, 4);

            release.countDown();
            save.get(WAIT, TimeUnit.SECONDS);
            assertTrue(exists.get(WAIT, TimeUnit.SECONDS), "the existence probe ran before the save landed");
            Chunk loaded = load.get(WAIT, TimeUnit.SECONDS);
            assertNotNull(loaded, "the load ran before the save landed");
            assertEquals(BlockType.STONE, loaded.getBlock(1, 5, 2));
        }
    }

    @Test
    void savesOfTheSameChunkLandInSubmissionOrder() throws Exception {
        // The save pool has several threads: without the per-chunk chain the newer save would
        // land first and the parked older one would overwrite it.
        String worldPath = tempDir.resolve("save-order").toString();
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService delegate = Executors.newFixedThreadPool(4);
        Chunk chunk = chunkWith(6, 6, BlockType.STONE);

        try (SaveService svc = init(new SaveService(worldPath, parkFirstTask(release, delegate),
                Executors.newFixedThreadPool(4)), worldWith());
             // Closes first (reverse order): a failed assertion must unpark the write before
             // svc.close() waits for the save pool to drain.
             AutoCloseable unpark = release::countDown) {
            CompletableFuture<Void> older = svc.saveChunk(chunk);    // snapshot: STONE, parked
            chunk.setBlock(1, 5, 2, BlockType.DIRT);
            CompletableFuture<Void> newer = svc.saveChunk(chunk);    // snapshot: DIRT

            // Unchained, the newer write would land right away (and the older one over it later).
            assertThrows(TimeoutException.class, () -> newer.get(300, TimeUnit.MILLISECONDS),
                "the newer save of the chunk did not wait for the older one in flight");
            release.countDown();
            CompletableFuture.allOf(older, newer).get(WAIT, TimeUnit.SECONDS);
        }

        assertEquals(BlockType.DIRT, savedBlock(worldPath, 6, 6), "the older snapshot overwrote the newer one");
    }

    @Test
    void chunksHoldingLiveEntitiesAreMarkedDirtyBeforeDirtyChunksAreCollected() throws Exception {
        // Entities live in the EntityManager, not block data: a chunk whose only change is a mob
        // walking in it is block-clean, and would be skipped if the marking came after collection.
        World world = worldWith();
        EntityManager entities = mock(EntityManager.class);
        when(world.getEntityManager()).thenReturn(entities);

        try (SaveService svc = open(tempDir.resolve("entities").toString(), world)) {
            svc.saveDirtyChunks().get(WAIT, TimeUnit.SECONDS);

            // Checked before close(): its flush collects again, which would mask a wrong order.
            InOrder order = inOrder(entities, world);
            order.verify(entities).markChunksWithLiveEntitiesDirty();
            order.verify(world).getDirtyChunks();
        }
    }

    @Test
    void saveStampsTheInjectedWorldTimeAndWeather() throws Exception {
        String worldPath = tempDir.resolve("stamps").toString();
        try (SaveService svc = new SaveService(worldPath)) {
            svc.setWorldTimeSource(new TimeOfDay(30_000L));
            svc.setWeatherSource(new WeatherState(120.0, WeatherMode.STORM));
            svc.initialize(worldData("stamps"), null, worldWith());
            svc.saveAll().get(WAIT, TimeUnit.SECONDS);
        }

        try (SaveService reloaded = new SaveService(worldPath)) {
            SaveService.LoadResult result = reloaded.loadWorld().get(WAIT, TimeUnit.SECONDS);
            assertTrue(result.isSuccess(), result.getError());
            assertEquals(30_000L, result.getWorldData().getWorldTimeTicks());
            assertEquals(120L, result.getWorldData().getWeatherSeconds());
            assertEquals(WeatherMode.STORM, result.getWorldData().getWeatherMode());
        }
    }

    @Test
    void anAutosaveWhosePlayerSnapshotThrowsLosesNothingAndTheNextAutosaveRuns() throws Exception {
        String worldPath = tempDir.resolve("snapshot-throws").toString();
        Chunk chunk = chunkWith(3, 3, BlockType.STONE);
        AtomicInteger snapshots = new AtomicInteger();
        SaveService.PlayerSource flaky = worldName -> {
            if (snapshots.getAndIncrement() == 0) {
                throw new IllegalStateException("player mid-teleport");
            }
            return null; // no player record this round; everything else still saves
        };

        try (SaveService svc = open(worldPath, worldWith(chunk))) {
            svc.bindPlayerSource(flaky, Runnable::run);

            svc.performAutoSave();
            CompletableFuture<Void> failed = svc.inFlightAutoSaveForTesting();
            assertTrue(failed.isCompletedExceptionally(), "the failed autosave reports its failure");
            assertTrue(chunk.getCcoDirtyTracker().isDataDirty(), "the skipped chunk stays dirty for next time");

            // The in-progress gate was released: a stuck gate would make this a silent no-op.
            svc.performAutoSave();
            CompletableFuture<Void> next = svc.inFlightAutoSaveForTesting();
            assertFalse(next == failed, "the second autosave never started");
            next.get(WAIT, TimeUnit.SECONDS);
            assertTrue(svc.chunkExists(3, 3).get(WAIT, TimeUnit.SECONDS));
        }
    }

    @Test
    void autosaveWithAPlayerSourceWritesThePlayerAndTheChunks() throws Exception {
        String worldPath = tempDir.resolve("autosave-player").toString();
        Chunk chunk = chunkWith(5, -5, BlockType.STONE);
        PlayerData record = PlayerData.builder().worldName("chunk-path").build();

        try (SaveService svc = open(worldPath, worldWith(chunk))) {
            svc.bindPlayerSource(worldName -> record, Runnable::run);
            svc.performAutoSave();
            svc.inFlightAutoSaveForTesting().get(WAIT, TimeUnit.SECONDS);
            assertTrue(svc.chunkExists(5, -5).get(WAIT, TimeUnit.SECONDS));
        }

        assertTrue(Files.exists(Path.of(worldPath, "player.json")), "the player record was written");
        assertEquals(BlockType.STONE, savedBlock(worldPath, 5, -5));
    }

    @Test
    void unreadableWorldMetadataIsAFailedLoadNotANewWorld() throws Exception {
        String worldPath = tempDir.resolve("bad-metadata").toString();
        Files.createDirectories(Path.of(worldPath));
        Files.writeString(Path.of(worldPath, "metadata.json"), "{\"seed\": ");

        try (SaveService svc = new SaveService(worldPath)) {
            SaveService.LoadResult result = svc.loadWorld().get(WAIT, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertNotNull(result.getError());
            assertFalse(result.isWorldMissing(), "damaged metadata must not read as a missing world");
        }
    }
}
