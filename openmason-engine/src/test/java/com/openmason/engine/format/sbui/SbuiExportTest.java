package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.ArchiveIO;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.sbui.SbuiManifest.DerivedKind;
import com.openmason.engine.format.sbui.SbuiManifest.Location;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SBUI export, dependency resolution rows, derived-cache freshness and editable import. */
@Tag("regression")
class SbuiExportTest {

    static final String LUA = "-- generated from graph:behaviors by omui-graphc 1\nreturn {}\n";

    static SbuiExporter.DerivedInput graphLua(String version) {
        return new SbuiExporter.DerivedInput("graphs/behaviors.lua", DerivedKind.GRAPH_LUA, "graph:behaviors",
                UiBytes.utf8(LUA), "omui-graphc", version);
    }

    static Map<String, UiBytes> collectedShared() {
        return Map.of(UiSamples.THEME_ID, UiBytes.copyOf(UiSamples.THEME_BYTES),
                UiSamples.COMMON_LUA_ID, UiBytes.copyOf(UiSamples.COMMON_LUA_BYTES),
                UiSamples.PANEL_TEXTURE_ID, UiBytes.copyOf(UiSamples.PANEL_TEXTURE_BYTES));
    }

    @Test
    void sharedExportDeclaresWhatMustShipAndWhereEverythingResolves() throws Exception {
        SbuiArchive sbui = SbuiExporter.export(UiSamples.pauseMenu(), SbuiExporter.Options.shared()).archive();
        SbuiManifest m = sbui.manifest();
        assertEquals(UiSamples.PAUSE_ID, m.assetId());
        assertEquals(UiSamples.PAUSE_ID, m.entry());
        assertEquals("source/pause_menu.omui", m.source().entry());

        var button = m.dependency(UiSamples.BUTTON_ID);
        assertEquals(UiDependency.Mode.EMBEDDED, button.mode());
        assertEquals(Location.SOURCE, button.location());
        assertEquals("assets/components/stone_button.omui", button.entry());
        for (String shared : List.of(UiSamples.THEME_ID, UiSamples.COMMON_LUA_ID, UiSamples.PANEL_TEXTURE_ID)) {
            var row = m.dependency(shared);
            assertEquals(UiDependency.Mode.SHARED, row.mode(), shared);
            assertNull(row.location());
            assertNull(row.entry());
        }
        assertEquals(Set.of("stonebreak:network.resync", "stonebreak:screen.pause", "stonebreak:session"),
                Set.copyOf(m.hostApis().stream().map(h -> h.id()).toList()));
        assertTrue(m.hostApis().stream().filter(h -> h.id().equals("stonebreak:network.resync")).findFirst()
                .orElseThrow().optional());
        assertTrue(sbui.assets().isEmpty());
    }

    @Test
    void collectAllEmbedsTheClosureAndImportsPortably() throws Exception {
        var options = new SbuiExporter.Options("stonebreak:ui/pause", collectedShared(), true, Map.of(), List.of());
        SbuiArchive sbui = SbuiExporter.export(UiSamples.pauseMenu(), options).archive();
        assertEquals("stonebreak:ui/pause", sbui.manifest().assetId());
        var texture = sbui.manifest().dependency(UiSamples.PANEL_TEXTURE_ID);
        assertEquals(Location.SBUI, texture.location());
        assertEquals("assets/stonebreak/ui/textures/panel.sbt", texture.entry());
        assertArrayEquals(UiSamples.PANEL_TEXTURE_BYTES, sbui.assets().get(texture.entry()).toArray());

        SbuiArchive read = SbuiReader.read(SbuiWriter.write(sbui), SbuiReader.Options.RUNTIME).archive();
        var portable = SbuiImporter.importPortable(read);
        assertEquals(3, portable.collected().size());
        OmuiArchive doc = OmuiReader.read(OmuiWriter.write(portable.document())).archive();
        for (UiDependency d : doc.dependencies().entries()) {
            assertEquals(UiDependency.Mode.EMBEDDED, d.mode(), d.id() + " is self-contained after a portable import");
        }
        assertArrayEquals(UiSamples.THEME_BYTES,
                doc.assets().get(doc.dependencies().find(UiSamples.THEME_ID).entry()).toArray());
    }

    @Test
    void collectAllWithAMissingRequiredDependencyIsBlocked() throws Exception {
        Map<String, UiBytes> partial = new LinkedHashMap<>(collectedShared());
        partial.remove(UiSamples.THEME_ID);
        var options = new SbuiExporter.Options(null, partial, true, Map.of(), List.of());
        UiFormatException e = assertThrows(UiFormatException.class,
                () -> SbuiExporter.export(UiSamples.pauseMenu(), options));
        assertTrue(e.has(Code.MISSING_ENTRY));
        assertTrue(e.getMessage().contains(UiSamples.THEME_ID));
    }

