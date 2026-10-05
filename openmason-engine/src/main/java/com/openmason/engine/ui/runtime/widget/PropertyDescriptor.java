package com.openmason.engine.ui.runtime.widget;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;

import java.util.Objects;

/**
 * One inspectable widget property.
 *
 * @param defaultValue value when no layer sets it ({@link UiValue#NULL} for "none")
 * @param description  inspector tooltip
 */
public record PropertyDescriptor(String name, ValueType type, UiValue defaultValue, String description) {

    public PropertyDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        defaultValue = defaultValue == null ? UiValue.NULL : defaultValue;
        if (!type.accepts(defaultValue)) {
            throw new IllegalArgumentException("default of " + name + " is not a " + type.wire());
        }
        description = description == null ? "" : description;
    }

    /** @return null when {@code value} fits, else the problem */
    public String problem(UiValue value) {
        return type.accepts(value) ? null : name + " expects " + type.wire() + ", got " + value.typeName();
    }
}
