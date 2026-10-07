package com.openmason.main.systems.uiEditor.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.AnimationCodec;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;
import com.openmason.engine.ui.runtime.widget.WidgetRegistry;
import com.openmason.main.systems.uiEditor.command.AnimationCommands;
import com.openmason.main.systems.uiEditor.command.DocumentCommands;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.command.OverrideCommands;
import com.openmason.main.systems.uiEditor.command.UiCommandException;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.Nodes;
import com.openmason.main.systems.uiEditor.document.UiIds;
import com.openmason.main.systems.uiEditor.document.UiTree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.openmason.main.systems.uiEditor.ops.UiOpField.ANY;
import static com.openmason.main.systems.uiEditor.ops.UiOpField.BINDINGS;
import static com.openmason.main.systems.uiEditor.ops.UiOpField.BOOL;
import static com.openmason.main.systems.uiEditor.ops.UiOpField.INT;
import static com.openmason.main.systems.uiEditor.ops.UiOpField.KEY;
import static com.openmason.main.systems.uiEditor.ops.UiOpField.KEYS;
import static com.openmason.main.systems.uiEditor.ops.UiOpField.NULLABLE_STRING;
import static com.openmason.main.systems.uiEditor.ops.UiOpField.OBJECT;
import static com.openmason.main.systems.uiEditor.ops.UiOpField.RULE;
import static com.openmason.main.systems.uiEditor.ops.UiOpField.STRING;
import static com.openmason.main.systems.uiEditor.ops.UiOpField.STRING_LIST;

/**
 * Every op a {@link UiOpBatch} accepts: its fields (shape-checked up front) and how it runs.
 * Each op is a thin adapter over the editor's own commands ({@link NodeCommands},
 * {@link OverrideCommands}, {@link DocumentCommands}, {@link AnimationCommands}), so automation
 * edits exactly as the panels do and inherits their checks.
 */
final class UiOpCatalog {

    @FunctionalInterface
    interface Apply {
        void apply(UiOpScope s, JsonNode op) throws UiCommandException;
    }

    /** Extra shape rules a field table cannot say (mutually exclusive fields, enums). */
    @FunctionalInterface
    interface Check {
        void check(JsonNode op);

        Check NONE = op -> { };
    }

    record Spec(String name, Map<String, UiOpField> fields, List<String> required, boolean binds, Check check,
                Apply apply) {
        String usage() {
            StringBuilder sb = new StringBuilder(name).append(" {");
            boolean first = true;
            for (Map.Entry<String, UiOpField> f : fields.entrySet()) {
                sb.append(first ? "" : ", ").append(f.getKey()).append(required.contains(f.getKey()) ? "" : "?");
                first = false;
            }
            if (binds) {
                sb.append(first ? "" : ", ").append("as?");
            }
            return sb.append('}').toString();
        }
    }

    static final WidgetRegistry WIDGETS = WidgetRegistry.withBuiltIns();
    private static final Set<String> BINDING_MODES = Set.of("to-target", "two-way", "to-source", "once");
    private static final Map<String, Spec> OPS = new LinkedHashMap<>();

    private static final Object[] LOCATION = {"parent", KEY, "index", INT, "slot", STRING, "before", KEY,
        "after", KEY};

