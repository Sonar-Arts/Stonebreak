package com.stonebreak.network.server;

import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.repository.PlayerSaveNames;
import com.stonebreak.world.save.serialization.JsonPlayerSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A joining remote player's saved record (issue #334 review, #358's rule for remote
 * players): a save that cannot be loaded refuses the join instead of being treated as "new
 * player" (whose next persist would overwrite it); an unreadable one is set aside first.
 */
@Timeout(30)
class NamedPlayerLoadTest {

    @TempDir
    Path tempDir;

    @Test
    void noFileMeansANewPlayer() throws Exception {
        try (SaveService svc = new SaveService(tempDir.resolve("w").toString())) {
            IntegratedServer.SavedRecord r = IntegratedServer.loadSavedRecord(svc, "Nobody");
            assertNull(r.blob());
            assertNull(r.refusal());
        }
    }

    @Test
    void aReadableSaveIsReturned() throws Exception {
        byte[] blob = new JsonPlayerSerializer().serialize(PlayerData.createDefault("w"));
        try (SaveService svc = new SaveService(tempDir.resolve("w").toString())) {
            svc.saveNamedPlayer("Alice", blob).get(10, TimeUnit.SECONDS);
            IntegratedServer.SavedRecord r = IntegratedServer.loadSavedRecord(svc, "Alice");
            assertArrayEquals(blob, r.blob());
            assertNull(r.refusal());
        }
    }

    @Test
    void anUnloadableSaveRefusesTheJoin() throws Exception {
        Path world = tempDir.resolve("w");
        try (SaveService svc = new SaveService(world.toString())) {
            Files.createDirectories(world.resolve("players").resolve(PlayerSaveNames.stem("Bob") + ".json"));   // I/O error
            IntegratedServer.SavedRecord r = IntegratedServer.loadSavedRecord(svc, "Bob");
            assertNotNull(r.refusal(), "an I/O failure used to read as 'no saved data'");
            assertNull(r.blob());
        }
    }

    @Test
    void anUnreadableSaveIsSetAsideNotOverwritten() throws Exception {
        Path world = tempDir.resolve("w");
        byte[] damaged = "{\"position\":{\"x\":1}}".getBytes(StandardCharsets.UTF_8);   // no inventory
        try (SaveService svc = new SaveService(world.toString())) {
            svc.saveNamedPlayer("Carol", damaged).get(10, TimeUnit.SECONDS);
            IntegratedServer.SavedRecord r = IntegratedServer.loadSavedRecord(svc, "Carol");
            assertNull(r.refusal());
            assertNull(r.blob(), "starts fresh");
            assertNotNull(r.notice());
            Path aside = world.resolve("players").resolve(PlayerSaveNames.stem("Carol") + ".json.corrupt");
            assertTrue(Files.exists(aside), "the damaged file is kept");
            assertArrayEquals(damaged, Files.readAllBytes(aside));
            assertEquals(false, Files.exists(world.resolve("players").resolve(PlayerSaveNames.stem("Carol") + ".json")));
        }
    }
}
