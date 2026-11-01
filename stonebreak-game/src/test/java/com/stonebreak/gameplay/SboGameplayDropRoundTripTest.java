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
 * Round-trip coverage for the SBO 1.8 {@code drops} section: export, re-parse,
 * editor-style re-save, the "present but empty" (drops nothing) case, and
 * back-compat for manifests without the section. Mirrors {@link SBOSoundRoundTripTest}.
 */
@Tag("regression")
class SboGameplayDropRoundTripTest {

    @TempDir
    Path dir;

    private Path writeFile(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private SBOFormat.ExportParameters params() {
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        params.setObjectId("test:clay");
        params.setObjectName("Clay");
        params.setObjectType(SBOFormat.ObjectType.BLOCK);
        params.setObjectPack("test");
        params.setAuthor("junit");
        return params;
    }

    private static SboGameplay.DropData clayTable() {
        return new SboGameplay.DropData(
                List.of(new SboGameplay.DropEntry("test:clay_chunk", 3, 4, 1f),
                        new SboGameplay.DropEntry("test:banana", 1, 1, 0.05f)),
                List.of(new SboGameplay.ToolDropOverride("test:silk_shovel",
                                List.of(SboGameplay.DropEntry.of("test:clay", 1))),
                        new SboGameplay.ToolDropOverride("test:cursed_shovel", List.of())));
    }

    @Test
    void exportAndReparseDrops() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params();
        SboGameplay.of(params).withDrops(clayTable()).applyTo(params);

        String out = dir.resolve("clay.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOFormat.Document manifest = new SBOParser().parseRaw(Path.of(out)).manifest();
        assertEquals(SBOFormat.FORMAT_VERSION, manifest.version());
        assertTrue(manifest.extensions().has(SboGameplayCodec.KEY), "new files carry the extension");
        SboGameplay doc = SboGameplay.of(manifest);
        assertTrue(doc.hasDrops());
        assertEquals(clayTable(), doc.drops(), "drop table survives export → parse byte-for-byte");

        // Resolution helper: by-hand → defaults; matching tool → override; unknown tool → defaults.
        assertEquals(2, doc.drops().dropsFor(null).size());
        assertEquals(List.of(SboGameplay.DropEntry.of("test:clay", 1)), doc.drops().dropsFor("test:silk_shovel"));
        assertTrue(doc.drops().dropsFor("test:cursed_shovel").isEmpty());
        assertEquals(2, doc.drops().dropsFor("test:pickaxe").size());
    }

    @Test
    void editorResavePreservesDrops() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params();
        SboGameplay.of(params).withDrops(clayTable()).applyTo(params);
        String out = dir.resolve("clay.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        String resaved = dir.resolve("clay2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));
        assertEquals(clayTable(), SboGameplay.of(new SBOParser().parseRaw(Path.of(resaved)).manifest()).drops());
    }

    @Test
    void emptyTableMeansDropsNothingAndIsPreserved() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params();
        SboGameplay.of(params).withDrops(SboGameplay.DropData.nothing()).applyTo(params);
        String out = dir.resolve("glass.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SboGameplay doc = SboGameplay.of(new SBOParser().parseRaw(Path.of(out)).manifest());
        assertTrue(doc.hasDrops(), "an explicitly empty table must not collapse to 'absent'");
        assertTrue(doc.drops().isEmpty());
        assertTrue(doc.drops().dropsFor(null).isEmpty());
    }

    @Test
    void dropLessSboStillRoundTripsWithNoDrops() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        String out = dir.resolve("plain.sbo").toString();
        assertTrue(new SBOSerializer().export(params(), omo, out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        assertFalse(SboGameplay.of(raw.manifest()).hasDrops());
        assertNull(SboGameplay.of(raw.manifest()).drops());

        String resaved = dir.resolve("plain2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));
        assertFalse(SboGameplay.of(new SBOParser().parseRaw(Path.of(resaved)).manifest()).hasDrops());
    }

    @Test
    void parserSkipsInvalidLinesButKeepsSection() throws IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree("""
                {"drops": {"default": [
                    {"objectId": "test:ok", "min": 1, "max": 2},
                    {"objectId": "", "min": 1},
                    {"objectId": "test:bad_range", "min": 3, "max": 1},
                    {"objectId": "test:bad_chance", "chance": 1.5}
                ], "byTool": [
                    {"tool": "", "drops": []},
                    {"tool": "test:axe"}
                ]}}""");
        SboGameplay.DropData data = SboGameplayCodec.readDrops(root);
        assertNotNull(data);
        assertEquals(List.of(new SboGameplay.DropEntry("test:ok", 1, 2, 1f)), data.drops());
        assertEquals(1, data.toolOverrides().size());
        assertEquals("test:axe", data.toolOverrides().get(0).toolObjectId());
        assertTrue(data.toolOverrides().get(0).drops().isEmpty());

        assertNull(SboGameplayCodec.readDrops(mapper.readTree("{}")), "absent section ⇒ null");
        assertNull(SboGameplayCodec.readDrops(mapper.readTree("{\"drops\": null}")));
    }

    @Test
    void recordValidation() {
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.DropEntry("", 1, 1, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.DropEntry("a:b", -1, 1, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.DropEntry("a:b", 2, 1, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.DropEntry("a:b", 1, 1, 1.01f));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.ToolDropOverride(" ", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SboGameplay.DropData(List.of(), List.of(
                new SboGameplay.ToolDropOverride("a:t", List.of()),
                new SboGameplay.ToolDropOverride("a:t", List.of()))));
        // zero-count lines are legal (a "may drop nothing" line with min 0)
        assertDoesNotThrow(() -> new SboGameplay.DropEntry("a:b", 0, 2, 1f));
    }
}