    static {
        // ── structure ──
        op("create", fields(LOCATION, "type", STRING, "id", STRING, "name", STRING, "classes", STRING_LIST,
            "props", OBJECT, "style", OBJECT, "data_source", STRING, "bindings", BINDINGS),
            List.of("type"), true, UiOpCatalog::checkCreate, UiOpCatalog::create);
        op("add_instance", fields(LOCATION, "component", STRING, "name", STRING, "params", OBJECT),
            List.of("component"), true, UiOpCatalog::checkLocation, UiOpCatalog::addInstance);
        op("delete", fields("keys", KEYS), List.of("keys"), false, Check.NONE,
            (s, o) -> s.run(NodeCommands.delete(s.documentNodes(s.keys(o, "keys"), "delete"))));
        op("move", fields(LOCATION, "keys", KEYS), List.of("keys"), false, UiOpCatalog::checkLocation,
            (s, o) -> {
                List<String> keys = s.documentNodes(s.keys(o, "keys"), "move");
                s.run(NodeCommands.move(keys, s.location(o)));
            });
        op("duplicate", fields("keys", KEYS), List.of("keys"), true, Check.NONE,
            (s, o) -> s.run(NodeCommands.duplicate(s.documentNodes(s.keys(o, "keys"), "duplicate"))));
        op("wrap", fields("keys", KEYS, "type", STRING), List.of("keys", "type"), true,
            o -> requireContainerType(o.get("type").asText()),
            (s, o) -> s.run(NodeCommands.wrap(s.documentNodes(s.keys(o, "keys"), "wrap"), o.get("type").asText())));
        op("reorder", fields("key", KEY, "delta", INT, "to", STRING), List.of("key"), false,
            UiOpCatalog::checkReorder, UiOpCatalog::reorder);
        op("rename", fields("key", KEY, "name", NULLABLE_STRING), List.of("key", "name"), false, Check.NONE,
            (s, o) -> s.run(NodeCommands.rename(s.documentNode(s.key(o, "key"), "rename").id(),
                UiOpScope.optText(o, "name"))));

        // ── element fields (component internals become overrides) ──
        op("set_prop", fields("keys", KEYS, "prop", STRING, "value", ANY), List.of("keys", "prop", "value"), false,
            Check.NONE, (s, o) -> setProp(s, s.keys(o, "keys"), o.get("prop").asText(), UiOpScope.value(o, "value")));
        op("clear_prop", fields("keys", KEYS, "prop", STRING), List.of("keys", "prop"), false, Check.NONE,
            (s, o) -> setProp(s, s.keys(o, "keys"), o.get("prop").asText(), null));
        op("set_style", fields("keys", KEYS, "style", OBJECT), List.of("keys", "style"), false, Check.NONE,
            (s, o) -> setStyle(s, s.keys(o, "keys"), UiJson.nullableMap(o.get("style"))));
        op("clear_style", fields("keys", KEYS, "properties", STRING_LIST), List.of("keys", "properties"), false,
            Check.NONE, (s, o) -> {
                Map<String, UiValue> clear = new LinkedHashMap<>();
                UiOpScope.strings(o, "properties").forEach(p -> clear.put(p, null));
                setStyle(s, s.keys(o, "keys"), clear);
            });
        op("set_classes", fields("keys", KEYS, "classes", STRING_LIST, "add", STRING_LIST, "remove", STRING_LIST),
            List.of("keys"), false, UiOpCatalog::checkClasses, UiOpCatalog::setClasses);
        op("set_data_source", fields("key", KEY, "path", NULLABLE_STRING), List.of("key", "path"), false, Check.NONE,
            (s, o) -> s.run(NodeCommands.setDataSource(s.documentNode(s.key(o, "key"), "set_data_source").id(),
                UiOpScope.optText(o, "path"))));
        op("bind", fields("key", KEY, "target", STRING, "path", STRING, "mode", STRING, "converter", STRING),
            List.of("key", "target", "path"), false, UiOpCatalog::checkBind, (s, o) -> {
                UiNode.UiBinding b = binding(o);
                s.run(NodeCommands.setBinding(s.documentNode(s.key(o, "key"), "bind").id(), b.target(), b));
            });
        op("unbind", fields("key", KEY, "target", STRING), List.of("key", "target"), false, Check.NONE,
            (s, o) -> s.run(NodeCommands.setBinding(s.documentNode(s.key(o, "key"), "unbind").id(),
                o.get("target").asText(), null)));
        op("set_param", fields("key", KEY, "param", STRING, "value", ANY), List.of("key", "param", "value"), false,
            Check.NONE, (s, o) -> s.run(OverrideCommands.setParam(s.documentNode(s.key(o, "key"), "set_param").id(),
                o.get("param").asText(), UiOpScope.value(o, "value"))));
        op("reset_override", fields("keys", KEYS), List.of("keys"), false, Check.NONE, (s, o) -> {
            for (String k : s.keys(o, "keys")) {
                if (k.indexOf('/') < 0) {
                    throw new UiCommandException("'" + k + "' is a document element, not an instance internal"
                        + " (reset_override takes keys like quit/label)");
                }
                s.run(OverrideCommands.reset(k));
            }
        });

        // ── document ──
        op("set_display_name", fields("name", STRING), List.of("name"), false, Check.NONE,
            (s, o) -> s.run(DocumentCommands.setDisplayName(o.get("name").asText())));
        op("add_sheet", fields("id", STRING), List.of("id"), false, Check.NONE,
            (s, o) -> s.run(DocumentCommands.addStyleSheet(o.get("id").asText())));
        op("attach_sheet", fields("id", STRING, "attached", BOOL), List.of("id"), false, Check.NONE,
            (s, o) -> s.run(DocumentCommands.attachStyleSheet(o.get("id").asText(), o.path("attached").asBoolean(true))));
        op("move_sheet", fields("id", STRING, "delta", INT), List.of("id", "delta"), false, Check.NONE,
            (s, o) -> s.run(DocumentCommands.moveStyleSheet(o.get("id").asText(), o.get("delta").asInt())));
        op("add_rule", fields("sheet", STRING, "selector", STRING, "style", OBJECT), List.of("sheet", "selector"), false,
            Check.NONE, (s, o) -> s.run(DocumentCommands.addRule(o.get("sheet").asText(), o.get("selector").asText(),
                o.has("style") ? withoutNulls(UiJson.nullableMap(o.get("style"))) : Map.of())));
        op("set_rule", fields("sheet", STRING, "rule", RULE, "selector", STRING, "style", OBJECT),
            List.of("sheet", "rule"), false, Check.NONE, UiOpCatalog::setRule);
        op("remove_rule", fields("sheet", STRING, "rule", RULE), List.of("sheet", "rule"), false, Check.NONE,
            (s, o) -> s.run(DocumentCommands.removeRule(o.get("sheet").asText(), rule(s, o))));
        op("move_rule", fields("sheet", STRING, "rule", RULE, "delta", INT), List.of("sheet", "rule", "delta"), false,
            Check.NONE, (s, o) -> s.run(DocumentCommands.moveRule(o.get("sheet").asText(), rule(s, o),
                o.get("delta").asInt())));
        op("set_token", fields("sheet", STRING, "name", STRING, "value", ANY), List.of("sheet", "name", "value"), false,
            o -> {
                if (!o.get("name").asText().startsWith("--")) {
                    throw new IllegalArgumentException("token names start with -- (\"--accent\")");
                }
            }, (s, o) -> s.run(DocumentCommands.setVariable(o.get("sheet").asText(), o.get("name").asText(),
                UiOpScope.value(o, "value"))));
        op("set_script", fields("id", STRING, "source", STRING), List.of("id", "source"), false, Check.NONE,
            (s, o) -> s.run(DocumentCommands.setScript(o.get("id").asText(), o.get("source").asText())));
        op("set_code_behind", fields("module", NULLABLE_STRING), List.of("module"), false, Check.NONE,
            (s, o) -> s.run(DocumentCommands.setCodeBehind(UiOpScope.optText(o, "module"))));
        op("put_clip", fields("clip", OBJECT), List.of("clip"), false, o -> {
            if (!o.get("clip").path("id").isTextual()) {
                throw new IllegalArgumentException("clip.id is required (the clip's id, e.g. \"open\")");
            }
        }, UiOpCatalog::putClip);
        op("remove_clip", fields("id", STRING), List.of("id"), false, Check.NONE,
            (s, o) -> s.run(AnimationCommands.removeClip(o.get("id").asText())));
        op("put_state_machine", fields("machine", OBJECT), List.of("machine"), false, o -> {
            if (!o.get("machine").path("id").isTextual()) {
                throw new IllegalArgumentException("machine.id is required (the state machine's id)");
            }
        }, UiAssetOps::putStateMachine);
        op("remove_state_machine", fields("id", STRING), List.of("id"), false, Check.NONE,
            UiAssetOps::removeStateMachine);

        // ── behavior graphs ──
        op("put_graph", fields("graph", OBJECT), List.of("graph"), false, o -> {
            if (!o.get("graph").path("id").isTextual()) {
                throw new IllegalArgumentException("graph.id is required (the graph's id, e.g. \"behaviors\")");
            }
        }, UiAssetOps::putGraph);
        op("remove_graph", fields("id", STRING), List.of("id"), false, Check.NONE, UiAssetOps::removeGraph);

        // ── dependency table (textures, sprites, fonts, sounds, shared scripts/sheets) ──
        op("add_dependency", fields("path", STRING, "id", STRING, "kind", STRING, "embed", BOOL, "optional", BOOL,
                "fallback", NULLABLE_STRING, "requires", STRING_LIST, "license", NULLABLE_STRING), List.of(), false,
            o -> {
                if (!o.hasNonNull("path") && !o.hasNonNull("id")) {
                    throw new IllegalArgumentException("give path (a project file) or id + kind");
                }
            }, UiAssetOps::add);
        op("set_dependency", fields("id", STRING, "optional", BOOL, "fallback", NULLABLE_STRING,
                "requires", STRING_LIST, "license", NULLABLE_STRING), List.of("id"), false, Check.NONE,
            UiAssetOps::set);
        op("remove_dependency", fields("id", STRING, "force", BOOL), List.of("id"), false, Check.NONE,
            UiAssetOps::remove);
        op("embed_dependency", fields("id", STRING), List.of("id"), false, Check.NONE,
            (s, o) -> UiAssetOps.embed(s, o.get("id").asText()));
        op("refresh_dependency", fields("id", STRING), List.of("id"), false, Check.NONE, UiAssetOps::refresh);
        op("extract_dependency", fields("id", STRING, "collision", STRING), List.of("id"), false, o -> {
            if (o.hasNonNull("collision") && !Set.of("fail", "keep_project", "replace")
                    .contains(o.get("collision").asText())) {
                throw new IllegalArgumentException("collision is fail, keep_project or replace");
            }
        }, UiAssetOps::extract);
        op("relink_dependency", fields("id", STRING, "path", STRING), List.of("id", "path"), false, Check.NONE,
            UiAssetOps::relink);
    }

