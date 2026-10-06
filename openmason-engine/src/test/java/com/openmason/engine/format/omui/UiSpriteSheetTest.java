package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiSpriteSheet.Fill;
import com.openmason.engine.format.omui.UiSpriteSheet.Frame;
import com.openmason.engine.format.omui.UiSpriteSheet.Sampling;
import com.openmason.engine.format.omui.UiSpriteSheet.ScaleMode;
import com.openmason.engine.format.omui.UiSpriteSheet.Skin;
import com.openmason.engine.format.omui.UiSpriteSheet.Slice;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;
import com.openmason.engine.format.omui.io.SpriteSheetCodec;
import com.openmason.engine.ui.assets.DependencyRefs;
import com.openmason.engine.ui.assets.SpriteFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code .sprites.json} wire shape, its checks, and sprite references in documents (#294). */
class UiSpriteSheetTest {

    private static UiSpriteSheet rich() {
        Sprite button = Sprite.of("button", 0, 0, 16, 8).withSlice(new Slice(3, 2, 3, 2), Fill.TILE, Fill.HIDDEN)
                .withLogicalSize(32, 16).withPivot(0, 1).withLook(ScaleMode.NINE_SLICE, Sampling.LINEAR, "#FF8000C0", 0.5);
        Sprite hover = Sprite.of("button_hover", 0, 8, 16, 8);
        Sprite spin = Sprite.of("spin", 16, 0, 8, 8).withFrames(List.of(new Frame(16, 0, 0.1), new Frame(24, 0, 0.25)),
                LoopMode.PING_PONG);
        return new UiSpriteSheet("t:tex/ui", 32, 16, List.of(spin, button, hover),
                List.of(new Skin("stone", "button", "button_hover", null, null, null, Map.of())),
                Map.of("x-custom", UiValue.of("kept")));
    }

    @Test
    void roundTripIsCanonicalAndAFixedPoint() throws Exception {
        UiSpriteSheet sheet = rich();
        byte[] bytes = SpriteSheetCodec.write(sheet);
        UiSpriteSheet back = SpriteSheetCodec.read(bytes, "s");
        assertEquals(sheet, back);
        assertArrayEquals(bytes, SpriteSheetCodec.write(back), "re-serialization is a fixed point");
        assertEquals(List.of("button", "button_hover", "spin"), back.sprites().stream().map(Sprite::name).toList(),
                "sprites are written in name order");
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("{\n  \"format\": \"omui-sprites\",\n  \"version\": 1,"), text);
        assertTrue(text.contains("\"x-custom\": \"kept\""), "unknown fields are preserved");
    }

    /**
     * The conformance fixture a non-Java reader must reproduce: {@code ui/omui/stone.sprites.json}
     * (regions, slice, pivot, logical size, fill, sampling, tint, opacity, frames, skin, an unknown
     * field). Regenerate with {@code -Dui.fixtures.write=true}.
     */
    @Test
    void pinnedFixtureIsReproducedByteForByte() throws Exception {
        java.nio.file.Path file = java.nio.file.Path.of("src/test/resources/ui/omui/stone.sprites.json");
        byte[] bytes = SpriteSheetCodec.write(rich());
        if (Boolean.getBoolean("ui.fixtures.write")) {
            java.nio.file.Files.write(file, bytes);
            return;
        }
        byte[] pinned;
        try (var in = UiSpriteSheetTest.class.getResourceAsStream("/ui/omui/stone.sprites.json")) {
            assertNotNull(in, "missing fixture (run with -Dui.fixtures.write=true)");
            pinned = in.readAllBytes();
        }
        assertArrayEquals(pinned, bytes);
        assertEquals(rich(), SpriteSheetCodec.read(pinned, "stone.sprites.json"));
    }

    @Test
    void defaultsAreOmitted() {
        UiSpriteSheet plain = new UiSpriteSheet("t:tex/ui", 8, 8, List.of(Sprite.of("a", 0, 0, 8, 8)), List.of(), Map.of());
        String text = new String(SpriteSheetCodec.write(plain), StandardCharsets.UTF_8);
        for (String absent : List.of("pivot", "slice", "edges", "center", "scale", "sampling", "tint", "opacity",
                "frames", "loop", "logical", "skins")) {
            assertFalse(text.contains(absent), absent + " is omitted at its default:\n" + text);
        }
        assertEquals(ScaleMode.STRETCH, plain.sprites().getFirst().effectiveScale());
        assertEquals(ScaleMode.NINE_SLICE, plain.sprites().getFirst()
                .withSlice(new Slice(1, 1, 1, 1), null, null).effectiveScale(), "a sliced sprite defaults to nine-slice");
    }

    @Test
    void structuralProblemsAreReported() {
        String json = """
                {"format": "omui-sprites", "version": 1, "texture": "t:tex/ui", "width": 8, "height": 8,
                 "sprites": [
                   {"name": "a", "x": 0, "y": 0, "w": 4, "h": 4, "tint": "red", "edges": "hidden"},
                   {"name": "a", "x": 0, "y": 0, "w": 0, "h": 4},
                   {"name": "9bad", "x": 0, "y": 0, "w": 1, "h": 1, "frames": [{"x": 0, "y": 0, "duration": 0}]}],
                 "skins": [{"name": "s", "normal": "nope"}]}
                """;
        UiFormatException e = assertThrows(UiFormatException.class,
                () -> SpriteSheetCodec.read(json.getBytes(StandardCharsets.UTF_8), "s"));
        assertTrue(e.has(Code.DUPLICATE_ID), e::toString);
        assertTrue(e.has(Code.INVALID_ID), "names are local ids");
        assertTrue(e.has(Code.UNRESOLVED_REFERENCE), "a skin names an existing sprite");
        assertTrue(e.has(Code.INVALID_VALUE), "w >= 1, colour syntax, duration > 0, edges cannot be hidden");
        String newer = "{\"format\": \"omui-sprites\", \"version\": 2, \"texture\": \"t:x/y\", \"width\": 1, \"height\": 1}";
        assertTrue(assertThrows(UiFormatException.class, () -> SpriteSheetCodec.read(
                newer.getBytes(StandardCharsets.UTF_8), "s")).has(Code.UNSUPPORTED_SCHEMA_VERSION));
    }

    @Test
    void resizedTextureRevalidatesRegionsInsteadOfSamplingOutOfBounds() {
        UiSpriteSheet sheet = rich();
        UiSpriteSheets.Check ok = UiSpriteSheets.check(sheet, 32, 16, "s");
        assertTrue(ok.ok(), ok.diagnostics()::toString);
        assertTrue(ok.diagnostics().isEmpty());

        // The texture was cropped to 20x16 in the Texture Editor.
        UiSpriteSheets.Check cropped = UiSpriteSheets.check(sheet, 20, 16, "s");
        assertFalse(cropped.drawable("spin"), "a frame at x=24 now lies outside");
        assertTrue(cropped.drawable("button"), "untouched regions keep drawing");
        assertTrue(cropped.diagnostics().stream().anyMatch(d -> d.code() == Code.TEXTURE_SIZE_CHANGED && !d.isError()
                && d.message().contains("20x16") && d.message().contains("32x16")), cropped.diagnostics()::toString);
        assertTrue(cropped.diagnostics().stream().anyMatch(d -> d.code() == Code.SPRITE_REGION_INVALID
                && d.pointer().equals("/sprites/2/frames/1")), "the finding points at the offending frame");

        Sprite tooThick = Sprite.of("thin", 0, 0, 4, 4).withSlice(new Slice(3, 0, 3, 0), null, null);
        UiSpriteSheets.Check slice = UiSpriteSheets.check(sheet.withSprites(List.of(tooThick)), 32, 16, "s");
        assertTrue(slice.drawable("thin"));
        assertFalse(slice.sliceUsable("thin"), "insets wider than the region");
        assertTrue(slice.diagnostics().getFirst().message().contains("left + right insets (3 + 3) exceed the width 4"));
    }

    @Test
    void spriteReferencesParseOnlyTheSheetForm() {
        assertEquals(new UiSpriteRef("t:ui/sprites", "btn"), UiSpriteRef.parse("t:ui/sprites#btn"));
        assertNull(UiSpriteRef.parse("t:ui/sprites"));
        assertNull(UiSpriteRef.parse("#btn"));
        assertNull(UiSpriteRef.parse("t:ui/sprites#1bad"));
        assertNull(UiSpriteRef.parse("plain text#with hash"));
        assertNull(UiSpriteRef.parse("t:a#b#c"));
        assertEquals("t:ui/sprites", UiSpriteRef.dependencyId("t:ui/sprites#btn"));
    }

    @Test
    void documentsDeclareSpritesAndReferenceRealSheets() throws Exception {
        byte[] tex = SpriteFixtures.frameTexture();
        byte[] sheet = SpriteFixtures.sheetBytes(SpriteFixtures.frameSheet());
        String ref = SpriteFixtures.SHEET_ID + "#panel";
        OmuiArchive doc = SpriteFixtures.screen("t:ui/s", SpriteFixtures.sharedRows(tex, sheet), ref, 40, 20);

        assertEquals(List.of(UiFeatures.SPRITES), List.copyOf(UiFeatures.used(doc)));
        OmuiArchive reread = OmuiReader.read(OmuiWriter.write(doc)).archive();
        assertEquals(doc, reread, "sprite references survive an OMUI round trip");

        OmuiArchive undeclared = doc.withManifest(new UiManifest(doc.manifest().schemaVersion(),
                doc.manifest().documentId(), doc.manifest().kind(), "", doc.manifest().uiApi(),
                doc.manifest().layoutSemantics(), List.of(), List.of(), List.of(), Map.of()));
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiWriter.write(undeclared))
                .has(Code.UNDECLARED_FEATURE));

        OmuiArchive wrongKind = SpriteFixtures.screen("t:ui/s", SpriteFixtures.sharedRows(tex, sheet),
                SpriteFixtures.TEXTURE_ID + "#panel", 40, 20);
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiWriter.write(wrongKind))
                .has(Code.UNRESOLVED_REFERENCE), "a texture row cannot be addressed as a sheet");
        OmuiArchive noName = SpriteFixtures.screen("t:ui/s", SpriteFixtures.sharedRows(tex, sheet),
                SpriteFixtures.SHEET_ID, 40, 20);
        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiWriter.write(noName));
        assertTrue(e.diagnostics().stream().anyMatch(d -> d.message().contains("#<name>")), e::toString);
    }

    @Test
    void spriteReferencesInDefaultsAlsoNeedTheFeature() {
        byte[] tex = SpriteFixtures.frameTexture();
        byte[] sheet = SpriteFixtures.sheetBytes(SpriteFixtures.frameSheet());
        OmuiArchive doc = SpriteFixtures.screen("t:ui/s", SpriteFixtures.sharedRows(tex, sheet), "none", 40, 20);
        UiGraph graph = new UiGraph("g", List.of(new UiGraph.GraphVariable("icon", ValueType.ASSET,
                UiValue.of(SpriteFixtures.SHEET_ID + "#panel"), Map.of())), List.of(), List.of(), List.of(), Map.of());
        OmuiArchive withGraph = doc.withGraph(graph);
        assertTrue(UiFeatures.used(withGraph).contains(UiFeatures.SPRITES), "graph variable defaults count");
        assertNotNull(UiFeatures.firstSpriteRef(withGraph));
    }

    @Test
    void renamingASheetRewritesItsSpriteReferences() {
        byte[] tex = SpriteFixtures.frameTexture();
        byte[] sheet = SpriteFixtures.sheetBytes(SpriteFixtures.frameSheet());
        OmuiArchive doc = SpriteFixtures.screen("t:ui/s", SpriteFixtures.sharedRows(tex, sheet),
                SpriteFixtures.SHEET_ID + "#panel", 40, 20);
        assertEquals(java.util.Set.of(SpriteFixtures.SHEET_ID, SpriteFixtures.TEXTURE_ID),
                DependencyRefs.reachable(doc, DependencyRefs.referenced(doc)), "the reference keeps both rows in use");

        OmuiArchive renamed = DependencyRefs.remap(doc, Map.of(SpriteFixtures.SHEET_ID, "t:ui/sprites/renamed"));
        UiValue bg = renamed.document().root().children().getFirst().style().get("background-image");
        assertEquals(UiValue.of("t:ui/sprites/renamed#panel"), bg);
        assertEquals(1, DependencyRefs.spriteRefs(renamed).size());
    }
}
