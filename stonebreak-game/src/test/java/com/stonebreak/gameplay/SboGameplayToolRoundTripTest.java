package com.stonebreak.gameplay;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.format.sbo.SBOSerializer;
import com.stonebreak.items.ToolMiningRules;

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
 * the {@link SboGameplay.ToolData} resolution rule. Mirrors {@link SBODropRoundTripTest}.
 */
@Tag("regression")
class SboGameplayToolRoundTripTest {

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

    private static SboGameplay.ToolData stonePickaxe() {
        return new SboGameplay.ToolData("pickaxe", 1, 0.2f, List.of("stone", "ore"), 131, 2.0f);
    }

    private static SboGameplay.GameProperties stoneBlock(String material, int requiredTier) {
        return new SboGameplay.GameProperties(1, 4.0f, true, true, 0, 0, false,
                false, 64, "BLOCKS", true, material, requiredTier);
    }

    @Test
    void exportAndReparseToolSection() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params("test:stone_pickaxe", SBOFormat.ObjectType.ITEM);
        SboGameplay.of(params).withTool(stonePickaxe()).applyTo(params);
        String out = dir.resolve("pick.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOFormat.Document manifest = new SBOParser().parseRaw(Path.of(out)).manifest();
        assertEquals(SBOFormat.FORMAT_VERSION, manifest.version());
        assertTrue(manifest.extensions().has(SboGameplayCodec.KEY), "new files carry the extension");
        SboGameplay doc = SboGameplay.of(manifest);
        assertTrue(doc.hasTool());
        assertEquals(stonePickaxe(), doc.tool());
    }

    @Test
    void optionalToolStatsAreOmittedAndStayNull() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params("test:shovel", SBOFormat.ObjectType.ITEM);
        SboGameplay.ToolData bare = new SboGameplay.ToolData("shovel", 0, 0.5f, List.of("dirt"), null, null);
        SboGameplay.of(params).withTool(bare).applyTo(params);
        String out = dir.resolve("shovel.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SboGameplay.ToolData parsed = SboGameplay.of(new SBOParser().parseRaw(Path.of(out)).manifest()).tool();
        assertEquals(bare, parsed);
        assertNull(parsed.durability());
        assertNull(parsed.attackDamage());
    }

    @Test
    void blockMiningFieldsRoundTripThroughEditorResave() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params("test:iron_ore", SBOFormat.ObjectType.BLOCK);
        SboGameplay.of(params).withGameProperties(stoneBlock("Ore", 1)).applyTo(params);
        String out = dir.resolve("ore.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        assertEquals("ore", SboGameplay.of(raw.manifest()).gameProperties().material(), "material is lowercased");
        assertEquals(1, SboGameplay.of(raw.manifest()).gameProperties().requiredTier());

        String resaved = dir.resolve("ore2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));
        assertEquals(stoneBlock("ore", 1), SboGameplay.of(new SBOParser().parseRaw(Path.of(resaved)).manifest()).gameProperties());
    }

    @Test
    void legacyManifestHasNoMiningData() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params("test:plain", SBOFormat.ObjectType.BLOCK);
        SboGameplay.of(params).withGameProperties(new SboGameplay.GameProperties(2, 1f, true, true, 0, 0,
                false, false, 64, "BLOCKS", true, null, 0)).applyTo(params);
        String out = dir.resolve("plain.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SboGameplay doc = SboGameplay.of(new SBOParser().parseRaw(Path.of(out)).manifest());
        assertFalse(doc.hasTool());
        assertNull(doc.gameProperties().material());
        assertEquals(0, doc.gameProperties().requiredTier());
    }

    @Test
    void parserAcceptsTierNamesAndDropsInvalidSections() throws IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        SboGameplay.ToolData named = SboGameplayCodec.readTool(mapper.readTree("""
                {"tool": {"toolClass": "Axe", "tier": "stone", "speedMultiplier": 0.2,
                          "materials": ["Wood", "wood", " "]}}"""));
        assertNotNull(named);
        assertEquals("axe", named.toolClass());
        assertEquals(1, named.tier());
        assertEquals(List.of("wood"), named.materials(), "materials lowercased, de-duplicated, blanks dropped");

        assertNull(SboGameplayCodec.readTool(mapper.readTree("{}")), "absent section ⇒ null");
        assertNull(SboGameplayCodec.readTool(mapper.readTree("{\"tool\": null}")));
        assertNull(SboGameplayCodec.readTool(mapper.readTree("{\"tool\": {\"toolClass\": \"\"}}")),
                "blank tool class ⇒ section ignored");
        assertNull(SboGameplayCodec.readTool(mapper.readTree(
                "{\"tool\": {\"toolClass\": \"axe\", \"speedMultiplier\": 0}}")));
    }

    @Test
    void resolutionRuleMatchesMaterialAndTier() {
        SboGameplay.ToolData pick = stonePickaxe();
        assertEquals(0.2f, ToolMiningRules.hardnessMultiplier(pick, "stone", 0));
        assertEquals(0.2f, ToolMiningRules.hardnessMultiplier(pick, "ORE", 1), "material match is case-insensitive");
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(pick, "ore", 2), "tier below requiredTier ⇒ no bonus");
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(pick, "wood", 0));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(pick, null, 0));

        assertEquals(0.8f, ToolMiningRules.effectiveHardness(pick, "stone", 0, 4.0f), 1e-6f);
        assertEquals(4.0f, ToolMiningRules.effectiveHardness(null, "stone", 0, 4.0f));
        assertEquals(ToolMiningRules.MIN_EFFECTIVE_HARDNESS,
                ToolMiningRules.effectiveHardness(pick, "stone", 0, 0.2f), "floor applies");
        // A multiplier >= 1 never slows a block down nor bypasses the floor logic.
        SboGameplay.ToolData blunt = new SboGameplay.ToolData("club", 0, 2f, List.of("stone"), null, null);
        assertEquals(4.0f, ToolMiningRules.effectiveHardness(blunt, "stone", 0, 4.0f));
    }

    @Test
    void recordValidation() {
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.ToolData(" ", 0, 1f, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.ToolData("a", -1, 1f, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.ToolData("a", 0, 0f, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.ToolData("a", 0, Float.NaN, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.ToolData("a", 0, 1f, List.of(), 0, null));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.ToolData("a", 0, 1f, List.of(), null, -1f));
        assertEquals(0, stoneBlock(" ", -3).requiredTier(), "negative tier clamps to 0");
        assertNull(stoneBlock(" ", 0).material(), "blank material ⇒ null");
        assertEquals("stone", ToolMiningRules.tierName(1));
        assertEquals("7", ToolMiningRules.tierName(7));
        assertEquals(0, ToolMiningRules.parseTier("Wooden"));
        assertEquals(5, ToolMiningRules.parseTier("5"));
        assertEquals(-1, ToolMiningRules.parseTier("mythril"));
    }
}
