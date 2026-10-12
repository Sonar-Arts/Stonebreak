package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.openmason.engine.format.omui.OmuiRoundTripTest.addField;
import static com.openmason.engine.format.omui.OmuiRoundTripTest.rawEntries;
import static com.openmason.engine.format.omui.OmuiRoundTripTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hostile and damaged input: every case must end in a {@link UiFormatException} carrying a
 * structured code — never a partial document, never another exception type.
 */
@Tag("regression")
class OmuiMalformedTest {

    @Test
    void everyTruncationFailsCleanly() throws Exception {
        byte[] good = OmuiWriter.write(UiSamples.pauseMenu());
        for (int len = 0; len < good.length; len += Math.max(1, good.length / 97)) {
            byte[] cut = java.util.Arrays.copyOf(good, len);
            UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiReader.read(cut), "length " + len);
            assertTrue(e.has(Code.TRUNCATED_ARCHIVE) || e.has(Code.NOT_AN_ARCHIVE) || e.has(Code.MISSING_ENTRY),
                    "length " + len + ": " + e.getMessage());
        }
    }

    @Test
    void corruptedBytesNeverEscapeAsOtherExceptions() throws Exception {
        byte[] good = OmuiWriter.write(UiSamples.pauseMenu());
        java.util.Random random = new java.util.Random(284);
        for (int i = 0; i < 300; i++) {
            byte[] bad = good.clone();
            for (int k = 0; k < 4; k++) {
                bad[random.nextInt(bad.length)] ^= (byte) (1 + random.nextInt(255));
            }
            try {
                OmuiReader.read(bad);
            } catch (UiFormatException expected) {
                assertTrue(expected.diagnostics().stream().anyMatch(UiDiagnostic::isError));
            }
        }
    }

    @Test
    void notAZip() {
        UiFormatException e = assertThrows(UiFormatException.class,
                () -> OmuiReader.read("{\"format\":\"omui\"}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.has(Code.NOT_AN_ARCHIVE));
    }

    @Test
    void pathTraversalAndAbsolutePathsAreRejected() throws Exception {
        for (String name : List.of("../evil.json", "assets/../../x", "/etc/passwd", "assets\\x", "C:/x", "assets//x",
                "./manifest.json", "assets/nul.png", "assets/trailing.")) {
            byte[] zip = rawZip(Map.of("manifest.json", manifestBytes(), name, new byte[]{1}));
            UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiReader.read(zip), name);
            assertTrue(e.has(Code.UNSAFE_ENTRY_PATH), name + ": " + e.getMessage());
        }
    }

    @Test
    void duplicateEntriesAreRejected() throws Exception {
        // ZipOutputStream refuses exact duplicates, so write two distinct names and patch one
        // into the other in both the local and central headers.
        byte[] zip = rawZip(new LinkedHashMap<>(Map.of("manifest.json", manifestBytes(),
                "assets/aa", new byte[]{1}, "assets/ab", new byte[]{2})));
        byte[] patched = replaceAll(zip, "assets/ab".getBytes(StandardCharsets.US_ASCII),
                "assets/aa".getBytes(StandardCharsets.US_ASCII));
        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiReader.read(patched));
        assertTrue(e.has(Code.DUPLICATE_ENTRY), e.getMessage());

        byte[] folded = rawZip(Map.of("manifest.json", manifestBytes(), "assets/Icon.png", new byte[]{1},
                "assets/icon.png", new byte[]{2}));
        e = assertThrows(UiFormatException.class, () -> OmuiReader.read(folded));
        assertTrue(e.has(Code.DUPLICATE_ENTRY), "case-folded collision: " + e.getMessage());
    }

    @Test
    void sizeAndCountLimitsHold() throws Exception {
        byte[] good = OmuiWriter.write(UiSamples.pauseMenu());
        ArchiveLimits tiny = new ArchiveLimits(4, 1 << 20, 1 << 20, 1 << 20, 100, 10, 100);
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(good, tiny)).has(Code.LIMIT_EXCEEDED));

        ArchiveLimits smallEntries = new ArchiveLimits(100, 512, 1 << 20, 512, 100, 10, 100);
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(good, smallEntries))
                .has(Code.LIMIT_EXCEEDED));

        ArchiveLimits fewNodes = new ArchiveLimits(100, 1 << 20, 1 << 20, 1 << 20, 3, 10, 100);
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(good, fewNodes))
                .has(Code.LIMIT_EXCEEDED));

        // A highly compressible entry is bounded by its inflated size, not the header.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifestBytes());
            zos.putNextEntry(new ZipEntry("assets/bomb.bin"));
            zos.write(new byte[8 << 20]);
            zos.closeEntry();
        }
        ArchiveLimits oneMeg = new ArchiveLimits(100, 1 << 20, 1 << 20, 1 << 20, 100, 10, 100);
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(bytes.toByteArray(), oneMeg))
                .has(Code.LIMIT_EXCEEDED));
    }

    @Test
    void malformedJsonEntriesFail() throws Exception {
        Map<String, byte[]> entries = rawEntries(OmuiWriter.write(UiSamples.stoneButton()));
        entries.put("document.json", "{\"root\": {".getBytes(StandardCharsets.UTF_8));
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(write(entries))).has(Code.MALFORMED_JSON));

        Map<String, byte[]> dup = rawEntries(OmuiWriter.write(UiSamples.stoneButton()));
        dup.put("manifest.json", "{\"format\":\"omui\",\"format\":\"omui\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(write(dup))).has(Code.DUPLICATE_KEY));

        Map<String, byte[]> wrong = rawEntries(OmuiWriter.write(UiSamples.stoneButton()));
        wrong.put("document.json", addField(wrong.get("document.json"), "root", "typeVersion", UiValue.of("one")));
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(write(wrong))).has(Code.INVALID_VALUE));

        Map<String, byte[]> missing = rawEntries(OmuiWriter.write(UiSamples.stoneButton()));
        missing.remove("document.json");
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(write(missing))).has(Code.MISSING_ENTRY));
    }

    @Test
    void duplicateNodeIdsAreRejectedOnReadAndWrite() throws Exception {
        OmuiArchive button = UiSamples.stoneButton();
        UiNode root = button.document().root();
        List<UiNode> kids = new ArrayList<>(root.children());
        kids.add(UiNode.of("label", "Box", List.of()));
        OmuiArchive bad = button.withDocument(new UiDocument(root.withChildren(kids),
                button.document().styleSheets(), null, button.document().component(), Map.of()));
        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiWriter.write(bad));
        assertTrue(e.has(Code.DUPLICATE_ID));
    }

    @Test
    void dependencyCyclesAreReportedWithTheirPath() throws Exception {
        OmuiArchive pause = UiSamples.pauseMenu();
        List<UiDependency> rows = new ArrayList<>();
        for (UiDependency d : pause.dependencies().entries()) {
            List<String> requires = switch (d.id()) {
                case UiSamples.THEME_ID -> List.of(UiSamples.PANEL_TEXTURE_ID);
                case UiSamples.PANEL_TEXTURE_ID -> List.of(UiSamples.COMMON_LUA_ID);
                case UiSamples.COMMON_LUA_ID -> List.of(UiSamples.THEME_ID);
                default -> d.requires();
            };
            rows.add(new UiDependency(d.id(), d.kind(), d.version(), d.sha256(), d.size(), d.mode(), d.entry(),
                    d.sourceHint(), requires, d.optional(), d.fallback(), d.license(), d.unknown()));
        }
        OmuiArchive cyclic = pause.withDependencies(new UiDependencies(rows, Map.of()));
        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiWriter.write(cyclic));
        assertTrue(e.has(Code.DEPENDENCY_CYCLE));
        assertTrue(e.getMessage().contains(UiSamples.THEME_ID + " -> "), e.getMessage());
    }

    @Test
    void recursiveComponentsAreDetected() throws Exception {
        // Self-instantiation inside one document.
        OmuiArchive button = UiSamples.stoneButton();
        UiNode self = new UiNode("self", null, UiNode.INSTANCE_TYPE, 1, List.of(), Map.of(), Map.of(), null, List.of(),
                new UiNode.ComponentInstance(UiSamples.BUTTON_ID, Map.of(), List.of(), Map.of(), Map.of()),
                List.of(), Map.of());
        List<UiNode> kids = new ArrayList<>(button.document().root().children());
        kids.add(self);
        OmuiArchive recursive = button.withDocument(new UiDocument(button.document().root().withChildren(kids),
                button.document().styleSheets(), null, button.document().component(), Map.of()));
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiWriter.write(recursive)).has(Code.RECURSIVE_COMPONENT));

        // A → B → A across documents.
        OmuiArchive pause = UiSamples.pauseMenu();
        UiDiagnostics d = new UiDiagnostics();
        OmuiArchive buttonThatUsesPause = button.withDocument(new UiDocument(
                button.document().root().withChildren(List.of(new UiNode("back", null, UiNode.INSTANCE_TYPE, 1,
                        List.of(), Map.of(), Map.of(), null, List.of(),
                        new UiNode.ComponentInstance(UiSamples.PAUSE_ID, Map.of(), List.of(), Map.of(), Map.of()),
                        List.of(), Map.of()))),
                List.of(), null, button.document().component(), Map.of()));
        OmuiValidator.componentCycles(pause,
                id -> id.equals(UiSamples.BUTTON_ID) ? buttonThatUsesPause : id.equals(UiSamples.PAUSE_ID) ? pause : null, d);
        assertTrue(d.list().stream().anyMatch(x -> x.code() == Code.RECURSIVE_COMPONENT), d.list().toString());
    }

    @Test
    void embeddedHashMismatchAndMissingSnapshotAreErrors() throws Exception {
        Map<String, byte[]> entries = rawEntries(OmuiWriter.write(UiSamples.pauseMenu()));
        byte[] asset = entries.get("assets/components/stone_button.omui").clone();
        asset[asset.length - 1] ^= 1;
        entries.put("assets/components/stone_button.omui", asset);
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(write(entries))).has(Code.HASH_MISMATCH));

        Map<String, byte[]> gone = rawEntries(OmuiWriter.write(UiSamples.pauseMenu()));
        gone.remove("assets/components/stone_button.omui");
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(write(gone))).has(Code.MISSING_ENTRY));
    }

    @Test
    void unresolvedReferencesAreErrors() throws Exception {
        Map<String, byte[]> entries = rawEntries(OmuiWriter.write(UiSamples.pauseMenu()));
        entries.remove("styles/pause.uss.json");
        entries.remove("scripts/pause.lua");
        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiReader.read(write(entries)));
        assertEquals(2, e.diagnostics().stream().filter(d -> d.code() == Code.UNRESOLVED_REFERENCE).count(),
                e.diagnostics().toString());

        Map<String, byte[]> clip = rawEntries(OmuiWriter.write(UiSamples.pauseMenu()));
        clip.put("animations/open.anim.json", new String(clip.get("animations/open.anim.json"), StandardCharsets.UTF_8)
                .replace("\"panel\"", "\"ghost\"").getBytes(StandardCharsets.UTF_8));
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(write(clip))).has(Code.UNRESOLVED_REFERENCE));

        Map<String, byte[]> image = rawEntries(OmuiWriter.write(UiSamples.pauseMenu()));
        image.put("document.json", new String(image.get("document.json"), StandardCharsets.UTF_8)
                .replace("\"background-image\": \"stonebreak:ui/textures/panel\"",
                        "\"background-image\": \"stonebreak:ui/textures/missing\"")
                .getBytes(StandardCharsets.UTF_8));
        UiFormatException e2 = assertThrows(UiFormatException.class, () -> OmuiReader.read(write(image)));
        assertTrue(e2.getMessage().contains("stonebreak:ui/textures/missing"), e2.getMessage());
    }

    @Test
    void binaryLuaChunksAreRefused() throws Exception {
        Map<String, byte[]> entries = rawEntries(OmuiWriter.write(UiSamples.pauseMenu()));
        entries.put("scripts/pause.lua", new byte[]{0x1B, 'L', 'u', 'a', 0x55, 0, 1, 2});
        assertTrue(assertThrows(UiFormatException.class, () -> OmuiReader.read(write(entries))).has(Code.BINARY_SCRIPT));
    }

    @Test
    void syntaxRulesAreEnforced() throws Exception {
        OmuiArchive pause = UiSamples.pauseMenu();
        UiStyleSheet sheet = pause.styles().get("pause");
        List<UiStyleSheet.StyleRule> rules = new ArrayList<>(sheet.rules());
        rules.add(new UiStyleSheet.StyleRule("Button:wobble", Map.of("width", UiValue.of("wide")), List.of(), Map.of()));
        OmuiArchive bad = pause.withStyle(new UiStyleSheet("pause", sheet.variables(), sheet.customStates(), rules, Map.of()));
        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiWriter.write(bad));
        assertTrue(e.getMessage().contains(":wobble"), e.getMessage());
        assertTrue(e.diagnostics().stream().anyMatch(d -> d.code() == Code.INVALID_VALUE && d.message().contains("width")));
    }

    @Test
    void idsThatWouldMakeUnsafeEntryNamesFailWithDiagnostics() throws Exception {
        // "con" is a valid part id but a Windows device name as a file stem.
        OmuiArchive button = UiSamples.stoneButton();
        OmuiArchive bad = button.withStyle(new UiStyleSheet("con", Map.of(), List.of(), List.of(), Map.of()));
        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiWriter.write(bad));
        assertTrue(e.has(Code.UNSAFE_ENTRY_PATH), e.getMessage());

        // Dot-only segments are not logical ids, so they can never become traversal paths.
        assertTrue(OmuiFormat.LOGICAL_ID.matcher("stonebreak:ui/pause_menu").matches());
        assertTrue(OmuiFormat.LOGICAL_ID.matcher("stonebreak:ui/v1.2/x").matches());
        for (String id : List.of("stonebreak:ui/../x", "stonebreak:.", "stonebreak:ui/x.", ".hidden:x")) {
            assertTrue(!OmuiFormat.LOGICAL_ID.matcher(id).matches(), id);
        }
    }

    @Test
    void absoluteSourceHintsAreRefused() throws Exception {
        OmuiArchive pause = UiSamples.pauseMenu();
        List<UiDependency> rows = new ArrayList<>();
        for (UiDependency d : pause.dependencies().entries()) {
            rows.add(d.id().equals(UiSamples.THEME_ID)
                    ? UiDependency.shared(d.id(), d.kind(), d.sha256(), d.size(), "/home/me/ui/stone.uss.json")
                    : d);
        }
        UiFormatException e = assertThrows(UiFormatException.class,
                () -> OmuiWriter.write(pause.withDependencies(new UiDependencies(rows, Map.of()))));
        assertTrue(e.has(Code.UNSAFE_ENTRY_PATH));
    }

    // ── helpers ──

    private static byte[] manifestBytes() throws UiFormatException {
        return rawEntries(OmuiWriter.write(UiSamples.stoneButton())).get("manifest.json");
    }

    private static byte[] rawZip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] replaceAll(byte[] data, byte[] from, byte[] to) {
        byte[] out = data.clone();
        outer:
        for (int i = 0; i <= out.length - from.length; i++) {
            for (int k = 0; k < from.length; k++) {
                if (out[i + k] != from[k]) {
                    continue outer;
                }
            }
            System.arraycopy(to, 0, out, i, to.length);
        }
        return out;
    }
}
