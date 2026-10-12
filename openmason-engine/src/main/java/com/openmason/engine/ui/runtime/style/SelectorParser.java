package com.openmason.engine.ui.runtime.style;

import com.openmason.engine.format.omui.UiSelectors;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Compiles selector lists in the {@link UiSelectors} grammar (the format owns syntax; this
 * class turns valid text into {@link Selector}s with specificity).
 */
public final class SelectorParser {

    private SelectorParser() {
    }

    /**
     * @param customStates the sheet's declared custom states
     * @throws IllegalArgumentException with the grammar problem when the list is invalid
     */
    public static List<Selector> parseList(String list, Set<String> customStates) {
        String problem = UiSelectors.problem(list, customStates);
        if (problem != null) {
            throw new IllegalArgumentException("invalid selector '" + list + "': " + problem);
        }
        List<Selector> out = new ArrayList<>();
        for (String part : list.split(",", -1)) {
            out.add(parse(part.strip()));
        }
        return List.copyOf(out);
    }

    private static Selector parse(String s) {
        List<Selector.Compound> compounds = new ArrayList<>();
        List<Selector.Combinator> combinators = new ArrayList<>();
        int ids = 0;
        int classes = 0;
        int types = 0;
        int i = 0;
        while (true) {
            String type = null;
            List<String> names = new ArrayList<>();
            List<String> cls = new ArrayList<>();
            List<String> states = new ArrayList<>();
            if (s.charAt(i) == '*') {
                i++;
            } else if (Character.isUpperCase(s.charAt(i))) {
                int start = i;
                while (i < s.length() && Character.isLetterOrDigit(s.charAt(i))) {
                    i++;
                }
                type = s.substring(start, i);
                types++;
            }
            while (i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == '#' || s.charAt(i) == ':')) {
                char kind = s.charAt(i++);
                int start = i;
                while (i < s.length() && isIdentChar(s.charAt(i))) {
                    i++;
                }
                String ident = s.substring(start, i);
                switch (kind) {
                    case '#' -> {
                        names.add(ident);
                        ids++;
                    }
                    case '.' -> {
                        cls.add(ident);
                        classes++;
                    }
                    default -> {
                        states.add(ident);
                        classes++;
                    }
                }
            }
            compounds.add(new Selector.Compound(type, names, cls, states));
            if (i >= s.length()) {
                break;
            }
            boolean child = false;
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '>')) {
                child |= s.charAt(i) == '>';
                i++;
            }
            combinators.add(child ? Selector.Combinator.CHILD : Selector.Combinator.DESCENDANT);
        }
        return new Selector(s, compounds, combinators, Selector.specificity(ids, classes, types));
    }

    private static boolean isIdentChar(char c) {
        return c < 0x80 && (Character.isLetterOrDigit(c) || c == '_' || c == '-');
    }
}
