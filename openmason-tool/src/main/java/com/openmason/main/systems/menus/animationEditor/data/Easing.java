package com.openmason.main.systems.menus.animationEditor.data;

/**
 * Interpolation curve between two adjacent keyframes.
 *
 * <p>v1 ships with {@link #LINEAR} only. The enum exists so the .oma format
 * can carry an easing string forward-compatibly when more curves (cubic,
 * ease-in/out) are added later without bumping the file version.
 */
public enum Easing {
    LINEAR;

    public static Easing fromString(String s) {
        if (s == null || s.isBlank()) return LINEAR;
        try {
            return Easing.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return LINEAR;
        }
    }
}
