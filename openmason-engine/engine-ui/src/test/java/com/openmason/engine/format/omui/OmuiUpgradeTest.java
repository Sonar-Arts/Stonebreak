package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.io.ArchiveIO;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit, staged schema upgrades from the frozen 0.1 draft; originals are never half-replaced. */
@Tag("regression")
class OmuiUpgradeTest {

    @TempDir
    Path tmp;

    @Test
    void readerRefusesTheDraftWithoutAnExplicitUpgrade() {
        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiReader.read(UiSamples.draftArchive()));
        assertTrue(e.has(Code.NEEDS_UPGRADE), e.getMessage());
    }

    @Test
    void draftUpgradesToCurrentSchema() throws Exception {
        OmuiUpgrader.Upgrade up = OmuiUpgrader.upgrade(UiSamples.draftArchive());
        assertTrue(up.upgraded());
        assertEquals(OmuiFormat.DRAFT_VERSION, up.from());
        assertEquals(List.of("0.1->1.0"), up.stages());

        OmuiArchive doc = up.archive();
        UiManifest m = doc.manifest();
        assertEquals(OmuiFormat.SCHEMA_VERSION, m.schemaVersion());
        assertEquals("stonebreak:ui/pause_draft", m.documentId());
        assertEquals("Pause (draft)", m.displayName());
        assertEquals(OmuiFormat.UI_API_VERSION, m.uiApi());
        assertEquals("flex-1", m.layoutSemantics());
        assertEquals("pause", doc.document().codeBehind());
        assertEquals("return {}\n", doc.scripts().get("pause"));

        UiNode panel = doc.document().root().children().getFirst();
        assertEquals(UiValue.of("column"), panel.style().get("flex-direction"));
        assertEquals(UiValue.of("center"), panel.style().get("justify-content"));
        assertEquals(UiValue.of(20), panel.style().get("row-gap"));
        assertEquals(UiValue.of(20), panel.style().get("column-gap"));
        assertEquals(UiValue.of(50), panel.style().get("padding-top"));
        assertFalse(panel.style().containsKey("padding-left"), "null box sides stay unset");

        UiNode resync = panel.children().get(1);
        assertEquals(UiValue.of("none"), resync.style().get("display"));
        UiNode quit = panel.children().get(2);
        assertEquals(UiValue.of("nowrap"), quit.style().get("flex-wrap"));
        assertEquals(UiValue.of("center"), quit.style().get("align-self"));
        assertEquals(UiValue.of("kept: unknown fields survive the upgrade"), quit.unknown().get("draftNote"));
    }

    @Test
    void currentDocumentsPassThroughUnchanged() throws Exception {
        byte[] current = OmuiWriter.write(UiSamples.pauseMenu());
        OmuiUpgrader.Upgrade up = OmuiUpgrader.upgrade(current);
        assertFalse(up.upgraded());
        assertEquals(UiSamples.pauseMenu(), up.archive());
    }

    @Test
    void inPlaceUpgradeKeepsABackupAndReplacesAtomically() throws Exception {
        Path file = tmp.resolve("pause_draft.omui");
        byte[] original = UiSamples.draftArchive();
        Files.write(file, original);

        OmuiUpgrader.Upgrade up = OmuiUpgrader.upgradeFile(file, file);
        assertTrue(up.upgraded());
        assertArrayEquals(original, Files.readAllBytes(tmp.resolve("pause_draft.omui.v0.1.bak")));
        assertEquals(up.archive(), OmuiReader.read(file).archive());
        try (var listing = Files.list(tmp)) {
            assertEquals(2, listing.count(), "only the upgraded file and its backup");
        }
    }

    @Test
    void failedUpgradeLeavesTheOriginalUntouched() throws Exception {
        Map<String, UiBytes> entries = new LinkedHashMap<>(UiSamples.draftEntries());
        String doc = new String(entries.get("document.json").toArray(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"gap\": 20", "\"gap\": 20, \"flexFlow\": \"row wrap\"");
        entries.put("document.json", UiBytes.utf8(doc));
        byte[] original = ArchiveIO.write(entries);
        Path file = tmp.resolve("broken.omui");
        Files.write(file, original);
        Path target = tmp.resolve("broken.upgraded.omui");

        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiUpgrader.upgradeFile(file, target));
        assertTrue(e.getMessage().contains("flexFlow"), e.getMessage());
        assertArrayEquals(original, Files.readAllBytes(file));
        assertFalse(Files.exists(target));
        assertThrows(UiFormatException.class, () -> OmuiUpgrader.upgradeFile(file, file));
        assertArrayEquals(original, Files.readAllBytes(file));
        try (var listing = Files.list(tmp)) {
            assertEquals(1, listing.count(), "no backup, temp or partial files");
        }
    }

    @Test
    void unknownOldVersionHasNoPath() {
        Map<String, UiBytes> entries = new LinkedHashMap<>(UiSamples.draftEntries());
        entries.put("manifest.json", UiBytes.utf8("{\"format\":\"omui\",\"schemaVersion\":\"0.0\"}"));
        UiFormatException e = assertThrows(UiFormatException.class,
                () -> OmuiUpgrader.upgrade(ArchiveIO.write(entries)));
        assertTrue(e.has(Code.UNSUPPORTED_SCHEMA_VERSION));
    }
}
