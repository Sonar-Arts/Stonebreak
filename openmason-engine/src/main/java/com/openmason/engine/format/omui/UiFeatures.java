package com.openmason.engine.format.omui;

import java.util.Map;

/**
 * Optional format features (§1 of the wire contract). A document that uses one lists it in
 * the manifest's {@code requires}, so a reader that predates it refuses the document cleanly
 * ({@code UNSUPPORTED_REQUIRED_FEATURE}) instead of misreading it; a writer refuses a document
 * that uses a feature without declaring it ({@code UNDECLARED_FEATURE}).
 */
public final class UiFeatures {

    /** The {@code ScrollView} widget and {@code overflow: scroll} (#287). */
    public static final String SCROLL = "ui-scroll";

    private UiFeatures() {
    }

    /** @return the feature a style declaration needs, or null */
    public static String forStyle(String property, UiValue value) {
        if ("overflow".equals(property) && value instanceof UiValue.Str s && "scroll".equals(s.value())) {
            return SCROLL;
        }
        return null;
    }

    /** @return the first feature {@code style} needs, or null */
    public static String forStyle(Map<String, UiValue> style) {
        for (Map.Entry<String, UiValue> e : style.entrySet()) {
            String f = forStyle(e.getKey(), e.getValue());
            if (f != null) {
                return f;
            }
        }
        return null;
    }
}
