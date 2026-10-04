package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.io.CanonicalJson;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.format.omui.OmuiRoundTripTest.rawEntries;
import static com.openmason.engine.format.omui.OmuiRoundTripTest.write;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions for the #284 review: canonical edge cases, writer/reader symmetry, resource bounds. */
@Tag("regression")
class OmuiHardeningTest {

    @TempDir
    Path tmp;

    @Test
    void nestedFreeFormObjectsEncodeCanonically() throws Exception {
        Map<String, UiValue> inner = new LinkedHashMap<>();
        inner.put("z", UiValue.of(1));
        inner.put("b", UiValue.of(2));
        OmuiArchive button = withRootProps(UiSamples.stoneButton(), Map.of("nested", new UiValue.Obj(inner)));
        byte[] first = OmuiWriter.write(button);
        assertArrayEquals(first, OmuiWriter.write(OmuiReader.read(first).archive()));
        assertTrue(new String(rawEntries(first).get("document.json"), StandardCharsets.UTF_8)
                .matches("(?s).*\"b\": 2,\\s+\"z\": 1.*"));
    }

    @Test
    void negativeZeroAndNanCannotSplitEqualityFromBytes() throws Exception {
        assertEquals(new UiGraph.GraphNode("n", "ui:x", 1, -0.0, 0, Map.of(), Map.of(), Map.of()),
                new UiGraph.GraphNode("n", "ui:x", 1, 0.0, 0, Map.of(), Map.of(), Map.of()));

        OmuiArchive pause = UiSamples.pauseMenu();
        UiAnimationClip clip = pause.animations().get("open");
        UiAnimationClip.AnimTrack t = clip.tracks().getFirst();
        UiAnimationClip nan = new UiAnimationClip("open", clip.duration(), clip.loop(), List.of(
                new UiAnimationClip.AnimTrack(t.target(), t.property(), List.of(
                        new UiAnimationClip.AnimKey(Double.NaN, UiValue.of(0), UiEasing.LINEAR, Map.of())), Map.of())),
                List.of(), Map.of());
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiWriter.write(pause.withAnimation(nan)))
                .has(Code.INVALID_VALUE));
    }

    @Test
    void writerRefusesWhatTheReaderWouldReject() throws Exception {
        OmuiArchive button = UiSamples.stoneButton();
        UiNode r = button.document().root();
        UiNode v0 = new UiNode(r.id(), r.name(), r.type(), 0, r.classes(), r.props(), r.style(), r.dataSource(),
                r.bindings(), r.instance(), r.children(), r.unknown());
        assertThrows(UiFormatException.class, () -> OmuiWriter.write(button.withDocument(
                new UiDocument(v0, button.document().styleSheets(), null, button.document().component(), Map.of()))));

        UiManifest m = button.manifest();
        UiManifest badHost = new UiManifest(m.schemaVersion(), m.documentId(), m.kind(), m.displayName(), m.uiApi(),
                m.layoutSemantics(), m.requires(), List.of(new UiManifest.HostRequirement("stonebreak:x", 0)), List.of(),
                Map.of());
        assertThrows(UiFormatException.class, () -> OmuiWriter.write(button.withManifest(badHost)));

        UiStyleSheet sheet = button.styles().get("stone_button");
        List<UiStyleSheet.StyleRule> rules = new ArrayList<>(sheet.rules());
        rules.add(new UiStyleSheet.StyleRule(".x", Map.of(), List.of(
                new UiStyleSheet.StyleTransition("opacity", -1, UiEasing.LINEAR, 0, Map.of())), Map.of()));
        assertThrows(UiFormatException.class, () -> OmuiWriter.write(button.withStyle(
                new UiStyleSheet(sheet.id(), sheet.variables(), sheet.customStates(), rules, Map.of()))));
    }

    @Test
    void subnormalsAndSurrogatesFollowTheContract() {
        assertEquals("5e-324", CanonicalJson.number(Double.MIN_VALUE));
        assertEquals("2.2250738585072014e-308", CanonicalJson.number(Double.MIN_NORMAL));
        assertEquals("1.7976931348623157e+308", CanonicalJson.number(Double.MAX_VALUE));
        UiDiagnostics d = new UiDiagnostics();
        CanonicalJson.parse("[\"\\ud800\"]".getBytes(StandardCharsets.UTF_8), "t", d);
        assertEquals(Code.INVALID_TEXT, d.list().getFirst().code());
        assertThrows(IllegalArgumentException.class, () -> UiValue.of("bad\uDC00"));
    }

    @Test
    void longRequiresChainsAndTooManyRowsAreBounded() throws Exception {
        OmuiArchive pause = UiSamples.pauseMenu();
        List<UiDependency> rows = new ArrayList<>(pause.dependencies().entries());
        String sha = UiBytes.sha256(new byte[0]);
        int chain = OmuiFormat.MAX_DEPENDENCIES - rows.size();
        for (int i = 0; i < chain; i++) {
            List<String> requires = i + 1 < chain ? List.of("chain:n" + (i + 1)) : List.of("chain:n0");
            rows.add(new UiDependency("chain:n" + i, UiDependency.Kind.IMAGE, null, sha, 0, UiDependency.Mode.SHARED,
                    null, null, requires, false, null, null, Map.of()));
        }
        // A single cycle through ~4,000 rows: reported once, compactly, without recursion.
        UiFormatException e = assertThrows(UiFormatException.class,
                () -> OmuiWriter.write(pause.withDependencies(new UiDependencies(rows, Map.of()))));
        assertEquals(1, e.diagnostics().stream().filter(x -> x.code() == Code.DEPENDENCY_CYCLE).count());
        assertTrue(e.getMessage().length() < 2000, "cycle message is bounded");

        rows.add(new UiDependency("chain:extra", UiDependency.Kind.IMAGE, null, sha, 0, UiDependency.Mode.SHARED,
                null, null, List.of(), false, null, null, Map.of()));
        assertTrue(assertThrows(UiFormatException.class,
                () -> OmuiWriter.write(pause.withDependencies(new UiDependencies(rows, Map.of())))).has(Code.LIMIT_EXCEEDED));
    }

    @Test
    void hiddenAndFileDirectoryOverlappingEntriesAreRejected() throws Exception {
        // Built with a plain ZipOutputStream: our own writer refuses these names outright.
        Map<String, byte[]> hidden = rawEntries(OmuiWriter.write(UiSamples.stoneButton()));
        hidden.put("assets/.icon.png", new byte[]{1});
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(plainZip(hidden))).has(Code.UNSAFE_ENTRY_PATH));
        assertThrows(IllegalArgumentException.class, () -> write(hidden));

        Map<String, byte[]> overlap = rawEntries(OmuiWriter.write(UiSamples.stoneButton()));
        overlap.put("assets/a", new byte[]{1});
        overlap.put("assets/a/b", new byte[]{2});
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(plainZip(overlap))).has(Code.DUPLICATE_ENTRY));
    }

    @Test
    void repeatedInPlaceUpgradesNeverOverwriteABackup() throws Exception {
        Path file = tmp.resolve("d.omui");
        Files.write(file, UiSamples.draftArchive());
        OmuiUpgrader.upgradeFile(file, file);
        Files.write(file, UiSamples.draftArchive());
        OmuiUpgrader.upgradeFile(file, file);
        assertTrue(Files.exists(tmp.resolve("d.omui.v0.1.bak")));
        assertTrue(Files.exists(tmp.resolve("d.omui.v0.1.bak.1")));
    }

    @Test
    void draftLayoutAndStyleMayNotDisagree() {
        Map<String, UiBytes> entries = new LinkedHashMap<>(UiSamples.draftEntries());
        String doc = new String(entries.get("document.json").toArray(), StandardCharsets.UTF_8)
                .replace("\"layout\": {\"width\": 520,", "\"style\": {\"width\": 999}, \"layout\": {\"width\": 520,");
        entries.put("document.json", UiBytes.utf8(doc));
        UiFormatException e = assertThrows(UiFormatException.class,
                () -> OmuiUpgrader.upgrade(com.openmason.engine.format.omui.io.ArchiveIO.write(entries)));
        assertTrue(e.getMessage().contains("width"), e.getMessage());
    }

    private static byte[] plainZip(Map<String, byte[]> entries) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(bytes)) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static OmuiArchive withRootProps(OmuiArchive a, Map<String, UiValue> props) {
        UiNode r = a.document().root();
        UiNode root = new UiNode(r.id(), r.name(), r.type(), r.typeVersion(), r.classes(), props, r.style(),
                r.dataSource(), r.bindings(), r.instance(), r.children(), r.unknown());
        return a.withDocument(new UiDocument(root, a.document().styleSheets(), a.document().codeBehind(),
                a.document().component(), Map.of()));
    }
}
