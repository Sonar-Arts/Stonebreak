package com.openmason.main.systems.menus.dialogs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Export dialogs must land in the repo's resource folders with the shipped
 * naming convention — these pin the routing and file-name rules.
 */
class GameResourceDirsTest {

    @Test
    void suggestedFileNameFollowsShippedConvention() {
        assertEquals("SB_Oak_Planks.sbo", GameResourceDirs.suggestedFileName("Oak Planks", "sbo", "object.sbo"));
        assertEquals("SB_Oak_Planks.sbo", GameResourceDirs.suggestedFileName("oak_planks", "sbo", "object.sbo"));
        assertEquals("SB_Tophat.sbe", GameResourceDirs.suggestedFileName("tophat", "sbe", "entity.sbe"));
        assertEquals("SB_Red_Sand_Stone.sbo", GameResourceDirs.suggestedFileName("  red  sand-stone! ", "sbo", "x.sbo"));
    }

    @Test
    void suggestedFileNameFallsBackWhenBlank() {
        assertEquals("object.sbo", GameResourceDirs.suggestedFileName("", "sbo", "object.sbo"));
        assertEquals("object.sbo", GameResourceDirs.suggestedFileName("!!!", "sbo", "object.sbo"));
        assertEquals("object.sbo", GameResourceDirs.suggestedFileName(null, "sbo", "object.sbo"));
    }

    @Test
    void resourcesRootResolvesFromAnyModuleDirectory() {
        // Surefire runs with cwd = openmason-tool/; the resources live in a sibling module.
        Path root = GameResourceDirs.resourcesRoot();
        assertNotNull(root, "game resources tree should be discoverable from the tool module");
        assertTrue(Files.isDirectory(root.resolve("sbo/blocks")));
    }

    @Test
    void sboFolderRoutesByObjectType() {
        Path root = GameResourceDirs.resourcesRoot();
        assertNotNull(root);
        assertEquals(root.resolve("sbo/blocks").toString(), GameResourceDirs.sboFolderFor("Block"));
        assertEquals(root.resolve("sbo/items").toString(), GameResourceDirs.sboFolderFor("item"));
        assertTrue(GameResourceDirs.sboFolderFor("Decoration").startsWith(root.resolve("sbo").toString()));
        assertEquals(root.resolve("sbe/Mobs").toString(), GameResourceDirs.sbeFolderFor("Mob"));
        assertEquals(root.resolve("sbe").toString(), GameResourceDirs.sbeFolderFor("NPC"));
    }

    @Test
    void insideResourcesCheck() {
        Path root = GameResourceDirs.resourcesRoot();
        assertNotNull(root);
        assertTrue(GameResourceDirs.isInsideResources(root.resolve("sbo/items").toString()));
        assertTrue(!GameResourceDirs.isInsideResources(System.getProperty("java.io.tmpdir")));
    }
}
