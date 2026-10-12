package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.uiarchive.UiPacker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.format.sbui.SbuiExportTest.collectedShared;
import static com.openmason.engine.format.sbui.SbuiExportTest.editGraph;
import static com.openmason.engine.format.sbui.SbuiExportTest.entries;
import static com.openmason.engine.format.sbui.SbuiExportTest.graphLua;
import static com.openmason.engine.format.sbui.SbuiExportTest.write;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #282 hardening, SBUI findings: row unknowns, entry collisions, nested budgets, pack/unpack policy. */
@Tag("regression")
class SbuiReviewFixesTest {

    @TempDir
    Path tmp;

    @Test
    void dependencyRowsCarryTheSourceRowsUnknownFields() throws Exception {
        OmuiArchive pause = withRows(UiSamples.pauseMenu(), rows -> {
            UiDependency t = rows.get(UiSamples.PANEL_TEXTURE_ID);
            rows.put(t.id(), copy(t, t.id(), t.sourceHint(), Map.of("futureHint", UiValue.of("keep"),
                    "location", UiValue.of("sbui"))));
        });
        SbuiArchive sbui = SbuiExporter.export(pause, SbuiExporter.Options.shared()).archive();
        SbuiManifest.SbuiDependency row = sbui.manifest().dependency(UiSamples.PANEL_TEXTURE_ID);
        assertEquals(UiValue.of("keep"), row.unknown().get("futureHint"));
        assertFalse(row.unknown().containsKey("location"), "an unknown field never shadows a known SBUI one");
        assertEquals(null, row.location()); // shared row: still resolved by the game, not the export
        SbuiArchive read = SbuiReader.read(SbuiWriter.write(sbui), SbuiReader.Options.RUNTIME).archive();
        assertEquals(UiValue.of("keep"), read.manifest().dependency(UiSamples.PANEL_TEXTURE_ID).unknown()
                .get("futureHint"));
    }

    @Test
    void twoDependenciesCollectingToOneEntryBlockTheExport() throws Exception {
        UiBytes bytes = UiBytes.copyOf(UiSamples.PANEL_TEXTURE_BYTES);
        OmuiArchive pause = withRows(UiSamples.pauseMenu(), rows -> {
            UiDependency t = rows.get(UiSamples.PANEL_TEXTURE_ID);
            rows.put("stonebreak:ui/textures/a.sbt", copy(t, "stonebreak:ui/textures/a.sbt", null, Map.of()));
            rows.put("stonebreak:ui/textures/a", copy(t, "stonebreak:ui/textures/a", "UI/textures/a.sbt", Map.of()));
        });
        Map<String, UiBytes> collected = new LinkedHashMap<>(collectedShared());
        collected.put("stonebreak:ui/textures/a.sbt", bytes);
        collected.put("stonebreak:ui/textures/a", bytes);
        UiFormatException e = assertThrows(UiFormatException.class, () -> SbuiExporter.export(pause,
                new SbuiExporter.Options(null, collected, true, Map.of(), List.of())));
        assertTrue(e.has(Code.DUPLICATE_ENTRY), e.getMessage());
    }

    @Test
    void portableImportNeverOverwritesAnEmbeddedAsset() throws Exception {
        // The source embeds its own bytes at the very entry the collected panel texture maps to.
        String clash = SbuiExporter.collectedEntry(UiSamples.pauseMenu().dependencies().find(UiSamples.PANEL_TEXTURE_ID));
        UiBytes mine = UiBytes.utf8("embedded-by-the-source");
        OmuiArchive pause = withRows(UiSamples.pauseMenu(), rows -> {
            UiDependency t = rows.get(UiSamples.PANEL_TEXTURE_ID);
            String id = "stonebreak:ui/textures/own";
            rows.put(id, new UiDependency(id, t.kind(), t.version(), mine.sha256(), mine.size(),
                    UiDependency.Mode.EMBEDDED, clash, null, t.requires(), false, null, t.license(), Map.of()));
        }).withAsset(clash, mine);
        SbuiArchive sbui = SbuiExporter.export(pause, new SbuiExporter.Options(null, collectedShared(), true,
                Map.of(), List.of())).archive();

        OmuiArchive portable = SbuiImporter.importPortable(SbuiReader.read(SbuiWriter.write(sbui),
                SbuiReader.Options.RUNTIME).archive()).document();
        assertArrayEquals(mine.toArray(), portable.assets().get(clash).toArray(), "embedded bytes survive");
        UiDependency panel = portable.dependencies().find(UiSamples.PANEL_TEXTURE_ID);
        assertNotEquals(clash, panel.entry());
        assertArrayEquals(UiSamples.PANEL_TEXTURE_BYTES, portable.assets().get(panel.entry()).toArray());
        assertEquals(portable, OmuiReader.read(OmuiWriter.write(portable)).archive());
    }

