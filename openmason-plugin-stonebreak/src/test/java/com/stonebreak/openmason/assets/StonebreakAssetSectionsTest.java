package com.stonebreak.openmason.assets;

import com.stonebreak.gameplay.SboGameplay;
import com.openmason.api.PluginInfo;
import com.openmason.api.asset.AssetSectionContribution;
import com.openmason.api.asset.AssetSurface;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbe.SBEParser;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.main.plugins.EditorRegistries;
import com.openmason.main.systems.menus.dialogs.AssetSectionSet;
import com.openmason.main.systems.menus.dialogs.SBEEditorWindow;
import com.openmason.main.systems.menus.dialogs.SBOEditorWindow;
import com.stonebreak.openmason.StonebreakGameTarget;
import com.stonebreak.openmason.StonebreakPlugin;
import com.stonebreak.openmason.StonebreakSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stonebreak's SBO/SBE game-data sections in the core editor windows (#431): the plugin registers
 * them, and opening + saving every shipped asset through them loses nothing.
 */
@Tag("integration")
class StonebreakAssetSectionsTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void stonebreakEditor() {
        StonebreakSession.install();
        AssetSectionSet.setSource(StonebreakAssetSections::all);
    }

    @AfterEach
    void reset() {
        AssetSectionSet.setSource(null);
        StonebreakSession.clear();
    }

    private static List<Path> shipped(String folder, String ext) throws IOException {
        Path tree = StonebreakGameTarget.create().resources().locate();
        assertNotNull(tree);
        try (Stream<Path> files = Files.walk(tree.resolve(folder))) {
            return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(ext)).sorted().toList();
        }
    }

    /** Identity, rendering and the Stonebreak game data, whichever layout it is stored in. */
    private static List<Object> gameData(SBOFormat.Document d) {
        return Arrays.asList(d.objectId(), d.objectType(), d.rendering(), SboGameplay.of(d));
    }

    @Test
    void thePluginRegistersEverySection() {
        EditorRegistries registries = new EditorRegistries();
        new StonebreakPlugin().register(registries.contextFor(
                new PluginInfo(StonebreakPlugin.ID, "Stonebreak", "1.0", Set.of(), false, "test")));
        List<String> ids = registries.assetSections().all().stream().map(AssetSectionContribution::id).toList();
        assertEquals(StonebreakAssetSections.all().stream().map(AssetSectionContribution::id).toList(), ids);
        assertTrue(registries.mcpTools().all().stream().anyMatch(g -> g.id().equals("stonebreak.assets")));
        assertTrue(registries.mcpTools().all().stream().anyMatch(g -> g.id().equals("stonebreak.lens")));
        assertTrue(registries.guideTopics().all().stream().anyMatch(t -> t.id().equals("assets")));
        // The containers are core now (#431): the plugin no longer registers their file types.
        assertTrue(registries.fileTypes().all().isEmpty(), registries.fileTypes().all().toString());
    }

    @Test
    void theEditorTabsKeepTheirPre431Order() {
        AssetSectionSet<SBOFormat.Document> set = new AssetSectionSet<>(AssetSurface.SBO_EDITOR, new SboHost());
        List<AssetSectionSet.Tab> core = List.of(
                new AssetSectionSet.Tab("core.metadata", "Metadata", 0, null),
                new AssetSectionSet.Tab("core.states", "States", 200, null),
                new AssetSectionSet.Tab("core.sounds", "Sounds", 500, null));
        set.load(new SBOFormat.Document("1.11", "stonebreak:x", "X", "block", "default", "-", "me", null, "now",
                "model.omo", null, null, List.of(), null, null, null));
        assertEquals(List.of("Metadata", "Game Properties", "States", "Recipes", "Smelting", "Fuel Table", "Sounds", "Drops",
                "Tool", "Weapon"), set.tabs(core).stream().map(AssetSectionSet.Tab::label).toList());
        set.close();
    }

    @Test
    void everyShippedSboRoundTripsThroughTheSections() throws IOException {
        SBOParser parser = new SBOParser();
        List<Path> files = shipped("sbo", ".sbo");
        assertFalse(files.isEmpty());
        for (Path file : files) {
            SBOFormat.Document original = parser.parseRaw(file).manifest();
            SBOEditorWindow editor = new SBOEditorWindow(null, null);
            assertTrue(editor.openFile(file.toString()), file.toString());
            assertEquals(gameData(original), gameData(editor.snapshotDocument()), "snapshot of " + file);

            Path out = tmp.resolve(file.getFileName());
            assertTrue(editor.writeDocumentTo(out.toString()), file.toString());
            SBOFormat.Document resaved = parser.parseRaw(out).manifest();
            assertEquals(gameData(original), gameData(resaved), "re-saved " + file);
            // Saved by the Stonebreak sections: the namespaced layout (#383), no legacy fields left.
            assertTrue(java.util.Collections.disjoint(resaved.extensions().legacyFieldNames(),
                    com.stonebreak.gameplay.SboGameplayCodec.LEGACY_FIELDS), file.toString());
            assertEquals(!SboGameplay.of(original).isEmpty(),
                    resaved.extensions().has(com.stonebreak.gameplay.SboGameplayCodec.KEY), file.toString());
            editor.close();
        }
    }

    @Test
    void everyShippedSbeKeepsItsEntityType() throws IOException {
        SBEParser parser = new SBEParser();
        for (Path file : shipped("sbe", ".sbe")) {
            SBEFormat.Document original = parser.parseRaw(file).manifest();
            SBEEditorWindow editor = new SBEEditorWindow(null, null);
            assertTrue(editor.openFile(file.toString()), file.toString());
            assertEquals(com.stonebreak.mobs.sbe.SbeEntityKind.fromId(original.entityType()).id(),
                    editor.snapshotDocument().entityType(), file.toString());
        }
    }

    @Test
    void anAgentWriteRefusesADuplicateNumericId() throws IOException {
        // Two shipped blocks: give the first the second's numeric id.
        List<Path> blocks = shipped("sbo/blocks", ".sbo");
        SBOParser parser = new SBOParser();
        SBOFormat.Document a = parser.parseRaw(blocks.get(0)).manifest();
        SBOFormat.Document b = parser.parseRaw(blocks.get(1)).manifest();
        SboGameplay.GameProperties gp = SboGameplay.of(a).gameProperties();
        SboGameplay.GameProperties other = SboGameplay.of(b).gameProperties();
        assertNotNull(gp);
        assertNotNull(other);

        SBOEditorWindow editor = new SBOEditorWindow(null, null);
        assertTrue(editor.openFile(blocks.get(0).toString()));
        assertNull(editor.validateForWrite());
        editor.applyDocument(SboGameplay.of(a).withGameProperties(new SboGameplay.GameProperties(other.numericId(),
                gp.hardness(), gp.solid(), gp.breakable(), gp.atlasX(), gp.atlasY(), gp.flower(), gp.stackable(),
                gp.maxStackSize(), gp.category(), gp.placeable(), gp.material(), gp.requiredTier())).applyTo(a));
        String problem = editor.validateForWrite();
        assertNotNull(problem);
        assertTrue(problem.startsWith("numeric_id_conflict: " + other.numericId()), problem);
    }

    @Test
    void theExportStepAddsDefaultGameProperties() {
        ExportGamePropertiesSection section = new ExportGamePropertiesSection(new SboHost());
        section.load(new SBOFormat.ExportParameters());
        SBOFormat.ExportParameters params = section.write(new SBOFormat.ExportParameters());
        SboGameplay.GameProperties gp = SboGameplay.of(params).gameProperties();
        assertNotNull(gp, "a block gets the next free numeric id");
        assertTrue(gp.numericId() > 0);
        assertNull(section.validate());
    }

    /** A block being edited, as the SBO windows describe it. */
    private static final class SboHost implements com.openmason.api.asset.AssetSectionHost {
        @Override public AssetSurface<?> surface() { return AssetSurface.SBO_EDITOR; }
        @Override public String objectId() { return "stonebreak:test_block"; }
        @Override public String objectType() { return "block"; }
        @Override public boolean texturePayload() { return false; }
        @Override public void markDirty() { }
    }
}
