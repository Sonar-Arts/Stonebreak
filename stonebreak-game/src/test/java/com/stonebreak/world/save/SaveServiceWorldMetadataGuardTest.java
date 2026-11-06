package com.stonebreak.world.save;

import com.openmason.engine.voxel.cco.coordinates.CcoBounds;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.repository.FileSaveRepository;
import com.stonebreak.world.save.serialization.JsonWorldSerializer;
import org.junit.jupiter.api.BeforeAll;
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

    /** The world dimensions the game installs at startup (GameBootstrap): the chunk codec reads them (#385). */
    @BeforeAll
    static void installWorldDimensions() {
        CcoBounds.configure(WorldConfiguration.voxelConfig());
    }

    private Path world(String name) throws Exception {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private static void writeCreationRecord(Path worldDir, long seed) throws Exception {
        WorldData record = WorldData.builder().seed(seed).worldName(worldDir.getFileName().toString())
            .createdTime(LocalDateTime.of(2025, 1, 2, 3, 4, 5)).build();
        Files.write(worldDir.resolve("world.json"), new JsonWorldSerializer().serialize(record));
    }

    private static void saveAChunk(Path worldDir) throws Exception {
        new FileSaveRepository(worldDir.toString()).saveChunk(ChunkData.builder().chunkX(0).chunkZ(0)
            .blocks(CcoPalettedChunkStorage.createEmpty(16, WorldConfiguration.WORLD_HEIGHT, 16, BlockType.AIR))
            .lastModified(LocalDateTime.now()).build());
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
        when(world.getDirtyChunks()).thenReturn(List.of());

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
    void missingMetadataInAWorldWithSavedChunksIsRebuiltNotNew() throws Exception {
        Path dir = world("chunks-no-metadata");
        writeCreationRecord(dir, 77L);
        saveAChunk(dir);

        SaveService.LoadResult result = load(dir);
        assertTrue(result.isSuccess(), result.getError());
        assertEquals(77L, result.getWorldData().getSeed());
        assertNotNull(result.getWorldNotice());
    }

    @Test
    void missingMetadataInAWorldWithSavedChunksAndNoWorldJsonRefuses() throws Exception {
        Path dir = world("chunks-only");
        saveAChunk(dir);
        assertRefused(load(dir));
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
