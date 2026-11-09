package com.stonebreak.world.save;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.save.model.WorldData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The main menu's preview of a saved world loads it with writes suspended: no save, autosave,
 * remote-player write or close flush may touch disk, and resuming counts play time from the resume,
 * not from the load (time spent looking at the menu is not play time).
 */
@Timeout(30)
class SaveServiceSuspendWritesTest {

    @TempDir
    Path tempDir;

    @Test
    void suspendedWritesLeaveTheFolderUntouchedThroughClose() throws Exception {
        Path worldDir = tempDir.resolve("held");
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(mock(Game.class));
            globals.when(Game::getWorld).thenReturn(world);
            SaveService svc = new SaveService(worldDir.toString());
            svc.suspendWrites();
            svc.initialize(WorldData.builder().seed(7L).worldName("held").build(), new Player(world), world);

            svc.saveAll().get(5, TimeUnit.SECONDS);
            svc.saveDirtyChunks().get(5, TimeUnit.SECONDS);
            svc.saveNamedPlayer("guest", "{}".getBytes()).get(5, TimeUnit.SECONDS);
            svc.performAutoSave();
            svc.flushSavesBlocking("test");
            svc.close();

            assertEquals(List.of(), filesUnder(worldDir), "a suspended service writes nothing, not even on close");
        }
    }

    @Test
    void resumedWritesSaveAndCountPlayTimeFromTheResume() throws Exception {
        Path worldDir = tempDir.resolve("resumed");
        AtomicLong clock = new AtomicLong(0L);
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(mock(Game.class));
            globals.when(Game::getWorld).thenReturn(world);
            try (SaveService svc = new SaveService(worldDir.toString(), Executors.newFixedThreadPool(2),
                    Executors.newFixedThreadPool(2), clock::get)) {
                svc.suspendWrites();
                svc.initialize(WorldData.builder().seed(7L).worldName("resumed").totalPlayTimeMillis(1_000L).build(),
                        new Player(world), world);
                clock.set(600_000L); // ten minutes on the main menu
                svc.saveAll().get(5, TimeUnit.SECONDS);
                assertFalse(Files.exists(worldDir.resolve("metadata.json")));

                svc.resumeWrites();
                assertFalse(svc.areWritesSuspended());
                clock.addAndGet(5_000L); // five seconds of play
                svc.saveAll().get(5, TimeUnit.SECONDS);

                assertTrue(Files.exists(worldDir.resolve("metadata.json")), "a resumed service saves");
                assertEquals(6_000L, svc.getWorldData().getTotalPlayTimeMillis(),
                        "the menu's ten minutes are not play time");
            }
        }
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }
}
