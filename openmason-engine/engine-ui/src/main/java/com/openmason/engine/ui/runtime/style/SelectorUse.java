package com.openmason.engine.ui.runtime.style;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which classes and pseudo-states the attached sheets' selectors test, and where: in the
 * subject compound (the styled element itself) or in an ancestor compound. A class or state
 * change then restyles only what can depend on it: nothing when no selector mentions it, the
 * element alone when only subjects do, the subtree when an ancestor compound does
 * ({@code Button:hover > Label}). Hovering a slot grid no longer re-cascades every descendant.
 */
public final class SelectorUse {

    /** What a class or state change can restyle. */
    public enum Reach { NONE, SELF, SUBTREE }

    private final Set<String> subjectClasses = new HashSet<>();
    private final Set<String> ancestorClasses = new HashSet<>();
    private final Set<String> subjectStates = new HashSet<>();
    private final Set<String> ancestorStates = new HashSet<>();

    private SelectorUse() {
    }

    public static SelectorUse of(List<SheetBinding> sheets) {
        SelectorUse u = new SelectorUse();
        for (SheetBinding b : sheets) {
            for (CompiledSheet.Rule rule : b.sheet().rules()) {
                for (Selector s : rule.selectors()) {
                    List<Selector.Compound> compounds = s.compounds();
                    for (int i = 0; i < compounds.size(); i++) {
                        boolean subject = i == compounds.size() - 1;
                        Selector.Compound c = compounds.get(i);
                        (subject ? u.subjectClasses : u.ancestorClasses).addAll(c.classes());
                        (subject ? u.subjectStates : u.ancestorStates).addAll(c.states());
                    }
                }
            }
        }
        return u;
    }

    public Reach classReach(String className) {
        return ancestorClasses.contains(className) ? Reach.SUBTREE
            : subjectClasses.contains(className) ? Reach.SELF : Reach.NONE;
    }

    public Reach stateReach(String state) {
        return ancestorStates.contains(state) ? Reach.SUBTREE
            : subjectStates.contains(state) ? Reach.SELF : Reach.NONE;
    }

    /** True when any selector tests {@code state} anywhere. */
    public boolean usesState(String state) {
        return subjectStates.contains(state) || ancestorStates.contains(state);
    }

    /** The wider of two reaches. */
    public static Reach max(Reach a, Reach b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
