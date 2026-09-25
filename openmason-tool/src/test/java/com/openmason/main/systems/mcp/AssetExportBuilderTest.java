package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbe.SBEParser;
import com.openmason.engine.format.sbe.SBESerializer;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.format.sbo.SBOSerializer;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON → ExportParameters → serializer → parser round trips, headless. */
class AssetExportBuilderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private Path omo;

    @BeforeEach
    void blankCubeOnDisk() {
        ModelState state = new ModelState();
        ModelOperationService ops = new ModelOperationService(state, new StatusService(), null);
        ops.newModel();
        omo = tmp.resolve("Cube.omo");
        assertTrue(ops.saveModelToPath(omo.toString()));
    }

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    private Path resolve(String raw) {
        Path p = Path.of(raw);
        return p.isAbsolute() ? p : tmp.resolve(raw);
    }

    @Test
    void sboDefaultsMirrorTheExportWindow() {
        SBOFormat.ExportParameters p = AssetExportBuilder.sbo(null, omo, "My Block.omo", this::resolve);
        assertEquals("My Block", p.getObjectName());
        assertEquals("stonebreak:my_block", p.getObjectId());
        assertEquals(SBOFormat.ObjectType.BLOCK, p.getObjectType());
        assertEquals("default", p.getObjectPack());
        assertFalse(p.getAuthor().isBlank());
        assertTrue(p.isValid(), p.getValidationError());
        assertNotNull(p.getGameProperties(), "blocks always get game properties");
        assertTrue(p.getGameProperties().numericId() > 0, "next free block id is suggested");
        assertTrue(p.getGameProperties().solid());
        assertEquals("BLOCKS", p.getGameProperties().categoryOrDefault());
        assertFalse(p.isStatesEnabled());
        assertNull(p.getDrops());
    }

    @Test
    void sboRoundTripThroughSerializerAndParser() throws Exception {
        JsonNode params = json("""
                {"objectId":"stonebreak:test_lamp","objectName":"Test Lamp","objectType":"block",
                 "author":"tester","description":"a lamp",
                 "gameProperties":{"numericId":9001,"hardness":2.5,"renderLayer":"cutout","transparent":true},
                 "states":[{"name":"off"},{"name":"on","loop":"loop"}],"defaultState":"on",
                 "sounds":[{"event":"break","resource":"/sounds/GrassWalk.wav","volume":0.5,"variation":true}],
                 "drops":{"drops":[{"objectId":"stonebreak:test_lamp","min":1,"max":2,"chance":0.75}],
                          "toolOverrides":[{"tool":"stonebreak:pickaxe","drops":[]}]}}
                """);
        SBOFormat.ExportParameters p = AssetExportBuilder.sbo(params, omo, "ignored", this::resolve);
        assertTrue(p.isValid(), p.getValidationError());
        assertEquals(2, p.getStates().size());
        assertEquals(omo.toString(), p.getStates().get(0).sourcePath(), "states default to the model's omo");
        assertEquals(SBOFormat.LoopMode.LOOP, p.getStates().get(1).loopMode());

        Path out = tmp.resolve("SB_Test_Lamp.sbo");
        assertTrue(new SBOSerializer().export(p, omo, out.toString()));
        SBOFormat.Document d = new SBOParser().parseRaw(out).manifest();
        assertEquals("stonebreak:test_lamp", d.objectId());
        assertEquals("block", d.objectType());
        assertEquals("a lamp", d.description());
        assertEquals(9001, d.gameProperties().numericId());
        assertEquals(2.5f, d.gameProperties().hardness(), 1e-6);
        assertEquals("CUTOUT", d.gameProperties().renderLayerOrDefault());
        assertTrue(d.gameProperties().transparent());
        assertEquals(2, d.states().size());
        assertEquals("on", d.defaultStateName());
        assertEquals(1, d.sounds().sounds().size());
        assertEquals("/sounds/GrassWalk.wav", d.sounds().sounds().get(0).resourcePath());
        assertEquals(1, d.drops().drops().size());
        assertEquals(0.75f, d.drops().drops().get(0).chance(), 1e-6);
        assertEquals(1, d.drops().toolOverrides().size());

        Map<String, Object> described = AssetExportBuilder.describe(d);
        assertEquals("stonebreak:test_lamp", described.get("objectId"));
        assertNotNull(described.get("gameProperties"));
        assertNotNull(described.get("drops"));
    }

    @Test
    void sbeRoundTripThroughSerializerAndParser() throws Exception {
        Path override = tmp.resolve("Variant.omo");
        Files.copy(omo, override);
        JsonNode params = json("""
                {"objectName":"Test Goose","entityType":"mob","author":"tester",
                 "states":[{"name":"idle"},{"name":"swim","model":"Variant.omo"}],
                 "variants":[{"name":"white"},{"name":"grey","model":"Variant.omo"}]}
                """);
        SBEFormat.ExportParameters p = AssetExportBuilder.sbe(params, "ignored", this::resolve);
        assertTrue(p.isValid(), p.getValidationError());
        assertNull(AssetExportBuilder.validateSbeBindings(p));
        assertEquals("stonebreak:test_goose", p.getObjectId());

        Path out = tmp.resolve("SB_Test_Goose.sbe");
        assertTrue(new SBESerializer().export(p, omo, out.toString()));
        SBEFormat.Document d = new SBEParser().parseRaw(out).manifest();
        assertEquals("stonebreak:test_goose", d.objectId());
        assertEquals("mob", d.entityType());
        assertEquals(2, d.states().size());
        assertNotNull(d.states().get(1).modelOverride());
        assertEquals(2, d.variants().size());
        assertNotNull(AssetExportBuilder.describe(d).get("variants"));
    }

    @Test
    void sbeBindingValidationCatchesDuplicates() throws Exception {
        SBEFormat.ExportParameters p = AssetExportBuilder.sbe(
                json("{\"states\":[{\"name\":\"a\"},{\"name\":\"a\"}]}"), "x", this::resolve);
        assertEquals("Duplicate state: 'a'", AssetExportBuilder.validateSbeBindings(p));
    }

    @Test
    void sboPatchChangesOnlyNamedFields() throws Exception {
        SBOFormat.ExportParameters p = AssetExportBuilder.sbo(
                json("{\"objectId\":\"stonebreak:p\",\"objectName\":\"P\",\"author\":\"t\","
                        + "\"gameProperties\":{\"numericId\":9002}}"), omo, "x", this::resolve);
        Path out = tmp.resolve("p.sbo");
        assertTrue(new SBOSerializer().export(p, omo, out.toString()));
        SBOFormat.Document base = new SBOParser().parseRaw(out).manifest();

        SBOFormat.Document patched = AssetExportBuilder.patchSbo(base, json("""
                {"description":"patched","gameProperties":{"hardness":7},"fuel":800,
                 "sounds":[{"event":"step","resource":"/sounds/x.wav"}],
                 "drops":[]}
                """));
        assertEquals("patched", patched.description());
        assertEquals(base.objectId(), patched.objectId());
        assertEquals(9002, patched.gameProperties().numericId(), "unnamed fields keep their value");
        assertEquals(7f, patched.gameProperties().hardness(), 1e-6);
        assertEquals(800, patched.fuel().burnTicks());
        assertEquals(1, patched.sounds().sounds().size());
        assertNotNull(patched.drops());
        assertTrue(patched.drops().drops().isEmpty(), "\"drops\": [] means drops nothing");

        SBOFormat.Document cleared = AssetExportBuilder.patchSbo(patched,
                json("{\"gameProperties\":null,\"drops\":null,\"fuel\":null}"));
        assertNull(cleared.gameProperties());
        assertNull(cleared.drops());
        assertNull(cleared.fuel());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AssetExportBuilder.patchSbo(base,
                        json("{\"sounds\":[{\"event\":\"hit\",\"filename\":\"sounds/nope.wav\"}]}")));
        assertTrue(e.getMessage().contains("not embedded"));
    }

    @Test
    void spriteDefaultsMatchTheTextureOnlyPayload() throws Exception {
        SBOFormat.GameProperties gp = AssetExportBuilder.spriteGameProperties(null, 1234);
        assertEquals(1234, gp.numericId());
        assertEquals(0f, gp.hardness(), 1e-6);
        assertFalse(gp.solid());
        assertTrue(gp.breakable());
        assertEquals(-1, gp.atlasX());
        assertEquals(-1, gp.atlasY());
        assertEquals("CUTOUT", gp.renderLayerOrDefault());
        assertTrue(gp.transparent());
        assertEquals(64, gp.maxStackSize());
        assertEquals("TOOLS", gp.categoryOrDefault());
        assertFalse(gp.placeable());

        SBOFormat.GameProperties patched = AssetExportBuilder.spriteGameProperties(
                json("{\"maxStackSize\":1,\"category\":\"food\"}"), 1234);
        assertEquals(1, patched.maxStackSize());
        assertEquals("FOOD", patched.categoryOrDefault());
        assertEquals("CUTOUT", patched.renderLayerOrDefault(), "unnamed fields keep the sprite default");
    }

    @Test
    void textureOnlyItemRoundTripsWithSpriteDefaults() throws Exception {
        Path omt = tmp.resolve("Sword.omt");
        Files.write(omt, new byte[]{1, 2, 3, 4});
        SBOFormat.ExportParameters p = new SBOFormat.ExportParameters();
        p.setObjectId("stonebreak:test_sword");
        p.setObjectName("Test Sword");
        p.setObjectType(SBOFormat.ObjectType.ITEM);
        p.setObjectPack("default");
        p.setAuthor("tester");
        p.setGameProperties(AssetExportBuilder.spriteGameProperties(null, 4321));
        assertTrue(p.isValid(), p.getValidationError());

        Path out = tmp.resolve("test_sword.sbo");
        assertTrue(new SBOSerializer().exportTexture(p, omt, out.toString()));

        SBOFormat.Document doc = new SBOParser().parseRaw(out).manifest();
        assertEquals("item", doc.objectType());
        assertNull(doc.omoFilename(), "texture-only SBOs carry no model");
        assertNotNull(doc.textureFilename());
        assertEquals(4321, doc.gameProperties().numericId());
        assertEquals("CUTOUT", doc.gameProperties().renderLayerOrDefault());
        assertFalse(doc.gameProperties().placeable());
    }

    @Test
    void textureSourceDefaultsMirrorTheTextureContextWindow() throws Exception {
        Path omt = tmp.resolve("Iron Sword.omt");
        Files.write(omt, new byte[]{1});
        SBOFormat.ExportParameters p = AssetExportBuilder.sboTexture(
                json("{\"source\":\"texture\"}"), omt, omt.getFileName().toString(), this::resolve);
        assertEquals("Iron Sword", p.getObjectName());
        assertEquals("stonebreak:iron_sword", p.getObjectId());
        assertEquals(SBOFormat.ObjectType.ITEM, p.getObjectType());
        assertNotNull(p.getGameProperties(), "items always get game properties");
        assertTrue(p.getGameProperties().numericId() > 0, "next free item id is suggested");
        assertEquals(AssetExportBuilder.spriteGameProperties(null, p.getGameProperties().numericId()),
                p.getGameProperties());

        SBOFormat.ExportParameters states = AssetExportBuilder.sboTexture(
                json("{\"states\":[{\"name\":\"idle\"},{\"name\":\"alt\",\"omt\":\"Alt.omt\"}]}"),
                omt, "Iron Sword.omt", this::resolve);
        assertEquals(omt.toString(), states.getStates().get(0).sourcePath());
        assertEquals(tmp.resolve("Alt.omt").toString(), states.getStates().get(1).sourcePath());
    }

    @Test
    void textureSourceSelectionAndRefusals() throws Exception {
        assertFalse(AssetExportBuilder.isTextureSource(null));
        assertFalse(AssetExportBuilder.isTextureSource(json("{}")));
        assertFalse(AssetExportBuilder.isTextureSource(json("{\"source\":\"model\"}")));
        assertTrue(AssetExportBuilder.isTextureSource(json("{\"source\":\"Texture\"}")));
        assertTrue(AssetExportBuilder.isTextureSource(json("{\"omt\":\"a.omt\"}")), "an omt implies texture");
        assertThrows(IllegalArgumentException.class,
                () -> AssetExportBuilder.isTextureSource(json("{\"source\":\"model\",\"omt\":\"a.omt\"}")));
        assertThrows(IllegalArgumentException.class,
                () -> AssetExportBuilder.isTextureSource(json("{\"source\":\"sprite\"}")));

        Path omt = tmp.resolve("t.omt");
        for (String locked : new String[]{"block", "entity"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> AssetExportBuilder.sboTexture(json("{\"objectType\":\"" + locked + "\"}"),
                            omt, "t.omt", this::resolve));
            assertTrue(e.getMessage().contains("needs a model"), e.getMessage());
        }
        assertEquals(SBOFormat.ObjectType.DECORATION, AssetExportBuilder.sboTexture(
                json("{\"objectType\":\"decoration\"}"), omt, "t.omt", this::resolve).getObjectType());
        assertThrows(IllegalArgumentException.class, () -> AssetExportBuilder.sboTexture(
                json("{\"states\":[{\"name\":\"a\",\"clip\":\"x.omanim\"}]}"), omt, "t.omt", this::resolve));
        assertThrows(IllegalArgumentException.class, () -> AssetExportBuilder.sboTexture(
                json("{\"states\":[{\"name\":\"a\",\"omo\":\"x.omo\"}]}"), omt, "t.omt", this::resolve));
    }

    @Test
    void wrongExtensionOrMissingSourceFileIsRefusedBeforeSerializing() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AssetExportBuilder.sbo(
                json("{\"states\":[{\"name\":\"a\",\"clip\":\"missing.omanim\"}]}"), omo, "x",
                raw -> {
                    throw new IllegalArgumentException("no_such_file: " + raw);
                }));
    }
}
