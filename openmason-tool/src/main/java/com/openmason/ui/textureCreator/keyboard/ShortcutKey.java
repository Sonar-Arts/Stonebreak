package com.openmason.ui.textureCreator.keyboard;

import imgui.ImGui;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;

/**
 * Represents a keyboard shortcut combination.
 * Follows SOLID principles with immutable value object pattern.
 *
 * @author Open Mason Team
 */
public class ShortcutKey {
    private final int keyCode;
    private final boolean ctrl;
    private final boolean shift;
    private final boolean alt;

    /**
     * Create a shortcut key with modifier keys.
     *
     * @param keyCode GLFW key code
     * @param ctrl true if Ctrl must be pressed
     * @param shift true if Shift must be pressed
     * @param alt true if Alt must be pressed
     */
    public ShortcutKey(int keyCode, boolean ctrl, boolean shift, boolean alt) {
        this.keyCode = keyCode;
        this.ctrl = ctrl;
        this.shift = shift;
        this.alt = alt;
    }

    /**
     * Create a shortcut with Ctrl modifier only.
     */
    public static ShortcutKey ctrl(int keyCode) {
        return new ShortcutKey(keyCode, true, false, false);
    }

    /**
     * Create a shortcut with Ctrl+Shift modifiers.
     */
    public static ShortcutKey ctrlShift(int keyCode) {
        return new ShortcutKey(keyCode, true, true, false);
    }

    /**
     * Create a shortcut with no modifiers (just the key).
     */
    public static ShortcutKey simple(int keyCode) {
        return new ShortcutKey(keyCode, false, false, false);
    }

    /**
     * Check if this shortcut is currently pressed.
     * Uses ImGui input state for modifier keys and GLFW key state for the key.
     *
     * @return true if the exact combination is pressed
     */
    public boolean isPressed() {
        // Check if key is pressed
        if (!ImGui.isKeyPressed(keyCode)) {
            return false;
        }

        // Check modifiers match exactly
        boolean ctrlPressed = ImGui.getIO().getKeyCtrl();
        boolean shiftPressed = ImGui.getIO().getKeyShift();
        boolean altPressed = ImGui.getIO().getKeyAlt();

        return (ctrl == ctrlPressed) &&
               (shift == shiftPressed) &&
               (alt == altPressed);
    }

    /**
     * Get human-readable representation (e.g., "Ctrl+Shift+S").
     */
    public String getDisplayName() {
        StringBuilder sb = new StringBuilder();

        if (ctrl) sb.append("Ctrl+");
        if (shift) sb.append("Shift+");
        if (alt) sb.append("Alt+");

        sb.append(getKeyName(keyCode));

        return sb.toString();
    }

    /**
     * Get human-readable key name from GLFW key code.
     */
    private String getKeyName(int keyCode) {
        // Common keys
        if (keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z) {
            return String.valueOf((char) ('A' + (keyCode - GLFW.GLFW_KEY_A)));
        }
        if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
            return String.valueOf((char) ('0' + (keyCode - GLFW.GLFW_KEY_0)));
        }

        // Special keys
        switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER: return "Enter";
            case GLFW.GLFW_KEY_ESCAPE: return "Esc";
            case GLFW.GLFW_KEY_DELETE: return "Del";
            case GLFW.GLFW_KEY_BACKSPACE: return "Backspace";
            case GLFW.GLFW_KEY_TAB: return "Tab";
            case GLFW.GLFW_KEY_SPACE: return "Space";
            case GLFW.GLFW_KEY_COMMA: return ",";
            case GLFW.GLFW_KEY_PERIOD: return ".";
            case GLFW.GLFW_KEY_SLASH: return "/";
            case GLFW.GLFW_KEY_EQUAL: return "=";
            case GLFW.GLFW_KEY_MINUS: return "-";
            case GLFW.GLFW_KEY_KP_ADD: return "Numpad +";
            case GLFW.GLFW_KEY_KP_SUBTRACT: return "Numpad -";
            case GLFW.GLFW_KEY_KP_0: return "Numpad 0";
            case GLFW.GLFW_KEY_KP_ENTER: return "Numpad Enter";
            default: return "Key " + keyCode;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShortcutKey that = (ShortcutKey) o;
        return keyCode == that.keyCode &&
               ctrl == that.ctrl &&
               shift == that.shift &&
               alt == that.alt;
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyCode, ctrl, shift, alt);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
