package com.openmason.engine.format.omui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One element of a document tree (the definition; runtime state never lives here).
 *
 * @param id          stable document-local id; survives rename, reparent and save. Every
 *                    reference (bindings, overrides, clips, editor metadata) uses it
 * @param name        the {@code #name} selector and {@code ui.q("#name")} handle; optional,
 *                    may be renamed freely
 * @param type        registered widget type ({@code Button}) or {@code Instance}
 * @param typeVersion widget descriptor version the properties were authored against
 * @param classes     style classes, canonically sorted and unique
 * @param props       widget properties (descriptor-validated by #287, not here)
 * @param style       inline style declarations, the highest-precedence authored layer
 * @param dataSource  data path this node supplies to its subtree (inherited, Unity-style);
 *                    a leading {@code .} makes it relative to the inherited source
 * @param bindings    declarative bindings, one per target, sorted by target
 * @param instance    component instance data when {@code type} is {@code Instance}
 * @param children    child nodes in paint/layout order
 * @param unknown     preserved fields this reader does not know
 */
public record UiNode(String id, String name, String type, int typeVersion, List<String> classes,
                     Map<String, UiValue> props, Map<String, UiValue> style, String dataSource,
                     List<UiBinding> bindings, ComponentInstance instance, List<UiNode> children,
                     Map<String, UiValue> unknown) {

    /** Widget type of component instance nodes. */
    public static final String INSTANCE_TYPE = "Instance";

    public UiNode {
        Objects.requireNonNull(id, "id");
        name = name == null || name.isEmpty() ? null : name;
        Objects.requireNonNull(type, "type");
        classes = Canon.sortedUnique(classes);
        props = Canon.values(props);
        style = Canon.values(style);
        dataSource = dataSource == null || dataSource.isEmpty() ? null : dataSource;
        bindings = Canon.sortedBy(bindings, UiBinding::target);
        children = Canon.list(children);
        unknown = Canon.unknown(unknown);
    }

    /** Minimal node for builders and tests. */
    public static UiNode of(String id, String type, List<UiNode> children) {
        return new UiNode(id, null, type, 1, List.of(), Map.of(), Map.of(), null, List.of(), null, children, Map.of());
    }

    public UiNode withChildren(List<UiNode> newChildren) {
        return new UiNode(id, name, type, typeVersion, classes, props, style, dataSource, bindings, instance,
                newChildren, unknown);
    }

    /** Depth-first, pre-order: this node, its children, then instance slot content. */
    public List<UiNode> flatten() {
        List<UiNode> out = new ArrayList<>();
        collect(this, out);
        return out;
    }

    private static void collect(UiNode node, List<UiNode> out) {
        out.add(node);
        node.children.forEach(c -> collect(c, out));
        if (node.instance != null) {
            node.instance.slots().values().forEach(list -> list.forEach(c -> collect(c, out)));
        }
    }

    public enum BindingMode implements WireEnum {
        TO_TARGET("to-target"), TWO_WAY("two-way"), TO_SOURCE("to-source"), ONCE("once");

        private final String wire;

        BindingMode(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /**
     * A declarative binding.
     *
     * @param target    {@code prop:<name>}, {@code style:<property>} or {@code class:<name>}
     *                  (a boolean source toggles the class)
     * @param path      data path, absolute ({@code session.online}) or relative to the
     *                  inherited data source ({@code .count})
     * @param mode      direction; {@link BindingMode#TO_TARGET} by default
     * @param converter optional pure code-behind function applied to the value
     */
    public record UiBinding(String target, String path, BindingMode mode, String converter,
                            Map<String, UiValue> unknown) {
        public UiBinding {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(path, "path");
            mode = mode == null ? BindingMode.TO_TARGET : mode;
            converter = converter == null || converter.isEmpty() ? null : converter;
            unknown = Canon.unknown(unknown);
        }

        public UiBinding(String target, String path) {
            this(target, path, BindingMode.TO_TARGET, null, Map.of());
        }
    }

    /**
     * Instance data of a component node.
     *
     * @param component dependency id of the component document
     * @param params    exposed parameter values, by parameter name
     * @param overrides explicit per-node overrides inside the component, sorted by target
     * @param slots     content for the component's named slots
     */
    public record ComponentInstance(String component, Map<String, UiValue> params, List<InstanceOverride> overrides,
                                    Map<String, List<UiNode>> slots, Map<String, UiValue> unknown) {
        public ComponentInstance {
            Objects.requireNonNull(component, "component");
            params = Canon.values(params);
            overrides = Canon.sortedBy(overrides, InstanceOverride::target);
            Map<String, List<UiNode>> copied = new LinkedHashMap<>();
            if (slots != null) {
                slots.forEach((k, v) -> copied.put(k, List.copyOf(v)));
            }
            slots = Canon.sortedMap(copied);
            unknown = Canon.unknown(unknown);
        }
    }

    /**
     * An explicit override of one node inside an instantiated component. Removing the
     * override is "reset to source".
     *
     * @param target        node id inside the component; {@code inner/leaf} reaches through a
     *                      nested instance by instance-node id
     * @param addClasses    classes added on top of the source node's classes
     * @param removeClasses source classes removed
     */
    public record InstanceOverride(String target, Map<String, UiValue> props, Map<String, UiValue> style,
                                   List<String> addClasses, List<String> removeClasses,
                                   Map<String, UiValue> unknown) {
        public InstanceOverride {
            Objects.requireNonNull(target, "target");
            props = Canon.values(props);
            style = Canon.values(style);
            addClasses = Canon.sortedUnique(addClasses);
            removeClasses = Canon.sortedUnique(removeClasses);
            unknown = Canon.unknown(unknown);
        }
    }
}
