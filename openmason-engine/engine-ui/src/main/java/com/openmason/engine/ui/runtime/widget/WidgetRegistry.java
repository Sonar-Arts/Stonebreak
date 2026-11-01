package com.openmason.engine.ui.runtime.widget;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.UiVocabulary;
import com.openmason.engine.format.omui.UiWidgets;
import com.openmason.engine.ui.theme.UiThemes;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Widget types a runtime can instantiate: the schema-1.0 built-ins plus namespaced game widgets
 * ({@code game:ItemSlot}) a game or tool registers. One registry is shared by the editor preview
 * and the game so validation is identical in both. A legacy bare alias of a game widget
 * ({@link com.openmason.engine.format.omui.UiVocabulary}, #384) resolves to the namespaced descriptor.
 */
public final class WidgetRegistry {

    private final Map<String, WidgetDescriptor> descriptors = new LinkedHashMap<>();

    /** A registry holding the built-in widgets. */
    public static WidgetRegistry withBuiltIns() {
        WidgetRegistry r = new WidgetRegistry();
        BuiltInWidgets.all().forEach(r::register);
        return r;
    }

    /** The built-ins plus every installed theme's game widgets ({@link UiThemes#installed()}, #384). */
    public static WidgetRegistry withInstalled() {
        WidgetRegistry r = withBuiltIns();
        UiThemes.installed().widgets().forEach(w -> r.register(w.descriptor()));
        return r;
    }

    private static volatile WidgetRegistry installedShared;
    private static volatile Object installedFor;

    /**
     * A shared {@link #withInstalled()} registry, rebuilt when the installed themes change (a plugin
     * registered one). For lookups only: never {@link #register} into it.
     */
    public static WidgetRegistry installed() {
        Object theme = UiThemes.installed();
        WidgetRegistry r = installedShared;
        if (r == null || installedFor != theme) {
            r = withInstalled();
            installedShared = r;
            installedFor = theme;
        }
        return r;
    }

    /** Registers or replaces a descriptor. Built-in versions must match the format's table. */
    public WidgetRegistry register(WidgetDescriptor descriptor) {
        String type = descriptor.type();
        if (!UiWidgets.isNamespaced(type)) {
            int formatVersion = UiWidgets.supportedVersion(type);
            if (formatVersion == 0 || descriptor.version() != formatVersion) {
                throw new IllegalArgumentException("built-in " + type + " v" + descriptor.version()
                    + " does not match the format's widget table (v" + formatVersion + ")");
            }
        }
        descriptors.put(type, descriptor);
        return this;
    }

    /** The descriptor of {@code type}, or of the namespaced type it is a legacy alias of; null when unknown. */
    public WidgetDescriptor get(String type) {
        WidgetDescriptor d = descriptors.get(type);
        return d != null || type == null ? d : descriptors.get(UiVocabulary.canonicalType(type));
    }

    public Collection<WidgetDescriptor> all() {
        return Collections.unmodifiableCollection(descriptors.values());
    }

    /**
     * Checks a node against its descriptor and returns the authored properties that are safe
     * to use: unknown properties are reported and dropped, mistyped ones likewise, never coerced.
     *
     * @return the node's valid authored props, or {@code null} when the widget type is unusable
     */
    public Map<String, UiValue> validate(UiNode node, String elementKey, Consumer<UiRuntimeDiagnostic> diagnostics) {
        WidgetDescriptor d = get(node.type());
        if (d == null) {
            diagnostics.accept(UiRuntimeDiagnostic.error(Code.UNKNOWN_WIDGET, elementKey,
                "no widget registered for type " + node.type()));
            return null;
        }
        if (node.typeVersion() > d.version()) {
            diagnostics.accept(UiRuntimeDiagnostic.error(Code.UNSUPPORTED_WIDGET_VERSION, elementKey,
                node.type() + " v" + node.typeVersion() + " is newer than this runtime (v" + d.version() + ")"));
            return null;
        }
        if (!d.acceptsChildren() && !node.children().isEmpty()) {
            diagnostics.accept(UiRuntimeDiagnostic.error(Code.CHILDREN_NOT_ALLOWED, elementKey,
                node.type() + " cannot have children; " + node.children().size() + " ignored"));
        }
        return validProps(d, node.props(), elementKey, diagnostics);
    }

    /** Filters {@code props} against {@code d}, reporting unknown and mistyped entries. */
    public static Map<String, UiValue> validProps(WidgetDescriptor d, Map<String, UiValue> props, String elementKey,
                                                  Consumer<UiRuntimeDiagnostic> diagnostics) {
        Map<String, UiValue> valid = new LinkedHashMap<>();
        props.forEach((name, value) -> {
            PropertyDescriptor p = d.property(name);
            if (p == null) {
                diagnostics.accept(UiRuntimeDiagnostic.warning(Code.UNKNOWN_PROPERTY, elementKey,
                    d.type() + " has no property " + name));
            } else if (p.problem(value) != null) {
                diagnostics.accept(UiRuntimeDiagnostic.error(Code.PROPERTY_TYPE, elementKey, p.problem(value)));
            } else {
                valid.put(name, value);
            }
        });
        return valid;
    }

    /** Convenience for descriptors: a property list in inspector order. */
    static List<PropertyDescriptor> props(PropertyDescriptor... p) {
        return List.of(p);
    }

    static PropertyDescriptor prop(String name, ValueType type, UiValue def, String description) {
        return new PropertyDescriptor(name, type, def, description);
    }
}
