package com.openmason.main.systems.uiEditor.document;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiNode.ComponentInstance;
import com.openmason.engine.format.omui.UiNode.UiBinding;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copy-with helpers for the {@link UiNode} record. Each keeps every other field, including
 * preserved unknown fields, so an edit never drops data a newer writer put there.
 */
public final class Nodes {

    private Nodes() {
    }

    public static UiNode withId(UiNode n, String id) {
        return new UiNode(id, n.name(), n.type(), n.typeVersion(), n.classes(), n.props(), n.style(), n.dataSource(),
            n.bindings(), n.instance(), n.children(), n.unknown());
    }

    public static UiNode withName(UiNode n, String name) {
        return new UiNode(n.id(), name, n.type(), n.typeVersion(), n.classes(), n.props(), n.style(), n.dataSource(),
            n.bindings(), n.instance(), n.children(), n.unknown());
    }

    public static UiNode withClasses(UiNode n, List<String> classes) {
        return new UiNode(n.id(), n.name(), n.type(), n.typeVersion(), classes, n.props(), n.style(), n.dataSource(),
            n.bindings(), n.instance(), n.children(), n.unknown());
    }

    public static UiNode withProps(UiNode n, Map<String, UiValue> props) {
        return new UiNode(n.id(), n.name(), n.type(), n.typeVersion(), n.classes(), props, n.style(), n.dataSource(),
            n.bindings(), n.instance(), n.children(), n.unknown());
    }

    public static UiNode withStyle(UiNode n, Map<String, UiValue> style) {
        return new UiNode(n.id(), n.name(), n.type(), n.typeVersion(), n.classes(), n.props(), style, n.dataSource(),
            n.bindings(), n.instance(), n.children(), n.unknown());
    }

    public static UiNode withDataSource(UiNode n, String dataSource) {
        return new UiNode(n.id(), n.name(), n.type(), n.typeVersion(), n.classes(), n.props(), n.style(), dataSource,
            n.bindings(), n.instance(), n.children(), n.unknown());
    }

    public static UiNode withBindings(UiNode n, List<UiBinding> bindings) {
        return new UiNode(n.id(), n.name(), n.type(), n.typeVersion(), n.classes(), n.props(), n.style(), n.dataSource(),
            bindings, n.instance(), n.children(), n.unknown());
    }

    public static UiNode withInstance(UiNode n, ComponentInstance instance) {
        return new UiNode(n.id(), n.name(), n.type(), n.typeVersion(), n.classes(), n.props(), n.style(), n.dataSource(),
            n.bindings(), instance, n.children(), n.unknown());
    }

    /** {@code map} with {@code key} set to {@code value}, or removed when {@code value} is null. */
    public static Map<String, UiValue> put(Map<String, UiValue> map, String key, UiValue value) {
        Map<String, UiValue> copy = new LinkedHashMap<>(map);
        if (value == null) {
            copy.remove(key);
        } else {
            copy.put(key, value);
        }
        return copy;
    }

    /** {@code n} with the binding for {@code target} replaced by {@code binding} (null removes it). */
    public static UiNode withBinding(UiNode n, String target, UiBinding binding) {
        List<UiBinding> list = new ArrayList<>();
        for (UiBinding b : n.bindings()) {
            if (!b.target().equals(target)) {
                list.add(b);
            }
        }
        if (binding != null) {
            list.add(binding);
        }
        return withBindings(n, list);
    }

    public static UiBinding binding(UiNode n, String target) {
        for (UiBinding b : n.bindings()) {
            if (b.target().equals(target)) {
                return b;
            }
        }
        return null;
    }

    /** {@code inst} with its params replaced. */
    public static ComponentInstance withParams(ComponentInstance inst, Map<String, UiValue> params) {
        return new ComponentInstance(inst.component(), params, inst.overrides(), inst.slots(), inst.unknown());
    }

    /** {@code inst} with the override of {@code target} replaced (null removes it: reset to source). */
    public static ComponentInstance withOverride(ComponentInstance inst, String target, UiNode.InstanceOverride o) {
        List<UiNode.InstanceOverride> list = new ArrayList<>();
        for (UiNode.InstanceOverride existing : inst.overrides()) {
            if (!existing.target().equals(target)) {
                list.add(existing);
            }
        }
        if (o != null && !isEmpty(o)) {
            list.add(o);
        }
        return new ComponentInstance(inst.component(), inst.params(), list, inst.slots(), inst.unknown());
    }

    public static UiNode.InstanceOverride override(ComponentInstance inst, String target) {
        for (UiNode.InstanceOverride o : inst.overrides()) {
            if (o.target().equals(target)) {
                return o;
            }
        }
        return null;
    }

    public static boolean isEmpty(UiNode.InstanceOverride o) {
        return o.props().isEmpty() && o.style().isEmpty() && o.addClasses().isEmpty() && o.removeClasses().isEmpty()
            && o.unknown().isEmpty();
    }

    /** A fresh node of {@code type} with no props, style or children. */
    public static UiNode create(String id, String type) {
        return new UiNode(id, null, type, 1, List.of(), Map.of(), Map.of(), null, List.of(), null, List.of(), Map.of());
    }
}