    @Test
    void freshDerivedCacheLoads() throws Exception {
        var options = new SbuiExporter.Options(null, Map.of(), false, Map.of(), List.of(graphLua("1")));
        byte[] bytes = SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), options).archive());
        var read = SbuiReader.read(bytes, new SbuiReader.Options(SbuiReader.StalePolicy.REJECT,
                Map.of(DerivedKind.GRAPH_LUA, "1"), ArchiveLimits.DEFAULT));
        assertTrue(read.staleDerived().isEmpty());
        var row = read.archive().manifest().derived().getFirst();
        assertEquals("derived/graphs/behaviors.lua", row.entry());
        assertEquals(UiBytes.sha256(OmuiWriter.entries(UiSamples.pauseMenu()).get("graphs/behaviors.graph.json")
                .toArray()), row.sourceSha256());
    }

    @Test
    void cacheFromAnOlderGraphIsStale() throws Exception {
        var withCache = new SbuiExporter.Options(null, Map.of(), false, Map.of(), List.of(graphLua("1")));
        Map<String, byte[]> old = entries(SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), withCache).archive()));

        // The graph is edited and re-exported without rebuilding; graft the old cache back in.
        OmuiArchive edited = editGraph(UiSamples.pauseMenu());
        Map<String, byte[]> fresh = entries(SbuiWriter.write(
                SbuiExporter.export(edited, SbuiExporter.Options.shared()).archive()));
        UiValue.Obj oldManifest = (UiValue.Obj) CanonicalJson.parse(old.get("manifest.json"), "m", new UiDiagnostics());
        UiValue.Obj newManifest = (UiValue.Obj) CanonicalJson.parse(fresh.get("manifest.json"), "m", new UiDiagnostics());
        Map<String, UiValue> f = new LinkedHashMap<>(newManifest.fields());
        f.put("derived", oldManifest.get("derived"));
        fresh.put("manifest.json", CanonicalJson.write(new UiValue.Obj(f)));
        fresh.put("derived/graphs/behaviors.lua", old.get("derived/graphs/behaviors.lua"));
        byte[] stale = write(fresh);

        UiFormatException e = assertThrows(UiFormatException.class,
                () -> SbuiReader.read(stale, SbuiReader.Options.RUNTIME));
        assertTrue(e.has(Code.STALE_DERIVED), e.getMessage());

        var report = SbuiReader.read(stale, SbuiReader.Options.EDITOR);
        assertEquals(1, report.staleDerived().size());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.code() == Code.STALE_DERIVED
                && d.severity() == UiDiagnostic.Severity.WARNING));
        // The writer never ships a stale cache.
        assertThrows(UiFormatException.class, () -> SbuiWriter.write(report.archive()));
    }

    @Test
    void cacheFromAnotherCompilerVersionIsStale() throws Exception {
        var options = new SbuiExporter.Options(null, Map.of(), false, Map.of(), List.of(graphLua("1")));
        byte[] bytes = SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), options).archive());
        var newer = new SbuiReader.Options(SbuiReader.StalePolicy.REPORT, Map.of(DerivedKind.GRAPH_LUA, "2"),
                ArchiveLimits.DEFAULT);
        assertEquals(1, SbuiReader.read(bytes, newer).staleDerived().size());
    }

    @Test
    void tamperedSourceOrAssetsAreRejected() throws Exception {
        var options = new SbuiExporter.Options(null, collectedShared(), true, Map.of(), List.of());
        Map<String, byte[]> good = entries(SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), options).archive()));

        Map<String, byte[]> source = new LinkedHashMap<>(good);
        OmuiArchive edited = UiSamples.pauseMenu().withScript("pause", UiSamples.PAUSE_LUA + "-- sneaky\n");
        source.put("source/pause_menu.omui", OmuiWriter.write(edited));
        assertTrue(assertThrows(UiFormatException.class, () -> SbuiReader.read(write(source), SbuiReader.Options.RUNTIME))
                .has(Code.HASH_MISMATCH));

        Map<String, byte[]> asset = new LinkedHashMap<>(good);
        asset.put("assets/stonebreak/ui/textures/panel.sbt", UiSamples.utf8("swapped"));
        assertTrue(assertThrows(UiFormatException.class, () -> SbuiReader.read(write(asset), SbuiReader.Options.RUNTIME))
                .has(Code.HASH_MISMATCH));

        Map<String, byte[]> noSource = new LinkedHashMap<>(good);
        noSource.remove("source/pause_menu.omui");
        assertTrue(assertThrows(UiFormatException.class, () -> SbuiReader.read(write(noSource), SbuiReader.Options.RUNTIME))
                .has(Code.MISSING_ENTRY));
    }

    @Test
    void manifestMustCoverTheEmbeddedRequirements() throws Exception {
        Map<String, byte[]> good = entries(SbuiWriter.write(
                SbuiExporter.export(UiSamples.pauseMenu(), SbuiExporter.Options.shared()).archive()));
        UiValue.Obj m = (UiValue.Obj) CanonicalJson.parse(good.get("manifest.json"), "m", new UiDiagnostics());
        Map<String, UiValue> f = new LinkedHashMap<>(m.fields());
        f.remove("hostApis");
        good.put("manifest.json", CanonicalJson.write(new UiValue.Obj(f)));
        UiFormatException e = assertThrows(UiFormatException.class,
                () -> SbuiReader.read(write(good), SbuiReader.Options.RUNTIME));
        assertTrue(e.has(Code.INCONSISTENT_MANIFEST));
    }

    @Test
    void unknownSbuiDataSurvives() throws Exception {
        Map<String, byte[]> good = entries(SbuiWriter.write(
                SbuiExporter.export(UiSamples.pauseMenu(), SbuiExporter.Options.shared()).archive()));
        UiValue.Obj m = (UiValue.Obj) CanonicalJson.parse(good.get("manifest.json"), "m", new UiDiagnostics());
        Map<String, UiValue> f = new LinkedHashMap<>(m.fields());
        f.put("x-future", UiValue.of(7));
        good.put("manifest.json", CanonicalJson.write(new UiValue.Obj(f)));
        good.put("signatures/export.sig", UiSamples.utf8("sig"));
        SbuiArchive read = SbuiReader.read(write(good), SbuiReader.Options.RUNTIME).archive();
        Map<String, byte[]> again = entries(SbuiWriter.write(read));
        assertTrue(new String(again.get("manifest.json")).contains("x-future"));
        assertArrayEquals(UiSamples.utf8("sig"), again.get("signatures/export.sig"));
    }

    @Test
    void newerMajorSbuiIsRefused() throws Exception {
        Map<String, byte[]> good = entries(SbuiWriter.write(
                SbuiExporter.export(UiSamples.stoneButton(), SbuiExporter.Options.shared()).archive()));
        good.put("manifest.json", new String(good.get("manifest.json")).replace("\"schemaVersion\": \"1.0\",\n  \"assetId\"",
                "\"schemaVersion\": \"2.0\",\n  \"assetId\"").getBytes());
        assertTrue(assertThrows(UiFormatException.class, () -> SbuiReader.read(write(good), SbuiReader.Options.RUNTIME))
                .has(Code.UNSUPPORTED_SCHEMA_VERSION));
    }

    @Test
    void hostRefusesUnsupportedContractsButDegradesOptionalOnes() throws Exception {
        SbuiManifest m = SbuiExporter.export(UiSamples.pauseMenu(), SbuiExporter.Options.shared()).archive().manifest();
        UiHostProfile game = new UiHostProfile(1, Set.of("flex-1"),
                Map.of("stonebreak:screen.pause", 1, "stonebreak:session", 1), Map.of());
        List<UiDiagnostic> ok = game.check(m);
        assertTrue(ok.stream().noneMatch(UiDiagnostic::isError), ok.toString());
        assertTrue(ok.stream().anyMatch(d -> d.code() == Code.UNSUPPORTED_HOST_API), "optional resync degrades");

        UiHostProfile old = new UiHostProfile(0, Set.of("flex-0"), Map.of("stonebreak:session", 1), Map.of());
        List<UiDiagnostic> bad = old.check(m);
        assertTrue(bad.stream().anyMatch(d -> d.isError() && d.code() == Code.UNSUPPORTED_UI_API));
        assertTrue(bad.stream().anyMatch(d -> d.isError() && d.code() == Code.UNSUPPORTED_LAYOUT_SEMANTICS));
        assertTrue(bad.stream().anyMatch(d -> d.isError() && d.message().contains("stonebreak:screen.pause")));
    }

    // ── helpers ──

    static OmuiArchive editGraph(OmuiArchive doc) {
        UiGraph g = doc.graphs().get("behaviors");
        List<UiGraph.GraphNode> nodes = new ArrayList<>(g.nodes());
        nodes.add(new UiGraph.GraphNode("extra", "ui:debug.log", 1, 700, 80, Map.of(), Map.of(), Map.of()));
        return doc.withGraph(new UiGraph(g.id(), g.variables(), nodes, g.edges(), g.functions(), g.unknown()));
    }

    static Map<String, byte[]> entries(byte[] archive) {
        UiDiagnostics d = new UiDiagnostics();
        return new LinkedHashMap<>(ArchiveIO.read(archive, ArchiveLimits.DEFAULT, d));
    }

    static byte[] write(Map<String, byte[]> entries) {
        Map<String, UiBytes> out = new LinkedHashMap<>();
        entries.forEach((k, v) -> out.put(k, UiBytes.copyOf(v)));
        return ArchiveIO.write(out);
    }
}
