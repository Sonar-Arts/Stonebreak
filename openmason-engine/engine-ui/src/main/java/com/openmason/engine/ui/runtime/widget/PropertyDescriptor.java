package com.openmason.engine.ui.runtime.widget;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One inspectable widget property.
 *
 * @param defaultValue value when no layer sets it ({@link UiValue#NULL} for "none")
 * @param description  inspector tooltip
 * @param keywords     for a {@link ValueType#STRING} property that is an enumeration
 *                     ({@code focusScope}, {@code role}): the accepted values; empty = any string
 */
public record PropertyDescriptor(String name, ValueType type, UiValue defaultValue, String description,
                                 Set<String> keywords) {

    public PropertyDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        defaultValue = defaultValue == null ? UiValue.NULL : defaultValue;
        keywords = keywords == null ? Set.of() : Set.copyOf(keywords);
        description = description == null ? "" : description;
        if (!type.accepts(defaultValue) || !keywordFits(keywords, defaultValue)) {
            throw new IllegalArgumentException("default of " + name + " is not a valid " + type.wire());
        }
    }

    public PropertyDescriptor(String name, ValueType type, UiValue defaultValue, String description) {
        this(name, type, defaultValue, description, Set.of());
    }

    /** @return null when {@code value} fits, else the problem */
    public String problem(UiValue value) {
        if (!type.accepts(value)) {
            return name + " expects " + type.wire() + ", got " + value.typeName();
        }
        return keywordFits(keywords, value) ? null : name + " expects one of " + new TreeSet<>(keywords);
    }

    private static boolean keywordFits(Set<String> keywords, UiValue value) {
        return keywords.isEmpty() || !(value instanceof UiValue.Str s) || keywords.contains(s.value());
    }
}
