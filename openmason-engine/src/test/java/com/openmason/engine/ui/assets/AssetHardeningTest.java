package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.assets.edit.AssetEdit;
import com.openmason.engine.ui.assets.edit.ProjectWrite;
import com.openmason.engine.ui.assets.edit.SbuiProjectImport;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.engine.ui.assets.export.ExportPlan;
import com.openmason.engine.ui.assets.export.ExportPlanner;
import com.openmason.engine.ui.assets.live.UiAssetCache;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.masonry.textures.MTextureCache;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.paint.ResolvedUiAssets;
import com.openmason.engine.ui.runtime.paint.UiImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.openmason.engine.ui.assets.SpriteFixtures.*;
import static com.openmason.engine.ui.assets.UiAssetFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #285/#294 hardening: runtime fallbacks and diagnostics, per-component resolution, component
 * embedded sprite sheets at export, all-or-nothing asset edits, symlink containment, import
 * placement, deterministic texture release and the asset cache's close race.
 */
class AssetHardeningTest {

    private static final String FANCY = "t:ui/textures/fancy";
    private static final String PLAIN = "t:ui/textures/plain";

    @TempDir
    Path tmp;

    private static byte[] solid(int w, int h, int argb) {
        return png(w, h, (x, y) -> argb);
    }

    @Test
    void runtimeFollowsOptionalFallbacksAndReportsThem() throws Exception {
        ProjectFolder folder = new ProjectFolder(tmp);
        ProjectAssetSource project = new ProjectAssetSource(folder, "");
        UiBytes plain = UiBytes.copyOf(solid(3, 2, 0xFF00FF00));
        folder.write("t/ui/textures/plain.png", plain);
        OmuiArchive doc = screenUsing("t:ui/s", FANCY, bytes("fancy"), null);
        doc = withRow(doc, with(doc.dependencies().find(FANCY), List.of(), true, PLAIN));
        doc = withRow(doc, UiDependency.shared(PLAIN, UiDependency.Kind.IMAGE, plain.sha256(), plain.size(),
                "t/ui/textures/plain.png"));

        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forDocument(doc, List.of(project)),
                List.of(project), new MTextureCache());
        MTexture t = assets.texture(FANCY);

