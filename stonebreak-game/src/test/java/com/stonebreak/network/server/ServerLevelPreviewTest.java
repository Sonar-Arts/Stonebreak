package com.stonebreak.network.server;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.WorldStorage;
import com.stonebreak.world.save.model.WorldData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The main menu's preview of a saved world ({@link ServerLevel#createAndLoad(String, long, boolean)}):
 * the world is loaded and streams, but its ticks move no clock and nothing it does reaches disk —
 * not the spawn pre-gen, not a save, not the close — until Resume promotes it.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ServerLevelPreviewTest {

    @TempDir
    Path home;

    @Test
    void aHeldPreviewChangesNothingOnDisk() throws Exception {
        withDataDir(() -> {
            String name = "preview-held";
            saveWorld(name);
            Map<String, String> before = snapshot(WorldStorage.worldDir(name));

            ServerLevel level = ServerLevel.createAndLoad(name, 1L, true);
            try {
                assertTrue(level.isPreview());
                long ticks = level.timeOfDay().getTotalTicks();
                double weather = level.weather().seconds();
                for (int i = 0; i < 100; i++) {
                    level.tick(0.05f);
                }
                assertEquals(ticks, level.timeOfDay().getTotalTicks(), "the day clock holds");
                assertEquals(weather, level.weather().seconds(), "the weather clock holds");
                level.saveService().saveAll().get(10, TimeUnit.SECONDS);
            } finally {
                level.cleanup(); // the close flush must not write either
            }
            assertEquals(before, snapshot(WorldStorage.worldDir(name)), "the preview left every saved file as it was");
        });
    }

    @Test
    void resumingMakesTheWorldLiveAndTakesTheMenusWeather() throws Exception {
        withDataDir(() -> {
            String name = "preview-resumed";
            saveWorld(name);
            ServerLevel level = ServerLevel.createAndLoad(name, 1L, true);
            try {
                double menuClouds = level.weather().seconds() + 90.0;
                long ticks = level.timeOfDay().getTotalTicks();
                level.promoteFromPreview(menuClouds);

                assertFalse(level.isPreview());
                assertFalse(level.saveService().areWritesSuspended());
                assertEquals(menuClouds, level.weather().seconds(), 1e-9, "the clouds the menu drifted are kept");
                level.tick(1f);
                assertTrue(level.timeOfDay().getTotalTicks() > ticks, "the day clock runs once resumed");
            } finally {
                level.cleanup();
            }
        });
    }

    /** A saved world on disk, as the game leaves it (metadata.json + world.json). */
    private void saveWorld(String name) throws Exception {
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
    }

    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = dir.relativize(p).toString();
                if (rel.startsWith("fastlod")) {
                    continue; // the client's LOD cache, not the world
                }
                try {
                    files.put(rel, HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return files;
    }

    private interface Body {
        void run() throws Exception;
    }

    private void withDataDir(Body body) throws Exception {
        String previous = System.getProperty(WorldStorage.DATA_DIR_PROPERTY);
        System.setProperty(WorldStorage.DATA_DIR_PROPERTY, home.toString());
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty(WorldStorage.DATA_DIR_PROPERTY);
            } else {
                System.setProperty(WorldStorage.DATA_DIR_PROPERTY, previous);
            }
        }
    }
}
