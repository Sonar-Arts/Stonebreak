package com.stonebreak.world.save;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remote players' {@code players/<name>.json} (issue #334 review): a load waits for that
 * player's in-flight write (a quick re-login after dropping items must not read the pre-drop
 * file — a dupe), writes of one player stay in order, an I/O failure is not "no saved data",
 * and an unreadable file can be set aside like #358 does for {@code player.json}.
 */
@Tag("regression")
@Timeout(60)
class NamedPlayerSaveOrderingTest {

    @TempDir
    Path tempDir;

    private static byte[] payload(int version) {
        // Big enough that a write takes real time, so an unordered load would overtake it.
        byte[] b = new byte[256 * 1024];
        Arrays.fill(b, (byte) ('a' + version % 26));
        byte[] tag = ("v" + version + ";").getBytes(StandardCharsets.UTF_8);
        System.arraycopy(tag, 0, b, 0, tag.length);
        return b;
    }

    @Test
    void aLoadRightAfterASaveReadsThatSave() throws Exception {
        try (SaveService svc = new SaveService(tempDir.resolve("w").toString())) {
            for (int i = 0; i < 40; i++) {
                byte[] expected = payload(i);
                svc.saveNamedPlayer("Alice", expected);          // not awaited: the quit save
                byte[] loaded = svc.loadNamedPlayer("Alice").get(10, TimeUnit.SECONDS);
                assertArrayEquals(expected, loaded, "re-login read a stale file at iteration " + i);
            }
        }
    }

    @Test
    void writesOfOnePlayerLandInOrder() throws Exception {
        try (SaveService svc = new SaveService(tempDir.resolve("w").toString())) {
            for (int i = 0; i < 30; i++) {
                svc.saveNamedPlayer("Bob", payload(i));
            }
            assertArrayEquals(payload(29), svc.loadNamedPlayer("Bob").get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void anIoFailureIsNotNoSavedData() throws Exception {
        Path world = tempDir.resolve("w");
        try (SaveService svc = new SaveService(world.toString())) {
            // A directory where the file should be: readAllBytes fails with an IOException.
            Files.createDirectories(world.resolve("players").resolve("Carol.json"));
            assertThrows(ExecutionException.class,
                    () -> svc.loadNamedPlayer("Carol").get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void anUnreadableFileIsSetAside() throws Exception {
        Path world = tempDir.resolve("w");
        try (SaveService svc = new SaveService(world.toString())) {
            svc.saveNamedPlayer("Dave", "garbage".getBytes(StandardCharsets.UTF_8));
            Path aside = svc.setNamedPlayerAside("Dave").get(10, TimeUnit.SECONDS);
            assertTrue(Files.exists(aside));
            assertTrue(aside.getFileName().toString().startsWith("Dave.json.corrupt"));
            assertFalse(Files.exists(world.resolve("players").resolve("Dave.json")));
        }
    }
}
