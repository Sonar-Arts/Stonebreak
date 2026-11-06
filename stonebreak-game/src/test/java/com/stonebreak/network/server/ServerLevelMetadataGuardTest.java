package com.stonebreak.network.server;

import com.stonebreak.world.save.WorldStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world whose metadata cannot be trusted must not boot: {@code createAndLoad} used to treat any
 * failed metadata load as a new world under the fallback seed, whose first save overwrote the
 * real metadata (and grew new terrain against the saved chunks).
 */
@Tag("regression")
class ServerLevelMetadataGuardTest {

    @TempDir
    Path home;
    private String previousDataDir;

    @BeforeEach
    void useTempHome() {
        previousDataDir = System.getProperty(WorldStorage.DATA_DIR_PROPERTY);
        System.setProperty(WorldStorage.DATA_DIR_PROPERTY, home.toString());
    }

    @AfterEach
    void restoreHome() {
        if (previousDataDir == null) {
            System.clearProperty(WorldStorage.DATA_DIR_PROPERTY);
        } else {
            System.setProperty(WorldStorage.DATA_DIR_PROPERTY, previousDataDir);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aWorldWithUnrecoverableMetadataIsNotBootedAndNothingIsOverwritten() throws Exception {
        String name = "unrecoverable-metadata";
        Path dir = Files.createDirectories(WorldStorage.worldDir(name));
        String damaged = "{\"seed\": 1234, \"worldName\": ";
        Files.writeString(dir.resolve("metadata.json"), damaged);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> ServerLevel.createAndLoad(name, 1L));
        assertTrue(refused.getMessage().contains(name), refused.getMessage());

        assertFalse(Files.exists(dir.resolve("metadata.json")), "no new-world metadata was written");
        assertEquals(damaged, Files.readString(dir.resolve("metadata.json.corrupt")));

        // Opening it again is refused the same way instead of booting the now metadata-less world as new.
        assertThrows(IllegalStateException.class, () -> ServerLevel.createAndLoad(name, 1L));
        assertFalse(Files.exists(dir.resolve("metadata.json")));
        assertEquals(damaged, Files.readString(dir.resolve("metadata.json.corrupt")));
    }
}
