package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiDocument.ComponentDef;
import com.openmason.engine.format.omui.UiDocument.EventDef;
import com.openmason.engine.format.omui.UiDocument.Param;
import com.openmason.engine.format.omui.UiDocument.Slot;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiNode.BindingMode;
import com.openmason.engine.format.omui.UiNode.ComponentInstance;
import com.openmason.engine.format.omui.UiNode.InstanceOverride;
import com.openmason.engine.format.omui.UiNode.UiBinding;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code document.json} ↔ {@link UiDocument}, including the node tree. */
public final class DocumentCodec {

    private final ArchiveLimits limits;
    private final UiDiagnostics diagnostics;
    private int nodeCount;
    private boolean limitReported;

    private DocumentCodec(ArchiveLimits limits, UiDiagnostics diagnostics) {
        this.limits = limits;
        this.diagnostics = diagnostics;
    }

    public static UiDocument read(UiValue root, ArchiveLimits limits, UiDiagnostics d) {
        return new DocumentCodec(limits, d).readDocument(ObjReader.of(root, OmuiFormat.DOCUMENT, "", d));
    }

    private UiDocument readDocument(ObjReader r) {
        UiNode rootNode;
        ObjReader rootReader = r.optionalObject("root");
        if (rootReader == null) {
            r.error(Code.MISSING_FIELD, "root", "Required field 'root' is missing");
            rootNode = UiNode.of("root", "Box", List.of());
        } else {
            rootNode = readNode(rootReader, 1);
        }
        List<String> sheets = r.stringList("styleSheets");
        String codeBehind = r.optionalString("codeBehind", null);
        ObjReader comp = r.optionalObject("component");
        ComponentDef component = comp == null ? null : readComponent(comp);
        return new UiDocument(rootNode, sheets, codeBehind, component, r.unknown());
    }

    private UiNode readNode(ObjReader r, int depth) {
        if (++nodeCount > limits.maxNodes() || depth > limits.maxTreeDepth()) {
            if (!limitReported) {
                limitReported = true;
                r.error(Code.LIMIT_EXCEEDED, null, nodeCount > limits.maxNodes()
                        ? "More than " + limits.maxNodes() + " nodes"
                        : "Tree deeper than " + limits.maxTreeDepth());
            }
            return UiNode.of("_", "Box", List.of());
        }
        String id = r.requiredString("id");
        String name = r.optionalString("name", null);
        String type = r.requiredString("type");
        int typeVersion = r.optionalInt("typeVersion", 1, 1, OmuiFormat.MAX_VERSION);
        List<String> classes = r.stringList("classes");
        Map<String, UiValue> props = r.freeMap("props");
        Map<String, UiValue> style = r.freeMap("style");
        String dataSource = r.optionalString("dataSource", null);
        List<UiBinding> bindings = new ArrayList<>();
        for (ObjReader b : r.objects("bindings")) {
            bindings.add(new UiBinding(b.requiredString("target"), b.requiredString("path"),
                    b.optionalEnum("mode", BindingMode.class, BindingMode.TO_TARGET),
                    b.optionalString("converter", null), b.unknown()));
        }
        ObjReader inst = r.optionalObject("instance");
        ComponentInstance instance = inst == null ? null : readInstance(inst, depth);
        List<UiNode> children = readChildren(r, "children", depth);
        return new UiNode(id, name, type, typeVersion, classes, props, style, dataSource, bindings, instance,
                children, r.unknown());
    }

    private List<UiNode> readChildren(ObjReader r, String key, int depth) {
        List<UiNode> out = new ArrayList<>();
        for (ObjReader c : r.objects(key)) {
            out.add(readNode(c, depth + 1));
        }
        return out;
    }

    private ComponentInstance readInstance(ObjReader r, int depth) {
        String component = r.requiredString("component");
        Map<String, UiValue> params = r.freeMap("params");
        List<InstanceOverride> overrides = new ArrayList<>();
        for (ObjReader o : r.objects("overrides")) {
            overrides.add(new InstanceOverride(o.requiredString("target"), o.freeMap("props"), o.freeMap("style"),
                    o.stringList("addClasses"), o.stringList("removeClasses"), o.unknown()));
        }
        Map<String, List<UiNode>> slots = new LinkedHashMap<>();
        ObjReader slotsReader = r.optionalObject("slots");
        if (slotsReader != null) {
            for (String slot : r.freeMap("slots").keySet()) {
                slots.put(slot, readChildren(slotsReader, slot, depth));
            }
        }
        return new ComponentInstance(component, params, overrides, slots, r.unknown());
    }

