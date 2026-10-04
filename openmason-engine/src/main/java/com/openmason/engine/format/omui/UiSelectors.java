package com.openmason.engine.format.omui;

import java.util.Set;

/**
 * Syntax of USS-like selectors (#287 owns matching and specificity):
 *
 * <pre>
 * list      := selector ( "," selector )*
 * selector  := compound ( combinator compound )*
 * combinator:= whitespace (descendant) | ">" (child), optionally surrounded by whitespace
 * compound  := "*" | Type? ( "." ident | "#" ident | ":" state )+ | Type
 * Type      := [A-Z][A-Za-z0-9]*          (built-in widget type; namespaced types are
 *                                          matched by class instead)
 * ident     := [A-Za-z_-][A-Za-z0-9_-]*
 * state     := hover | active | focus | disabled | checked | a declared custom state
 * </pre>
 */
public final class UiSelectors {

    public static final Set<String> BUILT_IN_STATES = Set.of("hover", "active", "focus", "disabled", "checked");
    public static final int MAX_LENGTH = 1024;
    public static final int MAX_COMPOUNDS = 32;

    private UiSelectors() {
    }

    /** @return {@code null} when {@code list} is valid, else the first problem */
    public static String problem(String list, Set<String> customStates) {
        if (list.length() > MAX_LENGTH) {
            return "selector longer than " + MAX_LENGTH + " characters";
        }
        for (String selector : list.split(",", -1)) {
            String p = selectorProblem(selector.strip(), customStates);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private static String selectorProblem(String s, Set<String> customStates) {
        if (s.isEmpty()) {
            return "empty selector";
        }
        int i = 0;
        int compounds = 0;
        while (true) {
            int start = i;
            i = compound(s, i, customStates);
            if (i < 0) {
                return problemAt(s, start, -i - 1, customStates);
            }
            if (++compounds > MAX_COMPOUNDS) {
                return "more than " + MAX_COMPOUNDS + " compound selectors";
            }
            if (i == s.length()) {
                return null;
            }
            int ws = i;
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
            if (i < s.length() && s.charAt(i) == '>') {
                i++;
                while (i < s.length() && s.charAt(i) == ' ') {
                    i++;
                }
            } else if (i == ws) {
                return "unexpected '" + s.charAt(i) + "' at " + i;
            }
            if (i == s.length()) {
                return "selector ends with a combinator";
            }
        }
    }

    /** @return index after the compound, or {@code -(errorIndex + 1)} */
    private static int compound(String s, int i, Set<String> customStates) {
        int start = i;
        if (i < s.length() && s.charAt(i) == '*') {
            i++;
        } else if (i < s.length() && s.charAt(i) >= 'A' && s.charAt(i) <= 'Z') {
            i++;
            while (i < s.length() && Character.isLetterOrDigit(s.charAt(i)) && s.charAt(i) < 0x80) {
                i++;
            }
        }
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '.' && c != '#' && c != ':') {
                break;
            }
            int identStart = ++i;
            i = ident(s, i);
            if (i == identStart) {
                return -(identStart + 1);
            }
            if (c == ':') {
                String state = s.substring(identStart, i);
                if (!BUILT_IN_STATES.contains(state) && !customStates.contains(state)) {
                    return -(identStart + 1);
                }
            }
        }
        return i == start ? -(start + 1) : i;
    }

    private static int ident(String s, int i) {
        if (i < s.length() && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '-')
                && s.charAt(i) < 0x80) {
            i++;
            while (i < s.length() && s.charAt(i) < 0x80
                    && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '-')) {
                i++;
            }
        }
        return i;
    }

    private static String problemAt(String s, int start, int at, Set<String> customStates) {
        if (at > 0 && at <= s.length() && s.charAt(at - 1) == ':') {
            int end = ident(s, at);
            if (end > at) {
                return "unknown pseudo-state ':" + s.substring(at, end) + "' (declare it in customStates)";
            }
        }
        return at < s.length() ? "unexpected '" + s.charAt(at) + "' at " + at : "incomplete selector at " + start;
    }

    /** True for a selector-safe identifier (class names, node names, custom states). */
    public static boolean isIdent(String s) {
        return s != null && !s.isEmpty() && ident(s, 0) == s.length() && !Character.isDigit(s.charAt(0));
    }
}
