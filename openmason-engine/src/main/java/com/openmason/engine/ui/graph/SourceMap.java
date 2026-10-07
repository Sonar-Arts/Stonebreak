package com.openmason.engine.ui.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generated line → originating graph node (#291), read from the {@code -- @node} markers in the
 * Lua itself, so a cached {@code derived/} chunk maps exactly like a fresh one.
 */
public final class SourceMap {

    /** A node location: {@code function} is "" for the event graph. */
    public record Location(String graph, String function, String node, String kind) {
        /** {@code count} or {@code fn:log_click/print}: the marker spelling. */
        public String key() {
            return function.isEmpty() ? node : "fn:" + function + "/" + node;
        }

        @Override
        public String toString() {
            return graph + "#" + key();
        }
    }

    private final String graph;
    private final List<Location> byLine;
    private final List<Boolean> markers;

    private SourceMap(String graph, List<Location> byLine, List<Boolean> markers) {
        this.graph = graph;
        this.byLine = byLine;
        this.markers = markers;
    }

    public static SourceMap parse(String graph, String lua) {
        List<Location> lines = new ArrayList<>();
        List<Boolean> markers = new ArrayList<>();
        Location current = null;
        for (String line : lua.split("\n", -1)) {
            String t = line.strip();
            boolean marker = t.startsWith(LuaWriter.MARKER);
            if (marker) {
                current = location(graph, t.substring(LuaWriter.MARKER.length()));
            }
            lines.add(current);
            markers.add(marker);
        }
        return new SourceMap(graph, Collections.unmodifiableList(lines), Collections.unmodifiableList(markers));
    }

    static Location location(String graph, String spec) {
        String[] parts = spec.strip().split("\\s+", 2);
        String loc = parts[0];
        if (loc.equals("-")) {
            return null;
        }
        String kind = parts.length > 1 ? parts[1] : "";
        if (loc.startsWith("fn:")) {
            int slash = loc.indexOf('/');
            if (slash > 3) {
                return new Location(graph, loc.substring(3, slash), loc.substring(slash + 1), kind);
            }
        }
        return new Location(graph, "", loc, kind);
    }

    /** The node owning 1-based {@code line}, or null for scaffolding lines and out-of-range lines. */
    public Location at(int line) {
        return line >= 1 && line <= byLine.size() ? byLine.get(line - 1) : null;
    }

    /** First line of a node's code (the line after its first marker), or 0. */
    public int lineOf(String function, String node) {
        for (int i = 0; i < byLine.size(); i++) {
            Location l = byLine.get(i);
            if (l != null && !markers.get(i) && l.function().equals(function) && l.node().equals(node)) {
                return i + 1;
            }
        }
        return 0;
    }

    public String graph() {
        return graph;
    }
}
