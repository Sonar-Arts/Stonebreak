package com.openmason.engine.ui.assets.export;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiManifest.HostRequirement;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.ui.assets.MountedAssetSource;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.export.PlanItem.Action;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.assets.UiAssetFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Export planning: closure, actions, blocking findings and the report (#285). */
class ExportPlannerTest {

    private static final String PANEL = UiSamples.PANEL_TEXTURE_ID;

    @TempDir
    Path tmp;
    ProjectAssetSource project;
    OmuiArchive pause;

    @BeforeEach
    void setUp() throws Exception {
        project = pauseProject(tmp);
        pause = UiSamples.pauseMenu();
    }

    @Test
    void sharedExportListsWhatMustShip() {
        ExportPlan plan = ExportPlanner.plan(pause, List.of(project), new ExportPlanner.Request(ExportMode.SHARED,
                Map.of(PANEL, "stonebreak:ui-core")));

        assertFalse(plan.blocked(), plan.diagnostics()::toString);
        assertTrue(plan.collected().isEmpty());
        assertEquals(List.of(UiSamples.COMMON_LUA_ID, PANEL, UiSamples.THEME_ID),
                plan.mustShip().stream().map(PlanItem::id).toList());
        assertEquals("stonebreak:ui-core", plan.item(PANEL).pack());
        assertEquals(Action.SOURCE_EMBEDDED, plan.item(UiSamples.BUTTON_ID).action());
        assertEquals("project:" + PANEL_HINT, plan.item(PANEL).location());
        assertEquals(List.of("stonebreak:network.resync", "stonebreak:screen.pause", "stonebreak:session"),
                plan.hostApis().stream().map(HostRequirement::id).toList(), "host contracts listed apart from artwork");
    }

    @Test
    void collectAllCollectsTheClosure() {
        ExportPlan plan = ExportPlanner.plan(pause, List.of(project), ExportPlanner.Request.of(ExportMode.COLLECT_ALL));

        assertFalse(plan.blocked(), plan.diagnostics()::toString);
        assertEquals(3, plan.collected().size());
        assertTrue(plan.mustShip().isEmpty());
        assertEquals(UiBytes.copyOf(UiSamples.PANEL_TEXTURE_BYTES), plan.collected().get(PANEL));
    }

    @Test
    void missingRequiredBlocksBothModes() throws Exception {
        project.folder().delete(PANEL_HINT);
        for (ExportMode mode : ExportMode.values()) {
            ExportPlan plan = ExportPlanner.plan(pause, List.of(project), ExportPlanner.Request.of(mode));
            assertTrue(plan.blocked(), mode::name);
            assertEquals(Action.MISSING, plan.item(PANEL).action());
            assertThrows(UiFormatException.class, () -> UiExportService.export(pause, List.of(project),
                    ExportPlanner.Request.of(mode), null, List.of()));
        }
    }

    @Test
    void optionalMissingUsesFallbackWithoutBlocking() throws Exception {
        String fancy = "stonebreak:ui/textures/fancy";
        OmuiArchive doc = screenUsing("stonebreak:ui/s", fancy, bytes("fancy"), null);
        doc = withRow(doc, with(doc.dependencies().find(fancy), List.of(), true, PANEL));
        doc = withRow(doc, UiDependency.shared(PANEL, UiDependency.Kind.TEXTURE,
                UiBytes.sha256(UiSamples.PANEL_TEXTURE_BYTES), UiSamples.PANEL_TEXTURE_BYTES.length, PANEL_HINT));

        ExportPlan plan = ExportPlanner.plan(doc, List.of(project), ExportPlanner.Request.of(ExportMode.COLLECT_ALL));

        assertFalse(plan.blocked(), plan.diagnostics()::toString);
        assertEquals(Action.FALLBACK, plan.item(fancy).action());
        assertEquals(PANEL, plan.item(fancy).fallback());
        assertEquals(Action.COLLECTED, plan.item(PANEL).action());
    }

    @Test
    void requiresCycleBlocks() {
        UiDependency theme = pause.dependencies().find(UiSamples.THEME_ID);
        UiDependency panel = pause.dependencies().find(PANEL);
        OmuiArchive doc = withRow(withRow(pause, with(theme, List.of(PANEL), false, null)),
                with(panel, List.of(UiSamples.THEME_ID), false, null));

        ExportPlan plan = ExportPlanner.plan(doc, List.of(project), ExportPlanner.Request.of(ExportMode.SHARED));

        assertTrue(plan.blocked());
        assertTrue(has(plan, Code.DEPENDENCY_CYCLE), plan.diagnostics()::toString);
    }

    @Test
    void collectedEntryCollisionsBlock() throws Exception {
        // assets/stonebreak/ui/t.sbt would be both a file and the directory of t.sbt/inner.sbt.
        UiBytes a = bytes("a");
        UiBytes b = bytes("b");
        project.folder().write("x/a.sbt", a);
        project.folder().write("x/b.sbt", b);
        OmuiArchive doc = screenUsing("stonebreak:ui/s", "stonebreak:ui/t", a, "x/a.sbt");
        doc = withRow(doc, UiDependency.shared("stonebreak:ui/t.sbt/inner", UiDependency.Kind.TEXTURE, b.sha256(),
                b.size(), "x/b.sbt"));

        ExportPlan plan = ExportPlanner.plan(doc, List.of(project), ExportPlanner.Request.of(ExportMode.COLLECT_ALL));

        assertTrue(plan.blocked(), plan.diagnostics()::toString);
        assertTrue(has(plan, Code.DUPLICATE_ENTRY));
        assertFalse(ExportPlanner.plan(doc, List.of(project), ExportPlanner.Request.of(ExportMode.SHARED)).blocked(),
                "nothing is collected in a shared export, so nothing collides");
    }

    @Test
    void componentNeedingAnUnlistedSharedDependencyBlocks() throws Exception {
        // A shared component whose own table needs a texture the screen's table does not list.
        String compId = "stonebreak:ui/components/badge";
        OmuiArchive badge = screenUsing(compId, "stonebreak:ui/textures/badge", bytes("badge"), "badge.sbt");
        badge = new OmuiArchive(new UiManifest(badge.manifest().schemaVersion(), compId,
                UiManifest.DocumentKind.COMPONENT, "", badge.manifest().uiApi(), badge.manifest().layoutSemantics(),
                List.of(), List.of(), List.of(new HostRequirement("stonebreak:item-icon", 2)), Map.of()),
                new UiDocument(badge.document().root(), List.of(), null,
                        new UiDocument.ComponentDef(List.of(), List.of(), List.of(), Map.of()), Map.of()),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), badge.dependencies(), Map.of(), Map.of(), Map.of());
        UiBytes badgeBytes = UiBytes.copyOf(OmuiWriter.write(badge));
        project.folder().write("ui/components/badge.omui", badgeBytes);

        UiNode inst = new UiNode("b", null, UiNode.INSTANCE_TYPE, 1, List.of(), Map.of(), Map.of(), null, List.of(),
                new UiNode.ComponentInstance(compId, Map.of(), List.of(), Map.of(), Map.of()), List.of(), Map.of());
        OmuiArchive screen = OmuiArchive.of(UiManifest.create("stonebreak:ui/hud", UiManifest.DocumentKind.SCREEN, ""),
                        new UiDocument(UiNode.of("root", "Box", List.of(inst)), List.of(), null, null, Map.of()))
                .withDependencies(new UiDependencies(List.of(UiDependency.shared(compId, UiDependency.Kind.COMPONENT,
                        badgeBytes.sha256(), badgeBytes.size(), "ui/components/badge.omui")), Map.of()));

        ExportPlan plan = ExportPlanner.plan(screen, List.of(project), ExportPlanner.Request.of(ExportMode.SHARED));
        assertTrue(plan.blocked());
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.isError() && d.code() == Code.UNRESOLVED_REFERENCE
                && d.message().contains("stonebreak:ui/textures/badge")), plan.diagnostics()::toString);
        assertEquals(List.of("stonebreak:item-icon"), plan.providers().stream().map(HostRequirement::id).toList(),
                "component providers join the host requirements");
    }

    @Test
    void recursiveCompositionThroughSharedComponentsBlocks() throws Exception {
        String a = "stonebreak:ui/components/a";
        String b = "stonebreak:ui/components/b";
        // a instances b and b instances a; a is the document being exported.
        OmuiArchive compB = component(b, a, UiBytes.utf8("placeholder"));
        UiBytes bBytes = UiBytes.copyOf(OmuiWriter.write(compB));
        project.folder().write("b.omui", bBytes);
        OmuiArchive compA = component(a, b, bBytes);
        ExportPlan plan = ExportPlanner.plan(compA, List.of(project), ExportPlanner.Request.of(ExportMode.SHARED));
        assertTrue(has(plan, Code.RECURSIVE_COMPONENT), plan.diagnostics()::toString);
        assertTrue(plan.blocked());
    }

    @Test
    void fontsAndSoundsNeedLicencesToBeRedistributed() throws Exception {
        UiBytes font = bytes("ttf bytes");
        project.folder().write("fonts/ui.ttf", font);
        OmuiArchive doc = withRow(pause, UiDependency.shared("stonebreak:ui/fonts/ui", UiDependency.Kind.FONT,
                font.sha256(), font.size(), "fonts/ui.ttf"));

        assertTrue(ExportPlanner.plan(doc, List.of(project), ExportPlanner.Request.of(ExportMode.COLLECT_ALL)).blocked());
        ExportPlan shared = ExportPlanner.plan(doc, List.of(project), ExportPlanner.Request.of(ExportMode.SHARED));
        assertFalse(shared.blocked());
        assertTrue(has(shared, Code.MISSING_FIELD));

        UiDependency licensed = new UiDependency("stonebreak:ui/fonts/ui", UiDependency.Kind.FONT, null, font.sha256(),
                font.size(), UiDependency.Mode.SHARED, null, "fonts/ui.ttf", List.of(), false, null, "OFL-1.1", Map.of());
        ExportPlan ok = ExportPlanner.plan(withRow(pause, licensed), List.of(project),
                ExportPlanner.Request.of(ExportMode.COLLECT_ALL));
        assertFalse(ok.blocked(), ok.diagnostics()::toString);
        assertEquals("OFL-1.1", ok.item("stonebreak:ui/fonts/ui").license());
    }

    @Test
    void shadowedSourcesAreReported() {
        MountedAssetSource packaged = MountedAssetSource.packaged("", path -> path.equals(
                "stonebreak/ui/textures/panel.sbt") ? bytes("packaged copy") : null);
        ExportPlan plan = ExportPlanner.plan(pause, List.of(project, packaged),
                ExportPlanner.Request.of(ExportMode.SHARED));

        assertTrue(has(plan, Code.ASSET_SHADOWED));
        assertEquals(List.of("packaged:stonebreak/ui/textures/panel.sbt"), plan.item(PANEL).shadowed());
    }

    @Test
    void reportIsDeterministicCanonicalJson() {
        ExportPlan plan = ExportPlanner.plan(pause, List.of(project), ExportPlanner.Request.of(ExportMode.SHARED));
        byte[] first = ExportReport.json(plan).toArray();
        byte[] second = ExportReport.json(ExportPlanner.plan(pause, List.of(project),
                ExportPlanner.Request.of(ExportMode.SHARED))).toArray();

        assertArrayEquals(first, second);
        String text = new String(first, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"action\": \"ships-shared\""), text);
        assertTrue(text.contains("\"source\": \"project:" + PANEL_HINT + "\""), text);
        assertFalse(text.contains(tmp.toString()), "no machine paths in the report");
    }

    @Test
    void unusedRowsAreInfoNotErrors() {
        UiBytes extra = bytes("extra");
        OmuiArchive doc = withRow(pause, UiDependency.shared("stonebreak:ui/textures/unused", UiDependency.Kind.TEXTURE,
                extra.sha256(), extra.size(), null));
        ExportPlan plan = ExportPlanner.plan(doc, List.of(project), ExportPlanner.Request.of(ExportMode.SHARED));
        UiDiagnostic unused = plan.diagnostics().stream().filter(d -> d.code() == Code.UNUSED_DEPENDENCY)
                .findFirst().orElseThrow();
        assertEquals(UiDiagnostic.Severity.INFO, unused.severity());
    }

    private static OmuiArchive component(String id, String instanced, UiBytes instancedBytes) {
        UiNode inst = new UiNode("i", null, UiNode.INSTANCE_TYPE, 1, List.of(), Map.of(), Map.of(), null, List.of(),
                new UiNode.ComponentInstance(instanced, Map.of(), List.of(), Map.of(), Map.of()), List.of(), Map.of());
        return OmuiArchive.of(UiManifest.create(id, UiManifest.DocumentKind.COMPONENT, ""),
                        new UiDocument(UiNode.of("root", "Box", List.of(inst)), List.of(), null,
                                new UiDocument.ComponentDef(List.of(), List.of(), List.of(), Map.of()), Map.of()))
                .withDependencies(new UiDependencies(List.of(UiDependency.shared(instanced, UiDependency.Kind.COMPONENT,
                        instancedBytes.sha256(), instancedBytes.size(), "b.omui")), Map.of()));
    }

    private static boolean has(ExportPlan plan, Code code) {
        return plan.diagnostics().stream().anyMatch(d -> d.code() == code);
    }
}
