package com.stonebreak.world.save;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldStorageTest {

    @Test
    void worldDirIsWorldsRootResolveName() {
        String worldName = "TestWorld";
        Path expected = WorldStorage.worldsRoot().resolve(worldName);
        assertEquals(expected, WorldStorage.worldDir(worldName));
    }

    @Test
    void worldPathIsWorldDirToString() {
        String worldName = "TestWorld";
        String expected = WorldStorage.worldDir(worldName).toString();
        assertEquals(expected, WorldStorage.worldPath(worldName));
    }

    @Test
    void resultsAreAbsolute() {
        assertTrue(WorldStorage.worldsRoot().isAbsolute(), "worldsRoot() must be absolute");
        assertTrue(WorldStorage.worldDir("X").isAbsolute(), "worldDir() result must be absolute");
    }

    @Test
    void nameWithSpaceIsPreserved() {
        assertEquals("My World", WorldStorage.worldDir("My World").getFileName().toString());
    }
}