    @Test
    void freeEntryAvoidsFileDirectoryOverlaps() {
        assertEquals("assets/a~2.png", SbuiImporter.freeEntry("assets/a.png", java.util.Set.of("assets/a.png")));
        assertEquals("assets/a~2", SbuiImporter.freeEntry("assets/a", java.util.Set.of("assets/a/b.png")));
        assertEquals("assets/collected~2/a/b", SbuiImporter.freeEntry("assets/a/b", java.util.Set.of("assets/a")));
        assertEquals("assets/x.png", SbuiImporter.freeEntry("assets/x.png", java.util.Set.of("assets/a.png")));
    }

    @Test
    void nestedArchivesShareTheContainersMemoryBudget() throws Exception {
        byte[] bytes = SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), SbuiExporter.Options.shared())
                .archive());
        long outer = 0;
        for (byte[] b : entries(bytes).values()) {
            outer += b.length;
        }
        // Enough for the outer entries, not for inflating the embedded OMUI on top of them.
        ArchiveLimits tight = ArchiveLimits.DEFAULT.withRemainingTotal(outer + 64);
        UiFormatException e = assertThrows(UiFormatException.class, () -> SbuiReader.read(bytes,
                new SbuiReader.Options(SbuiReader.StalePolicy.REJECT, Map.of(), tight)));
        assertTrue(e.has(Code.LIMIT_EXCEEDED), e.getMessage());
        // The default budget reads it.
        SbuiReader.read(bytes, SbuiReader.Options.RUNTIME);
    }

    @Test
    void anSbuiThatUnpacksAlwaysPacks() throws Exception {
        var withCache = new SbuiExporter.Options(null, Map.of(), false, Map.of(), List.of(graphLua("1")));
        Map<String, byte[]> old = entries(SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), withCache)
                .archive()));
        Map<String, byte[]> fresh = entries(SbuiWriter.write(SbuiExporter.export(editGraph(UiSamples.pauseMenu()),
                SbuiExporter.Options.shared()).archive()));
        UiValue.Obj oldManifest = (UiValue.Obj) CanonicalJson.parse(old.get("manifest.json"), "m", new UiDiagnostics());
        UiValue.Obj newManifest = (UiValue.Obj) CanonicalJson.parse(fresh.get("manifest.json"), "m", new UiDiagnostics());
        Map<String, UiValue> f = new LinkedHashMap<>(newManifest.fields());
        f.put("derived", oldManifest.get("derived"));
        fresh.put("manifest.json", CanonicalJson.write(new UiValue.Obj(f)));
        fresh.put("derived/graphs/behaviors.lua", old.get("derived/graphs/behaviors.lua"));
        byte[] stale = write(fresh);

        // Refused both ways (it used to unpack, then refuse to pack).
        UiFormatException e = assertThrows(UiFormatException.class,
                () -> UiPacker.unpack(stale, tmp.resolve("stale"), false));
        assertTrue(e.has(Code.STALE_DERIVED), e.getMessage());

        byte[] good = SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), withCache).archive());
        UiPacker.unpack(good, tmp.resolve("good"), false);
        assertArrayEquals(good, UiPacker.pack(tmp.resolve("good")));
    }

    // ── helpers ──

    private interface RowEdit {
        void apply(Map<String, UiDependency> rows);
    }

    private static OmuiArchive withRows(OmuiArchive a, RowEdit edit) {
        Map<String, UiDependency> rows = new LinkedHashMap<>();
        a.dependencies().entries().forEach(r -> rows.put(r.id(), r));
        edit.apply(rows);
        return a.withDependencies(new OmuiArchive.UiDependencies(new ArrayList<>(rows.values()),
                a.dependencies().unknown()));
    }

    private static UiDependency copy(UiDependency t, String id, String hint, Map<String, UiValue> unknown) {
        return new UiDependency(id, t.kind(), t.version(), t.sha256(), t.size(), t.mode(), t.entry(), hint,
                t.requires(), t.optional(), t.fallback(), t.license(), unknown);
    }
}
