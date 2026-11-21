package com.stonebreak.world.save;

import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.repository.FileSaveRepository;
import com.stonebreak.world.save.serialization.JsonWorldSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code metadata.json} is guarded like every other save file (#358's rule): a load that cannot
 * be trusted never reads as a new world, because booting one under the fallback seed and saving
 * it overwrites the real world. An unreadable file is set aside and rebuilt from
 * {@code world.json}; when that is impossible, or the file could not even be read, the load fails
 * and every file stays in place.
 */
@Tag("regression")
@Timeout(30)
class SaveServiceWorldMetadataGuardTest {

    private static final long WAIT = 10;
    private static final String DAMAGED = "{\"seed\": 42, \"worldName\": ";

    @TempDir
    Path tempDir;

    private Path world(String name) throws Exception {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private static void writeCreationRecord(Path worldDir, long seed) throws Exception {
        WorldData record = WorldData.builder().seed(seed).worldName(worldDir.getFileName().toString())
            .createdTime(LocalDateTime.of(2025, 1, 2, 3, 4, 5)).build();
        Files.write(worldDir.resolve("world.json"), new JsonWorldSerializer().serialize(record));
    }

    /** Saved terrain: a file in the world's cube store (its content does not matter to the metadata guard). */
    private static void saveACube(Path worldDir) throws Exception {
        Path cubes = Files.createDirectories(worldDir.resolve(WorldFormat.CUBES_DIR));
        Files.write(cubes.resolve("r.0.0.0.cvr"), new byte[64]);
    }

    /** A world of the old column format: chunk files and a version-1 record. */
    private static void writeColumnWorld(Path worldDir, boolean withMetadata) throws Exception {
        Path region = Files.createDirectories(worldDir.resolve("chunks/r.0.0"));
        Files.write(region.resolve("c.0.0.sbc"), new byte[32]);
        String v1 = "{\n  \"seed\": 11,\n  \"worldName\": \"" + worldDir.getFileName() + "\",\n"
            + "  \"spawnPosition\": {\n    \"x\": 0.0,\n    \"y\": 100.0,\n    \"z\": 0.0\n  },\n"
            + "  \"creationTime\": \"2025-01-02T03:04:05\",\n  \"lastPlayed\": \"2025-01-02T03:04:05\",\n"
            + "  \"totalPlayTimeMillis\": 5,\n  \"worldTimeTicks\": 6000,\n  \"formatVersion\": 1\n}";
        Files.writeString(worldDir.resolve("world.json"), v1);
        if (withMetadata) {
            Files.writeString(worldDir.resolve("metadata.json"), v1);
        }
    }

    private static SaveService.LoadResult load(Path worldDir) throws Exception {
        try (SaveService svc = new SaveService(worldDir.toString())) {
            return svc.loadWorld().get(WAIT, TimeUnit.SECONDS);
        }
    }

    private static void assertRefused(SaveService.LoadResult result) {
        assertFalse(result.isSuccess());
        assertFalse(result.isWorldMissing(), "a world that could not be opened must not read as a new world");
        assertNotNull(result.getError());
    }

    @Test
    void aWorldThatWasNeverSavedIsMissing() throws Exception {
        SaveService.LoadResult result = load(world("never-saved"));
        assertFalse(result.isSuccess());
        assertTrue(result.isWorldMissing());
    }

    @Test
    void unreadableMetadataIsSetAsideAndRebuiltFromWorldJson() throws Exception {
        Path dir = world("rebuilt");
        writeCreationRecord(dir, 42L);
        Files.writeString(dir.resolve("metadata.json"), DAMAGED);

        SaveService.LoadResult result = load(dir);

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(42L, result.getWorldData().getSeed(), "the seed (so the terrain) comes from world.json");
        assertNotNull(result.getWorldNotice());
        assertTrue(result.getWorldNotice().contains("metadata.json.corrupt"), result.getWorldNotice());
        assertNull(result.getPlayerNotice());
        assertEquals(DAMAGED, Files.readString(dir.resolve("metadata.json.corrupt")), "the damaged bytes are kept");
        assertFalse(Files.exists(dir.resolve("metadata.json")));
    }

    @Test
    void aRebuiltWorldSavesCleanMetadataThatLoadsNextTime() throws Exception {
        Path dir = world("rebuilt-then-saved");
        writeCreationRecord(dir, 42L);
        Files.writeString(dir.resolve("metadata.json"), DAMAGED);
        World world = mock(World.class);

        try (SaveService svc = new SaveService(dir.toString())) {
            SaveService.LoadResult result = svc.loadWorld().get(WAIT, TimeUnit.SECONDS);
            svc.setWorldTimeSource(new TimeOfDay(0));
            svc.initialize(result.getWorldData(), null, world);
            svc.saveAll().get(WAIT, TimeUnit.SECONDS);
        }

        SaveService.LoadResult next = load(dir);
        assertTrue(next.isSuccess(), next.getError());
        assertEquals(42L, next.getWorldData().getSeed());
        assertNull(next.getWorldNotice(), "a clean metadata.json loads without a rebuild");
    }

    @Test
    void unreadableMetadataWithoutWorldJsonRefusesAndStaysRefused() throws Exception {
        Path dir = world("no-record");
        Files.writeString(dir.resolve("metadata.json"), DAMAGED);

        assertRefused(load(dir));
        assertEquals(DAMAGED, Files.readString(dir.resolve("metadata.json.corrupt")));

        // The set-aside file must not turn the next load into a "new world".
        assertRefused(load(dir));
        assertEquals(DAMAGED, Files.readString(dir.resolve("metadata.json.corrupt")));
    }

    @Test
    void anUnreadableWorldJsonCannotRebuildEither() throws Exception {
        Path dir = world("bad-record");
        Files.writeString(dir.resolve("world.json"), "not json");
        Files.writeString(dir.resolve("metadata.json"), DAMAGED);

        assertRefused(load(dir));
    }

    @Test
    void missingMetadataInAWorldWithSavedCubesIsRebuiltNotNew() throws Exception {
        Path dir = world("cubes-no-metadata");
        writeCreationRecord(dir, 77L);
        saveACube(dir);

        SaveService.LoadResult result = load(dir);
        assertTrue(result.isSuccess(), result.getError());
        assertEquals(77L, result.getWorldData().getSeed());
        assertNotNull(result.getWorldNotice());
    }

    @Test
    void missingMetadataInAWorldWithSavedCubesAndNoWorldJsonRefuses() throws Exception {
        Path dir = world("cubes-only");
        saveACube(dir);
        assertRefused(load(dir));
    }

    // ---- incompatible versions (cubic runtime #447: no migration, old worlds are refused, never touched) ----

    @Test
    void aColumnFormatWorldIsRefusedAsIncompatibleAndLeftAlone() throws Exception {
        Path dir = world("column-world");
        writeColumnWorld(dir, true);
        byte[] metadataBefore = Files.readAllBytes(dir.resolve("metadata.json"));

        SaveService.LoadResult result = load(dir);

        assertRefused(result);
        assertTrue(result.isIncompatible());
        assertTrue(result.getError().startsWith(WorldFormat.INCOMPATIBLE), result.getError());
        assertTrue(Files.exists(dir.resolve("chunks/r.0.0/c.0.0.sbc")), "old chunk files are never deleted");
        assertEquals(new String(metadataBefore), Files.readString(dir.resolve("metadata.json")),
            "the old metadata is not rewritten");
        assertFalse(Files.exists(dir.resolve("metadata.json.corrupt")), "an old record is not damaged");
    }

    @Test
    void aColumnWorldWithoutMetadataIsIncompatibleNotRebuiltOrNew() throws Exception {
        Path dir = world("column-no-metadata");
        writeColumnWorld(dir, false);

        SaveService.LoadResult result = load(dir);
        assertRefused(result);
        assertTrue(result.isIncompatible(), result.getError());
    }

    @Test
    void currentRecordWithOnlyColumnChunkFilesIsIncompatible() throws Exception {
        Path dir = world("current-record-old-files");
        writeCreationRecord(dir, 3L);
        Files.copy(dir.resolve("world.json"), dir.resolve("metadata.json"));
        Path region = Files.createDirectories(dir.resolve("chunks/r.0.0"));
        Files.write(region.resolve("c.0.0.sbc"), new byte[8]);

        SaveService.LoadResult result = load(dir);
        assertTrue(result.isIncompatible(), String.valueOf(result.getError()));
    }

    @Test
    void otherWorldBoundsAreIncompatible() throws Exception {
        Path dir = world("other-bounds");
        WorldData tall = WorldData.builder().seed(1L).worldName("other-bounds").bounds(-64, 320, 32).build();
        Files.write(dir.resolve("metadata.json"), new JsonWorldSerializer().serialize(tall));

        SaveService.LoadResult result = load(dir);
        assertTrue(result.isIncompatible(), String.valueOf(result.getError()));
    }

    @Test
    void aCurrentWorldLoads() throws Exception {
        Path dir = world("current");
        writeCreationRecord(dir, 8L);
        Files.copy(dir.resolve("world.json"), dir.resolve("metadata.json"));
        saveACube(dir);

        SaveService.LoadResult result = load(dir);
        assertTrue(result.isSuccess(), result.getError());
        assertFalse(result.isIncompatible());
        assertEquals(WorldFormat.CURRENT_VERSION, result.getWorldData().getFormatVersion());
    }

    @Test
    void missingMetadataWithAPlayerRecordIsNotANewWorld() throws Exception {
        Path dir = world("player-only");
        Files.writeString(dir.resolve("player.json"), "{}");
        assertRefused(load(dir));
    }

    @Test
    void metadataThatCannotBeReadAtAllRefusesAndIsNotSetAside() throws Exception {
        // A directory where the file belongs: the read fails with an I/O error, not a parse
        // error. The file may be fine (locked, a flaky disk), so nothing is moved.
        Path dir = world("io-error");
        writeCreationRecord(dir, 5L);
        Files.createDirectory(dir.resolve("metadata.json"));

        assertRefused(load(dir));
        assertTrue(Files.isDirectory(dir.resolve("metadata.json")));
        assertFalse(Files.exists(dir.resolve("metadata.json.corrupt")));
    }

    @Test
    void aRebuildAndASetAsidePlayerBothReachThePlayer() throws Exception {
        Path dir = world("both-damaged");
        writeCreationRecord(dir, 9L);
        Files.writeString(dir.resolve("metadata.json"), DAMAGED);
        Files.writeString(dir.resolve("player.json"), "{\"inventory\": [");

        SaveService.LoadResult result = load(dir);
        assertTrue(result.isSuccess(), result.getError());
        assertNotNull(result.getWorldNotice());
        assertNotNull(result.getPlayerNotice());
        assertTrue(Files.exists(dir.resolve("player.json.corrupt")));
    }
}