        assertNotNull(t, "a missing optional row draws its fallback, as export promised");
        assertEquals(3, t.width());
        assertTrue(assets.diagnostics().stream().anyMatch(d -> d.code() == Code.MISSING_ENTRY
                && d.message().contains("using fallback")), assets.diagnostics()::toString);
    }

    @Test
    void checkRefusesMissingRequiredAssetsUpFront() throws Exception {
        ProjectAssetSource project = pauseProject(tmp.resolve("p"));
        project.folder().delete(PANEL_HINT);
        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forDocument(UiSamples.pauseMenu(),
                List.of(project)), List.of(project), new MTextureCache());

        List<UiDiagnostic> found = assets.check();

        assertTrue(found.stream().anyMatch(d -> d.isError() && d.message().contains(UiSamples.PANEL_TEXTURE_ID)),
                found::toString);
        assertTrue(assets.diagnostics().stream().anyMatch(UiDiagnostic::isError), "kept for hosts and the F3 card");
        assertNull(assets.texture(UiSamples.PANEL_TEXTURE_ID));
    }

    /** A component kind document whose root shows {@code ref}, embedding {@code rows} with their bytes. */
    private static OmuiArchive component(String id, String ref, List<UiDependency> rows, Map<String, UiBytes> entries) {
        UiNode root = new UiNode("root", null, "Box", 1, List.of(), Map.of(),
                Map.of("background-image", UiValue.of(ref), "width", UiValue.of(12), "height", UiValue.of(12)), null,
                List.of(), null, List.of(), Map.of());
        OmuiArchive c = OmuiArchive.of(UiManifest.create(id, UiManifest.DocumentKind.COMPONENT, ""),
                new UiDocument(root, List.of(), null, new UiDocument.ComponentDef(List.of(), List.of(), List.of(),
                        Map.of()), Map.of())).withDependencies(new UiDependencies(rows, Map.of()));
        for (Map.Entry<String, UiBytes> e : entries.entrySet()) {
            c = c.withAsset(e.getKey(), e.getValue());
        }
        return UiDocs.declare(c, List.of(UiFeatures.SPRITES));
    }

    private static UiNode instance(String key, String component) {
        return new UiNode(key, null, UiNode.INSTANCE_TYPE, 1, List.of(), Map.of(), Map.of(), null, List.of(),
                new UiNode.ComponentInstance(component, Map.of(), List.of(), Map.of(), Map.of()), List.of(), Map.of());
    }

    /** A screen embedding {@code components} (id → archive) and instancing each once. */
    private static OmuiArchive host(Map<String, OmuiArchive> components) throws Exception {
        List<UiDependency> rows = new ArrayList<>();
        List<UiNode> kids = new ArrayList<>();
        OmuiArchive doc = null;
        Map<String, UiBytes> entries = new java.util.LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, OmuiArchive> c : components.entrySet()) {
            UiBytes bytes = UiBytes.copyOf(OmuiWriter.write(c.getValue()));
            String entry = "assets/c" + i + ".omui";
            rows.add(UiDependency.embedded(c.getKey(), UiDependency.Kind.COMPONENT, bytes, entry, null));
            entries.put(entry, bytes);
            kids.add(instance("i" + i++, c.getKey()));
        }
        doc = OmuiArchive.of(UiManifest.create("t:ui/host", UiManifest.DocumentKind.SCREEN, ""),
                new UiDocument(UiNode.of("root", "Box", kids), List.of(), null, null, Map.of()))
                .withDependencies(new UiDependencies(rows, Map.of()));
        for (Map.Entry<String, UiBytes> e : entries.entrySet()) {
            doc = doc.withAsset(e.getKey(), e.getValue());
        }
        return doc;
    }

    @Test
    void componentsEmbeddingTheSameIdDrawTheirOwnBytes() throws Exception {
        UiBytes red = UiBytes.copyOf(solid(2, 2, 0xFFFF0000));
        UiBytes blue = UiBytes.copyOf(solid(5, 5, 0xFF0000FF));
        OmuiArchive a = component("t:ui/components/a", "t:ui/textures/icon",
                List.of(UiDependency.embedded("t:ui/textures/icon", UiDependency.Kind.IMAGE, red, "assets/icon.png", null)),
                Map.of("assets/icon.png", red));
        OmuiArchive b = component("t:ui/components/b", "t:ui/textures/icon",
                List.of(UiDependency.embedded("t:ui/textures/icon", UiDependency.Kind.IMAGE, blue, "assets/icon.png", null)),
                Map.of("assets/icon.png", blue));
        OmuiArchive doc = host(new java.util.LinkedHashMap<>(Map.of("t:ui/components/a", a, "t:ui/components/b", b)));
        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forDocument(doc, List.of()), List.of(),
                new MTextureCache());
        assertNotNull(assets.component("t:ui/components/a"));
        assertNotNull(assets.component("t:ui/components/b"));

        UiImage inA = assets.image("t:ui/components/a", "t:ui/textures/icon");
        UiImage inB = assets.image("t:ui/components/b", "t:ui/textures/icon");

        assertEquals(2, inA.still().texture().width());
        assertEquals(5, inB.still().texture().width(), "b's snapshot, not the first table that lists the id");
    }

    @Test
    void exportAcceptsAComponentThatEmbedsItsOwnSpriteSheet() throws Exception {
        UiBytes texture = UiBytes.copyOf(frameTexture());
        UiBytes sheet = UiBytes.copyOf(sheetBytes(frameSheet()));
        UiDependency sheetRow = with(UiDependency.embedded(SHEET_ID, UiDependency.Kind.SPRITES, sheet,
                "assets/frame.sprites.json", null), List.of(TEXTURE_ID), false, null);
        OmuiArchive comp = component("t:ui/components/framed", SHEET_ID + "#panel",
                List.of(sheetRow, UiDependency.embedded(TEXTURE_ID, UiDependency.Kind.TEXTURE, texture,
                        "assets/frame.omt", null)),
                Map.of("assets/frame.sprites.json", sheet, "assets/frame.omt", texture));
        OmuiArchive doc = UiDocs.declare(host(Map.of("t:ui/components/framed", comp)), List.of(UiFeatures.SPRITES));

        ExportPlan plan = ExportPlanner.plan(doc, List.of(), ExportPlanner.Request.of(ExportMode.COLLECT_ALL));
        assertFalse(plan.blocked(), plan.diagnostics()::toString);

        // the component's own sheet is geometry-checked: a reference to a missing sprite still blocks
        OmuiArchive broken = component("t:ui/components/framed", SHEET_ID + "#nope", comp.dependencies().entries(),
                comp.assets());
        ExportPlan bad = ExportPlanner.plan(UiDocs.declare(host(Map.of("t:ui/components/framed", broken)),
                List.of(UiFeatures.SPRITES)), List.of(), ExportPlanner.Request.of(ExportMode.COLLECT_ALL));
        assertTrue(bad.blocked());
        assertTrue(bad.diagnostics().stream().anyMatch(d -> d.code() == Code.UNKNOWN_SPRITE), bad.diagnostics()::toString);
    }

    @Test
    void undoOfAMultiFileEditIsAllOrNothing() throws Exception {
        ProjectFolder folder = new ProjectFolder(tmp);
        OmuiArchive before = UiSamples.stoneButton();
        AssetEdit edit = new AssetEdit("import", before, before, List.of(
                new ProjectWrite("UI/a.png", null, bytes("a")),
                new ProjectWrite("UI/b.png", null, bytes("b")),
                new ProjectWrite("UI/c.png", null, bytes("c"))), List.of());
        edit.apply(folder);
        folder.write("UI/a.png", bytes("the author edited a"));

        assertThrows(IOException.class, () -> edit.undo(folder));

        assertEquals(bytes("b"), folder.read("UI/b.png"), "nothing was reverted: the document still points at b");
        assertEquals(bytes("c"), folder.read("UI/c.png"));
        assertEquals(bytes("the author edited a"), folder.read("UI/a.png"));

        folder.write("UI/a.png", bytes("a"));
        edit.undo(folder);
        assertNull(folder.read("UI/a.png"));
        assertNull(folder.read("UI/c.png"));
    }

    @Test
    void applyChecksEveryFileBeforeWritingAny() throws Exception {
        ProjectFolder folder = new ProjectFolder(tmp);
        folder.write("UI/c.png", bytes("someone else's"));
        OmuiArchive doc = UiSamples.stoneButton();
        AssetEdit edit = new AssetEdit("extract", doc, doc, List.of(
                new ProjectWrite("UI/a.png", null, bytes("a")),
                new ProjectWrite("UI/c.png", null, bytes("c"))), List.of());

        assertThrows(IOException.class, () -> edit.apply(folder));
        assertNull(folder.read("UI/a.png"), "refused before touching anything");
    }

    @Test
    void projectFolderRefusesSymlinksLeadingOutside() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path project = Files.createDirectories(tmp.resolve("project"));
        try {
            Files.createSymbolicLink(project.resolve("UI"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable: " + e);
        }
        ProjectFolder folder = new ProjectFolder(project);

        assertThrows(IOException.class, () -> folder.read("UI/secret.txt"));
        assertThrows(IOException.class, () -> folder.write("UI/new.png", bytes("x")));
        assertFalse(Files.exists(outside.resolve("new.png")));
        assertFalse(folder.exists("UI/secret.txt"));

        folder.write("inside/ok.png", bytes("ok"));
        assertEquals(bytes("ok"), folder.read("inside/ok.png"));
    }

    @Test
    void importedRowsLandUnderTheUiFolderWhateverTheirHint() throws Exception {
        UiBytes theme = UiBytes.copyOf(UiSamples.THEME_BYTES);
        OmuiArchive doc = withRow(UiSamples.pauseMenu(), UiDependency.shared(UiSamples.THEME_ID,
                UiDependency.Kind.STYLESHEET, theme.sha256(), theme.size(), "settings/project.json"));
        Path p = tmp.resolve("src");
        ProjectAssetSource source = pauseProject(p);
        source.folder().write("settings/project.json", theme);
        var result = com.openmason.engine.ui.assets.export.UiExportService.export(doc, List.of(source),
                ExportPlanner.Request.of(ExportMode.COLLECT_ALL), null, List.of());
        Path out = tmp.resolve("pause.sbui");
        com.openmason.engine.ui.assets.export.UiExportService.save(result, out);
        SbuiArchive sbui = com.openmason.engine.format.sbui.SbuiReader.read(out,
                com.openmason.engine.format.sbui.SbuiReader.Options.EDITOR).archive();

        ProjectAssetSource fresh = new ProjectAssetSource(new ProjectFolder(tmp.resolve("dst")), "UI/");
        SbuiProjectImport.Result r = SbuiProjectImport.importIntoProject(sbui, fresh);

        assertFalse(r.edit().writes().isEmpty());
        for (ProjectWrite w : r.edit().writes()) {
            assertTrue(w.path().startsWith("UI/"), w.path());
        }
    }

    @Test
    void releasedTexturesCloseAfterTheirGrace() {
        MTextureCache cache = new MTextureCache();
        MTexture t = cache.get("k", key -> MTexture.decode(key, solid(2, 2, 0xFFFFFFFF)));
        assertNotNull(t.image());

        assertTrue(cache.release("k"));
        cache.drainReleases(System.nanoTime());
        assertFalse(t.isClosed(), "a frame already holding the image may still be drawing it");
        assertEquals(1, cache.pendingReleases());

        cache.drainReleases(System.nanoTime() + MTextureCache.RELEASE_GRACE_NANOS + 1);
        assertTrue(t.isClosed());
        assertNull(t.image(), "a holder that missed the release draws nothing instead of a closed image");
        assertNull(t.region(0, 0, 1, 1));
        assertEquals(0, cache.pendingReleases());
        assertNotSame(t, cache.get("k", key -> MTexture.decode(key, solid(2, 2, 0xFFFFFFFF))));
    }

    @Test
    void aLoadFinishingAfterCloseIsReleasedNotInstalled() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();
        UiAssetCache<String> cache = new UiAssetCache<>(id -> new ResolvedAsset(id, UiDependency.Kind.IMAGE,
                bytes("x"), AssetOrigin.PROJECT, "project", "x"), a -> {
            loading.countDown();
            proceed.await();
            return "value";
        }, v -> released.incrementAndGet());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = cache.getAsync("id", pool);
            loading.await();
            cache.close();
            proceed.countDown();
            assertNull(future.get());
            assertEquals(1, released.get());
            assertNull(cache.peek("id"));
            assertNull(cache.get("id"), "a closed cache loads nothing");
        } finally {
            pool.shutdownNow();
        }
    }
}
