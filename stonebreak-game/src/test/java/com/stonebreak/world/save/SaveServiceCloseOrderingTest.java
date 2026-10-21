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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Issue #366: closing the save service must drain an autosave that is already executing
 * (cancel(false) cannot stop it) BEFORE the final flush — otherwise the autosave's older
 * world/player snapshot lands after the flush and overwrites it — and an autosave that has
 * not yet passed its in-progress gate must bail once close() has started.
 */
@Tag("regression")
@Timeout(30)
class SaveServiceCloseOrderingTest {

    @TempDir
    Path tempDir;

    /**
     * Save pool that parks the FIRST submitted task on {@code latch} before running it —
     * standing in for an autosave write that is in flight (past its gate, mid-IO) while
     * close() runs. Later tasks (the final flush) execute normally on the delegate.
     */
    private static ExecutorService parkingSavePool(CountDownLatch latch, AtomicBoolean armed,
                                                   ExecutorService delegate) {
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                if (armed.compareAndSet(true, false)) {
                    delegate.execute(() -> {
                        try {
                            latch.await();
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

    @Test
    void closeJoinsInFlightAutosaveBeforeFinalFlush() throws Exception {
        String worldPath = tempDir.resolve("close-ordering").toString();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        ExecutorService delegate = Executors.newFixedThreadPool(4);
        ExecutorService savePool = parkingSavePool(latch, armed, delegate);
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());

        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            Game game = mock(Game.class);
            globals.when(Game::getInstance).thenReturn(game);
            globals.when(Game::getWorld).thenReturn(world);
            Player player = new Player(world);

            SaveService svc = new SaveService(worldPath, savePool, Executors.newFixedThreadPool(2));
            boolean closedHere = false;
            try {
                svc.initialize(WorldData.builder().seed(3L).worldName("close-ordering")
                    .totalPlayTimeMillis(1_000L).build(), player, world);

                // The autosave submits exactly one task (no dirty chunks): its metadata write,
                // which now sits parked on the latch — in flight, past the in-progress gate.
                svc.performAutoSave();
                svc.updateWorldData(d -> d.withCheatsEnabled(true));

                Thread closer = new Thread(svc::close, "closer");
                closer.start();
                Thread.sleep(500); // let close() reach its wait on the in-flight autosave
                latch.countDown();
                closer.join(20_000);
                assertFalse(closer.isAlive(), "close() did not return");

                CompletableFuture<Void> inFlight = svc.inFlightAutoSaveForTesting();
                assertNotNull(inFlight, "an autosave was in flight when close() ran");
                // Settle deterministically: in the broken ordering close() returned while this
                // write was still pending; waiting for it lets the stale overwrite land before
                // the assertion instead of racing it.
                inFlight.get(10, TimeUnit.SECONDS);
                closedHere = true;
            } finally {
                if (!closedHere) {
                    latch.countDown();
                    svc.close();
                }
                savePool.shutdown();
            }
        }

        try (SaveService reloaded = new SaveService(worldPath)) {
            SaveService.LoadResult result = reloaded.loadWorld().get(10, TimeUnit.SECONDS);
            assertTrue(result.isSuccess(), result.getError());
            WorldData loaded = result.getWorldData();
            assertTrue(loaded.isCheatsEnabled(),
                "the final flush must land after the in-flight autosave, not before it");
            assertTrue(loaded.getTotalPlayTimeMillis() >= 1_000L, "play time must not be reset");
        }
    }

    @Test
    void performAutoSaveAfterCloseIsANoOp() throws Exception {
        String worldPath = tempDir.resolve("closed-gate").toString();
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());

        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            Game game = mock(Game.class);
            globals.when(Game::getInstance).thenReturn(game);
            globals.when(Game::getWorld).thenReturn(world);
            Player player = new Player(world);

            try (SaveService svc = new SaveService(worldPath, Executors.newFixedThreadPool(2),
                    Executors.newFixedThreadPool(2))) {
                svc.initialize(WorldData.builder().seed(5L).worldName("closed-gate")
                    .totalPlayTimeMillis(2_000L).build(), player, world);
                svc.saveAll().get(10, TimeUnit.SECONDS);
                svc.close();

                Path metadata = Path.of(worldPath, "metadata.json");
                byte[] afterClose = Files.readAllBytes(metadata);
                // Widen the play-time delta a rogue post-close save would stamp.
                Thread.sleep(100);
                svc.performAutoSave();
                CompletableFuture<Void> inFlight = svc.inFlightAutoSaveForTesting();
                if (inFlight != null) {
                    inFlight.get(10, TimeUnit.SECONDS);
                }
                assertArrayEquals(afterClose, Files.readAllBytes(metadata),
                    "a closed service must not write again");
            }
        }
    }
}
