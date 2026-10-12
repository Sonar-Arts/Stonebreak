package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.style.CompiledSheet;
import com.openmason.engine.ui.runtime.style.Selector;
import com.openmason.engine.ui.runtime.style.SelectorMatcher;
import com.openmason.engine.ui.runtime.style.Styleable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.rule;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a class, state or inherited-value change restyles (#282 hardening): only what a selector
 * can reach, and inherited values flow without re-running the cascade — with exactly the results
 * a full cascade gives.
 */
class StyleInvalidationTest {

    private static String color(UiDocumentInstance ui, String key) {
        UiValue v = ui.find(key).computedStyle().get("color");
        return v == null ? null : ((UiValue.Str) v).value();
    }

    /** A 3x3 grid of slots, each a Button with a Label inside. */
    private static OmuiArchive grid(UiStyleSheet sheet) {
        List<UiDocs.N> slots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            slots.add(node("s" + i, "Button").cls("slot").kids(label("s" + i + "l", "x").cls("count")));
        }
        return screen("t:ui/grid", box("root").kids(slots.toArray(UiDocs.N[]::new)), sheet);
    }

    @Test
    void aStateOnlySubjectsTestRestylesTheElementAlone() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(grid(sheet("s",
            rule(".slot:hover", "background-color", "#FFFFFF"))), UiRuntimeContext.basic());
        ui.resolveStyles();
        ui.find("s4").setState(UiElement.HOVER, true);
        assertEquals(1, ui.resolveStyles(), "only the hovered slot re-cascades, not its label");
        assertEquals(UiValue.of("#FFFFFF"), ui.find("s4").computedStyle().get("background-color"));
    }

    @Test
    void aStateTestedInAnAncestorCompoundRestylesTheSubtree() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(grid(sheet("s",
            rule(".slot:hover > .count", "color", "#FFFF00"))), UiRuntimeContext.basic());
        ui.resolveStyles();
        ui.find("s4").setState(UiElement.HOVER, true);
        assertEquals(2, ui.resolveStyles(), "the slot and its label");
        assertEquals("#FFFF00", color(ui, "s4l"));
        ui.find("s4").setState(UiElement.HOVER, false);
        ui.resolveStyles();
        assertNull(color(ui, "s4l"));
    }

    @Test
    void aStateNoSelectorTestsRestylesNothing() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(grid(sheet("s", rule(".slot", "width", 10))),
            UiRuntimeContext.basic());
        ui.resolveStyles();
        ui.find("s4").setState(UiElement.HOVER, true);
        assertEquals(0, ui.resolveStyles());
        ui.find("s4").addClass("unused");
        assertEquals(0, ui.resolveStyles());
    }

    @Test
    void disablingAParentRestylesDescendantsThatMatchDisabled() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(grid(sheet("s",
            rule(".count:disabled", "color", "#808080"))), UiRuntimeContext.basic());
        ui.resolveStyles();
        ui.find("s4").setEnabled(false);
        ui.resolveStyles();
        assertEquals("#808080", color(ui, "s4l"), ":disabled is inherited by the subtree");
    }

    @Test
    void anAnimatedInheritedColourFlowsToChildrenWithoutDisturbingTheirOwnValues() {
        OmuiArchive doc = screen("t:ui/hud", box("root").kids(
            box("panel").style("color", "#FFFFFF").kids(
                label("plain", "a"),
                label("own", "b").style("color", "#00FF00"),
                box("inner").kids(label("deep", "c")))));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        ui.resolveStyles();
        for (String c : new String[]{"#FF0000FF", "#800000FF", "#FF0000FF"}) {
            ui.find("panel").setAnimatedStyle("color", UiValue.of(c));
            ui.resolveStyles();
            assertEquals(c, color(ui, "plain"));
            assertEquals(c, color(ui, "deep"), "inherited through an element that declares nothing");
            assertEquals("#00FF00", color(ui, "own"), "a declared value still wins over the inherited one");
        }
        ui.find("panel").clearAnimatedStyle("color");
        ui.resolveStyles();
        assertEquals("#FFFFFF", color(ui, "deep"));
    }

    @Test
    void aCustomPropertyChangeStillReCascadesChildren() {
        OmuiArchive doc = screen("t:ui/vars", box("root").kids(
            box("panel").style("--accent", "#111111").kids(label("t", "a").style("color", "var(--accent)"))));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        ui.resolveStyles();
        assertEquals("#111111", color(ui, "t"));
        ui.find("panel").setStyle("--accent", UiValue.of("#222222"));
        ui.resolveStyles();
        assertEquals("#222222", color(ui, "t"));
    }

    // ── the rule index finds exactly what brute force finds ────────────────

    private record Stub(String styleType, String styleName, Set<String> classes, Set<String> states,
                        Styleable styleParent, boolean listsClasses) implements Styleable {
        @Override
        public boolean hasClass(String className) {
            return classes.contains(className);
        }

        @Override
        public boolean hasState(String state) {
            return states.contains(state);
        }

        @Override
        public Iterable<String> styleClasses() {
            return listsClasses ? classes : null;
        }
    }

    @Test
    void indexedCandidatesMatchExactlyTheRulesBruteForceMatches() {
        UiStyleSheet src = sheet("s",
            rule("Button", "width", 1), rule(".slot", "width", 2), rule("#resume", "width", 3),
            rule("*", "width", 4), rule(":hover", "width", 5), rule("Button.slot:hover", "width", 6),
            rule("#panel Label", "width", 7), rule(".a.b", "width", 8), rule(".b, Label", "width", 9),
            rule("Box > .slot", "width", 10), rule("#resume.slot", "width", 11));
        CompiledSheet sheet = CompiledSheet.compile("s", src, d -> {
        });
        Stub panel = new Stub("Box", "panel", Set.of(), Set.of(), null, true);
        List<Stub> elements = List.of(
            new Stub("Button", "resume", Set.of("slot"), Set.of("hover"), panel, true),
            new Stub("Button", null, Set.of("slot", "a", "b"), Set.of(), panel, true),
            new Stub("Label", null, Set.of(), Set.of("hover"), panel, true),
            new Stub("Image", "x", Set.of("b"), Set.of(), null, true),
            new Stub("Box", null, Set.of("slot"), Set.of(), panel, true));
        for (Stub e : elements) {
            Set<Integer> brute = new HashSet<>();
            for (CompiledSheet.Rule r : sheet.rules()) {
                if (r.match(e) >= 0) {
                    brute.add(r.index());
                }
            }
            Set<Integer> indexed = new HashSet<>();
            sheet.candidates(e, entry -> {
                if (SelectorMatcher.matches(entry.selector(), e)) {
                    indexed.add(entry.rule().index());
                }
            });
            assertEquals(brute, indexed, "element " + e.styleType() + " " + e.styleName() + " " + e.classes());
        }
    }

    @Test
    void elementsThatCannotListClassesGetEverySelector() {
        CompiledSheet sheet = CompiledSheet.compile("s", sheet("s", rule(".a", "width", 1), rule("#b", "width", 2)),
            d -> {
            });
        List<Selector> seen = new ArrayList<>();
        sheet.candidates(new Stub("Box", null, Set.of("a"), Set.of(), null, false), e -> seen.add(e.selector()));
        assertEquals(2, seen.size());
    }

    @Test
    void indexedResolutionKeepsTheCascadeOrder() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(screen("t:ui/order", box("root").kids(
            node("x", "Button").cls("slot")), sheet("s",
            rule(".slot", "color", "#000001"), rule("Button", "color", "#000002"), rule("Button.slot", "color", "#000003"),
            rule(".slot", "color", "#000004"))), UiRuntimeContext.basic());
        ui.resolveStyles();
        assertEquals("#000003", color(ui, "x"), "highest specificity wins regardless of bucket");
    }
}
