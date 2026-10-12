package com.openmason.engine.ui.graph;

import java.util.HashSet;
import java.util.Set;

/**
 * Indented Lua lines with source markers. A marker line ({@code -- @node <loc> <kind>}) is
 * written whenever the node that owns the following lines changes, so {@link SourceMap} can map
 * any line back to its node by scanning the text alone, for fresh and cached code alike.
 */
final class LuaWriter {

    static final String MARKER = "-- @node ";

    private final StringBuilder out = new StringBuilder();
    private final Set<String> names = new HashSet<>();
    private int indent;
    /** Owner of the lines written so far, as the last written marker says ("-" = none). */
    private String written = "-";
    /** A marker owed before the next line (markers are lazy, so two never stack up). */
    private String pending;
    private String pendingLoc;

    LuaWriter(Set<String> reserved) {
        names.addAll(reserved);
        names.addAll(LuaText.KEYWORDS);
    }

    void line(String lua) {
        if (pending != null) {
            out.append("  ".repeat(indent)).append(pending).append('\n');
            written = pendingLoc;
            pending = null;
        }
        out.append("  ".repeat(indent)).append(lua).append('\n');
    }

    void blank() {
        out.append('\n');
    }

    void comment(String text) {
        line("-- " + text);
    }

    void open(String lua) {
        line(lua);
        indent++;
    }

    void close(String lua) {
        indent--;
        line(lua);
    }

    void mid(String lua) {
        indent--;
        line(lua);
        indent++;
    }

    /** Lines from here on belong to {@code loc}; writes a marker only when the owner changes. */
    void marker(String loc, String kind) {
        if (loc.equals(written)) {
            pending = null;
        } else {
            pending = MARKER + loc + (kind == null ? "" : " " + kind);
            pendingLoc = loc;
        }
    }

    /** Lines from here on belong to no node (chunk scaffolding). */
    void unmark() {
        marker("-", null);
    }

    /** A local name derived from {@code base}, unique in the chunk and never shadowing a global the code uses. */
    String fresh(String base) {
        String b = LuaText.sanitize(base);
        String n = b;
        for (int i = 2; !names.add(n); i++) {
            n = b + "_" + i;
        }
        return n;
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
