package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.component;
import static com.openmason.engine.ui.runtime.UiDocs.contract;
import static com.openmason.engine.ui.runtime.UiDocs.has;
import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.override;
import static com.openmason.engine.ui.runtime.UiDocs.overrideProps;
import static com.openmason.engine.ui.runtime.UiDocs.param;
import static com.openmason.engine.ui.runtime.UiDocs.rule;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static com.openmason.engine.ui.runtime.UiDocs.slot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Component expansion: params, overrides, slots, sheets, identity and composition errors. */
class ComponentInstanceTest {

    private static final String CARD = "t:ui/components/card";

    /** A card: root Box(.card) > title Label bound to .title, plus a body slot. */
    private static OmuiArchive card(String titleDefault, Object rootWidth) {
        return component(CARD,
            box("frame").name("frame").cls("card").style("width", rootWidth).kids(
                label("title", titleDefault).name("title").bind("prop:text", ".title"),
                box("body")),
            contract(List.of(param("title", ValueType.STRING, titleDefault), param("count", ValueType.INT, 0)),
                List.of(slot("body", "body"))),
            sheet("card", rule(".card", "height", 40), rule(".card:hover", "opacity", 0.5)));
    }

    private static UiRuntimeContext ctx(OmuiArchive... components) {
        Map<String, OmuiArchive> map = new java.util.HashMap<>();
        for (OmuiArchive c : components) {
            map.put(c.manifest().documentId(), c);
        }
        return UiRuntimeContext.basic().withSource(UiDocumentSource.of(map, Map.of()));
    }

