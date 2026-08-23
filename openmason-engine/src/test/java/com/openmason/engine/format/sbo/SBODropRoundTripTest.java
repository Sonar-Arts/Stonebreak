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
 * Round-trip coverage for the SBO 1.8 {@code drops} section: export, re-parse,
 * editor-style re-save, the "present but empty" (drops nothing) case, and
 * back-compat for manifests without the section. Mirrors {@link SBOSoundRoundTripTest}.
 */
@Tag("regression")
class SBODropRoundTripTest {

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

    private static SBOFormat.DropData clayTable() {
        return new SBOFormat.DropData(
                List.of(new SBOFormat.DropEntry("test:clay_chunk", 3, 4, 1f),
                        new SBOFormat.DropEntry("test:banana", 1, 1, 0.05f)),
                List.of(new SBOFormat.ToolDropOverride("test:silk_shovel",
                                List.of(SBOFormat.DropEntry.of("test:clay", 1))),
                        new SBOFormat.ToolDropOverride("test:cursed_shovel", List.of())));
    }

    @Test
    void exportAndReparseDrops() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params();
        params.setDrops(clayTable());

        String out = dir.resolve("clay.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOFormat.Document doc = new SBOParser().parseRaw(Path.of(out)).manifest();
        assertEquals(SBOFormat.FORMAT_VERSION, doc.version());
        assertTrue(doc.hasDrops());
        assertEquals(clayTable(), doc.drops(), "drop table survives export → parse byte-for-byte");

        // Resolution helper: by-hand → defaults; matching tool → override; unknown tool → defaults.
        assertEquals(2, doc.drops().dropsFor(null).size());
        assertEquals(List.of(SBOFormat.DropEntry.of("test:clay", 1)), doc.drops().dropsFor("test:silk_shovel"));
        assertTrue(doc.drops().dropsFor("test:cursed_shovel").isEmpty());
        assertEquals(2, doc.drops().dropsFor("test:pickaxe").size());
    }

    @Test
    void editorResavePreservesDrops() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params();
        params.setDrops(clayTable());
        String out = dir.resolve("clay.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        String resaved = dir.resolve("clay2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));
        assertEquals(clayTable(), new SBOParser().parseRaw(Path.of(resaved)).manifest().drops());
    }

    @Test
    void emptyTableMeansDropsNothingAndIsPreserved() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = params();
        params.setDrops(SBOFormat.DropData.nothing());
        String out = dir.resolve("glass.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOFormat.Document doc = new SBOParser().parseRaw(Path.of(out)).manifest();
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
        assertFalse(raw.manifest().hasDrops());
        assertNull(raw.manifest().drops());

        String resaved = dir.resolve("plain2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));
        assertFalse(new SBOParser().parseRaw(Path.of(resaved)).manifest().hasDrops());
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
        SBOFormat.DropData data = SBOParser.parseDrops(root);
        assertNotNull(data);
        assertEquals(List.of(new SBOFormat.DropEntry("test:ok", 1, 2, 1f)), data.drops());
        assertEquals(1, data.toolOverrides().size());
        assertEquals("test:axe", data.toolOverrides().get(0).toolObjectId());
        assertTrue(data.toolOverrides().get(0).drops().isEmpty());

        assertNull(SBOParser.parseDrops(mapper.readTree("{}")), "absent section ⇒ null");
        assertNull(SBOParser.parseDrops(mapper.readTree("{\"drops\": null}")));
    }

    @Test
    void recordValidation() {
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.DropEntry("", 1, 1, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.DropEntry("a:b", -1, 1, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.DropEntry("a:b", 2, 1, 1f));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.DropEntry("a:b", 1, 1, 1.01f));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.ToolDropOverride(" ", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.DropData(List.of(), List.of(
                new SBOFormat.ToolDropOverride("a:t", List.of()),
                new SBOFormat.ToolDropOverride("a:t", List.of()))));
        // zero-count lines are legal (a "may drop nothing" line with min 0)
        assertDoesNotThrow(() -> new SBOFormat.DropEntry("a:b", 0, 2, 1f));
    }
}
