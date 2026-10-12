package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiPaths;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed data path (wire contract §5.2): {@code session.online}, {@code .items[2].count} or
 * {@code .} (the inherited source itself). Absolute paths start at a host root registered in a
 * {@link DataRegistry}; relative ones resolve against the nearest inherited data source.
 *
 * @param relative leading {@code .}
 * @param segments field names ({@link String}) and list indices ({@link Integer}), in order
 */
public record DataPath(boolean relative, List<Object> segments) {

    public static final DataPath SELF = new DataPath(true, List.of());

    public DataPath {
        segments = List.copyOf(segments);
        if (!relative && (segments.isEmpty() || !(segments.getFirst() instanceof String))) {
            throw new IllegalArgumentException("an absolute path starts with a root name");
        }
    }

    /** @throws IllegalArgumentException when {@code path} is not a data path */
    public static DataPath parse(String path) {
        if (!UiPaths.isDataPath(path)) {
            throw new IllegalArgumentException("not a data path: '" + path + "'");
        }
        if (path.equals(".")) {
            return SELF;
        }
        boolean relative = path.startsWith(".");
        List<Object> segs = new ArrayList<>();
        int i = relative ? 1 : 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                i++;
            } else if (c == '[') {
                int end = path.indexOf(']', i);
                segs.add(Integer.parseInt(path.substring(i + 1, end)));
                i = end + 1;
            } else {
                int end = i;
                while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                segs.add(path.substring(i, end));
                i = end;
            }
        }
        return new DataPath(relative, segs);
    }

    public static DataPath root(String name) {
        return new DataPath(false, List.of(name));
    }

    public boolean isSelf() {
        return relative && segments.isEmpty();
    }

    /** The host root of an absolute path. */
    public String rootName() {
        if (relative) {
            throw new IllegalStateException("relative path " + this + " has no root");
        }
        return (String) segments.getFirst();
    }

    /** Segments after the root, as a relative path ({@code session.online} → {@code .online}). */
    public DataPath tail() {
        return new DataPath(true, segments.subList(relative ? 0 : 1, segments.size()));
    }

    /** {@code this} followed by the relative {@code rel}; {@code rel} absolute replaces it. */
    public DataPath resolve(DataPath rel) {
        if (!rel.relative) {
            return rel;
        }
        List<Object> segs = new ArrayList<>(segments);
        segs.addAll(rel.segments);
        return new DataPath(relative, segs);
    }

    /** True when {@code other} is this path or lies below it. */
    public boolean contains(DataPath other) {
        return relative == other.relative && other.segments.size() >= segments.size()
            && other.segments.subList(0, segments.size()).equals(segments);
    }

    /**
     * The value this relative path reaches from {@code value} ({@code null} when a segment is
     * missing). For an absolute path, the root segment is skipped: {@code value} is the root's.
     */
    public UiValue evaluate(UiValue value) {
        UiValue cur = value;
        for (int i = relative ? 0 : 1; i < segments.size() && cur != null; i++) {
            Object s = segments.get(i);
            if (s instanceof Integer index) {
                cur = cur instanceof UiValue.Arr a && index < a.items().size() ? a.items().get(index) : null;
            } else {
                cur = cur instanceof UiValue.Obj o ? o.get((String) s) : null;
            }
        }
        return cur;
    }

    /** Type at this path from the root's (or source's) {@code type}, or {@code null} when the schema has no such member. */
    public DataType typeFrom(DataType type) {
        DataType cur = type;
        for (int i = relative ? 0 : 1; i < segments.size() && cur != null; i++) {
            cur = cur.child(segments.get(i));
        }
        return cur;
    }

    /** {@code value} with the member at this path replaced by {@code replacement} (objects only). */
    public UiValue with(UiValue value, UiValue replacement) {
        return with(value, relative ? 0 : 1, replacement);
    }

    private UiValue with(UiValue value, int i, UiValue replacement) {
        if (i == segments.size()) {
            return replacement;
        }
        Object s = segments.get(i);
        if (s instanceof Integer index) {
            if (!(value instanceof UiValue.Arr a) || index >= a.items().size()) {
                throw new IllegalArgumentException("no item " + index + " at " + this);
            }
            List<UiValue> items = new ArrayList<>(a.items());
            items.set(index, with(items.get(index), i + 1, replacement));
            return new UiValue.Arr(items);
        }
        java.util.Map<String, UiValue> fields = new java.util.LinkedHashMap<>(
            value instanceof UiValue.Obj o ? o.fields() : java.util.Map.of());
        UiValue child = fields.get(s);
        fields.put((String) s, with(child == null ? UiValue.NULL : child, i + 1, replacement));
        return new UiValue.Obj(fields);
    }

    @Override
    public String toString() {
        if (isSelf()) {
            return ".";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Object s = segments.get(i);
            if (s instanceof Integer index) {
                sb.append('[').append(index).append(']');
            } else {
                if (i > 0 || relative) {
                    sb.append('.');
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
