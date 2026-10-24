package com.stonebreak.network.server;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.WorldStorage;
import com.stonebreak.world.save.model.WorldData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Issue #368: a session shutdown interrupts a server still booting. {@code createAndLoad} used to
 * swallow the interrupt around every wait and, when it hit the metadata load, booted the world
 * as a NEW one under the fallback seed — whose save then overwrote the real metadata.
 */
@Tag("regression")
class ServerLevelInterruptTest {

    @TempDir
    Path home;
    private String realHome;

    @BeforeEach
    void useTempHome() {
        String xdg = System.getenv("XDG_DATA_HOME");
        assumeTrue(xdg == null || xdg.isBlank(), "worlds would land in XDG_DATA_HOME, not the temp home");
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        if (realHome != null) {
            System.setProperty("user.home", realHome);
        }
        Thread.interrupted();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void anInterruptedBootOfASavedWorldThrowsAndKeepsItsMetadata() throws Exception {
        String name = "interrupt-368";
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(mock(Game.class));
            globals.when(Game::getWorld).thenReturn(world);
            try (SaveService svc = new SaveService(WorldStorage.worldPath(name))) {
                svc.initialize(WorldData.builder().seed(99L).worldName(name).build(), new Player(world), world);
                svc.saveAll().get(10, TimeUnit.SECONDS);
            }
        }

        Thread.currentThread().interrupt(); // shutdown() interrupting the boot
        assertThrows(InterruptedException.class, () -> ServerLevel.createAndLoad(name, 1L));
        assertFalse(Thread.currentThread().isInterrupted(), "the interrupt was delivered as the exception");

        try (SaveService svc = new SaveService(WorldStorage.worldPath(name))) {
            WorldData data = svc.loadWorld().get(10, TimeUnit.SECONDS).getWorldData();
            assertEquals(99L, data.getSeed(), "an interrupted load must never boot (and save) a new world");
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void anInterruptedBootOfANewWorldWritesNothing() {
        String name = "interrupt-new-368";
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> ServerLevel.createAndLoad(name, 7L));
        assertFalse(Files.exists(WorldStorage.worldDir(name).resolve("metadata.json")),
                "a boot stopped before it started must not create the world");
    }
}
