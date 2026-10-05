package com.openmason.engine.ui.runtime.widget;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A registered widget type: its descriptor version, typed properties (inspector order) and
 * structural traits. Documents record the version they were authored against in
 * {@code typeVersion}; a newer document version than the registered descriptor is refused.
 *
 * @param acceptsChildren false for leaves ({@code Label}); children on a leaf are reported
 * @param measured        Yoga asks the host for this element's intrinsic size (text, images)
 */
public record WidgetDescriptor(String type, int version, Map<String, PropertyDescriptor> properties,
                               boolean acceptsChildren, boolean measured, String description) {

    public WidgetDescriptor {
        Objects.requireNonNull(type, "type");
        if (version < 1) {
            throw new IllegalArgumentException("descriptor version must be >= 1");
        }
        properties = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        description = description == null ? "" : description;
    }

    public static WidgetDescriptor of(String type, int version, boolean acceptsChildren, boolean measured,
                                      String description, List<PropertyDescriptor> properties) {
        Map<String, PropertyDescriptor> map = new LinkedHashMap<>();
        for (PropertyDescriptor p : properties) {
            if (map.put(p.name(), p) != null) {
                throw new IllegalArgumentException("duplicate property " + p.name() + " on " + type);
            }
        }
        return new WidgetDescriptor(type, version, map, acceptsChildren, measured, description);
    }

    public PropertyDescriptor property(String name) {
        return properties.get(name);
    }
}
