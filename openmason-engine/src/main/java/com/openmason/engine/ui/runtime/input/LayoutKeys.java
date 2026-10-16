package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.masonry.MKeys;

/**
 * Maps GLFW key tokens to the active keyboard layout before they reach a UI router (#288 review;
 * moved from the game, #381).
 *
 * <p>GLFW key tokens name physical US-layout keys: on QWERTZ the key labelled Z sends
 * {@code GLFW_KEY_Y}, on AZERTY the key labelled A sends {@code GLFW_KEY_Q}. Text shortcuts
 * (Ctrl+Z, Ctrl+A, ...) must follow the label, so hosts translate letter keys through
 * {@code glfwGetKeyName} first. Shared by the game host and the Open Mason preview; the host
 * passes the name lookup ({@code GLFW::glfwGetKeyName}) so the engine needs no GLFW binding.
 */
public final class LayoutKeys {

    /** The layout name of a key ({@code glfwGetKeyName}); null for non-printable keys. */
    @FunctionalInterface
    public interface KeyNames {
        String nameOf(int glfwKey, int scancode);
    }

    private LayoutKeys() {
    }

    /**
     * The layout-mapped key for {@code glfwKey}: a printable key whose layout name is a single
     * ASCII letter becomes {@code GLFW_KEY_A..Z}; anything else is returned unchanged.
     * Must be called on the GLFW main thread.
     */
    public static int translate(int glfwKey, int scancode, KeyNames names) {
        String name;
        try {
            name = names.nameOf(glfwKey, scancode);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return glfwKey; // GLFW not initialised (headless tests)
        }
        return fromName(glfwKey, name);
    }

    /** Pure mapping of {@link #translate}, given the key's layout name. */
    public static int fromName(int glfwKey, String name) {
        if (name == null || name.length() != 1) {
            return glfwKey;
        }
        char c = Character.toUpperCase(name.charAt(0));
        if (c >= 'A' && c <= 'Z') {
            int mapped = MKeys.KEY_A + (c - 'A');
            return mapped <= MKeys.KEY_Z ? mapped : glfwKey;
        }
        return glfwKey;
    }
}
