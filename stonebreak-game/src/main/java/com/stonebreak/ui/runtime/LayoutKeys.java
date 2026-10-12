package com.stonebreak.ui.runtime;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
import static org.lwjgl.glfw.GLFW.glfwGetKeyName;

/**
 * Maps GLFW key tokens to the active keyboard layout before they reach a UI router (#288 review).
 *
 * <p>GLFW key tokens name physical US-layout keys: on QWERTZ the key labelled Z sends
 * {@code GLFW_KEY_Y}, on AZERTY the key labelled A sends {@code GLFW_KEY_Q}. Text shortcuts
 * (Ctrl+Z, Ctrl+A, ...) must follow the label, so hosts translate letter keys through
 * {@code glfwGetKeyName} first. Shared by the game host and the Open Mason preview.
 */
public final class LayoutKeys {

    private LayoutKeys() {
    }

    /**
     * The layout-mapped key for {@code glfwKey}: a printable key whose layout name is a single
     * ASCII letter becomes {@code GLFW_KEY_A..Z}; anything else is returned unchanged.
     * Must be called on the GLFW main thread.
     */
    public static int translate(int glfwKey, int scancode) {
        String name;
        try {
            name = glfwGetKeyName(glfwKey, scancode);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return glfwKey; // GLFW not initialised (headless tests)
        }
        return fromName(glfwKey, name);
    }

    /** Pure mapping of {@link #translate}, given the key's layout name. */
    static int fromName(int glfwKey, String name) {
        if (name == null || name.length() != 1) {
            return glfwKey;
        }
        char c = Character.toUpperCase(name.charAt(0));
        if (c >= 'A' && c <= 'Z') {
            int mapped = GLFW_KEY_A + (c - 'A');
            return mapped <= GLFW_KEY_Z ? mapped : glfwKey;
        }
        return glfwKey;
    }
}
