package com.openmason.engine.ui.runtime.binding;

import java.util.Map;

/** The converter functions a document's code-behind provides, by local id (#289). */
@FunctionalInterface
public interface UiConverters {

    UiConverters NONE = name -> null;

    /** @return the converter, or {@code null} when the code-behind defines none by that name */
    UiConverter find(String name);

    static UiConverters of(Map<String, UiConverter> converters) {
        Map<String, UiConverter> copy = Map.copyOf(converters);
        return copy::get;
    }

    /** This set, falling back to {@code other} for names it lacks. */
    default UiConverters or(UiConverters other) {
        return name -> {
            UiConverter c = find(name);
            return c != null ? c : other.find(name);
        };
    }
}
