package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.io.ArchiveIO;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiImporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.format.sbui.SbuiWriter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #284 acceptance: documents survive OMUI → SBUI → OMUI without semantic loss, serialization
 * is deterministic, unknown optional data survives, and version/feature negotiation fails
 * clearly. Everything here runs headless — no GL context, no tool or game classes.
 */
@Tag("regression")
class OmuiRoundTripTest {

    @TempDir
    Path tmp;

    @Test
    void screenSurvivesOmuiToSbuiToOmui() throws Exception {
        OmuiArchive pause = UiSamples.pauseMenu();
        byte[] omui = OmuiWriter.write(pause);
        OmuiArchive read = OmuiReader.read(omui).archive();
        assertEquals(pause, read, "OMUI read-back must equal the in-memory document");

        var export = SbuiExporter.export(read, SbuiExporter.Options.shared());
        byte[] sbui = SbuiWriter.write(export.archive());
        var sbuiRead = SbuiReader.read(sbui, SbuiReader.Options.RUNTIME);
        assertEquals(pause, sbuiRead.archive().source(), "SBUI embeds the canonical OMUI verbatim");

        var imported = SbuiImporter.importEditable(sbuiRead.archive());
        OmuiArchive back = imported.document();
        assertNotNull(back.editor().get(SbuiImporter.PROVENANCE_ENTRY), "import records provenance");
        assertEquals(pause, withoutProvenance(back), "import yields the same document plus provenance");
        assertEquals(pause.document(), back.document());
        assertEquals(pause.styles(), back.styles());
        assertEquals(pause.graphs(), back.graphs());
        assertEquals(pause.animations(), back.animations());
        assertEquals(pause.scripts(), back.scripts());
        assertEquals(pause.dependencies(), back.dependencies());

        // And the re-imported document is still a valid, writable OMUI.
        assertEquals(pause, withoutProvenance(OmuiReader.read(OmuiWriter.write(back)).archive()));
    }

    @Test
    void componentSurvivesRoundTrip() throws Exception {
        OmuiArchive button = UiSamples.stoneButton();
        OmuiArchive read = OmuiReader.read(OmuiWriter.write(button)).archive();
        assertEquals(button, read);
        var sbui = SbuiReader.read(SbuiWriter.write(SbuiExporter.export(read, SbuiExporter.Options.shared()).archive()),
                SbuiReader.Options.RUNTIME);
        assertEquals(button, withoutProvenance(SbuiImporter.importEditable(sbui.archive()).document()));
        assertNotNull(read.document().component());
        assertEquals("icon_box", read.document().component().slots().getFirst().host());
    }

