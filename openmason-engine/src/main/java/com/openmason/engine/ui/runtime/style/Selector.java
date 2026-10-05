package com.openmason.engine.ui.runtime.style;

import java.util.List;
import java.util.Objects;

/**
 * One compiled complex selector ({@code #panel > Label.title}), compounds left to right.
 *
 * <p><b>Specificity</b> follows USS/CSS: ({@code #name} count, {@code .class} + {@code :state}
 * count, type count), compared in that order; {@code *} and combinators add nothing. Each
 * count saturates at 1023 and the triple packs into one comparable int.
 *
 * @param combinators {@code combinators.get(i)} joins compound {@code i} to {@code i + 1}
 */
public record Selector(String source, List<Compound> compounds, List<Combinator> combinators, int specificity) {

    public enum Combinator { DESCENDANT, CHILD }

    /**
     * One compound selector.
     *
     * @param type    widget type, or {@code null} for {@code *} / no type
     */
    public record Compound(String type, List<String> names, List<String> classes, List<String> states) {
        public Compound {
            names = List.copyOf(names);
            classes = List.copyOf(classes);
            states = List.copyOf(states);
        }
    }

    public Selector {
        Objects.requireNonNull(source, "source");
        compounds = List.copyOf(compounds);
        combinators = List.copyOf(combinators);
        if (compounds.isEmpty() || combinators.size() != compounds.size() - 1) {
            throw new IllegalArgumentException("malformed selector " + source);
        }
    }

    /** Packs (ids, classes + states, types). */
    public static int specificity(int ids, int classesAndStates, int types) {
        return Math.min(ids, 1023) << 20 | Math.min(classesAndStates, 1023) << 10 | Math.min(types, 1023);
    }

    public int ids() {
        return specificity >>> 20;
    }

    public int classesAndStates() {
        return specificity >>> 10 & 1023;
    }

    public int types() {
        return specificity & 1023;
    }

    /** The rightmost compound, the element the selector styles. */
    public Compound subject() {
        return compounds.getLast();
    }
}
