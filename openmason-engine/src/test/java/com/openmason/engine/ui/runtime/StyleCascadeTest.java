package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.has;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.rule;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Selector matching, specificity, layering and variables through the runtime (no native layout). */
class StyleCascadeTest {

    private static UiDocumentInstance styled(OmuiArchive doc, UiRuntimeContext ctx) {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, ctx);
        ui.resolveStyles();
        return ui;
    }

    private static UiDocumentInstance styled(OmuiArchive doc) {
        return styled(doc, UiRuntimeContext.basic());
    }

    private static String color(UiDocumentInstance ui, String key) {
        UiValue v = ui.find(key).computedStyle().get("color");
        return v == null ? null : ((UiValue.Str) v).value();
    }

    @Test
    void oneClassRuleRestylesEveryMatchingElement() {
        OmuiArchive doc = screen("t:ui/menu", box("root").kids(
                node("a", "Button").cls("menu-button"),
                node("b", "Button").cls("menu-button"),
                node("c", "Button")),
            sheet("s", rule(".menu-button", "-sb-tint", "#FF0000")));
        UiDocumentInstance ui = styled(doc);
        assertEquals(List.of("a", "b"), ui.qAll(".menu-button").stream().map(UiElement::key).toList());
        assertEquals(UiValue.of("#FF0000"), ui.find("a").computedStyle().get("-sb-tint"));
        assertEquals(UiValue.of("#FF0000"), ui.find("b").computedStyle().get("-sb-tint"));
        assertNull(ui.find("c").computedStyle().get("-sb-tint"));
    }

    @Test
    void sheetRankBeatsSpecificityAndSpecificityBeatsOrder() {
        UiStyleSheet theme = sheet("theme", rule("#x", "color", "#111111"));
        UiStyleSheet doc1 = sheet("first", rule("Label.big", "color", "#222222"), rule(".big", "color", "#333333"));
        UiStyleSheet doc2 = sheet("second", rule("Label", "color", "#444444"));
        OmuiArchive doc = screen("t:ui/rank", box("root").kids(label("x", "hi").name("x").cls("big")), doc1, doc2);
        UiDocumentInstance ui = styled(doc, UiRuntimeContext.basic().withTheme(List.of(theme)));
        // Theme #x has the highest specificity but the lowest rank; inside the document,
        // Label.big (0,1,1) beats .big (0,1,0) and the later sheet's weaker Label rule.
        assertEquals("#222222", color(ui, "x"));
    }

    @Test
    void laterRuleWinsOnEqualSpecificity() {
        OmuiArchive doc = screen("t:ui/order", box("root").kids(label("x", "hi").cls("a", "b")),
            sheet("s", rule(".a", "color", "#AAAAAA"), rule(".b", "color", "#BBBBBB")));
        assertEquals("#BBBBBB", color(styled(doc), "x"));
    }

    @Test
    void inlineLocalAndAnimationLayersStackInOrder() {
        OmuiArchive doc = screen("t:ui/layers", box("root").kids(label("x", "hi").name("x").style("color", "#000001")),
            sheet("s", rule("#x", "color", "#FFFFFF")));
        UiDocumentInstance ui = styled(doc);
        assertEquals("#000001", color(ui, "x"), "inline beats any sheet rule");
        UiElement x = ui.find("x");
        x.setStyle("color", UiValue.of("#000002"));
        ui.resolveStyles();
        assertEquals("#000002", color(ui, "x"), "local write beats inline");
        x.setAnimatedStyle("color", UiValue.of("#000003"));
        ui.resolveStyles();
        assertEquals("#000003", color(ui, "x"), "animation channel applies last");
        x.clearAnimatedStyle("color");
        ui.resolveStyles();
        assertEquals("#000002", color(ui, "x"), "releasing the channel restores the value underneath");
        assertEquals(UiValue.of("#000001"), x.node().style().get("color"), "the definition is never written");
    }

    @Test
    void pseudoStatesRestyleAndDisabledInherits() {
        OmuiArchive doc = screen("t:ui/states", box("root").kids(
                node("btn", "Button").cls("b").kids(label("txt", "Go"))),
            sheet("s", rule(".b", "opacity", 1), rule(".b:hover", "opacity", 0.75),
                rule("Label:disabled", "color", "#777777")));
        UiDocumentInstance ui = styled(doc);
        UiElement btn = ui.find("btn");
        assertEquals(1.0, btn.computedStyle().number("opacity", -1));
        btn.setState(UiElement.HOVER, true);
        ui.resolveStyles();
        assertEquals(0.75, btn.computedStyle().number("opacity", -1));
        btn.setState(UiElement.HOVER, false);
        btn.setEnabled(false);
        ui.resolveStyles();
        assertEquals(1.0, btn.computedStyle().number("opacity", -1));
        assertEquals("#777777", color(ui, "txt"), "children of a disabled element match :disabled");
        assertFalse(ui.find("txt").isEnabledInHierarchy());
    }

    @Test
    void variablesResolveFromSheetsAndInheritedCustomProperties() {
        UiStyleSheet s = sheet("s", Map.of("--accent", "#C8A050"), List.of(),
            rule("Label", "color", "var(--accent)"));
        OmuiArchive doc = screen("t:ui/vars", box("root").kids(
            label("plain", "a"),
            box("themed").style("--accent", "#00FF00").kids(label("inner", "b"))), s);
        UiDocumentInstance ui = styled(doc);
        assertEquals("#C8A050", color(ui, "plain"));
        assertEquals("#00FF00", color(ui, "inner"), "a closer custom property wins for the subtree");

        ui.find("themed").setStyle("--accent", UiValue.of("#0000FF"));
        ui.resolveStyles();
        assertEquals("#0000FF", color(ui, "inner"), "changing a token restyles descendants");
        assertEquals("#C8A050", color(ui, "plain"));
    }

    @Test
    void unresolvedAndCyclicVariablesAreReportedAndDropped() {
        UiStyleSheet s = sheet("s", Map.of("--a", "var(--b)", "--b", "var(--a)"), List.of(),
            rule("#x", "color", "var(--missing)"), rule("#y", "color", "var(--a)"));
        OmuiArchive doc = screen("t:ui/badvars", box("root").kids(label("x", "a").name("x"), label("y", "b").name("y")), s);
        UiDocumentInstance ui = styled(doc);
        assertNull(color(ui, "x"));
        assertNull(color(ui, "y"));
        assertTrue(has(ui, Code.UNRESOLVED_VARIABLE));
        assertTrue(has(ui, Code.VARIABLE_CYCLE));
    }

    @Test
    void mistypedValuesThroughVariablesAreDropped() {
        UiStyleSheet s = sheet("s", Map.of("--size", "#FFFFFF"), List.of(), rule("#x", "width", "var(--size)"));
        UiDocumentInstance ui = styled(screen("t:ui/mistyped", box("root").kids(box("x").name("x")), s));
        assertNull(ui.find("x").computedStyle().get("width"));
        assertTrue(has(ui, Code.STYLE_VALUE));
    }

    @Test
    void inheritedPropertiesFlowDownAndOthersDoNot() {
        OmuiArchive doc = screen("t:ui/inherit", box("root").style("color", "#123456").style("width", 100)
            .kids(box("mid").kids(label("leaf", "x"))));
        UiDocumentInstance ui = styled(doc);
        assertEquals("#123456", color(ui, "leaf"));
        assertNull(ui.find("leaf").computedStyle().get("width"));
    }

    @Test
    void transitionsTravelWithTheWinningRule() {
        UiStyleSheet.StyleRule hover = new UiStyleSheet.StyleRule(".b:hover", Map.of("-sb-tint", UiValue.of("#FFFFFF")),
            List.of(new UiStyleSheet.StyleTransition("-sb-tint", 0.1, null, 0, Map.of())), Map.of());
        UiDocumentInstance ui = styled(screen("t:ui/tr", box("root").kids(node("b", "Button").cls("b")),
            sheet("s", hover)));
        UiElement b = ui.find("b");
        assertNull(b.computedStyle().transition("-sb-tint"));
        b.setState(UiElement.HOVER, true);
        ui.resolveStyles();
        assertNotNull(b.computedStyle().transition("-sb-tint"));
        assertEquals(0.1, b.computedStyle().transition("-sb-tint").duration());
    }

    @Test
    void localClassTogglesStayOnTheInstance() {
        OmuiArchive doc = screen("t:ui/cls", box("root").kids(box("x").cls("base")),
            sheet("s", rule(".on", "opacity", 0.5)));
        UiDocumentInstance a = styled(doc);
        UiDocumentInstance b = styled(doc);
        a.find("x").addClass("on");
        a.resolveStyles();
        b.resolveStyles();
        assertEquals(0.5, a.find("x").computedStyle().number("opacity", 1));
        assertEquals(1, b.find("x").computedStyle().number("opacity", 1), "another instance is untouched");
        assertEquals(List.of("base"), doc.document().root().children().getFirst().classes(), "definition untouched");
    }

    @Test
    void undeclaredCustomStatesAreReported() {
        OmuiArchive doc = screen("t:ui/st", box("root").kids(box("x")),
            sheet("s", Map.of(), List.of("selected"), rule(".a:selected", "opacity", 0.5)));
        UiDocumentInstance ui = styled(doc);
        ui.find("x").setState("selected", true);
        assertFalse(has(ui, Code.UNKNOWN_STATE));
        ui.find("x").setState("pressed", true);
        assertTrue(has(ui, Code.UNKNOWN_STATE));
    }

    @Test
    void writesToBoundTargetsAreReportedAndIgnored() {
        OmuiArchive doc = screen("t:ui/bound", box("root").kids(label("x", "a").bind("prop:text", "session.title")));
        UiDocumentInstance ui = styled(doc);
        UiElement x = ui.find("x");
        assertFalse(x.setProp("text", UiValue.of("hacked")));
        assertEquals("a", x.text("text"));
        assertTrue(has(ui, Code.BOUND_PROPERTY_WRITE));
    }
}
