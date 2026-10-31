package com.stonebreak.openmason;

import com.openmason.api.OpenMasonPlugin;
import com.openmason.api.target.GameTarget;
import com.openmason.main.plugins.EditorRegistries;
import com.openmason.main.plugins.PluginManager;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Stonebreak game target and plugin as the editor loads them (#380, #382). */
class StonebreakGameTargetTest {

    @Test
    void stonebreakIsTheLegacyTargetAndShipsUi() {
        GameTarget sb = StonebreakGameTarget.create();
        assertEquals(StonebreakPlugin.ID, sb.id());
        assertTrue(sb.legacyDefault());
        assertEquals("stonebreak", sb.defaultNamespace());
        assertEquals("SB_", sb.assetFilePrefix());
        assertTrue(sb.writes().writable() && sb.writes().confirmWrites());
        assertNotNull(sb.uiDeploy());
        assertNotNull(sb.numericIds());
    }

    @Test
    void theUiHostDeclaresTheGamesContracts() throws Exception {
        var host = StonebreakGameTarget.create().uiHost();
        assertNotNull(host);
        assertTrue(host.profile().hostApis().containsKey("stonebreak:session"), host.profile().toString());
        assertTrue(host.declaration().contracts().containsKey("stonebreak:screen.pause"));
        assertTrue(host.fixtureJson().length > 2, host.fixtureName());
        assertTrue(!host.packagedAssets().isEmpty());
        try (var typeface = host.loadTypeface()) {
            assertNotNull(typeface);
        }
    }

    @Test
    void foldersRouteOnTheRealTree() throws Exception {
        GameTarget sb = StonebreakGameTarget.create();
        Path root = sb.resources().locate();
        assertNotNull(root, "the Stonebreak tree should be discoverable from the plugin module");
        assertTrue(Files.isDirectory(root.resolve("sbo/blocks")));
        assertEquals("sbo/blocks", sb.folders().folderFor("sbo", "block"));
        assertEquals("sbe/Mobs", sb.folders().folderFor("sbe", "mob"));
        try (InputStream in = sb.resources().open("sbo/blocks/SB_Oak_Stairs.sbo")) {
            assertTrue(in.readAllBytes().length > 0);
        }
    }

    @Test
    void theNewBiomeTemplateTargetsStonebreak() {
        assertEquals(StonebreakPlugin.ID, StonebreakGameTarget.newBiomeTemplate().getTargetId());
    }

    @Test
    void theConventionsPackIsReadableFromThePluginModule() {
        // The pack the plugin registers, read the way the knowledge tool reads it (through the tool's
        // ResourceText, so module encapsulation applies).
        String text = StonebreakPlugin.conventionsPack().markdown().get();
        assertTrue(text.startsWith("# Stonebreak conventions"), text);
        assertTrue(text.contains("## Clip conventions"));
    }

    @Test
    void theEditorDiscoversThePluginOnTheModulePath() {
        List<OpenMasonPlugin> found = new PluginManager(new EditorRegistries()).discover();
        assertTrue(found.stream().anyMatch(p -> p instanceof StonebreakPlugin), found.toString());
    }
}
