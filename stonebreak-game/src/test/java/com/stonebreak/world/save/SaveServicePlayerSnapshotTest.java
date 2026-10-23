package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.save.model.WorldData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Issue #356: the autosave snapshots the player on the thread that owns it (the client main
 * thread) as deep copies, so the Save-IO threads never serialize a stack the game is editing,
 * and a close() on that owner thread does not wait out the snapshot.
 */
@Tag("regression")
@Timeout(30)
class SaveServicePlayerSnapshotTest {

    @TempDir
    Path tempDir;

    @Test
    void autosaveSnapshotsOnOwnerThreadAsCopies() throws Exception {
        String worldPath = tempDir.resolve("snapshot-copies").toString();
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());
        // Stands in for Game.runOnMainThread: tasks wait until the "main thread" (this test) drains them.
        BlockingQueue<Runnable> mainThreadTasks = new LinkedBlockingQueue<>();

        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            Game game = mock(Game.class);
            globals.when(Game::getInstance).thenReturn(game);
            globals.when(Game::getWorld).thenReturn(world);
            Player player = new Player(world);
            player.getInventory().setHotbarItem(0, new ItemStack(BlockType.STONE, 12));

            try (SaveService svc = new SaveService(worldPath)) {
                svc.initialize(WorldData.builder().seed(5L).worldName("snapshot-copies").build(), null, world);
                svc.bindPlayer(player, mainThreadTasks::add);

                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread autoSave = new Thread(() -> {
                    try {
                        svc.performAutoSave();
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                }, "AutoSave-test");
                autoSave.start();

                Runnable snapshot = mainThreadTasks.poll(10, TimeUnit.SECONDS);
                assertNotNull(snapshot, "the autosave must hand its player snapshot to the owner thread");
                snapshot.run();
                // The game keeps editing the live stack right after the snapshot was taken.
                player.getInventory().getHotbarItem(0).setCount(1);

                autoSave.join(10_000);
                assertFalse(autoSave.isAlive(), "performAutoSave did not return");
                assertNull(failure.get());
                svc.inFlightAutoSaveForTesting().get(10, TimeUnit.SECONDS);

                // Read the autosave's file back before close(): its final flush saves the live state.
                try (SaveService reloaded = new SaveService(worldPath)) {
                    SaveService.LoadResult result = reloaded.loadWorld().get(10, TimeUnit.SECONDS);
                    assertTrue(result.isSuccess(), result.getError());
                    ItemStack saved = result.getPlayerData().getInventory()[0];
                    assertNotNull(saved);
                    assertEquals(12, saved.getCount(), "the autosave holds the snapshot, not the live stack");
                }
            }
        }
    }

    @Test
    void closeOnOwnerThreadCancelsPendingSnapshot() throws Exception {
        String worldPath = tempDir.resolve("snapshot-close").toString();
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());
        BlockingQueue<Runnable> neverDrained = new LinkedBlockingQueue<>();

        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            Game game = mock(Game.class);
            globals.when(Game::getInstance).thenReturn(game);
            globals.when(Game::getWorld).thenReturn(world);
            Player player = new Player(world);

            SaveService svc = new SaveService(worldPath);
            svc.initialize(WorldData.builder().seed(6L).worldName("snapshot-close").build(), null, world);
            svc.bindPlayer(player, neverDrained::add);

            Thread autoSave = new Thread(svc::performAutoSave, "AutoSave-test");
            autoSave.start();
            assertNotNull(neverDrained.poll(10, TimeUnit.SECONDS), "snapshot queued on the owner thread");

            // The owner thread now closes the service instead of draining its queue.
            long started = System.nanoTime();
            svc.close();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            autoSave.join(5_000);

            assertFalse(autoSave.isAlive(), "the autosave must give up its cancelled snapshot");
            assertTrue(elapsedMs < 5_000, "close() waited " + elapsedMs + " ms on the snapshot");
        }
    }
}
