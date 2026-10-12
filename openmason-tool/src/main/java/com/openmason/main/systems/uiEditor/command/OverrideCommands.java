package com.openmason.main.systems.uiEditor.command;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiNode.ComponentInstance;
import com.openmason.engine.format.omui.UiNode.InstanceOverride;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.main.systems.uiEditor.document.Nodes;
import com.openmason.main.systems.uiEditor.document.UiTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Edits of a component instance: its parameters and its explicit per-node overrides. An
 * element inside an instance is addressed by its element key ({@code quit/button}): the first
 * segment is the instance node in this document, the rest is the override target inside the
 * component ({@code inner/leaf} reaches through nested instances). Removing an override is
 * "reset to source".
 */
public final class OverrideCommands {

    private OverrideCommands() {
    }

    /** The instance node id and override target of an internal element key, or null for document nodes. */
    public record Target(String instanceId, String target) {
        public static Target of(String key) {
            int slash = key.indexOf('/');
            return slash < 0 ? null : new Target(key.substring(0, slash), key.substring(slash + 1));
        }
    }

    /** Sets ({@code null} clears) an instance parameter. */
    public static UiCommand setParam(String instanceId, String param, UiValue value) {
        return UiCommand.of((value == null ? "Reset " : "Set ") + param, "param:" + instanceId + ":" + param, ctx -> {
            UiNode n = requireInstance(ctx, instanceId);
            ComponentInstance inst = Nodes.withParams(n.instance(), Nodes.put(n.instance().params(), param, value));
            ctx.setRoot(UiTree.replace(ctx.root(), instanceId, node -> Nodes.withInstance(node, inst)));
        });
    }

    /** Sets or clears ({@code null}) one overridden property of an internal element. */
    public static UiCommand setProp(String key, String prop, UiValue value) {
        return edit(key, (value == null ? "Reset " : "Override ") + prop, "oprop:" + key + ":" + prop,
            o -> new InstanceOverride(o.target(), Nodes.put(o.props(), prop, value), o.style(), o.addClasses(),
                o.removeClasses(), o.unknown()));
    }

    /** Sets or clears ({@code UiValue.NULL} entries) overridden style declarations of an internal element. */
    public static UiCommand setStyle(String key, Map<String, UiValue> declarations, String label) {
        return edit(key, label, "ostyle:" + key + ":" + String.join(",", declarations.keySet()), o -> {
            Map<String, UiValue> style = o.style();
            for (Map.Entry<String, UiValue> e : declarations.entrySet()) {
                style = Nodes.put(style, e.getKey(), e.getValue() == UiValue.NULL ? null : e.getValue());
            }
            return new InstanceOverride(o.target(), o.props(), style, o.addClasses(), o.removeClasses(), o.unknown());
        });
    }

    /** Replaces the class edits of an internal element's override. */
    public static UiCommand setClasses(String key, List<String> add, List<String> remove) {
        return edit(key, "Override classes", null,
            o -> new InstanceOverride(o.target(), o.props(), o.style(), add, remove, o.unknown()));
    }

    /** Removes the whole override of an internal element (reset to the component's source). */
    public static UiCommand reset(String key) {
        return UiCommand.of("Reset " + key + " to source", ctx -> {
            Target t = requireTarget(key);
            UiNode n = requireInstance(ctx, t.instanceId());
            ComponentInstance inst = Nodes.withOverride(n.instance(), t.target(), null);
            ctx.setRoot(UiTree.replace(ctx.root(), t.instanceId(), node -> Nodes.withInstance(node, inst)));
        });
    }

    private static UiCommand edit(String key, String label, String mergeKey,
                                  java.util.function.UnaryOperator<InstanceOverride> fn) {
        return UiCommand.of(label, mergeKey, ctx -> {
            Target t = requireTarget(key);
            UiNode n = requireInstance(ctx, t.instanceId());
            InstanceOverride current = Nodes.override(n.instance(), t.target());
            if (current == null) {
                current = new InstanceOverride(t.target(), Map.of(), Map.of(), List.of(), List.of(), Map.of());
            }
            ComponentInstance inst = Nodes.withOverride(n.instance(), t.target(), fn.apply(current));
            ctx.setRoot(UiTree.replace(ctx.root(), t.instanceId(), node -> Nodes.withInstance(node, inst)));
        });
    }

    private static Target requireTarget(String key) throws UiCommandException {
        Target t = Target.of(key);
        if (t == null) {
            throw new UiCommandException(key + " is not inside a component instance");
        }
        return t;
    }

    private static UiNode requireInstance(UiEditContext ctx, String id) throws UiCommandException {
        UiNode n = ctx.require(id);
        if (n.instance() == null) {
            throw new UiCommandException(id + " is not a component instance");
        }
        return n;
    }

    /** The overrides of {@code instance} whose target lies inside {@code prefix} (for listings). */
    public static List<InstanceOverride> under(ComponentInstance instance, String prefix) {
        List<InstanceOverride> out = new ArrayList<>();
        for (InstanceOverride o : instance.overrides()) {
            if (prefix == null || o.target().equals(prefix) || o.target().startsWith(prefix + "/")) {
                out.add(o);
            }
        }
        return out;
    }
}