    @Test
    void serializationIsDeterministic() throws Exception {
        byte[] first = OmuiWriter.write(UiSamples.pauseMenu());
        byte[] second = OmuiWriter.write(UiSamples.pauseMenu());
        assertArrayEquals(first, second, "same document, same bytes");
        byte[] reread = OmuiWriter.write(OmuiReader.read(first).archive());
        assertArrayEquals(first, reread, "read → write is a fixed point");

        byte[] sbuiA = SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), SbuiExporter.Options.shared()).archive());
        byte[] sbuiB = SbuiWriter.write(SbuiReader.read(sbuiA, SbuiReader.Options.RUNTIME).archive());
        assertArrayEquals(sbuiA, sbuiB);
    }

    @Test
    void canonicalFormIgnoresConstructionOrder() throws Exception {
        OmuiArchive pause = UiSamples.pauseMenu();
        UiNode root = pause.document().root();
        UiNode reordered = new UiNode(root.id(), root.name(), root.type(), root.typeVersion(),
                List.of("b", "a", "b"), root.props(), new java.util.TreeMap<>(root.style()).descendingMap(),
                root.dataSource(), root.bindings(), root.instance(), root.children(), root.unknown());
        UiNode sorted = new UiNode(root.id(), root.name(), root.type(), root.typeVersion(),
                List.of("a", "b"), root.props(), root.style(), root.dataSource(), root.bindings(), root.instance(),
                root.children(), root.unknown());
        assertEquals(sorted, reordered);
        assertArrayEquals(
                OmuiWriter.write(pause.withDocument(new UiDocument(sorted, pause.document().styleSheets(),
                        pause.document().codeBehind(), null, Map.of()))),
                OmuiWriter.write(pause.withDocument(new UiDocument(reordered, pause.document().styleSheets(),
                        pause.document().codeBehind(), null, Map.of()))));
    }

    @Test
    void unknownOptionalFieldsAndEntriesSurviveEditorRoundTrip() throws Exception {
        Map<String, byte[]> entries = rawEntries(OmuiWriter.write(UiSamples.pauseMenu()));
        entries.put("manifest.json", addField(entries.get("manifest.json"), "", "x-futureManifest", UiValue.of("keep")));
        entries.put("document.json", addField(entries.get("document.json"), "root", "x-futureNode",
                new UiValue.Obj(Map.of("depth", UiValue.of(3)))));
        entries.put("styles/pause.uss.json", addField(entries.get("styles/pause.uss.json"), "", "x-futureSheet", UiValue.TRUE));
        entries.put("telemetry/notes.txt", UiSamples.utf8("future entry"));

        OmuiReader.Result read = OmuiReader.read(write(entries));
        assertTrue(read.diagnostics().stream().anyMatch(d -> d.code() == Code.UNKNOWN_FIELD_PRESERVED));
        assertTrue(read.diagnostics().stream().anyMatch(d -> d.code() == Code.UNKNOWN_ENTRY));
        assertEquals(UiValue.of("keep"), read.archive().manifest().unknown().get("x-futureManifest"));
        assertNotNull(read.archive().document().root().unknown().get("x-futureNode"));

        // An editor edit elsewhere keeps all of it.
        OmuiArchive edited = read.archive().withScript("pause", UiSamples.PAUSE_LUA + "-- edited\n");
        Map<String, byte[]> after = rawEntries(OmuiWriter.write(edited));
        assertTrue(new String(after.get("manifest.json")).contains("x-futureManifest"));
        assertTrue(new String(after.get("document.json")).contains("x-futureNode"));
        assertTrue(new String(after.get("styles/pause.uss.json")).contains("x-futureSheet"));
        assertArrayEquals(UiSamples.utf8("future entry"), after.get("telemetry/notes.txt"));
    }

    @Test
    void newerMinorReadsWithWarningAndKeepsItsVersion() throws Exception {
        Map<String, byte[]> entries = rawEntries(OmuiWriter.write(UiSamples.stoneButton()));
        entries.put("manifest.json", setField(entries.get("manifest.json"), "schemaVersion", UiValue.of("1.7")));
        OmuiReader.Result read = OmuiReader.read(write(entries));
        assertTrue(read.diagnostics().stream().anyMatch(d -> d.code() == Code.NEWER_MINOR_VERSION));
        assertEquals(new SchemaVersion(1, 7), read.archive().manifest().schemaVersion());
        assertTrue(new String(rawEntries(OmuiWriter.write(read.archive())).get("manifest.json")).contains("\"1.7\""));
    }

    @Test
    void newerMajorFailsWithStructuredDiagnostic() throws Exception {
        Map<String, byte[]> entries = rawEntries(OmuiWriter.write(UiSamples.stoneButton()));
        entries.put("manifest.json", setField(entries.get("manifest.json"), "schemaVersion", UiValue.of("2.0")));
        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiReader.read(write(entries)));
        assertTrue(e.has(Code.UNSUPPORTED_SCHEMA_VERSION), e.getMessage());
    }

    @Test
    void unsupportedRequiredFeatureFailsClearly() throws Exception {
        Map<String, byte[]> entries = rawEntries(OmuiWriter.write(UiSamples.stoneButton()));
        entries.put("manifest.json", setField(entries.get("manifest.json"), "requires",
                new UiValue.Arr(List.of(UiValue.of("grid-layout")))));
        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiReader.read(write(entries)));
        assertTrue(e.has(Code.UNSUPPORTED_REQUIRED_FEATURE));
        assertTrue(e.getMessage().contains("grid-layout"));
    }

    @Test
    void readerNeedsNoGlOrToolClasses() throws Exception {
        // The engine format packages must not drag in GL, ImGui or game classes.
        for (String pkg : List.of("omui", "omui/io", "sbui", "uiarchive")) {
            Path dir = Path.of("src/main/java/com/openmason/engine/format", pkg);
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String src = Files.readString(f);
                    assertFalse(src.contains("org.lwjgl"), f + " imports LWJGL");
                    assertFalse(src.contains("imgui"), f + " imports ImGui");
                    assertFalse(src.contains("com.stonebreak"), f + " imports game code");
                    assertFalse(src.contains("ObjectInputStream") || src.contains("Class.forName"),
                            f + " must never instantiate classes from an asset");
                }
            }
        }
    }

    @Test
    void atomicSaveReplacesWholeFile() throws Exception {
        Path target = tmp.resolve("pause.omui");
        OmuiWriter.save(UiSamples.stoneButton(), target);
        byte[] before = Files.readAllBytes(target);
        OmuiWriter.save(UiSamples.pauseMenu(), target);
        assertArrayEquals(OmuiWriter.write(UiSamples.pauseMenu()), Files.readAllBytes(target));
        assertFalse(java.util.Arrays.equals(before, Files.readAllBytes(target)));
        try (var listing = Files.list(tmp)) {
            assertEquals(1, listing.count(), "no temporary files left behind");
        }
    }

    // ── helpers ──

    static OmuiArchive withoutProvenance(OmuiArchive a) {
        Map<String, UiBytes> editor = new LinkedHashMap<>(a.editor());
        editor.remove(SbuiImporter.PROVENANCE_ENTRY);
        return new OmuiArchive(a.manifest(), a.document(), a.styles(), a.graphs(), a.animations(), a.stateMachines(), a.scripts(),
                a.dependencies(), a.assets(), editor, a.extraEntries());
    }

    static Map<String, byte[]> rawEntries(byte[] archive) {
        UiDiagnostics d = new UiDiagnostics();
        Map<String, byte[]> entries = ArchiveIO.read(archive, ArchiveLimits.DEFAULT, d);
        assertFalse(d.hasErrors(), d.list().toString());
        return new LinkedHashMap<>(entries);
    }

    static byte[] write(Map<String, byte[]> entries) {
        Map<String, UiBytes> out = new LinkedHashMap<>();
        entries.forEach((k, v) -> out.put(k, UiBytes.copyOf(v)));
        return ArchiveIO.write(out);
    }

    /** Adds {@code key} to the object at {@code path} (dot-separated, "" = root). */
    static byte[] addField(byte[] json, String path, String key, UiValue value) {
        UiValue root = CanonicalJson.parse(json, "test", new UiDiagnostics());
        return CanonicalJson.write(add(root, path.isEmpty() ? new String[0] : path.split("\\."), 0, key, value));
    }

    static byte[] setField(byte[] json, String key, UiValue value) {
        return addField(json, "", key, value);
    }

    private static UiValue add(UiValue node, String[] path, int i, String key, UiValue value) {
        UiValue.Obj obj = (UiValue.Obj) node;
        Map<String, UiValue> f = new LinkedHashMap<>(obj.fields());
        if (i == path.length) {
            f.put(key, value);
        } else {
            f.put(path[i], add(f.get(path[i]), path, i + 1, key, value));
        }
        return new UiValue.Obj(f);
    }
}
