package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.io.CanonicalJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1 → 1.0. Schema 0.1 is the frozen pre-release draft of the #283 spike: a manifest with
 * {@code id}/{@code name}, nodes carrying a flat camelCase {@code layout} object (spike
 * {@code FlexTree} vocabulary, {@code start}/{@code end} alignment, {@code [l,t,r,b]} box
 * arrays, boolean {@code wrap}), and the code-behind as an archive path in {@code script}.
 * 1.0 moves layout into kebab-case style declarations (Unity convention), names alignment
 * with flexbox keywords, adds the API/semantics versions and references scripts by id.
 *
 * <p>The draft is closed: an unknown {@code layout} key or keyword is an error, because
 * guessing would change geometry silently. Every other unknown field is carried over.
 */
final class DraftUpgradeStage implements UpgradeStage {

    private static final Map<String, String> SCALAR_KEYS = Map.ofEntries(
            Map.entry("direction", "flex-direction"),
            Map.entry("justify", "justify-content"),
            Map.entry("alignItems", "align-items"),
            Map.entry("alignSelf", "align-self"),
            Map.entry("alignContent", "align-content"),
            Map.entry("grow", "flex-grow"),
            Map.entry("shrink", "flex-shrink"),
            Map.entry("basis", "flex-basis"),
            Map.entry("width", "width"),
            Map.entry("height", "height"),
            Map.entry("minWidth", "min-width"),
            Map.entry("minHeight", "min-height"),
            Map.entry("maxWidth", "max-width"),
            Map.entry("maxHeight", "max-height"),
            Map.entry("aspectRatio", "aspect-ratio"),
            Map.entry("position", "position"),
            Map.entry("display", "display"));

    private static final Map<String, String> ALIGN_WORDS = Map.of(
            "start", "flex-start", "end", "flex-end", "center", "center", "stretch", "stretch",
            "auto", "auto", "between", "space-between", "around", "space-around", "evenly", "space-evenly");

    private static final String[] SIDES = {"left", "top", "right", "bottom"};
    private static final SchemaVersion TARGET = new SchemaVersion(1, 0);

    @Override
    public SchemaVersion from() {
        return OmuiFormat.DRAFT_VERSION;
    }

    @Override
    public SchemaVersion to() {
        return TARGET;
    }

    @Override
    public Map<String, byte[]> apply(Map<String, byte[]> entries, UiDiagnostics d) {
        Map<String, byte[]> out = new LinkedHashMap<>(entries);
        UiValue manifest = OmuiReader.json(entries, OmuiFormat.MANIFEST, true, ArchiveLimits.DEFAULT, d);
        UiValue document = OmuiReader.json(entries, OmuiFormat.DOCUMENT, true, ArchiveLimits.DEFAULT, d);
        if (!(manifest instanceof UiValue.Obj m) || !(document instanceof UiValue.Obj doc)) {
            if (!d.hasErrors()) {
                d.error(Code.WRONG_TYPE, OmuiFormat.DOCUMENT, "", "Draft manifest and document must be objects");
            }
            return null;
        }
        out.put(OmuiFormat.MANIFEST, CanonicalJson.write(manifest(m)));
        UiValue.Obj upgraded = document(doc, d);
        if (upgraded == null) {
            return null;
        }
        out.put(OmuiFormat.DOCUMENT, CanonicalJson.write(upgraded));
        return out;
    }

    private static UiValue.Obj manifest(UiValue.Obj m) {
        Map<String, UiValue> f = new LinkedHashMap<>(m.fields());
        f.put("schemaVersion", UiValue.of(TARGET.toString()));
        rename(f, "id", "documentId");
        rename(f, "name", "displayName");
        f.putIfAbsent("uiApi", UiValue.of(1));
        f.putIfAbsent("layoutSemantics", UiValue.of(OmuiFormat.LAYOUT_SEMANTICS));
        return new UiValue.Obj(f);
    }

    private static UiValue.Obj document(UiValue.Obj doc, UiDiagnostics d) {
        Map<String, UiValue> f = new LinkedHashMap<>(doc.fields());
        // Only a string is the draft's code-behind path; any other value is an unknown field
        // and is carried over like every other one.
        if (f.get("script") instanceof UiValue.Str s) {
            f.remove("script");
            String id = OmuiReader.part(s.value(), OmuiFormat.SCRIPTS_DIR, OmuiFormat.SCRIPT_SUFFIX);
            if (id == null) {
                d.error(Code.INVALID_VALUE, OmuiFormat.DOCUMENT, "/script", "Draft script path must be scripts/<id>.lua");
                return null;
            }
            f.put("codeBehind", UiValue.of(id));
        }
        UiValue root = f.get("root");
        if (root instanceof UiValue.Obj r) {
            UiValue.Obj node = node(r, "/root", d);
            if (node == null) {
                return null;
            }
            f.put("root", node);
        }
        return new UiValue.Obj(f);
    }