    private static UiDocumentInstance run(OmuiArchive doc, UiRuntimeContext ctx) {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, ctx);
        ui.resolveStyles();
        return ui;
    }

    @Test
    void oneComponentInstantiatesTwiceWithIsolatedStateAndDifferentProperties() {
        OmuiArchive doc = screen("t:ui/two", box("root").kids(
            inst("a", CARD, Map.of("title", "Alpha")),
            inst("b", CARD, Map.of("title", "Beta"))));
        UiDocumentInstance ui = run(doc, ctx(card("Untitled", 100)));

        assertEquals("Alpha", ui.find("a/title").text("text"));
        assertEquals("Beta", ui.find("b/title").text("text"));
        assertNotNull(ui.find("a/frame"));
        assertSame(ui.find("a/frame").node(), ui.find("b/frame").node(), "both instances share one definition node");

        ui.find("a/frame").setState(UiElement.HOVER, true);
        ui.find("a/title").setProp("text", UiValue.of("ignored")); // bound: rejected
        ui.find("a/frame").addClass("picked");
        ui.resolveStyles();
        assertEquals(0.5, ui.find("a/frame").computedStyle().number("opacity", 1));
        assertEquals(1, ui.find("b/frame").computedStyle().number("opacity", 1), "hover stays on its instance");
        assertTrue(ui.find("a/frame").hasClass("picked"));
        assertTrue(!ui.find("b/frame").hasClass("picked"));
        assertEquals(List.of("card"), ui.find("a/frame").node().classes(), "the shared definition is unchanged");
    }

    @Test
    void goldenPauseMenuExpandsItsStoneButtons() throws Exception {
        OmuiArchive pause = UiSamples.pauseMenu();
        UiRuntimeContext context = UiRuntimeContext.basic().withSource(UiDocumentSource.of(
            Map.of(UiSamples.BUTTON_ID, UiSamples.stoneButton()),
            Map.of(UiSamples.THEME_ID, sheet("stone"))));
        UiDocumentInstance ui = run(pause, context);

        assertTrue(ui.diagnostics().stream().noneMatch(d -> d.severity() == Severity.ERROR), ui.diagnostics().toString());
        assertEquals("Resume Game", ui.find("resume/label").text("text"));
        assertEquals("Quit to Menu", ui.find("quit/label").text("text"));
        assertEquals(UiValue.of(360.0), ui.find("resume/button").computedStyle().get("width"), "component sheet applies");
        assertTrue(ui.find("quit/button").hasClass("danger"), "override addClasses");
        assertTrue(!ui.find("resume/button").hasClass("danger"));
        UiElement icon = ui.find("quit_icon");
        assertNotNull(icon, "slot content keeps its outer-document id as key");
        assertSame(ui.find("quit/icon_box"), icon.parent(), "slot content lands in the slot host");

        // The pause sheet's "Instance .danger:hover" reaches into the component.
        UiElement quit = ui.find("quit/button");
        quit.setState(UiElement.HOVER, true);
        ui.resolveStyles();
        assertEquals(UiValue.of("#FF8080FF"), quit.computedStyle().get("-sb-tint"));
        ui.find("resume/button").setState("selected", true); // declared custom state, but not .danger
        ui.resolveStyles();
        assertNull(ui.find("resume/button").computedStyle().get("-sb-tint"), ":selected alone needs .danger too");
    }

    @Test
    void parameterAndSlotMistakesAreDiagnosed() {
        OmuiArchive doc = screen("t:ui/bad", box("root").kids(
            inst("a", CARD, Map.of("title", 5, "colour", "red"), List.of(), Map.of("footer", List.of(box("f"))))));
        UiDocumentInstance ui = run(doc, ctx(card("Untitled", 100)));
        assertTrue(has(ui, Code.PARAM_TYPE));
        assertTrue(has(ui, Code.UNKNOWN_PARAM));
        assertTrue(has(ui, Code.UNKNOWN_SLOT));
        assertEquals("Untitled", ui.find("a/title").text("text"), "a mistyped param falls back to its default");
        assertNull(ui.find("f"), "content for an unknown slot is dropped");
    }

    @Test
    void missingAndRecursiveComponentsLeaveAnEmptyInstance() {
        String loopA = "t:ui/components/loop_a";
        String loopB = "t:ui/components/loop_b";
        OmuiArchive a = component(loopA, box("ra").kids(inst("toB", loopB, Map.of())), contract(List.of(), List.of()));
        OmuiArchive b = component(loopB, box("rb").kids(inst("toA", loopA, Map.of())), contract(List.of(), List.of()));
        OmuiArchive doc = screen("t:ui/loop", box("root").kids(inst("x", loopA, Map.of()), inst("y", "t:ui/nope", Map.of())));
        UiDocumentInstance ui = run(doc, ctx(a, b));
        assertTrue(has(ui, Code.RECURSIVE_COMPONENT));
        assertTrue(has(ui, Code.MISSING_COMPONENT));
        // Keys join node ids through instances only: x > (loop_a) toB > (loop_b) toA.
        assertNotNull(ui.find("x/toB/toA"), "expansion stops at the repeat");
        assertTrue(ui.find("x/toB/toA").children().isEmpty());
        assertTrue(ui.find("y").children().isEmpty());
    }

    @Test
    void nestedOverridesReachThroughInstancesAndOuterAuthorsWin() {
        String outer = "t:ui/components/panel";
        OmuiArchive panel = component(outer, box("p").kids(
                inst("inner", CARD, Map.of("title", "Inner"), List.of(override("frame", Map.of("width", 70), List.of("from-panel"), List.of())), Map.of())),
            contract(List.of(), List.of()));
        OmuiArchive doc = screen("t:ui/nest", box("root").kids(
            inst("x", outer, Map.of(), List.of(
                override("inner/frame", Map.of("width", 80), List.of("from-screen"), List.of("card")),
                override("inner/ghost", Map.of("width", 1), List.of(), List.of())), Map.of())));
        UiDocumentInstance ui = run(doc, ctx(panel, card("Untitled", 100)));
        UiElement frame = ui.find("x/inner/frame");
        assertEquals(UiValue.of(80.0), frame.computedStyle().get("width"), "the outermost override wins");
        assertTrue(frame.hasClass("from-panel") && frame.hasClass("from-screen"));
        assertTrue(!frame.hasClass("card"), "removeClasses");
        assertTrue(ui.diagnostics().stream().anyMatch(d -> d.code() == Code.OVERRIDE_TARGET_MISSING
            && d.element().equals("x") && d.message().contains("inner/ghost")), ui.diagnostics().toString());
    }

    @Test
    void componentSourceEditsPropagateWithoutOverwritingOverrides() {
        OmuiArchive doc = screen("t:ui/edit", box("root").kids(
            inst("a", CARD, Map.of(), List.of(override("frame", Map.of("width", 50), List.of(), List.of()),
                overrideProps("title", Map.of())), Map.of())));
        UiDocumentInstance v1 = run(doc, ctx(card("Version one", 100)));
        assertEquals(UiValue.of(50.0), v1.find("a/frame").computedStyle().get("width"));
        assertEquals("Version one", v1.find("a/title").text("text"));

        // The component's author changes the default title and the root width, and adds a style.
        OmuiArchive edited = component(CARD,
            box("frame").name("frame").cls("card").style("width", 200).style("min-height", 30).kids(
                label("title", "Version two").name("title").bind("prop:text", ".title"), box("body")),
            contract(List.of(param("title", ValueType.STRING, "Version two")), List.of(slot("body", "body"))));
        UiDocumentInstance v2 = run(doc, ctx(edited));
        assertEquals(UiValue.of(50.0), v2.find("a/frame").computedStyle().get("width"), "explicit override survives");
        assertEquals(UiValue.of(30.0), v2.find("a/frame").computedStyle().get("min-height"), "new source style arrives");
        assertEquals("Version two", v2.find("a/title").text("text"), "new source default arrives");
    }

    @Test
    void renameAndReparentKeepStableKeysAndOverrides() {
        OmuiArchive before = screen("t:ui/rr", box("root").kids(
            inst("go", CARD, Map.of("title", "Go"), List.of(override("frame", Map.of("width", 33), List.of(), List.of())), Map.of())
                .name("goButton")));
        OmuiArchive after = screen("t:ui/rr", box("root").kids(box("group").kids(
            inst("go", CARD, Map.of("title", "Go"), List.of(override("frame", Map.of("width", 33), List.of(), List.of())), Map.of())
                .name("startButton"))));
        UiDocumentInstance a = run(before, ctx(card("x", 100)));
        UiDocumentInstance b = run(after, ctx(card("x", 100)));
        for (UiDocumentInstance ui : List.of(a, b)) {
            assertEquals("Go", ui.find("go/title").text("text"));
            assertEquals(UiValue.of(33.0), ui.find("go/frame").computedStyle().get("width"));
        }
        assertSame(a.find("go"), a.q("#goButton"));
        assertSame(b.find("go"), b.q("#startButton"), "the name is a handle, the key is the identity");
        assertEquals("group", b.find("go").parent().key());
    }

    @Test
    void documentSheetsOutrankComponentSheetsWhichStayScoped() {
        UiStyleSheet docSheet = sheet("doc", rule(".card", "height", 99));
        OmuiArchive doc = screen("t:ui/scope", box("root").kids(
            inst("a", CARD, Map.of()),
            box("lookalike").cls("card")), sheet("noop"), docSheet);
        UiDocumentInstance ui = run(doc, ctx(card("x", 100)));
        assertEquals(UiValue.of(99.0), ui.find("a/frame").computedStyle().get("height"), "outer document restyles internals");
        ui.find("lookalike").setState(UiElement.HOVER, true);
        ui.resolveStyles();
        assertEquals(1, ui.find("lookalike").computedStyle().number("opacity", 1),
            "the component's :hover rule does not leak outside its instance");
    }

    @Test
    void widgetDescriptorsValidateNodes() {
        UiNode tooNew = new UiNode("future", null, "Label", 9, List.of(), Map.of(), Map.of(), null, List.of(), null,
            List.of(), Map.of());
        OmuiArchive doc = OmuiArchive.of(
            com.openmason.engine.format.omui.UiManifest.create("t:ui/w", com.openmason.engine.format.omui.UiManifest.DocumentKind.SCREEN, "w"),
            new com.openmason.engine.format.omui.UiDocument(box("root").build().withChildren(List.of(
                node("odd", "Gizmo").build(),
                label("l", "x").prop("txet", "typo").build(),
                node("bad", "Label").prop("text", 5).build(),
                label("parent", "x").kids(box("orphan")).build(),
                tooNew)), List.of(), null, null, Map.of()));
        UiDocumentInstance ui = run(doc, UiRuntimeContext.basic());
        assertTrue(has(ui, Code.UNKNOWN_WIDGET));
        assertTrue(has(ui, Code.UNKNOWN_PROPERTY));
        assertTrue(has(ui, Code.PROPERTY_TYPE));
        assertTrue(has(ui, Code.CHILDREN_NOT_ALLOWED));
        assertTrue(has(ui, Code.UNSUPPORTED_WIDGET_VERSION));
        assertEquals("", ui.find("bad").text("text"), "a mistyped prop is dropped, not coerced");
        assertNull(ui.find("orphan"));
        assertNotNull(ui.find("odd"), "unknown widgets keep a placeholder so the screen still lays out");
    }
}
