package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.assets.UiAssetFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Resolution precedence, ownership, relocation and fallbacks (#285). */
class AssetResolverTest {

    @TempDir
    Path tmp;

    @Test
    void sharedRowsResolveThroughProjectHints() throws Exception {
        ProjectAssetSource project = pauseProject(tmp.resolve("p"));
        Resolution r = AssetResolver.forDocument(UiSamples.pauseMenu(), List.of(project)).resolveAll();

        assertTrue(r.complete(), r.diagnostics()::toString);
        ResolvedAsset panel = r.get(UiSamples.PANEL_TEXTURE_ID);
        assertEquals(AssetOrigin.PROJECT, panel.origin());
        assertEquals(PANEL_HINT, panel.location());
        assertEquals(UiBytes.copyOf(UiSamples.PANEL_TEXTURE_BYTES), panel.bytes());
        assertEquals(AssetOrigin.DOCUMENT, r.get(UiSamples.BUTTON_ID).origin());
    }

    @Test
    void embeddedSnapshotIsNeverReplacedByAProjectResourceWithTheSameId() throws Exception {
        ProjectAssetSource project = pauseProject(tmp.resolve("p"));
        // A project copy of the component exists at the row's hint and at the convention path, with other bytes.
        UiBytes local = bytes("a different, newer stone button");
        project.folder().write("ui/components/stone_button.omui", local);
        project.folder().write(project.conventionPath(UiSamples.BUTTON_ID, UiDependency.Kind.COMPONENT, null), local);

        OmuiArchive pause = UiSamples.pauseMenu();
        ResolvedAsset button = AssetResolver.forDocument(pause, List.of(project)).resolveAll().get(UiSamples.BUTTON_ID);

        assertEquals(AssetOrigin.DOCUMENT, button.origin());
        assertEquals(pause.assets().get("assets/components/stone_button.omui"), button.bytes());
    }

    @Test
    void mixedDocumentKeepsSnapshotWhenTheSharedOriginalChanges() throws Exception {
        ProjectAssetSource project = pauseProject(tmp.resolve("p"));
        OmuiArchive pause = UiSamples.pauseMenu();
        UiBytes snapshot = pause.assets().get("assets/components/stone_button.omui");
        // The component's shared original is edited after the snapshot was taken.
        project.folder().write("ui/components/stone_button.omui", bytes("edited in the project"));
        project.folder().write(PANEL_HINT, bytes("edited panel"));

        Resolution r = AssetResolver.forDocument(pause, List.of(project)).resolveAll();

        assertEquals(snapshot, r.get(UiSamples.BUTTON_ID).bytes(), "embedded stays the snapshot");
        assertEquals(bytes("edited panel"), r.get(UiSamples.PANEL_TEXTURE_ID).bytes(), "shared follows the project");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code() == Code.HASH_MISMATCH && !d.isError()),
                "shared drift is reported, not fatal");
    }

    @Test
    void movedProjectResolvesWithoutRepair() throws Exception {
        Path original = tmp.resolve("original");
        pauseProject(original);
        Path moved = tmp.resolve("elsewhere/renamed project");
        Files.createDirectories(moved.getParent());
        Files.move(original, moved);

        ProjectAssetSource project = new ProjectAssetSource(new ProjectFolder(moved), CONVENTION_DIR);
        Resolution r = AssetResolver.forDocument(UiSamples.pauseMenu(), List.of(project)).resolveAll();

        assertTrue(r.complete(), r.diagnostics()::toString);
        assertEquals(4, r.assets().size());
        r.assets().values().forEach(a -> assertFalse(a.location().startsWith("/"), "locations stay relative"));
    }

    @Test
    void conventionPathServesRowsWithStaleHints() throws Exception {
        ProjectFolder folder = new ProjectFolder(tmp);
        ProjectAssetSource project = new ProjectAssetSource(folder, CONVENTION_DIR);
        UiBytes tex = bytes("tex");
        folder.write("UI/stonebreak/ui/textures/panel.sbt", tex);
        OmuiArchive doc = screenUsing("stonebreak:ui/s", UiSamples.PANEL_TEXTURE_ID, tex, "gone/panel.sbt");

        ResolvedAsset a = AssetResolver.forDocument(doc, List.of(project)).resolveAll().get(UiSamples.PANEL_TEXTURE_ID);

        assertEquals("UI/stonebreak/ui/textures/panel.sbt", a.location());
    }

    @Test
    void firstSourceOwnsAnIdAndShadowedCopiesAreVisible() throws Exception {
        ProjectAssetSource project = pauseProject(tmp.resolve("p"));
        MountedAssetSource packaged = MountedAssetSource.packaged("ui/shared/",
                memory(Map.of("ui/shared/stonebreak/ui/textures/panel.sbt", bytes("packaged panel"))));
        AssetResolver resolver = AssetResolver.forDocument(UiSamples.pauseMenu(), List.of(project, packaged));

        assertEquals(AssetOrigin.PROJECT, resolver.resolveAll().get(UiSamples.PANEL_TEXTURE_ID).origin());
        assertEquals(List.of("project", "packaged"),
                resolver.candidates(UiSamples.PANEL_TEXTURE_ID).stream().map(ResolvedAsset::source).toList());
    }

    @Test
    void missingRequiredIsAnErrorThatNamesWhereItLooked() throws Exception {
        ProjectAssetSource project = pauseProject(tmp.resolve("p"));
        project.folder().delete(PANEL_HINT);

        Resolution r = AssetResolver.forDocument(UiSamples.pauseMenu(), List.of(project)).resolveAll();

        assertFalse(r.complete());
        assertTrue(r.missing().contains(UiSamples.PANEL_TEXTURE_ID));
        UiDiagnostic e = r.diagnostics().stream().filter(UiDiagnostic::isError).findFirst().orElseThrow();
        assertEquals(Code.MISSING_ENTRY, e.code());
        assertTrue(e.message().contains("project"), e.message());
    }

    @Test
    void optionalFallsBackAndChainsAreLoopChecked() throws Exception {
        ProjectFolder folder = new ProjectFolder(tmp);
        ProjectAssetSource project = new ProjectAssetSource(folder, "");
        UiBytes plain = bytes("plain");
        folder.write("stonebreak/ui/textures/plain.sbt", plain);
        OmuiArchive doc = screenUsing("stonebreak:ui/s", "stonebreak:ui/textures/fancy", bytes("fancy"), null);
        UiDependency fancy = doc.dependencies().find("stonebreak:ui/textures/fancy");
        doc = withRow(doc, with(fancy, List.of(), true, "stonebreak:ui/textures/plain"));
        doc = withRow(doc, UiDependency.shared("stonebreak:ui/textures/plain", UiDependency.Kind.TEXTURE,
                plain.sha256(), plain.size(), null));

        Resolution r = AssetResolver.forDocument(doc, List.of(project)).resolveAll();
        assertTrue(r.complete(), r.diagnostics()::toString);
        assertEquals(plain, r.get("stonebreak:ui/textures/fancy").bytes());
        assertEquals("stonebreak:ui/textures/plain", r.fallbacks().get("stonebreak:ui/textures/fancy"));

        // a ↔ b optional fallback loop with neither present
        OmuiArchive loop = withRow(doc, with(doc.dependencies().find("stonebreak:ui/textures/plain"), List.of(), true,
                "stonebreak:ui/textures/fancy"));
        folder.delete("stonebreak/ui/textures/plain.sbt");
        Resolution looped = AssetResolver.forDocument(loop, List.of(project)).resolveAll();
        assertTrue(looped.diagnostics().stream().anyMatch(d -> d.code() == Code.DEPENDENCY_CYCLE));
    }

    @Test
    void runtimeRoutesPackRowsToTheirPackAndIgnoresHints() throws Exception {
        OmuiArchive doc = UiSamples.pauseMenu();
        SbuiArchive sbui = SbuiExporter.export(doc, new SbuiExporter.Options(null, Map.of(), false,
                Map.of(UiSamples.PANEL_TEXTURE_ID, "stonebreak:ui-core"), List.of())).archive();
        Map<String, UiBytes> root = new HashMap<>();
        root.put("ui/shared/stonebreak/ui/themes/stone.uss.json", UiBytes.copyOf(UiSamples.THEME_BYTES));
        root.put("ui/shared/stonebreak/ui/scripts/common.lua", UiBytes.copyOf(UiSamples.COMMON_LUA_BYTES));
        // In the default root too: must NOT be used for a row that names a pack.
        root.put("ui/shared/stonebreak/ui/textures/panel.sbt", bytes("wrong root copy"));
        MountedAssetSource packaged = MountedAssetSource.packaged("ui/shared/", memory(root));

        Resolution unmounted = AssetResolver.forExport(sbui, List.of(packaged)).resolveAll();
        assertFalse(unmounted.complete());
        assertTrue(unmounted.diagnostics().stream().anyMatch(d -> d.message().contains("not mounted")));

        MountedAssetSource pack = MountedAssetSource.pack("stonebreak:ui-core", memory(Map.of(
                "stonebreak/ui/textures/panel.sbt", UiBytes.copyOf(UiSamples.PANEL_TEXTURE_BYTES))));
        Resolution r = AssetResolver.forExport(sbui, List.of(packaged, pack)).resolveAll();
        assertTrue(r.complete(), r.diagnostics()::toString);
        assertEquals(AssetOrigin.PACK, r.get(UiSamples.PANEL_TEXTURE_ID).origin());
        assertEquals(AssetOrigin.PACKAGED, r.get(UiSamples.THEME_ID).origin());
        assertEquals(AssetOrigin.DOCUMENT, r.get(UiSamples.BUTTON_ID).origin());
    }

    @Test
    void projectFolderRefusesEscapingAndUnportablePaths() throws IOException {
        ProjectFolder folder = new ProjectFolder(tmp);
        for (String bad : List.of("../outside.sbt", "/abs.sbt", "a/./b.sbt", "C:/x.sbt", "a\\b.sbt", ".hidden/x")) {
            assertThrows(IOException.class, () -> folder.read(bad), bad);
            assertFalse(folder.exists(bad));
        }
        assertNull(folder.relativize(tmp.getParent()));
        assertEquals("a/b.sbt", folder.relativize(tmp.resolve("a").resolve("b.sbt")));
    }

    @Test
    void zipPacksResolveLikeDirectories() throws Exception {
        Path zip = tmp.resolve("core.zip");
        Files.write(zip, com.openmason.engine.format.omui.io.ArchiveIO.write(Map.of(
                "stonebreak/ui/scripts/common.lua", UiBytes.copyOf(UiSamples.COMMON_LUA_BYTES))));
        MountedAssetSource pack = MountedAssetSource.pack("stonebreak:core", ResourceOpener.zip(zip));

        ResolvedAsset a = pack.find(UiSamples.COMMON_LUA_ID, UiDependency.Kind.SCRIPT, "ignored/hint.lua");

        assertEquals(UiBytes.copyOf(UiSamples.COMMON_LUA_BYTES), a.bytes());
        assertEquals("stonebreak/ui/scripts/common.lua", a.location());
        assertNull(pack.find("stonebreak:ui/nope", UiDependency.Kind.SCRIPT, null));
    }

    static ResourceOpener memory(Map<String, UiBytes> files) {
        return files::get;
    }
}