    private ComponentDef readComponent(ObjReader r) {
        List<Param> params = new ArrayList<>();
        for (ObjReader p : r.objects("params")) {
            params.add(readParam(p));
        }
        List<EventDef> events = new ArrayList<>();
        for (ObjReader e : r.objects("events")) {
            List<Param> args = new ArrayList<>();
            for (ObjReader a : e.objects("args")) {
                args.add(readParam(a));
            }
            events.add(new EventDef(e.requiredString("name"), args, e.unknown()));
        }
        List<Slot> slots = new ArrayList<>();
        for (ObjReader s : r.objects("slots")) {
            slots.add(new Slot(s.requiredString("name"), s.requiredString("host"), s.unknown()));
        }
        return new ComponentDef(params, events, slots, r.unknown());
    }

    static Param readParam(ObjReader p) {
        String name = p.requiredString("name");
        ValueType type = p.requiredEnum("type", ValueType.class, ValueType.STRING);
        UiValue def = p.raw("default");
        return new Param(name, type, def, p.unknown());
    }

    // ── writing ──

    public static UiValue.Obj write(UiDocument doc) {
        return new ObjWriter()
                .put("root", writeNode(doc.root()))
                .putStrings("styleSheets", doc.styleSheets())
                .put("codeBehind", doc.codeBehind())
                .put("component", doc.component() == null ? null : writeComponent(doc.component()))
                .putUnknown(doc.unknown())
                .build();
    }

    private static UiValue writeNode(UiNode n) {
        return new ObjWriter()
                .put("id", n.id())
                .put("name", n.name())
                .put("type", n.type())
                .putIfNot("typeVersion", n.typeVersion(), 1)
                .putStrings("classes", n.classes())
                .putMap("props", n.props())
                .putMap("style", n.style())
                .put("dataSource", n.dataSource())
                .putList("bindings", n.bindings(), DocumentCodec::writeBinding)
                .put("instance", n.instance() == null ? null : writeInstance(n.instance()))
                .putList("children", n.children(), DocumentCodec::writeNode)
                .putUnknown(n.unknown())
                .build();
    }

    private static UiValue writeBinding(UiBinding b) {
        return new ObjWriter()
                .put("target", b.target())
                .put("path", b.path())
                .putEnumIfNot("mode", b.mode(), BindingMode.TO_TARGET)
                .put("converter", b.converter())
                .putUnknown(b.unknown())
                .build();
    }

    private static UiValue writeInstance(ComponentInstance i) {
        Map<String, UiValue> slots = new LinkedHashMap<>();
        i.slots().forEach((name, nodes) -> slots.put(name,
                new UiValue.Arr(nodes.stream().map(DocumentCodec::writeNode).toList())));
        return new ObjWriter()
                .put("component", i.component())
                .putMap("params", i.params())
                .putList("overrides", i.overrides(), o -> new ObjWriter()
                        .put("target", o.target())
                        .putMap("props", o.props())
                        .putMap("style", o.style())
                        .putStrings("addClasses", o.addClasses())
                        .putStrings("removeClasses", o.removeClasses())
                        .putUnknown(o.unknown())
                        .build())
                // Not putMap: slot content is record-encoded nodes in schema order, which a
                // deep free-form sort would scramble. Slot names are already code-point sorted.
                .put("slots", slots.isEmpty() ? null : new UiValue.Obj(slots))
                .putUnknown(i.unknown())
                .build();
    }

    private static UiValue writeComponent(ComponentDef c) {
        return new ObjWriter()
                .putList("params", c.params(), DocumentCodec::writeParam)
                .putList("events", c.events(), e -> new ObjWriter()
                        .put("name", e.name())
                        .putList("args", e.args(), DocumentCodec::writeParam)
                        .putUnknown(e.unknown())
                        .build())
                .putList("slots", c.slots(), s -> new ObjWriter()
                        .put("name", s.name())
                        .put("host", s.host())
                        .putUnknown(s.unknown())
                        .build())
                .putUnknown(c.unknown())
                .build();
    }

    static UiValue writeParam(Param p) {
        return new ObjWriter()
                .put("name", p.name())
                .putEnum("type", p.type())
                .put("default", p.defaultValue())
                .putUnknown(p.unknown())
                .build();
    }
}
