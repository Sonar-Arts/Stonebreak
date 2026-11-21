package com.stonebreak.world.save;

import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.ui.worldSelect.WorldSelectText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which worlds this build opens (#447: no migration; other formats are "incompatible", never touched). */
class WorldFormatTest {

    private static WorldData.Builder current() {
        return WorldData.builder().seed(1L).worldName("W");
    }

    @Test
    void aNewWorldIsCompatible() {
        assertTrue(WorldFormat.isCompatible(current().build()));
        assertTrue(WorldFormat.isCompatible(null), "no record: nothing to refuse here");
    }

    @Test
    void olderAndNewerFormatsAreIncompatible() {
        WorldFormat.Check older = WorldFormat.check(current().formatVersion(1).build());
        assertFalse(older.compatible());
        assertTrue(older.reason().startsWith(WorldFormat.INCOMPATIBLE) && older.reason().contains("older"),
            older.reason());
        WorldFormat.Check newer = WorldFormat.check(current().formatVersion(WorldFormat.CURRENT_VERSION + 1).build());
        assertFalse(newer.compatible());
        assertTrue(newer.reason().contains("newer"), newer.reason());
    }

    @Test
    void otherBoundsOrCubeEdgeAreIncompatible() {
        assertFalse(WorldFormat.isCompatible(current().bounds(0, 256, 32).build()));
        assertFalse(WorldFormat.isCompatible(current().bounds(-384, 640, 16).build()));
    }

    @Test
    void columnChunkFilesWithoutACubeStoreAreIncompatibleEvenWithACurrentRecord(@TempDir Path dir) throws Exception {
        WorldData data = current().build();
        assertTrue(WorldFormat.check(data, dir).compatible());
        Files.createDirectories(dir.resolve("chunks/r.0.0"));
        assertTrue(WorldFormat.check(data, dir).compatible(), "an empty chunks/ folder is not old data");
        Files.write(dir.resolve("chunks/r.0.0/c.0.0.sbc"), new byte[4]);
        assertTrue(WorldFormat.hasLegacyColumnFiles(dir));
        assertFalse(WorldFormat.check(data, dir).compatible());
        Files.createDirectories(dir.resolve("cubes"));
        assertTrue(WorldFormat.check(data, dir).compatible(), "a cube store makes it a cubic world");
    }

    @Test
    void theWorldListSaysIncompatibleVersion() {
        assertEquals(WorldSelectText.INCOMPATIBLE_META, WorldSelectText.meta(current().formatVersion(1).build()));
        assertTrue(WorldSelectText.INCOMPATIBLE_META.startsWith("Incompatible version"));
        assertTrue(WorldSelectText.INCOMPATIBLE_META.chars().allMatch(c -> c < 128), "menu font is ASCII only");
        String meta = WorldSelectText.meta(current().seed(77L).build());
        assertTrue(meta != null && meta.contains("Seed: 77"), meta);
    }
}
