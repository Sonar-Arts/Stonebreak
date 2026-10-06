package com.openmason.engine.ui.graph.edit;

import com.openmason.engine.format.omui.UiValue;

import java.util.Map;

/**
 * One item of the node palette: a kind with properties filled in. Annotated Lua functions,
 * graph functions and variables each get their own entries ({@code lua:call} with
 * {@code function} set, ...), so they are found by name like any built-in node.
 */
public record PaletteEntry(String kind, String title, String category, String doc, Map<String, UiValue> props) {

    public PaletteEntry {
        props = Map.copyOf(props);
        doc = doc == null ? "" : doc;
    }

    /** Case-insensitive match of every space-separated word against title, category, kind and doc. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String hay = (title + " " + category + " " + kind + " " + doc).toLowerCase(java.util.Locale.ROOT);
        for (String word : query.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+")) {
            if (!hay.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** Ranking: title prefix first, then title contains, then the rest. */
    int rank(String query) {
        if (query == null || query.isBlank()) {
            return 0;
        }
        String t = title.toLowerCase(java.util.Locale.ROOT);
        String q = query.toLowerCase(java.util.Locale.ROOT).trim();
        return t.startsWith(q) ? 0 : t.contains(q) ? 1 : 2;
    }
}
