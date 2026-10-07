package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.style.StyleTrace;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.command.OverrideCommands;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.document.Nodes;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.view.widgets.ValueFields;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the inspector edits: one or more document nodes (edits apply to all; disagreeing values
 * show as mixed) or one element inside a component instance (edits become overrides of that
 * instance, with reset-to-source). Effective values and their origins come from the primary
 * element's runtime state and cascade trace.
 */
final class InspectorTarget {

    final UiEditorDocument doc;
    final List<String> keys;
    final List<UiNode> nodes;
    final boolean internal;
    final UiElement element;
    final StyleTrace trace;
    final UiNode.InstanceOverride override;
    final OverrideCommands.Target overrideTarget;

    private InspectorTarget(UiEditorDocument doc, List<String> keys, List<UiNode> nodes, boolean internal,
                            UiElement element, StyleTrace trace, UiNode.InstanceOverride override,
                            OverrideCommands.Target overrideTarget) {
        this.doc = doc;
        this.keys = keys;
        this.nodes = nodes;
        this.internal = internal;
        this.element = element;
        this.trace = trace;
        this.override = override;
        this.overrideTarget = overrideTarget;
    }

    /** The target for the active selection, or null when nothing is selected. */
    static InspectorTarget of(UiEditorContext ctx) {
        UiEditorDocument doc = ctx.doc();
        if (doc == null || doc.selection().isEmpty()) {
            return null;
        }
        String primary = doc.primary();
        UiElement el = ctx.element(primary);
        StyleTrace trace = el == null ? StyleTrace.EMPTY : el.owner().styleTrace(el);
        if (primary.indexOf('/') >= 0) {
            OverrideCommands.Target t = OverrideCommands.Target.of(primary);
            UiNode inst = ctx.node(t.instanceId());
            UiNode.InstanceOverride o = inst == null || inst.instance() == null ? null
                : Nodes.override(inst.instance(), t.target());
            return new InspectorTarget(doc, List.of(primary), List.of(), true, el, trace, o, t);
        }
        List<String> keys = new ArrayList<>(NodeCommands.documentNodes(doc.selection()));
        List<UiNode> nodes = new ArrayList<>();
        for (String k : keys) {
            UiNode n = ctx.node(k);
            if (n != null) {
                nodes.add(n);
            }
        }
        if (nodes.isEmpty()) {
            return null;
        }
        return new InspectorTarget(doc, keys, nodes, false, el, trace, null, null);
    }

    boolean multi() {
        return nodes.size() > 1;
    }

    UiNode node() {
        return nodes.isEmpty() ? null : nodes.getFirst();
    }

    String type() {
        if (internal) {
            return element == null ? "?" : element.type();
        }
        String t = nodes.getFirst().type();
        for (UiNode n : nodes) {
            if (!n.type().equals(t)) {
                return null;
            }
        }
        return t;
    }

    WidgetDescriptor descriptor() {
        String t = type();
        return t == null ? null : NodeCommandsAccess.descriptor(t);
    }

    // ── style ───────────────────────────────────────────────────────────────

    /** The authored layer's value: inline style (nodes) or override style (internal); MIXED or null. */
    UiValue style(String prop) {
        if (internal) {
            return override == null ? null : override.style().get(prop);
        }
        return common(nodes.stream().map(n -> n.style().get(prop)).toList());
    }

    /** What the primary element shows (the cascade's result), or null. */
    UiValue effective(String prop) {
        return element == null ? null : element.computedStyle().get(prop);
    }

    /** Where the effective value comes from, for tooltips ("rule .panel in pause", "inline"). */
    String origin(String prop) {
        StyleTrace.Declaration d = trace.winners().get(prop);
        if (d == null) {
            UiValue v = effective(prop);
            return v == null ? "default" : "inherited from the parent";
        }
        return switch (d.layer()) {
            case RULE -> "rule " + d.rule().selector() + "  (" + d.rule().sheetId() + ")";
            case INLINE -> "inline style";
            case OVERRIDE -> "instance override";
            case BINDING -> "binding";
            case LOCAL -> "script";
            case ANIMATION -> "animation";
        };
    }

    UiCommand setStyle(String prop, UiValue value) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put(prop, value == null ? UiValue.NULL : value);
        return setStyle(m, (value == null ? "Reset " : "Set ") + prop);
    }

    UiCommand setStyle(Map<String, UiValue> declarations, String label) {
        if (internal) {
            return OverrideCommands.setStyle(keys.getFirst(), declarations, label);
        }
        return NodeCommands.setStyle(keys, declarations, label);
    }

    // ── props ───────────────────────────────────────────────────────────────

    UiValue prop(String name) {
        if (internal) {
            return override == null ? null : override.props().get(name);
        }
        return common(nodes.stream().map(n -> n.props().get(name)).toList());
    }

    /** The value the element currently uses (authored, overridden, bound or default). */
    UiValue effectiveProp(String name) {
        return element == null ? null : element.prop(name);
    }

    UiCommand setProp(String name, UiValue value) {
        if (internal) {
            return OverrideCommands.setProp(keys.getFirst(), name, value);
        }
        return NodeCommands.setProp(keys, name, value);
    }

    boolean bound(String target) {
        if (internal) {
            return element != null && element.isBound(target);
        }
        return nodes.stream().anyMatch(n -> Nodes.binding(n, target) != null);
    }

    private static UiValue common(List<UiValue> values) {
        UiValue first = values.getFirst();
        for (UiValue v : values) {
            if (!Objects.equals(v, first)) {
                return ValueFields.MIXED;
            }
        }
        return first;
    }

    /** Descriptor lookup through the editor's registry. */
    private static final class NodeCommandsAccess {
        private static final com.openmason.engine.ui.runtime.widget.WidgetRegistry REGISTRY =
            com.openmason.engine.ui.runtime.widget.WidgetRegistry.withBuiltIns();

        static WidgetDescriptor descriptor(String type) {
            return REGISTRY.get(type);
        }
    }
}
