package com.openmason.main.systems.uiEditor.ops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.style.Selector;
import com.openmason.engine.ui.runtime.style.SelectorParser;
import com.openmason.engine.ui.runtime.style.StyleTrace;
import com.openmason.main.systems.uiEditor.command.OverrideCommands;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.Nodes;
import com.openmason.main.systems.uiEditor.document.UiTree;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Read-only views of a UI document for automation (#324): the element tree, one element's
 * authored fields plus its computed style and where each value came from, and the style sheets
 * with the rules that match an element. Pure functions of the source and (optionally) a laid-out
 * runtime instance; nothing here can change either.
 */
public final class UiInspector {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private UiInspector() {
    }

    /**
     * A key as written by a caller: {@code #name} (optionally {@code #name/inner}) is resolved to
     * the element id of the node with that name; anything else is returned as is.
     *
     * @throws IllegalArgumentException when no element has the name
     */
    public static String resolveKey(UiNode root, String key) {
        if (key == null || !key.startsWith("#")) {
            return key;
        }
        int slash = key.indexOf('/');
        String name = slash < 0 ? key.substring(1) : key.substring(1, slash);
        for (UiNode n : root.flatten()) {
            if (name.equals(n.name())) {
                return slash < 0 ? n.id() : n.id() + key.substring(slash);
            }
        }
        throw new IllegalArgumentException("No element named '" + name + "'; names: " + new java.util.TreeSet<>(
            UiTree.names(root)));
    }

    // ── tree ────────────────────────────────────────────────────────────────

    /**
     * The element tree, keys first. {@code internals} adds the elements inside component
     * instances (from the runtime, keys like {@code quit/label}) when {@code ui} is given.
     */
    public static ObjectNode tree(OmuiArchive doc, UiDocumentInstance ui, boolean internals) {
        return node(doc.document().root(), ui, internals);
    }

    private static ObjectNode node(UiNode n, UiDocumentInstance ui, boolean internals) {
        ObjectNode o = F.objectNode();
        o.put("key", n.id());
        o.put("type", n.type());
        if (n.name() != null) {
            o.put("name", n.name());
        }
        if (!n.classes().isEmpty()) {
            o.set("classes", strings(n.classes()));
        }
        if (n.instance() != null) {
            o.put("component", n.instance().component());
            if (!n.instance().overrides().isEmpty()) {
                o.put("overrides", n.instance().overrides().size());
            }
            if (!n.instance().slots().isEmpty()) {
                ObjectNode slots = o.putObject("slots");
                n.instance().slots().forEach((slot, list) -> {
                    ArrayNode arr = slots.putArray(slot);
                    list.forEach(c -> arr.add(node(c, ui, internals)));
                });
            }
            UiElement el = ui == null ? null : ui.find(n.id());
            if (internals && el != null && !el.children().isEmpty()) {
                ArrayNode arr = o.putArray("internals");
                el.children().forEach(c -> arr.add(internal(c)));
            }
        }
        if (!n.children().isEmpty()) {
            ArrayNode kids = o.putArray("children");
            n.children().forEach(c -> kids.add(node(c, ui, internals)));
        }
        return o;
    }

    private static ObjectNode internal(UiElement el) {
        ObjectNode o = F.objectNode();
        o.put("key", el.key());
        o.put("type", el.type());
        if (el.name() != null) {
            o.put("name", el.name());
        }
        if (!el.children().isEmpty()) {
            ArrayNode kids = o.putArray("children");
            el.children().forEach(c -> kids.add(internal(c)));
        }
        return o;
    }

    // ── one element ─────────────────────────────────────────────────────────

    /**
     * One element: its authored source (or, inside an instance, the component's node and this
     * document's override of it), and with a runtime its rect and, when {@code computed}, every
     * computed property with the layer and rule it came from.
     *
     * @throws IllegalArgumentException when the key names nothing
     */
    public static ObjectNode element(OmuiArchive doc, UiDocumentInstance ui, String key, boolean computed) {
        UiNode root = doc.document().root();
        key = resolveKey(root, key);
        ObjectNode o = F.objectNode();
        o.put("key", key);
        UiElement el = ui == null ? null : ui.find(key);
        if (key.indexOf('/') < 0) {
            UiNode n = UiTree.find(root, key);
            if (n == null) {
                throw new IllegalArgumentException("No element '" + key + "'" + near(root, key));
            }
            authored(o, n);
            NodeLocation at = UiTree.locate(root, key);
            if (at != null) {
                ObjectNode loc = o.putObject("location");
                loc.put("parent", at.parentId());
                if (at.slot() != null) {
                    loc.put("slot", at.slot());
                }
                loc.put("index", at.index());
            }
        } else {
            OverrideCommands.Target t = OverrideCommands.Target.of(key);
            UiNode inst = UiTree.find(root, t.instanceId());
            if (inst == null || inst.instance() == null) {
                throw new IllegalArgumentException("'" + t.instanceId() + "' is not a component instance in this"
                    + " document (internal keys are <instance>/<node>)");
            }
            if (el == null && ui != null) {
                throw new IllegalArgumentException("Component " + inst.instance().component() + " has no element '"
                    + t.target() + "' (ui_tree with internals lists them)");
            }
            o.put("instance", t.instanceId());
            o.put("component", inst.instance().component());
            if (el != null) {
                o.put("type", el.type());
                if (el.name() != null) {
                    o.put("name", el.name());
                }
                ObjectNode source = o.putObject("source");
                authored(source, el.node());
            }
            UiNode.InstanceOverride ov = Nodes.override(inst.instance(), t.target());
            if (ov != null) {
                ObjectNode oo = o.putObject("override");
                putMap(oo, "props", ov.props());
                putMap(oo, "style", ov.style());
                if (!ov.addClasses().isEmpty()) {
                    oo.set("addClasses", strings(ov.addClasses()));
                }
                if (!ov.removeClasses().isEmpty()) {
                    oo.set("removeClasses", strings(ov.removeClasses()));
                }
            }
        }
        if (el != null) {
            UiRect r = el.rect();
            ObjectNode rect = o.putObject("rect");
            rect.put("x", r.x());
            rect.put("y", r.y());
            rect.put("w", r.width());
            rect.put("h", r.height());
            if (computed) {
                computed(o, ui, el);
            }
        }
        return o;
    }

    private static void authored(ObjectNode o, UiNode n) {
        o.put("type", n.type());
        if (n.name() != null) {
            o.put("name", n.name());
        }
        if (!n.classes().isEmpty()) {
            o.set("classes", strings(n.classes()));
        }
        putMap(o, "props", n.props());
        putMap(o, "style", n.style());
        if (n.dataSource() != null) {
            o.put("dataSource", n.dataSource());
        }
        if (!n.bindings().isEmpty()) {
            ArrayNode arr = o.putArray("bindings");
            for (UiNode.UiBinding b : n.bindings()) {
                ObjectNode bo = arr.addObject();
                bo.put("target", b.target());
                bo.put("path", b.path());
                if (b.mode() != UiNode.BindingMode.TO_TARGET) {
                    bo.put("mode", b.mode().wire());
                }
                if (b.converter() != null) {
                    bo.put("converter", b.converter());
                }
            }
        }
        if (n.instance() != null) {
            ObjectNode io = o.putObject("instanceOf");
            io.put("component", n.instance().component());
            putMap(io, "params", n.instance().params());
            if (!n.instance().overrides().isEmpty()) {
                io.set("overrides", strings(n.instance().overrides().stream().map(UiNode.InstanceOverride::target)
                    .toList()));
            }
            if (!n.instance().slots().isEmpty()) {
                io.set("slots", strings(List.copyOf(n.instance().slots().keySet())));
            }
        }
        if (!n.children().isEmpty()) {
            o.set("children", strings(n.children().stream().map(UiNode::id).toList()));
        }
    }

    private static void computed(ObjectNode o, UiDocumentInstance ui, UiElement el) {
        StyleTrace trace = ui.styleTrace(el);
        ObjectNode values = o.putObject("computed");
        el.computedStyle().values().forEach((prop, v) -> {
            StyleTrace.Declaration d = trace.winners().get(prop);
            if (d == null) {
                values.set(prop, UiJson.json(v));
            } else {
                ObjectNode e = values.putObject(prop);
                e.set("value", UiJson.json(v));
                e.put("from", origin(d));
            }
        });
        ArrayNode rules = o.putArray("matchedRules");
        for (StyleTrace.MatchedRule r : trace.rules()) {
            rules.add(r.sheetId() + "#" + r.ruleIndex() + " " + r.selector() + " (spec " + r.specificity() + ")");
        }
        if (!el.classes().isEmpty()) {
            o.set("runtimeClasses", strings(List.copyOf(el.classes())));
        }
    }

    private static String origin(StyleTrace.Declaration d) {
        if (d.layer() == StyleTrace.Layer.RULE && d.rule() != null) {
            return "rule " + d.rule().sheetId() + "#" + d.rule().ruleIndex() + " " + d.rule().selector();
        }
        return d.layer().name().toLowerCase();
    }

    // ── style sheets ────────────────────────────────────────────────────────

    /**
     * The attached sheets in precedence order (lowest first) plus unattached in-archive sheets,
     * with tokens and rules; with {@code key} and a runtime, only the rules matching that element.
     */
    public static ObjectNode styleSheets(OmuiArchive doc, UiDocumentInstance ui, String key) {
        ObjectNode o = F.objectNode();
        UiDocument d = doc.document();
        o.set("attached", strings(d.styleSheets()));
        ArrayNode sheets = o.putArray("sheets");
        for (UiStyleSheet s : doc.styles().values()) {
            ObjectNode so = sheets.addObject();
            so.put("id", s.id());
            so.put("attached", d.styleSheets().contains(s.id()));
            putMap(so, "tokens", s.variables());
            if (!s.customStates().isEmpty()) {
                so.set("customStates", strings(s.customStates()));
            }
            ArrayNode rules = so.putArray("rules");
            for (int i = 0; i < s.rules().size(); i++) {
                UiStyleSheet.StyleRule r = s.rules().get(i);
                ObjectNode ro = rules.addObject();
                ro.put("index", i);
                ro.put("selector", r.selector());
                ro.put("specificity", specificity(r.selector(), s));
                ro.set("style", UiJson.json(r.style()));
                if (!r.transitions().isEmpty()) {
                    ro.put("transitions", r.transitions().size());
                }
            }
        }
        if (key != null) {
            key = resolveKey(d.root(), key);
            UiElement el = ui == null ? null : ui.find(key);
            if (el == null) {
                throw new IllegalArgumentException("No laid-out element '" + key + "' to match rules against");
            }
            ArrayNode matched = o.putArray("matching");
            StyleTrace trace = ui.styleTrace(el);
            for (StyleTrace.MatchedRule r : trace.rules()) {
                ObjectNode mo = matched.addObject();
                mo.put("sheet", r.sheetId());
                mo.put("index", r.ruleIndex());
                mo.put("selector", r.selector());
                mo.put("specificity", r.specificity());
                mo.put("contributes", trace.contributes(r));
            }
        }
        return o;
    }

    private static int specificity(String selector, UiStyleSheet s) {
        try {
            int best = 0;
            for (Selector sel : SelectorParser.parseList(selector, new HashSet<>(s.customStates()))) {
                best = Math.max(best, sel.specificity());
            }
            return best;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ArrayNode strings(List<String> list) {
        ArrayNode a = F.arrayNode();
        list.forEach(a::add);
        return a;
    }

    private static void putMap(ObjectNode o, String field, Map<String, UiValue> m) {
        if (m != null && !m.isEmpty()) {
            o.set(field, UiJson.json(m));
        }
    }

    private static String near(UiNode root, String key) {
        List<String> ids = UiTree.ids(root);
        return ids.size() <= 20 ? ". Elements: " + ids : ". " + ids.size() + " elements (ui_tree lists them)";
    }
}
