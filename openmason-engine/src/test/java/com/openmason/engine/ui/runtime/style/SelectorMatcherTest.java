package com.openmason.engine.ui.runtime.style;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectorMatcherTest {

    /** Minimal styleable for matching without a runtime tree. */
    private record E(String type, String name, Set<String> classes, Set<String> states, E parent) implements Styleable {
        @Override
        public String styleType() {
            return type.contains(":") ? null : type;
        }

        @Override
        public String styleName() {
            return name;
        }

        @Override
        public boolean hasClass(String c) {
            return classes.contains(c);
        }

        @Override
        public boolean hasState(String s) {
            return states.contains(s);
        }

        @Override
        public Styleable styleParent() {
            return parent;
        }
    }

    private static Selector one(String s) {
        return SelectorParser.parseList(s, Set.of("selected")).getFirst();
    }

    @Test
    void specificityCountsIdsThenClassesAndStatesThenTypes() {
        Selector s = one("Box#panel > Label.title:hover");
        assertEquals(1, s.ids());
        assertEquals(2, s.classesAndStates());
        assertEquals(2, s.types());
        assertTrue(one("#a").specificity() > one(".a.b.c.d").specificity(), "one id beats any number of classes");
        assertTrue(one(".a").specificity() > one("Box Label Button").specificity(), "one class beats types");
        assertEquals(0, one("*").specificity());
        assertEquals(one(".a:hover").specificity(), one(".a.b").specificity(), "states count like classes");
    }

    @Test
    void parsesListsAndRejectsUndeclaredStates() {
        assertEquals(2, SelectorParser.parseList("Instance .danger:hover, Instance .danger:selected", Set.of("selected")).size());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SelectorParser.parseList(".a:pressed", Set.of()));
        assertTrue(e.getMessage().contains("pressed"));
    }

    @Test
    void matchesCompoundsCombinatorsAndStates() {
        E root = new E("Box", "root", Set.of(), Set.of(), null);
        E panel = new E("Box", "panel", Set.of("panel"), Set.of(), root);
        E row = new E("Box", null, Set.of("row"), Set.of(), panel);
        E label = new E("Label", "title", Set.of("title", "big"), Set.of("hover"), row);

        assertTrue(SelectorMatcher.matches(one("Label"), label));
        assertTrue(SelectorMatcher.matches(one("Label.title.big"), label));
        assertFalse(SelectorMatcher.matches(one("Label.title.small"), label));
        assertTrue(SelectorMatcher.matches(one("#panel Label"), label), "descendant reaches past a parent");
        assertFalse(SelectorMatcher.matches(one("#panel > Label"), label), "child requires the direct parent");
        assertTrue(SelectorMatcher.matches(one("#panel > .row > Label"), label));
        assertTrue(SelectorMatcher.matches(one(".title:hover"), label));
        assertFalse(SelectorMatcher.matches(one(".title:selected"), label));
        assertTrue(SelectorMatcher.matches(one("*"), label));
        assertTrue(SelectorMatcher.matches(one("Box > Box Label"), label), "backtracks over ambiguous ancestors");
    }

    @Test
    void namespacedHostTypesNeverMatchTypeSelectors() {
        E crucible = new E("stonebreak:CrucibleView", null, Set.of("crucible"), Set.of(), null);
        assertFalse(SelectorMatcher.matches(one("CrucibleView"), crucible));
        assertTrue(SelectorMatcher.matches(one(".crucible"), crucible));
    }

    @Test
    void ruleMatchReportsBestSpecificityOfTheList() {
        E el = new E("Button", "ok", Set.of("primary"), Set.of(), null);
        CompiledSheet.Rule rule = new CompiledSheet.Rule(0,
            SelectorParser.parseList("Button, #ok, .missing", Set.of()), java.util.Map.of(), List.of());
        assertEquals(one("#ok").specificity(), rule.match(el));
    }
}
