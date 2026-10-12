package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiImporter;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ProjectFolder;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.engine.ui.assets.export.ExportPlan;
import com.openmason.engine.ui.assets.export.ExportPlanner;
import com.openmason.engine.ui.masonry.textures.MTextureCache;
import com.openmason.engine.ui.runtime.paint.ResolvedUiAssets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.assets.UiAssetFixtures.CONVENTION_DIR;
import static com.openmason.engine.ui.assets.UiAssetFixtures.pauseProject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declared Lua modules resolve through #285 asset resolution (#292): the golden pause menu's
 * code-behind requires the shared {@code stonebreak:ui/scripts/common} module, which must load
 * from a moved project, from a collect-all export with no project at all, and from a portable
 * import opened in an empty project.
 */
class ScriptModuleResolutionTest {

    @TempDir
    Path tmp;

    private static ScriptRig rig(OmuiArchive doc, AssetResolver resolver, List<? extends AssetSource> sources) {
        ResolvedUiAssets assets = new ResolvedUiAssets(resolver, sources, new MTextureCache());
        return new ScriptRig(doc, assets, null, UiScriptOptions.DEFAULTS, UiScriptServices.NONE);
    }

    private static void assertModulesLoaded(ScriptRig rig) {
        assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
        assertTrue(rig.rt.modules().contains("pause.lua"), rig.rt.modules().toString());
    }

    @Test
    void sharedModuleResolvesFromAMovedProject() throws Exception {
        Path original = tmp.resolve("original");
        pauseProject(original);
        Path moved = tmp.resolve("somewhere else/renamed");
        Files.createDirectories(moved.getParent());
        Files.move(original, moved);
        ProjectAssetSource project = new ProjectAssetSource(new ProjectFolder(moved), CONVENTION_DIR);
        OmuiArchive pause = UiSamples.pauseMenu();
        try (ScriptRig rig = rig(pause, AssetResolver.forDocument(pause, List.of(project)), List.of(project))) {
            assertModulesLoaded(rig);
        }
    }

    @Test
    void sharedModuleResolvesFromACollectAllExportWithoutTheProject() throws Exception {
        ProjectAssetSource project = pauseProject(tmp.resolve("p"));
        OmuiArchive pause = UiSamples.pauseMenu();
        ExportPlan plan = ExportPlanner.plan(pause, List.of(project), ExportPlanner.Request.of(ExportMode.COLLECT_ALL));
        assertTrue(plan.collected().containsKey(UiSamples.COMMON_LUA_ID), plan.collected().keySet().toString());
        SbuiArchive sbui = SbuiExporter.export(pause, new SbuiExporter.Options(null, plan.collected(), true, Map.of(),
            List.of())).archive();
        try (ScriptRig rig = rig(sbui.source(), AssetResolver.forExport(sbui, List.of()), List.of())) {
            assertModulesLoaded(rig);
        }
    }

    @Test
    void sharedModuleResolvesAfterAPortableImportIntoAnEmptyProject() throws Exception {
        ProjectAssetSource project = pauseProject(tmp.resolve("p"));
        OmuiArchive pause = UiSamples.pauseMenu();
        ExportPlan plan = ExportPlanner.plan(pause, List.of(project), ExportPlanner.Request.of(ExportMode.COLLECT_ALL));
        SbuiArchive sbui = SbuiExporter.export(pause, new SbuiExporter.Options(null, plan.collected(), true, Map.of(),
            List.of())).archive();
        OmuiArchive imported = SbuiImporter.importPortable(sbui).document();
        ProjectAssetSource empty = new ProjectAssetSource(new ProjectFolder(tmp.resolve("empty")), CONVENTION_DIR);
        try (ScriptRig rig = rig(imported, AssetResolver.forDocument(imported, List.of(empty)), List.of(empty))) {
            assertModulesLoaded(rig);
        }
    }

    @Test
    void embeddedModuleTravelsWithTheDocumentFileAndItsExport() throws Exception {
        String id = "t:ui/scripts/embedded_util";
        UiBytes bytes = UiBytes.utf8("return { greet = function() return 'from embedded' end }");
        OmuiArchive doc = ScriptRig.withCode(UiDocs.screen("t:ui/emb", ScriptBehaviourTest.root(
                UiDocs.label("out", "").name("out"))), """
            local util = require("t:ui/scripts/embedded_util")
            function on_open(ui) ui.q("#out"):setText(util.greet()) end
            """)
            .withAsset("assets/scripts/embedded_util.lua", bytes)
            .withDependencies(new OmuiArchive.UiDependencies(List.of(UiDependency.embedded(id,
                UiDependency.Kind.SCRIPT, bytes, "assets/scripts/embedded_util.lua", null)), Map.of()));
        // Save, move the file somewhere else, read it back: the module lives inside the archive.
        Path saved = tmp.resolve("a/doc.omui");
        Files.createDirectories(saved.getParent());
        OmuiWriter.save(doc, saved);
        Path moved = tmp.resolve("b/elsewhere/doc.omui");
        Files.createDirectories(moved.getParent());
        Files.move(saved, moved);
        OmuiArchive reread = OmuiReader.read(moved).archive();
        try (ScriptRig rig = rig(reread, AssetResolver.forDocument(reread, List.of()), List.of())) {
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            assertEquals("from embedded", rig.text("out"));
        }
        // A shared export keeps the embedded row in its embedded source document.
        SbuiArchive sbui = SbuiExporter.export(reread, SbuiExporter.Options.shared()).archive();
        try (ScriptRig rig = rig(sbui.source(), AssetResolver.forExport(sbui, List.of()), List.of())) {
            assertEquals("from embedded", rig.text("out"), rig.rt.diagnostics().toString());
        }
    }

    @Test
    void aMissingSharedModuleIsAnErrorNamingIt() throws Exception {
        ProjectAssetSource project = pauseProject(tmp.resolve("p"));
        project.folder().delete("ui/scripts/common.lua");
        OmuiArchive pause = UiSamples.pauseMenu();
        try (ScriptRig rig = rig(pause, AssetResolver.forDocument(pause, List.of(project)), List.of(project))) {
            UiScriptDiagnostic d = rig.rt.diagnostics().getFirst();
            assertEquals("pause.lua:2", d.location(), d.toString());
            assertTrue(d.message().contains("stonebreak:ui/scripts/common"), d.message());
        }
    }
}
