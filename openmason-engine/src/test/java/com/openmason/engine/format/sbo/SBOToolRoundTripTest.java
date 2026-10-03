package com.openmason.engine.format.sbo;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip coverage for the SBO 1.9 mining data: the item {@code tool}
 * section and the block {@code gameProperties.material/requiredTier} fields —
 * export, re-parse, editor-style re-save, legacy manifests without them, and
 * the {@link SBOFormat.ToolData} resolution rule. Mirrors {@link SBODropRoundTripTest}.
 */
@Tag("regression")
class SBOToolRoundTripTest {

    @TempDir
    Path dir;

    private Path writeFile(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private static SBOFormat.ExportParameters params(String id, SBOFormat.ObjectType type) {
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        params.setObjectId(id);
        params.setObjectName(id);
        params.setObjectType(type);
        params.setObjectPack("test");
        params.setAuthor("junit");
        return params;
    }

    private static SBOFormat.ToolData stonePickaxe() {
        return new SBOFormat.ToolData("pickaxe", 1, 0.2f, List.of("stone", "ore"), 131, 2.0f);
    }

    private static SBOFormat.GameProperties stoneBlock(String material, int requiredTier) {
        return new SBOFormat.GameProperties(1, 4.0f, true, true, 0, 0, "OPAQUE", false, false,
                false, 64, "BLOCKS", true, material, requiredTier);
    }

    @Test
    void exportAndReparseToolSection() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params("test:stone_pickaxe", SBOFormat.ObjectType.ITEM);
        params.setTool(stonePickaxe());
        String out = dir.resolve("pick.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOFormat.Document doc = new SBOParser().parseRaw(Path.of(out)).manifest();
        assertEquals("1.9", doc.version());
        assertTrue(doc.hasTool());
        assertEquals(stonePickaxe(), doc.tool());
    }

    @Test
    void optionalToolStatsAreOmittedAndStayNull() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params("test:shovel", SBOFormat.ObjectType.ITEM);
        SBOFormat.ToolData bare = new SBOFormat.ToolData("shovel", 0, 0.5f, List.of("dirt"), null, null);
        params.setTool(bare);
        String out = dir.resolve("shovel.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOFormat.ToolData parsed = new SBOParser().parseRaw(Path.of(out)).manifest().tool();
        assertEquals(bare, parsed);
        assertNull(parsed.durability());
        assertNull(parsed.attackDamage());
    }

    @Test
    void blockMiningFieldsRoundTripThroughEditorResave() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params("test:iron_ore", SBOFormat.ObjectType.BLOCK);
        params.setGameProperties(stoneBlock("Ore", 1));
        String out = dir.resolve("ore.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        assertEquals("ore", raw.manifest().gameProperties().material(), "material is lowercased");
        assertEquals(1, raw.manifest().gameProperties().requiredTier());

        String resaved = dir.resolve("ore2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));
        assertEquals(stoneBlock("ore", 1), new SBOParser().parseRaw(Path.of(resaved)).manifest().gameProperties());
    }

    @Test
    void legacyManifestHasNoMiningData() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params("test:plain", SBOFormat.ObjectType.BLOCK);
        params.setGameProperties(new SBOFormat.GameProperties(2, 1f, true, true, 0, 0, "OPAQUE",
                false, false, false, 64, "BLOCKS", true));
        String out = dir.resolve("plain.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOFormat.Document doc = new SBOParser().parseRaw(Path.of(out)).manifest();
        assertFalse(doc.hasTool());
        assertNull(doc.gameProperties().material());
        assertEquals(0, doc.gameProperties().requiredTier());
    }

    @Test
    void parserAcceptsTierNamesAndDropsInvalidSections() throws IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        SBOFormat.ToolData named = SBOParser.parseTool(mapper.readTree("""
                {"tool": {"toolClass": "Axe", "tier": "stone", "speedMultiplier": 0.2,
                          "materials": ["Wood", "wood", " "]}}"""));
        assertNotNull(named);
        assertEquals("axe", named.toolClass());
        assertEquals(1, named.tier());
        assertEquals(List.of("wood"), named.materials(), "materials lowercased, de-duplicated, blanks dropped");

        assertNull(SBOParser.parseTool(mapper.readTree("{}")), "absent section ⇒ null");
        assertNull(SBOParser.parseTool(mapper.readTree("{\"tool\": null}")));
        assertNull(SBOParser.parseTool(mapper.readTree("{\"tool\": {\"toolClass\": \"\"}}")),
                "blank tool class ⇒ section ignored");
        assertNull(SBOParser.parseTool(mapper.readTree(
                "{\"tool\": {\"toolClass\": \"axe\", \"speedMultiplier\": 0}}")));
    }

    @Test
    void resolutionRuleMatchesMaterialAndTier() {
        SBOFormat.ToolData pick = stonePickaxe();
        assertEquals(0.2f, pick.hardnessMultiplier("stone", 0));
        assertEquals(0.2f, pick.hardnessMultiplier("ORE", 1), "material match is case-insensitive");
        assertEquals(1.0f, pick.hardnessMultiplier("ore", 2), "tier below requiredTier ⇒ no bonus");
        assertEquals(1.0f, pick.hardnessMultiplier("wood", 0));
        assertEquals(1.0f, pick.hardnessMultiplier(null, 0));

        assertEquals(0.8f, SBOFormat.ToolData.effectiveHardness(pick, "stone", 0, 4.0f), 1e-6f);
        assertEquals(4.0f, SBOFormat.ToolData.effectiveHardness(null, "stone", 0, 4.0f));
        assertEquals(SBOFormat.ToolData.MIN_EFFECTIVE_HARDNESS,
                SBOFormat.ToolData.effectiveHardness(pick, "stone", 0, 0.2f), "floor applies");
        // A multiplier >= 1 never slows a block down nor bypasses the floor logic.
        SBOFormat.ToolData blunt = new SBOFormat.ToolData("club", 0, 2f, List.of("stone"), null, null);
        assertEquals(4.0f, SBOFormat.ToolData.effectiveHardness(blunt, "stone", 0, 4.0f));
    }

    @Test
    void recordValidation() {
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.ToolData(" ", 0, 1f, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.ToolData("a", -1, 1f, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.ToolData("a", 0, 0f, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.ToolData("a", 0, Float.NaN, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.ToolData("a", 0, 1f, List.of(), 0, null));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.ToolData("a", 0, 1f, List.of(), null, -1f));
        assertEquals(0, stoneBlock(" ", -3).requiredTier(), "negative tier clamps to 0");
        assertNull(stoneBlock(" ", 0).material(), "blank material ⇒ null");
        assertEquals("stone", SBOFormat.ToolData.tierName(1));
        assertEquals("7", SBOFormat.ToolData.tierName(7));
        assertEquals(0, SBOFormat.ToolData.parseTier("Wooden"));
        assertEquals(5, SBOFormat.ToolData.parseTier("5"));
        assertEquals(-1, SBOFormat.ToolData.parseTier("mythril"));
    }
}