    private static UiValue.Obj node(UiValue.Obj n, String ptr, UiDiagnostics d) {
        Map<String, UiValue> f = new LinkedHashMap<>(n.fields());
        UiValue layout = f.remove("layout");
        if (layout instanceof UiValue.Obj l) {
            Map<String, UiValue> style = new LinkedHashMap<>();
            if (!layout(l, style, ptr + "/layout", d)) {
                return null;
            }
            if (f.get("style") instanceof UiValue.Obj existing) {
                for (Map.Entry<String, UiValue> e : existing.fields().entrySet()) {
                    if (style.containsKey(e.getKey())) {
                        d.error(Code.INVALID_VALUE, OmuiFormat.DOCUMENT, ptr + "/style/" + e.getKey(),
                                "Set by both the draft layout and style; refusing to pick one");
                        return null;
                    }
                    style.put(e.getKey(), e.getValue());
                }
            }
            f.put("style", UiValue.Obj.sorted(style));
        }
        if (f.get("children") instanceof UiValue.Arr children) {
            List<UiValue> upgraded = new ArrayList<>();
            for (int i = 0; i < children.items().size(); i++) {
                if (!(children.items().get(i) instanceof UiValue.Obj c)) {
                    d.error(Code.WRONG_TYPE, OmuiFormat.DOCUMENT, ptr + "/children/" + i, "Expected an object");
                    return null;
                }
                UiValue.Obj child = node(c, ptr + "/children/" + i, d);
                if (child == null) {
                    return null;
                }
                upgraded.add(child);
            }
            f.put("children", new UiValue.Arr(upgraded));
        }
        return new UiValue.Obj(f);
    }

    private static boolean layout(UiValue.Obj l, Map<String, UiValue> style, String ptr, UiDiagnostics d) {
        for (Map.Entry<String, UiValue> e : l.fields().entrySet()) {
            String key = e.getKey();
            UiValue v = e.getValue();
            String at = ptr + "/" + key;
            switch (key) {
                case "wrap" -> {
                    if (!(v instanceof UiValue.Bool b)) {
                        return fail(d, at, "wrap must be a boolean");
                    }
                    style.put("flex-wrap", UiValue.of(b.value() ? "wrap" : "nowrap"));
                }
                case "gap" -> {
                    style.put("row-gap", v);
                    style.put("column-gap", v);
                }
                case "margin", "padding", "border" -> {
                    if (!(v instanceof UiValue.Arr a) || a.items().size() != 4) {
                        return fail(d, at, key + " must be [left, top, right, bottom]");
                    }
                    for (int i = 0; i < 4; i++) {
                        String name = key.equals("border") ? "border-" + SIDES[i] + "-width" : key + "-" + SIDES[i];
                        if (!(a.items().get(i) instanceof UiValue.Null)) {
                            style.put(name, a.items().get(i));
                        }
                    }
                }
                case "insets" -> {
                    if (!(v instanceof UiValue.Obj o)) {
                        return fail(d, at, "insets must be an object");
                    }
                    for (Map.Entry<String, UiValue> side : o.fields().entrySet()) {
                        if (!List.of(SIDES).contains(side.getKey())) {
                            return fail(d, at + "/" + side.getKey(), "Unknown inset side");
                        }
                        style.put(side.getKey(), side.getValue());
                    }
                }
                default -> {
                    String target = SCALAR_KEYS.get(key);
                    if (target == null) {
                        return fail(d, at, "Unknown draft layout key '" + key + "'");
                    }
                    if (key.startsWith("align") || key.equals("justify")) {
                        String word = v instanceof UiValue.Str s ? ALIGN_WORDS.get(s.value()) : null;
                        if (word == null) {
                            return fail(d, at, "Unknown draft alignment " + v);
                        }
                        v = UiValue.of(word);
                    }
                    style.put(target, v);
                }
            }
        }
        return true;
    }

    private static boolean fail(UiDiagnostics d, String ptr, String message) {
        d.error(Code.INVALID_VALUE, OmuiFormat.DOCUMENT, ptr, message);
        return false;
    }

    private static void rename(Map<String, UiValue> f, String from, String to) {
        UiValue v = f.remove(from);
        if (v != null) {
            f.put(to, v);
        }
    }
}
