package com.stonebreak.world.save;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.save.model.WorldData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Follow-up to #366 (PR #416 review): play time is counted exactly once. The interval since
 * the last save is added to the in-memory {@link WorldData} when a save takes its snapshot,
 * so it must be marked counted in that same step — not when the write completes. Otherwise a
 * save that snapshots while another is still writing, or after a write failed, adds the same
 * interval again. Driven by an injected clock, never wall-clock sleeps.
 */
@Tag("regression")
@Timeout(30)
class SaveServicePlayTimeTest {

    private static final long START_PLAY_TIME = 1_000L;

    @TempDir
    Path tempDir;

    @Test
    void failedAutosaveFollowedBySuccessfulSaveCountsElapsedTimeOnce() throws Exception {
        String worldPath = tempDir.resolve("failed-autosave").toString();
        AtomicLong clock = new AtomicLong(0L);
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());

        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            Game game = mock(Game.class);
            globals.when(Game::getInstance).thenReturn(game);
            globals.when(Game::getWorld).thenReturn(world);
            Player player = new Player(world);

            try (SaveService svc = new SaveService(worldPath, Executors.newFixedThreadPool(2),
                    Executors.newFixedThreadPool(2), clock::get)) {
                svc.initialize(worldData("failed-autosave"), player, world);

                // A non-empty directory where metadata.json goes: the atomic replace fails.
                Path metadata = Path.of(worldPath, "metadata.json");
                Files.createDirectories(metadata);
                Files.writeString(metadata.resolve("blocker"), "x");

                clock.set(10_000L);
                svc.performAutoSave();
                CompletableFuture<Void> failed = svc.inFlightAutoSaveForTesting();
                assertNotNull(failed, "the autosave ran");
                assertThrows(ExecutionException.class, () -> failed.get(10, TimeUnit.SECONDS),
                    "the autosave's metadata write must fail");

                Files.delete(metadata.resolve("blocker"));
                Files.delete(metadata);

                clock.set(25_000L);
                svc.saveAll().get(10, TimeUnit.SECONDS);
            }
        }

        assertEquals(START_PLAY_TIME + 25_000L, loadPlayTime(worldPath),
            "play time must equal the elapsed time, not count the failed autosave's interval again");
    }

    @Test
    void overlappingSavesDoNotCountTheSameIntervalTwice() throws Exception {
        String worldPath = tempDir.resolve("overlapping-saves").toString();
        AtomicLong clock = new AtomicLong(0L);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        ExecutorService delegate = Executors.newFixedThreadPool(4);
        ExecutorService savePool = parkingFirstTask(release, armed, delegate);
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());

        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            Game game = mock(Game.class);
            globals.when(Game::getInstance).thenReturn(game);
            globals.when(Game::getWorld).thenReturn(world);
            Player player = new Player(world);

            SaveService svc = new SaveService(worldPath, savePool, Executors.newFixedThreadPool(2), clock::get);
            try {
                svc.initialize(worldData("overlapping-saves"), player, world);

                // The autosave snapshots at t=10 s; its write stays parked (in flight).
                clock.set(10_000L);
                svc.performAutoSave();
                CompletableFuture<Void> autosave = svc.inFlightAutoSaveForTesting();
                assertNotNull(autosave, "the autosave ran");

                // A full save snapshots at t=20 s, before the autosave's write has finished.
                clock.set(20_000L);
                svc.saveAll().get(10, TimeUnit.SECONDS);
                assertFalse(autosave.isDone(), "the autosave's write must still be in flight");
                assertEquals(START_PLAY_TIME + 20_000L, svc.getWorldData().getTotalPlayTimeMillis(),
                    "the second snapshot must not count 0..10 s again");

                release.countDown();
                autosave.get(10, TimeUnit.SECONDS);

                // A third save adds only the time since the second snapshot.
                clock.set(30_000L);
                svc.saveAll().get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                svc.close();
                savePool.shutdown();
            }
        }

        assertEquals(START_PLAY_TIME + 30_000L, loadPlayTime(worldPath),
            "play time must equal the elapsed time across overlapping saves");
    }

    private static WorldData worldData(String name) {
        return WorldData.builder().seed(7L).worldName(name).totalPlayTimeMillis(START_PLAY_TIME).build();
    }

    private static long loadPlayTime(String worldPath) throws Exception {
        try (SaveService reloaded = new SaveService(worldPath)) {
            SaveService.LoadResult result = reloaded.loadWorld().get(10, TimeUnit.SECONDS);
            assertTrue(result.isSuccess(), result.getError());
            return result.getWorldData().getTotalPlayTimeMillis();
        }
    }

    /** Save pool that parks the FIRST submitted task on {@code release}; later tasks run normally. */
    private static ExecutorService parkingFirstTask(CountDownLatch release, AtomicBoolean armed,
                                                    ExecutorService delegate) {
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

            @Override
            public void shutdown() {
                delegate.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return delegate.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return delegate.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return delegate.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return delegate.awaitTermination(timeout, unit);
            }
        };
    }
}
