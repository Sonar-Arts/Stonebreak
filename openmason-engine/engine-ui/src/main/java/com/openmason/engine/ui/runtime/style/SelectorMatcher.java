package com.openmason.engine.ui.runtime.style;

import java.util.List;

/**
 * Right-to-left selector matching over {@link Styleable} parents. A descendant combinator
 * backtracks through every ancestor, so {@code A B > C} is matched exactly, not greedily.
 */
public final class SelectorMatcher {

    private SelectorMatcher() {
    }

    public static boolean matches(Selector selector, Styleable element) {
        List<Selector.Compound> compounds = selector.compounds();
        int last = compounds.size() - 1;
        return matchesCompound(compounds.get(last), element) && matchFrom(selector, last - 1, element);
    }

    /** Matches compounds {@code [0..index]} against the ancestors of {@code element}. */
    private static boolean matchFrom(Selector selector, int index, Styleable element) {
        if (index < 0) {
            return true;
        }
        Selector.Compound compound = selector.compounds().get(index);
        Selector.Combinator combinator = selector.combinators().get(index);
        Styleable ancestor = element.styleParent();
        if (combinator == Selector.Combinator.CHILD) {
            return ancestor != null && matchesCompound(compound, ancestor) && matchFrom(selector, index - 1, ancestor);
        }
        for (; ancestor != null; ancestor = ancestor.styleParent()) {
            if (matchesCompound(compound, ancestor) && matchFrom(selector, index - 1, ancestor)) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchesCompound(Selector.Compound compound, Styleable element) {
        if (compound.type() != null && !compound.type().equals(element.styleType())) {
            return false;
        }
        for (String name : compound.names()) {
            if (!name.equals(element.styleName())) {
                return false;
            }
        }
        for (String c : compound.classes()) {
            if (!element.hasClass(c)) {
                return false;
            }
        }
        for (String state : compound.states()) {
            if (!element.hasState(state)) {
                return false;
            }
        }
        return true;
    }
}
