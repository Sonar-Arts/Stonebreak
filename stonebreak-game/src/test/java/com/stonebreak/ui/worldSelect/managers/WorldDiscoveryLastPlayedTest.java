package com.stonebreak.ui.worldSelect.managers;

import com.stonebreak.world.save.WorldStorage;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.serialization.JsonWorldSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Worlds sort by when they were really last played: {@code metadata.json}, which every save
 * rewrites — not {@code world.json}, the creation record, whose last-played time never moves.
 * The main menu previews the first of them.
 */
class WorldDiscoveryLastPlayedTest {

    @TempDir
    Path home;
    private String previous;
    private WorldDiscoveryManager discovery;

    @BeforeEach
    void pointAtTempHome() {
        previous = System.getProperty(WorldStorage.DATA_DIR_PROPERTY);
        System.setProperty(WorldStorage.DATA_DIR_PROPERTY, home.toString());
        discovery = new WorldDiscoveryManager();
    }

    @AfterEach
    void restore() {
        discovery.dispose();
        if (previous == null) {
            System.clearProperty(WorldStorage.DATA_DIR_PROPERTY);
        } else {
            System.setProperty(WorldStorage.DATA_DIR_PROPERTY, previous);
        }
    }

    @Test
    void theWorldPlayedMostRecentlySortsFirstWhateverItsCreationRecordSays() throws Exception {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 0);
        // Old: created first, but played just now (only metadata.json knows).
        world("Old", base, base.plusDays(30));
        // Newer: created later, never played since.
        world("Newer", base.plusDays(10), null);

        assertEquals(List.of("Old", "Newer"), discovery.discoverWorlds());
        assertEquals(base.plusDays(30), discovery.getWorldData("Old").getLastPlayed());
    }

    @Test
    void anUnreadableMetadataFileFallsBackToTheCreationRecord() throws Exception {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 0);
        world("Broken", base, null);
        Files.writeString(WorldStorage.worldDir("Broken").resolve("metadata.json"), "{not json");

        assertEquals(base, discovery.getWorldData("Broken").getLastPlayed());
        assertEquals(List.of("Broken"), discovery.discoverWorlds());
    }

    /** A world as the game makes it: world.json + chunks/, and metadata.json once it has been played. */
    private static void world(String name, LocalDateTime created, LocalDateTime played) throws Exception {
        Path dir = WorldStorage.worldDir(name);
        Files.createDirectories(dir.resolve("chunks"));
        JsonWorldSerializer serializer = new JsonWorldSerializer();
        WorldData record = WorldData.builder().seed(name.hashCode()).worldName(name).lastPlayed(created).build();
        Files.write(dir.resolve("world.json"), serializer.serialize(record));
        if (played != null) {
            Files.write(dir.resolve("metadata.json"), serializer.serialize(record.withLastPlayed(played)));
        }
    }
}
