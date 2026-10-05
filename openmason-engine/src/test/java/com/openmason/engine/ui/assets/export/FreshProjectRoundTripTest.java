package com.openmason.engine.ui.assets.export;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency.Mode;
import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiImporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.ui.assets.AssetKinds;
import com.openmason.engine.ui.assets.AssetOrigin;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.HostCompatibility;
import com.openmason.engine.ui.assets.MountedAssetSource;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ProjectFolder;
import com.openmason.engine.ui.assets.Resolution;
import com.openmason.engine.ui.assets.ResourceOpener;
import com.openmason.engine.ui.assets.edit.SbuiProjectImport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.openmason.engine.ui.assets.UiAssetFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Export from one project, import into a clean one, and run against a deployed resource root
 * with no Open Mason workspace (#285 acceptance).
 */
@Tag("integration")
class FreshProjectRoundTripTest {

    private static final UiHostProfile GAME = new UiHostProfile(1, Set.of("flex-1"),
            Map.of("stonebreak:screen.pause", 1, "stonebreak:session", 1, "stonebreak:network.resync", 1), Map.of());

    @TempDir
    Path tmp;

    @Test
    void collectAllOpensEditableInACleanProject() throws Exception {
        ProjectAssetSource source = pauseProject(tmp.resolve("authoring"));
        OmuiArchive pause = UiSamples.pauseMenu();
        Path out = tmp.resolve("out/pause_menu.sbui");
        Files.createDirectories(out.getParent());

        UiExportService.Result result = UiExportService.export(pause, List.of(source),
                ExportPlanner.Request.of(ExportMode.COLLECT_ALL), null, List.of());
        UiExportService.save(result, out);
        assertTrue(Files.exists(UiExportService.reportPath(out)), "report written next to the export");

        SbuiArchive sbui = SbuiReader.read(out, SbuiReader.Options.EDITOR).archive();

        // Portable: everything embedded in the OMUI, opens in an empty project.
        Path cleanRoot = tmp.resolve("clean");
        Files.createDirectories(cleanRoot);
        ProjectAssetSource clean = new ProjectAssetSource(new ProjectFolder(cleanRoot), CONVENTION_DIR);
        OmuiArchive portable = SbuiImporter.importPortable(sbui).document();
        Resolution r = AssetResolver.forDocument(portable, List.of(clean)).resolveAll();
        assertTrue(r.complete(), r.diagnostics()::toString);
        r.assets().values().forEach(a -> assertEquals(AssetOrigin.DOCUMENT, a.origin(), a.id()));
        assertEquals(pause.document(), portable.document(), "source stays editable and unchanged");
        assertEquals(portable, OmuiReader.read(OmuiWriter.write(portable)).archive());

        // Project import: collected assets become shared project files in the clean project.
        SbuiProjectImport.Result imported = SbuiProjectImport.importIntoProject(sbui, clean);
        OmuiArchive doc = imported.edit().apply(clean.folder());
        assertTrue(imported.remapped().isEmpty());
        Resolution viaProject = AssetResolver.forDocument(doc, List.of(clean)).resolveAll();
        assertTrue(viaProject.complete(), viaProject.diagnostics()::toString);
        assertEquals(AssetOrigin.PROJECT, viaProject.get(UiSamples.PANEL_TEXTURE_ID).origin());
        assertEquals(UiBytes.copyOf(UiSamples.PANEL_TEXTURE_BYTES), clean.folder().read(PANEL_HINT));
    }

    @Test
    void sharedExportResolvesFromADeployedRootWithoutAWorkspace() throws Exception {
        ProjectAssetSource source = pauseProject(tmp.resolve("authoring"));
        OmuiArchive pause = UiSamples.pauseMenu();
        UiExportService.Result result = UiExportService.export(pause, List.of(source),
                ExportPlanner.Request.of(ExportMode.SHARED), "stonebreak:ui/pause", List.of());

        // Deploy exactly what the plan says must ship, by convention, into a game resource root.
        Path deployed = tmp.resolve("game-resources");
        ProjectFolder root = new ProjectFolder(deployed);
        Resolution authored = AssetResolver.forDocument(pause, List.of(source)).resolveAll();
        for (PlanItem item : result.plan().mustShip()) {
            root.write("ui/shared/" + AssetKinds.candidates(item.id(), item.kind()).getFirst(),
                    authored.get(item.id()).bytes());
        }
        MountedAssetSource packaged = MountedAssetSource.packaged("ui/shared/", ResourceOpener.directory(deployed));

        HostCompatibility ok = HostCompatibility.check(result.sbui(), GAME, List.of(packaged));
        assertTrue(ok.runnable(), () -> ok.host() + " " + ok.assets());

        // The authoring project vanishing changes nothing for the game.
        Resolution r = AssetResolver.forExport(result.sbui(), List.of(packaged)).resolveAll();
        assertTrue(r.complete());
        assertEquals(Mode.EMBEDDED, result.sbui().source().dependencies().find(UiSamples.BUTTON_ID).mode());
    }

    @Test
    void hostWithoutRequiredContractsReportsIncompatibility() throws Exception {
        ProjectAssetSource source = pauseProject(tmp.resolve("authoring"));
        SbuiArchive sbui = UiExportService.export(UiSamples.pauseMenu(), List.of(source),
                ExportPlanner.Request.of(ExportMode.COLLECT_ALL), null, List.of()).sbui();
        UiHostProfile bare = new UiHostProfile(1, Set.of("flex-1"), Map.of("stonebreak:session", 1), Map.of());

        HostCompatibility c = HostCompatibility.check(sbui, bare, List.of());

        assertTrue(c.assets().stream().noneMatch(d -> d.isError()), "artwork is all there");
        assertFalse(c.runnable(), "but the host lacks stonebreak:screen.pause");
        assertTrue(c.host().stream().anyMatch(d -> d.isError() && d.message().contains("stonebreak:screen.pause")));
        assertTrue(c.host().stream().anyMatch(d -> !d.isError() && d.message().contains("network.resync")),
                "optional contract degrades with a warning");
    }

    @Test
    void importIntoAProjectWithConflictingIdsRemapsInsteadOfOverwriting() throws Exception {
        ProjectAssetSource source = pauseProject(tmp.resolve("authoring"));
        SbuiArchive sbui = UiExportService.export(UiSamples.pauseMenu(), List.of(source),
                ExportPlanner.Request.of(ExportMode.COLLECT_ALL), null, List.of()).sbui();

        ProjectAssetSource target = pauseProject(tmp.resolve("target"));
        UiBytes theirs = bytes("the target project's own panel");
        target.folder().write(PANEL_HINT, theirs);

        SbuiProjectImport.Result imported = SbuiProjectImport.importIntoProject(sbui, target);
        OmuiArchive doc = imported.edit().apply(target.folder());

        assertEquals(Map.of(UiSamples.PANEL_TEXTURE_ID, UiSamples.PANEL_TEXTURE_ID + "-imported"), imported.remapped());
        assertEquals(theirs, target.folder().read(PANEL_HINT), "existing project asset untouched");
        Resolution r = AssetResolver.forDocument(doc, List.of(target)).resolveAll();
        assertTrue(r.complete(), r.diagnostics()::toString);
        assertEquals(UiBytes.copyOf(UiSamples.PANEL_TEXTURE_BYTES), r.get(UiSamples.PANEL_TEXTURE_ID + "-imported").bytes());
        assertNull(doc.dependencies().find(UiSamples.PANEL_TEXTURE_ID));
        // Identical theme and script were reused, not duplicated.
        assertEquals(1, imported.edit().writes().size());

        imported.edit().undo(target.folder());
        assertNull(target.folder().read(r.get(UiSamples.PANEL_TEXTURE_ID + "-imported").location()));
    }
}
