package com.openmason.main.systems.menus.animationEditor.data;

import java.util.Locale;

/**
 * Interpolation curve between two adjacent keyframes (editor-side mirror of the
 * engine's {@link com.openmason.engine.format.oma.Easing}).
 *
 * <p>The constant names match the engine enum one-for-one so an easing value
 * round-trips through the .oma file and samples identically in the tool preview
 * and the game runtime. The actual curve math is delegated to the engine so the
 * two never drift apart.
 */
public enum Easing {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT;

    /**
     * Remap a normalized segment parameter {@code t} in {@code [0,1]} through
     * this curve, using the canonical engine easing math.
     */
    public float apply(float t) {
        return com.openmason.engine.format.oma.Easing.valueOf(name()).apply(t);
    }

    public static Easing fromString(String s) {
        if (s == null || s.isBlank()) return LINEAR;
        try {
            return Easing.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return LINEAR;
        }
    }
}