    private UiOpCatalog() {
    }

    static Spec get(String name) {
        return OPS.get(name);
    }

    static String names() {
        return String.join(", ", OPS.keySet());
    }

    /** One line per op ({@code name {field, optional?}}) for guides and teaching errors. */
    static List<String> usages() {
        return OPS.values().stream().map(Spec::usage).toList();
    }

    static String closest(String name) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : OPS.keySet()) {
            int d = distance(name, c);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best != null && bestDist <= Math.max(1, name.length() / 3) ? best : null;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static void op(String name, Map<String, UiOpField> fields, List<String> required, boolean binds,
                           Check check, Apply apply) {
        OPS.put(name, new Spec(name, fields, required, binds, check, apply));
    }

    private static Map<String, UiOpField> fields(Object... kv) {
        Map<String, UiOpField> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i++) {
            if (kv[i] instanceof Object[] nested) {
                out.putAll(fields(nested));
            } else {
                out.put((String) kv[i], (UiOpField) kv[++i]);
            }
        }
        return out;
    }

    // ── checks ──────────────────────────────────────────────────────────────

    private static void checkLocation(JsonNode o) {
        boolean relative = o.hasNonNull("before") || o.hasNonNull("after");
        if (o.hasNonNull("before") && o.hasNonNull("after")) {
            throw new IllegalArgumentException("give before or after, not both");
        }
        if (relative && (o.hasNonNull("parent") || o.hasNonNull("index") || o.hasNonNull("slot"))) {
            throw new IllegalArgumentException("before/after place next to a sibling; drop parent/index/slot");
        }
    }

    private static void checkCreate(JsonNode o) {
        checkLocation(o);
        String type = o.get("type").asText();
        if (UiNode.INSTANCE_TYPE.equals(type)) {
            throw new IllegalArgumentException("component instances are placed with add_instance {component}");
        }
        if (type.indexOf(':') < 0 && WIDGETS.get(type) == null) {
            throw new IllegalArgumentException("unknown widget type '" + type + "'; built-ins: " + widgetTypes());
        }
        if (o.hasNonNull("id") && !OmuiFormat.LOCAL_ID.matcher(o.get("id").asText()).matches()) {
            throw new IllegalArgumentException("id '" + o.get("id").asText()
                + "' is not a valid element id (letter or _, then letters, digits, _ -; at most 64)");
        }
        if (o.has("bindings")) {
            for (JsonNode b : o.get("bindings")) {
                if (!b.path("target").isTextual() || !b.path("path").isTextual()) {
                    throw new IllegalArgumentException("each binding needs target and path");
                }
                checkMode(b.path("mode"));
            }
        }
    }

    private static void requireContainerType(String type) {
        if (!NodeCommands.acceptsChildren(type)) {
            throw new IllegalArgumentException(type + " cannot hold children; containers: " + containerTypes());
        }
    }

    private static void checkReorder(JsonNode o) {
        boolean to = o.hasNonNull("to");
        if (to == o.hasNonNull("delta")) {
            throw new IllegalArgumentException("give delta (+1 forward, -1 backward) or to (\"front\"|\"back\")");
        }
        if (to && !Set.of("front", "back").contains(o.get("to").asText())) {
            throw new IllegalArgumentException("to is \"front\" or \"back\"");
        }
    }

    private static void checkClasses(JsonNode o) {
        if (!o.has("classes") && !o.has("add") && !o.has("remove")) {
            throw new IllegalArgumentException("give classes (replace) and/or add / remove");
        }
    }

    private static void checkBind(JsonNode o) {
        String target = o.get("target").asText();
        if (!(target.startsWith("prop:") || target.startsWith("style:") || target.startsWith("class:"))) {
            throw new IllegalArgumentException("target is prop:<name>, style:<property> or class:<name>");
        }
        checkMode(o.path("mode"));
    }

    private static void checkMode(JsonNode mode) {
        if (mode.isTextual() && !BINDING_MODES.contains(mode.asText())) {
            throw new IllegalArgumentException("binding mode is one of " + new TreeSet<>(BINDING_MODES));
        }
    }

    private static String widgetTypes() {
        return WIDGETS.all().stream().map(WidgetDescriptor::type).filter(t -> !UiNode.INSTANCE_TYPE.equals(t))
            .sorted().toList().toString();
    }

    private static String containerTypes() {
        return WIDGETS.all().stream().map(WidgetDescriptor::type).filter(NodeCommands::acceptsChildren).sorted()
            .toList().toString();
    }

    // ── structure ───────────────────────────────────────────────────────────

    private static void create(UiOpScope s, JsonNode o) throws UiCommandException {
        String type = o.get("type").asText();
        NodeLocation at = s.location(o);
        Set<String> taken = new HashSet<>(UiTree.ids(s.ctx.root()));
        String id;
        if (o.hasNonNull("id")) {
            id = o.get("id").asText();
            if (taken.contains(id)) {
                throw new UiCommandException("Element id '" + id + "' is already used");
            }
        } else {
            id = UiIds.fresh(type, taken);
        }
        s.run(NodeCommands.insert("Add " + type, Nodes.create(id, type), at));
        fill(s, id, o);
        s.ctx.select(List.of(id));
    }

    /** The optional fields shared by create: name, classes, props, style, data source, bindings. */
    private static void fill(UiOpScope s, String id, JsonNode o) throws UiCommandException {
        if (o.hasNonNull("name")) {
            s.run(NodeCommands.rename(id, o.get("name").asText()));
        }
        if (o.has("classes")) {
            checkClassNames(UiOpScope.strings(o, "classes"));
            s.run(NodeCommands.setClasses(id, UiOpScope.strings(o, "classes")));
        }
        if (o.has("props")) {
            for (Map.Entry<String, UiValue> e : UiJson.nullableMap(o.get("props")).entrySet()) {
                if (e.getValue() != null) {
                    s.run(NodeCommands.setProp(List.of(id), e.getKey(), e.getValue()));
                }
            }
        }
        if (o.has("style")) {
            Map<String, UiValue> style = withoutNulls(UiJson.nullableMap(o.get("style")));
            if (!style.isEmpty()) {
                s.run(NodeCommands.setStyle(List.of(id), style, "Style " + id));
            }
        }
        if (o.hasNonNull("data_source")) {
            s.run(NodeCommands.setDataSource(id, o.get("data_source").asText()));
        }
        if (o.has("bindings")) {
            for (JsonNode b : o.get("bindings")) {
                UiNode.UiBinding binding = binding(b);
                s.run(NodeCommands.setBinding(id, binding.target(), binding));
            }
        }
    }

    private static void addInstance(UiOpScope s, JsonNode o) throws UiCommandException {
        String component = o.get("component").asText();
        if (component.equals(s.ctx.doc().manifest().documentId())) {
            throw new UiCommandException("A document cannot contain an instance of itself");
        }
        UiDependency listed = s.ctx.doc().dependencies().find(component);
        List<UiDependency> rows = s.components.rows(component);
        if (listed == null && rows.isEmpty()) {
            throw new UiCommandException("No component '" + component + "': it is neither a dependency of this"
                + " document nor a component in the open project (save the component first; ui_documents"
                + " lists open ones)");
        }
        if (listed != null && listed.kind() != UiDependency.Kind.COMPONENT) {
            throw new UiCommandException(component + " is a " + listed.kind().wire() + " dependency, not a component");
        }
        // rows the document already lists are kept as they are; missing closure rows are added
        s.run(DocumentCommands.addInstance(component, rows, s.location(o)));
        String id = s.ctx.selection().iterator().next();
        if (o.hasNonNull("name")) {
            s.run(NodeCommands.rename(id, o.get("name").asText()));
        }
        if (o.has("params")) {
            for (Map.Entry<String, UiValue> e : UiJson.nullableMap(o.get("params")).entrySet()) {
                s.run(OverrideCommands.setParam(id, e.getKey(), e.getValue()));
            }
        }
        s.ctx.select(List.of(id));
    }

    private static void reorder(UiOpScope s, JsonNode o) throws UiCommandException {
        String id = s.documentNode(s.key(o, "key"), "reorder").id();
        if (o.hasNonNull("to")) {
            s.run(NodeCommands.reorderToEnd(id, "front".equals(o.get("to").asText())));
        } else {
            s.run(NodeCommands.reorder(id, o.get("delta").asInt()));
        }
    }

    // ── element fields ──────────────────────────────────────────────────────

    private static void setProp(UiOpScope s, List<String> keys, String prop, UiValue value) throws UiCommandException {
        List<String> docNodes = new ArrayList<>();
        for (String k : keys) {
            if (k.indexOf('/') >= 0) {
                s.requireInternal(k);
                s.run(OverrideCommands.setProp(k, prop, value));
            } else {
                s.ctx.require(k);
                docNodes.add(k);
            }
        }
        if (!docNodes.isEmpty()) {
            s.run(NodeCommands.setProp(docNodes, prop, value));
        }
    }

    /** {@code style} values of {@code null} remove the declaration. */
    private static void setStyle(UiOpScope s, List<String> keys, Map<String, UiValue> style) throws UiCommandException {
        Map<String, UiValue> decl = new LinkedHashMap<>();
        style.forEach((k, v) -> decl.put(k, v == null ? UiValue.NULL : v));
        List<String> docNodes = new ArrayList<>();
        for (String k : keys) {
            if (k.indexOf('/') >= 0) {
                s.requireInternal(k);
                s.run(OverrideCommands.setStyle(k, decl, "Override style"));
            } else {
                s.ctx.require(k);
                docNodes.add(k);
            }
        }
        if (!docNodes.isEmpty()) {
            s.run(NodeCommands.setStyle(docNodes, decl, "Set style"));
        }
    }

    private static void setClasses(UiOpScope s, JsonNode o) throws UiCommandException {
        List<String> add = UiOpScope.strings(o, "add");
        List<String> remove = UiOpScope.strings(o, "remove");
        checkClassNames(add);
        checkClassNames(remove);
        for (String k : s.keys(o, "keys")) {
            if (k.indexOf('/') >= 0) {
                if (o.has("classes")) {
                    throw new UiCommandException("'" + k + "' is inside a component: use add/remove (an override"
                        + " adds or removes classes on top of the component's own)");
                }
                s.requireInternal(k);
                UiNode inst = s.ctx.require(k.substring(0, k.indexOf('/')));
                OverrideCommands.Target t = OverrideCommands.Target.of(k);
                UiNode.InstanceOverride cur = inst.instance() == null ? null : Nodes.override(inst.instance(), t.target());
                Set<String> adds = new LinkedHashSet<>(cur == null ? List.of() : cur.addClasses());
                Set<String> removes = new LinkedHashSet<>(cur == null ? List.of() : cur.removeClasses());
                adds.addAll(add);
                remove.forEach(adds::remove);
                removes.addAll(remove);
                add.forEach(removes::remove);
                s.run(OverrideCommands.setClasses(k, List.copyOf(adds), List.copyOf(removes)));
            } else {
                UiNode n = s.ctx.require(k);
                List<String> base = o.has("classes") ? UiOpScope.strings(o, "classes") : n.classes();
                checkClassNames(base);
                Set<String> next = new LinkedHashSet<>(base);
                next.addAll(add);
                remove.forEach(next::remove);
                s.run(NodeCommands.setClasses(k, List.copyOf(next)));
            }
        }
    }

    private static void checkClassNames(List<String> classes) throws UiCommandException {
        for (String c : classes) {
            if (!com.openmason.engine.format.omui.UiSelectors.isIdent(c)) {
                throw new UiCommandException("'" + c + "' is not a valid class name (letters, digits, _ and -)");
            }
        }
    }

    private static UiNode.UiBinding binding(JsonNode b) {
        UiNode.BindingMode mode = UiNode.BindingMode.TO_TARGET;
        if (b.path("mode").isTextual()) {
            for (UiNode.BindingMode m : UiNode.BindingMode.values()) {
                if (m.wire().equals(b.get("mode").asText())) {
                    mode = m;
                }
            }
        }
        String converter = b.path("converter").isTextual() ? b.get("converter").asText() : null;
        return new UiNode.UiBinding(b.get("target").asText(), b.get("path").asText(), mode, converter, Map.of());
    }

    // ── style sheets ────────────────────────────────────────────────────────

    /** A rule by index or by exact selector (the first match). */
    private static int rule(UiOpScope s, JsonNode o) throws UiCommandException {
        String sheetId = o.get("sheet").asText();
        UiStyleSheet sheet = s.ctx.doc().styles().get(sheetId);
        if (sheet == null) {
            throw new UiCommandException("Style sheet '" + sheetId + "' is not part of this document; in-archive"
                + " sheets: " + s.ctx.doc().styles().keySet());
        }
        JsonNode r = o.get("rule");
        if (r.isIntegralNumber()) {
            return r.asInt();
        }
        List<String> selectors = new ArrayList<>();
        for (int i = 0; i < sheet.rules().size(); i++) {
            if (sheet.rules().get(i).selector().equals(r.asText())) {
                return i;
            }
            selectors.add(sheet.rules().get(i).selector());
        }
        throw new UiCommandException("Sheet '" + sheetId + "' has no rule '" + r.asText() + "'; rules: " + selectors);
    }

    private static void setRule(UiOpScope s, JsonNode o) throws UiCommandException {
        String sheet = o.get("sheet").asText();
        int index = rule(s, o);
        if (o.hasNonNull("selector")) {
            s.run(DocumentCommands.setRuleSelector(sheet, index, o.get("selector").asText()));
        }
        if (o.has("style")) {
            for (Map.Entry<String, UiValue> e : UiJson.nullableMap(o.get("style")).entrySet()) {
                s.run(DocumentCommands.setRuleDeclaration(sheet, index, e.getKey(), e.getValue()));
            }
        }
    }

    private static Map<String, UiValue> withoutNulls(Map<String, UiValue> m) {
        Map<String, UiValue> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    // ── animation ───────────────────────────────────────────────────────────

    private static void putClip(UiOpScope s, JsonNode o) throws UiCommandException {
        JsonNode clipJson = o.get("clip");
        String id = clipJson.get("id").asText();
        if (!OmuiFormat.PART_ID.matcher(id).matches()) {
            throw new UiCommandException("'" + id + "' is not a valid clip id (lowercase, digits, _ - /)");
        }
        UiDiagnostics d = new UiDiagnostics();
        UiAnimationClip clip = AnimationCodec.read(id, OmuiFormat.ANIMATIONS_DIR + id + ".anim.json",
            UiJson.value(clipJson), d);
        if (d.hasErrors()) {
            throw new UiCommandException("Clip '" + id + "': " + d.list().getFirst().message());
        }
        s.run(DocumentCommands.putClip(clip));
    }
}
