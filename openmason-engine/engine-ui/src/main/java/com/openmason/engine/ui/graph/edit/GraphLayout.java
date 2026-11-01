package com.openmason.engine.ui.graph.edit;

import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.CanonicalJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Editor-only annotations of a graph (#291): comment frames and node groups, stored apart from
 * the graph's semantics in {@code editor/graphs/<id>.layout.json}. Runtimes ignore {@code editor/}
 * entries, and the graph file's source hash (the derived-cache key) never sees them, so
 * annotating a graph never invalidates its compiled Lua. Node positions stay on the nodes
 * ({@code x}, {@code y}), where the format put them; the compiler ignores them too.
 */
public record GraphLayout(List<Comment> comments, List<Group> groups) {

    public static final GraphLayout EMPTY = new GraphLayout(List.of(), List.of());

    /**
     * A resizable comment frame on one body's canvas.
     *
     * @param function {@code ""} for the event graph, else the function id
     * @param color    {@code #RRGGBB(AA)} or empty for the default
     */
    public record Comment(String id, String function, double x, double y, double width, double height, String text,
                          String color) {
        public Comment {
            Objects.requireNonNull(id, "id");
            function = function == null ? "" : function;
            text = text == null ? "" : text;
            color = color == null ? "" : color;
        }

        public boolean contains(double px, double py) {
            return px >= x && py >= y && px <= x + width && py <= y + height;
        }
    }

    /** Nodes that select and move together, with a title. */
    public record Group(String id, String function, String title, List<String> nodes, String color) {
        public Group {
            Objects.requireNonNull(id, "id");
            function = function == null ? "" : function;
            title = title == null ? "" : title;
            nodes = List.copyOf(nodes);
            color = color == null ? "" : color;
        }
    }

    public GraphLayout {
        comments = List.copyOf(comments);
        groups = List.copyOf(groups);
    }

    /** The editor entry holding a graph's layout. */
    public static String entry(String graphId) {
        return "editor/graphs/" + graphId + ".layout.json";
    }

    public boolean isEmpty() {
        return comments.isEmpty() && groups.isEmpty();
    }

    public GraphLayout withComments(List<Comment> c) {
        return new GraphLayout(c, groups);
    }

    public GraphLayout withGroups(List<Group> g) {
        return new GraphLayout(comments, g);
    }

    /** Canonical JSON bytes. */
    public UiBytes toBytes() {
        List<UiValue> cs = new ArrayList<>();
        for (Comment c : comments) {
            Map<String, UiValue> m = new LinkedHashMap<>();
            m.put("id", UiValue.of(c.id()));
            m.put("function", UiValue.of(c.function()));
            m.put("x", UiValue.of(c.x()));
            m.put("y", UiValue.of(c.y()));
            m.put("width", UiValue.of(c.width()));
            m.put("height", UiValue.of(c.height()));
            m.put("text", UiValue.of(c.text()));
            m.put("color", UiValue.of(c.color()));
            cs.add(UiValue.Obj.sorted(m));
        }
        List<UiValue> gs = new ArrayList<>();
        for (Group g : groups) {
            Map<String, UiValue> m = new LinkedHashMap<>();
            m.put("id", UiValue.of(g.id()));
            m.put("function", UiValue.of(g.function()));
            m.put("title", UiValue.of(g.title()));
            m.put("nodes", new UiValue.Arr(g.nodes().stream().map(n -> (UiValue) UiValue.of(n)).toList()));
            m.put("color", UiValue.of(g.color()));
            gs.add(UiValue.Obj.sorted(m));
        }
        Map<String, UiValue> root = new LinkedHashMap<>();
        root.put("comments", new UiValue.Arr(cs));
        root.put("groups", new UiValue.Arr(gs));
        return UiBytes.copyOf(CanonicalJson.write(UiValue.Obj.sorted(root)));
    }

    /** Reads a layout entry; anything malformed reads as {@link #EMPTY} (layouts are advisory). */
    public static GraphLayout read(UiBytes bytes) {
        if (bytes == null) {
            return EMPTY;
        }
        UiValue root = CanonicalJson.parse(bytes.toArray(), "layout", new UiDiagnostics());
        if (!(root instanceof UiValue.Obj o)) {
            return EMPTY;
        }
        List<Comment> comments = new ArrayList<>();
        if (o.get("comments") instanceof UiValue.Arr a) {
            for (UiValue v : a.items()) {
                if (v instanceof UiValue.Obj c && str(c, "id") != null) {
                    comments.add(new Comment(str(c, "id"), str(c, "function"), num(c, "x"), num(c, "y"),
                        num(c, "width"), num(c, "height"), str(c, "text"), str(c, "color")));
                }
            }
        }
        List<Group> groups = new ArrayList<>();
        if (o.get("groups") instanceof UiValue.Arr a) {
            for (UiValue v : a.items()) {
                if (v instanceof UiValue.Obj g && str(g, "id") != null) {
                    List<String> nodes = new ArrayList<>();
                    if (g.get("nodes") instanceof UiValue.Arr ns) {
                        ns.items().forEach(n -> {
                            if (n instanceof UiValue.Str s) {
                                nodes.add(s.value());
                            }
                        });
                    }
                    groups.add(new Group(str(g, "id"), str(g, "function"), str(g, "title"), nodes, str(g, "color")));
                }
            }
        }
        return new GraphLayout(comments, groups);
    }

    private static String str(UiValue.Obj o, String k) {
        return o.get(k) instanceof UiValue.Str s ? s.value() : null;
    }

    private static double num(UiValue.Obj o, String k) {
        return o.get(k) instanceof UiValue.Num n ? n.value() : 0;
    }
}
