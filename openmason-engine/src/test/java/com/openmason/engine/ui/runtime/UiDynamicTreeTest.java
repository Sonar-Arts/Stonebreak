package com.openmason.engine.ui.runtime;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.component;
import static com.openmason.engine.ui.runtime.UiDocs.contract;
import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.override;
import static com.openmason.engine.ui.runtime.UiDocs.param;
import static com.openmason.engine.ui.runtime.UiDocs.rule;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Scroll containers, overlay layers, runtime structural edits, live reload and references. */
class UiDynamicTreeTest {

    private static final float EPS = 1e-3f;
    private static final String TILE = "t:ui/components/tile";

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
    }

    private static UiDocumentInstance laid(OmuiArchive doc, UiRuntimeContext ctx, float w, float h) {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, ctx);
        ui.setMetrics(UiMetrics.of(w, h, 1));
        ui.update();
        return ui;
    }

    private static UiDocumentInstance laid(OmuiArchive doc) {
        return laid(doc, UiRuntimeContext.basic(), 400, 300);
    }

    // ── scrolling ───────────────────────────────────────────────────────────

    private static OmuiArchive list(int rows) {
        UiDocs.N view = node("list", "ScrollView").style("width", 100).style("height", 60).style("padding-bottom", 5);
        for (int i = 0; i < rows; i++) {
            view.kids(box("row" + i).style("height", 20));
        }
        return screen("t:ui/list", box("root").kids(view));
    }

    @Test
    void scrollViewsClampMoveTheirContentAndNeverRelayout() {
        try (UiDocumentInstance ui = laid(list(5))) {
            UiElement list = ui.find("list");
            assertTrue(list.isScrollContainer());
            assertEquals(5 * 20 + 5 - 60, list.maxScrollY(), EPS, "content + padding beyond the view");
            assertEquals(0, list.maxScrollX(), EPS, "a vertical ScrollView never scrolls sideways");
            assertEquals(60, ui.find("row3").rect().y(), EPS);

            ui.consumeDirtyRegion();
            list.scrollTo(0, 1000);
            UiDocumentInstance.UpdateStats stats = ui.update();
            assertFalse(stats.laidOut(), "scrolling is a visual change, not a layout");
            assertEquals(45, list.scrollY(), EPS, "clamped to the content");
            assertEquals(60 - 45, ui.find("row3").rect().y(), EPS);
            assertEquals(60, ui.find("row3").layoutRect().y(), EPS, "layout geometry is untouched");
            UiRect dirty = ui.consumeDirtyRegion();
            assertTrue(dirty.y() >= -EPS && dirty.bottom() <= 105 + EPS, "only the view's content area: " + dirty);

            assertSame(ui.find("row2"), ui.hitTest(50, 1), "row2 (layout y 40) now sits at the top");
            assertNull(ui.hitTest(50, -30), "row0 scrolled above the view is clipped, not hit");
            assertSame(ui.find("row3"), ui.hitTest(50, 20));
            assertNotSame(ui.find("row4"), ui.hitTest(50, 70), "content below the view is clipped");
        }
    }

    @Test
    void scrollIntoViewRevealsAnElementAndContentChangesReclamp() {
        try (UiDocumentInstance ui = laid(list(6))) {
            UiElement list = ui.find("list");
            ui.find("row5").scrollIntoView();
            assertEquals(6 * 20 - 60, list.scrollY(), EPS, "the last row's bottom meets the view's bottom");
            ui.find("row0").scrollIntoView();
            assertEquals(0, list.scrollY(), EPS);
            list.scrollBy(0, 1000);
            ui.find("row5").remove();
            ui.find("row4").remove();
            ui.update();
            assertEquals(4 * 20 + 5 - 60, list.scrollY(), EPS, "a shorter list pulls the offset back in range");
        }
    }

    @Test
    void scrollViewsNeedTheScrollFeatureDeclared() {
        OmuiArchive undeclared = list(2);
        var problems = com.openmason.engine.format.omui.OmuiValidator.validate(undeclared);
        assertTrue(problems.stream().anyMatch(d -> d.code() == com.openmason.engine.format.omui.UiDiagnostic.Code.UNDECLARED_FEATURE),
            problems.toString());
        OmuiArchive declared = undeclared.withManifest(new com.openmason.engine.format.omui.UiManifest(
            undeclared.manifest().schemaVersion(), undeclared.manifest().documentId(), undeclared.manifest().kind(),
            undeclared.manifest().displayName(), undeclared.manifest().uiApi(), undeclared.manifest().layoutSemantics(),
            List.of(com.openmason.engine.format.omui.UiFeatures.SCROLL), undeclared.manifest().hostApis(),
            undeclared.manifest().providers(), undeclared.manifest().unknown()));
        assertTrue(com.openmason.engine.format.omui.OmuiValidator.validate(declared).stream().noneMatch(
            com.openmason.engine.format.omui.UiDiagnostic::isError));
    }

    // ── overlays ────────────────────────────────────────────────────────────

    @Test
    void overlayLayersPaintAboveAndEscapeClips() {
        OmuiArchive doc = screen("t:ui/overlay", box("root").kids(
            box("clip").style("width", 50).style("height", 50).style("overflow", "hidden").kids(
                box("tooltip").style("position", "absolute").style("left", 30).style("top", 30)
                    .style("width", 80).style("height", 40).style("-sb-layer", 5)),
            box("later").style("width", 200).style("height", 100)));
        try (UiDocumentInstance ui = laid(doc)) {
            assertSame(ui.find("tooltip"), ui.hitTest(60, 60),
                "an overlay sits above the later sibling and is not cut off by its ancestor's clip");
            var order = ui.paintOrder();
            assertEquals(ui.find("tooltip"), order.entries().getLast().root(), "overlays paint last");
            ui.find("tooltip").setStyle("-sb-layer", UiValue.of(0));
            ui.update();
            assertSame(ui.find("later"), ui.hitTest(60, 60), "back in the clip, under the later sibling");
        }
    }

    // ── structural edits ────────────────────────────────────────────────────

    private static OmuiArchive tile() {
        return component(TILE, box("frame").cls("tile").kids(label("caption", "?").bind("prop:text", ".caption")),
            contract(List.of(param("caption", ValueType.STRING, "?")), List.of()),
            sheet("tile", rule(".tile", "width", 30, "height", 30)));
    }

    private static UiRuntimeContext withTile() {
        return UiRuntimeContext.basic().withSource(UiDocumentSource.of(Map.of(TILE, tile()), Map.of()));
    }

    @Test
    void elementsCanBeInsertedAndRemovedWhileTheInstanceRuns() {
        OmuiArchive doc = screen("t:ui/grid", box("root").kids(box("grid").style("flex-direction", "row")),
            sheet("s", rule(".new", "height", 12)));
        try (UiDocumentInstance ui = laid(doc, withTile(), 400, 300)) {
            UiElement grid = ui.find("grid");
            UiElement a = grid.insertChild(-1, inst("t1", TILE, Map.of("caption", "One")).build());
            UiElement b = grid.insertChild(0, box("first").cls("new").style("width", 10).build());
            ui.update();
            assertEquals(List.of("first", "t1"), grid.children().stream().map(UiElement::key).toList());
            assertEquals("One", ui.find("t1/caption").text("text"), "inserted instances expand their component");
            assertEquals(12, b.rect().height(), EPS, "document sheets match inserted elements");
            assertEquals(10, a.rect().x(), EPS, "layout includes the new children");
            assertEquals(30, ui.find("t1/frame").rect().width(), EPS, "the component's sheet arrived with it");

            IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
                () -> grid.insertChild(-1, box("first").build()));
            assertTrue(dup.getMessage().contains("first"));
            assertThrows(IllegalArgumentException.class,
                () -> ui.find("t1/caption").insertChild(0, box("x").build()), "labels are leaves");

            a.remove();
            ui.update();
            assertNull(ui.find("t1"));
            assertNull(ui.find("t1/caption"), "the whole subtree leaves the key index");
            assertTrue(a.isRemoved());
            assertThrows(IllegalStateException.class, a::remove);
            assertThrows(IllegalArgumentException.class, () -> ui.root().remove());
            assertEquals(1, grid.children().size());
            grid.insertChild(-1, inst("t1", TILE, Map.of("caption", "Again")).build());
            ui.update();
            assertEquals("Again", ui.find("t1/caption").text("text"), "a freed key can be reused");
        }
    }

    // ── live reload ─────────────────────────────────────────────────────────

    @Test
    void reloadPicksUpComponentEditsAndKeepsInstanceState() {
        OmuiArchive doc = screen("t:ui/reload", box("root").kids(
            inst("t1", TILE, Map.of("caption", "Keep"), List.of(override("frame", Map.of("width", 44), List.of(), List.of())),
                Map.of()),
            box("gone")));
        Map<String, OmuiArchive> components = new java.util.HashMap<>(Map.of(TILE, tile()));
        UiDocumentSource source = new UiDocumentSource() {
            @Override
            public OmuiArchive component(String id) {
                return components.get(id);
            }

            @Override
            public com.openmason.engine.format.omui.UiStyleSheet styleSheet(String id) {
                return null;
            }
        };
        try (UiDocumentInstance ui = laid(doc, UiRuntimeContext.basic().withSource(source), 400, 300)) {
            UiElement frame = ui.find("t1/frame");
            frame.setState(UiElement.HOVER, true);
            frame.addClass("picked");
            frame.setStyle("opacity", UiValue.of(0.5));

            // The component's author edits its sheet and adds a node; the screen drops "gone".
            components.put(TILE, component(TILE, box("frame").cls("tile").kids(
                    label("caption", "?").bind("prop:text", ".caption"), box("badge")),
                contract(List.of(param("caption", ValueType.STRING, "?")), List.of()),
                sheet("tile", rule(".tile", "width", 30, "height", 60))));
            OmuiArchive edited = screen("t:ui/reload", box("root").kids(
                inst("t1", TILE, Map.of("caption", "Keep"),
                    List.of(override("frame", Map.of("width", 44), List.of(), List.of())), Map.of())));
            UiDocumentInstance.ReloadReport report = ui.reload(edited);
            ui.update();

            UiElement next = ui.find("t1/frame");
            assertNotSame(frame, next, "elements are rebuilt");
            assertTrue(frame.isRemoved());
            assertTrue(next.hasState(UiElement.HOVER) && next.hasClass("picked"), "instance state carried over");
            assertEquals(0.5, next.computedStyle().number("opacity", 1));
            assertEquals(44, next.rect().width(), EPS, "the explicit override still wins");
            assertEquals(60, next.rect().height(), EPS, "the component's new sheet applies");
            assertNotNull(ui.find("t1/badge"));
            assertTrue(report.added().contains("t1/badge"));
            assertTrue(report.dropped().contains("gone"));
            assertTrue(report.kept().contains("t1/frame"));
        }
    }

    // ── references ──────────────────────────────────────────────────────────

    @Test
    void renameAndReparentKeepEveryIdentityReferenceResolving() throws Exception {
        OmuiArchive pause = UiSamples.pauseMenu();
        UiRuntimeContext ctx = UiRuntimeContext.basic().withSource(UiDocumentSource.of(
            Map.of(UiSamples.BUTTON_ID, UiSamples.stoneButton()), Map.of(UiSamples.THEME_ID, sheet("stone"))));
        List<UiReferences.Reference> refs = UiReferences.identityReferences(pause);
        assertTrue(refs.stream().anyMatch(r -> r.kind() == UiReferences.Kind.CLIP_TRACK && r.targetKey().equals("panel")));
        assertTrue(refs.stream().anyMatch(r -> r.kind() == UiReferences.Kind.GRAPH_TARGET && r.targetKey().equals("resume")));
        assertTrue(refs.stream().anyMatch(r -> r.kind() == UiReferences.Kind.OVERRIDE && r.targetKey().equals("quit/button")));

        // Rename the panel and the resume button, and move resume into a new group box.
        var root = pause.document().root();
        var panel = root.children().getFirst();
        var resume = panel.children().get(1);
        var renamedResume = new com.openmason.engine.format.omui.UiNode(resume.id(), "continueButton", resume.type(),
            resume.typeVersion(), resume.classes(), resume.props(), resume.style(), resume.dataSource(), resume.bindings(),
            resume.instance(), resume.children(), resume.unknown());
        var group = com.openmason.engine.format.omui.UiNode.of("group", "Box", List.of(renamedResume));
        List<com.openmason.engine.format.omui.UiNode> kids = new java.util.ArrayList<>(panel.children());
        kids.set(1, group);
        var renamedPanel = new com.openmason.engine.format.omui.UiNode(panel.id(), "mainPanel", panel.type(),
            panel.typeVersion(), panel.classes(), panel.props(), panel.style(), panel.dataSource(), panel.bindings(),
            panel.instance(), kids, panel.unknown());
        OmuiArchive edited = pause.withDocument(new com.openmason.engine.format.omui.UiDocument(
            root.withChildren(List.of(renamedPanel)), pause.document().styleSheets(), pause.document().codeBehind(),
            pause.document().component(), pause.document().unknown()));

        Map<OmuiArchive, String> parentOfResume = new java.util.LinkedHashMap<>();
        parentOfResume.put(pause, "panel");
        parentOfResume.put(edited, "group");
        parentOfResume.forEach((doc, parent) -> {
            UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, ctx);
            ui.resolveStyles();
            assertEquals(List.of(), UiReferences.unresolved(ui), "every clip, graph and override reference resolves");
            assertEquals("Resume Game", ui.find("resume/label").text("text"), "bindings follow the node");
            assertEquals(parent, ui.find("resume").parent().key(), "same key, new parent");
        });

        UiReferences.RenameImpact impact = UiReferences.renameImpact(pause, "panel", "mainPanel");
        assertEquals("panel", impact.oldName());
        assertEquals(List.of("pause#rules/1"), impact.ruleChanges(), "#panel > Label.title is the one rule naming it");
        assertEquals("#mainPanel > Label.title", impact.rewrittenSheets().get("pause").rules().get(1).selector());
        UiReferences.RenameImpact scripts = UiReferences.renameImpact(pause, "resume", "continueButton");
        assertEquals(1, scripts.scriptMentions().size(), "Lua ui.q(\"#resume\") is reported, never rewritten");
        assertTrue(scripts.scriptMentions().getFirst().startsWith("pause:"));
        assertTrue(scripts.rewrittenSheets().isEmpty());
    }

    @Test
    void unresolvedReferencesAreReported() throws Exception {
        OmuiArchive pause = UiSamples.pauseMenu();
        var root = pause.document().root();
        var panel = root.children().getFirst();
        var noPanel = root.withChildren(List.of(com.openmason.engine.format.omui.UiNode.of("other", "Box", panel.children())));
        OmuiArchive broken = pause.withDocument(new com.openmason.engine.format.omui.UiDocument(noPanel,
            pause.document().styleSheets(), pause.document().codeBehind(), null, Map.of()));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(broken, UiRuntimeContext.basic().withSource(
            UiDocumentSource.of(Map.of(UiSamples.BUTTON_ID, UiSamples.stoneButton()), Map.of())));
        assertTrue(UiReferences.unresolved(ui).stream().anyMatch(d -> d.element().equals("panel")),
            "the open clip animates a node that no longer exists");
        assertTrue(UiReferences.unresolved(ui).stream().allMatch(d -> d.code() == Code.OVERRIDE_TARGET_MISSING));
    }
}
