package com.openmason.main.systems.menus.textureCreator.keyboard;

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
        return switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_ESCAPE -> "Esc";
            case GLFW.GLFW_KEY_DELETE -> "Del";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_COMMA -> ",";
            case GLFW.GLFW_KEY_PERIOD -> ".";
            case GLFW.GLFW_KEY_SLASH -> "/";
            case GLFW.GLFW_KEY_EQUAL -> "=";
            case GLFW.GLFW_KEY_MINUS -> "-";
            case GLFW.GLFW_KEY_KP_ADD -> "Numpad +";
            case GLFW.GLFW_KEY_KP_SUBTRACT -> "Numpad -";
            case GLFW.GLFW_KEY_KP_0 -> "Numpad 0";
            case GLFW.GLFW_KEY_KP_ENTER -> "Numpad Enter";
            default -> "Key " + keyCode;
        };
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

    /**
     * Serialize this shortcut key to a string format for persistence.
     * Format: "Ctrl+Shift+S", "Ctrl+S", "S", etc.
     *
     * @return serialized string representation
     */
    public String serialize() {
        return getDisplayName();
    }

    /**
     * Parse a shortcut key from its string representation.
     * Handles formats like "Ctrl+Shift+S", "Ctrl+S", "S", etc.
     *
     * @param keybindString the string to parse (e.g., "Ctrl+Shift+S")
     * @return the parsed ShortcutKey
     * @throws IllegalArgumentException if the string format is invalid or contains unknown keys
     */
    public static ShortcutKey parse(String keybindString) throws IllegalArgumentException {
        if (keybindString == null || keybindString.trim().isEmpty()) {
            throw new IllegalArgumentException("Keybind string cannot be null or empty");
        }

        String[] parts = keybindString.split("\\+");
        if (parts.length == 0) {
            throw new IllegalArgumentException("Invalid keybind format: " + keybindString);
        }

        boolean ctrl = false;
        boolean shift = false;
        boolean alt = false;
        String keyName = null;

        // Parse modifiers and key
        for (String part : parts) {
            String trimmed = part.trim();
            switch (trimmed) {
                case "Ctrl":
                    ctrl = true;
                    break;
                case "Shift":
                    shift = true;
                    break;
                case "Alt":
                    alt = true;
                    break;
                default:
                    // This should be the key itself (last part)
                    if (keyName != null) {
                        throw new IllegalArgumentException("Multiple keys found in keybind: " + keybindString);
                    }
                    keyName = trimmed;
                    break;
            }
        }

        if (keyName == null) {
            throw new IllegalArgumentException("No key found in keybind (only modifiers): " + keybindString);
        }

        // Parse the key name to GLFW key code
        int keyCode = parseKeyName(keyName);
        if (keyCode == -1) {
            throw new IllegalArgumentException("Unknown key name: " + keyName);
        }

        return new ShortcutKey(keyCode, ctrl, shift, alt);
    }

    /**
     * Parse a key name string to its GLFW key code.
     *
     * @param keyName the key name (e.g., "S", "Enter", "Esc")
     * @return the GLFW key code, or -1 if not recognized
     */
    private static int parseKeyName(String keyName) {
        // Single character keys (A-Z, 0-9)
        if (keyName.length() == 1) {
            char c = keyName.charAt(0);
            if (c >= 'A' && c <= 'Z') {
                return GLFW.GLFW_KEY_A + (c - 'A');
            }
            if (c >= '0' && c <= '9') {
                return GLFW.GLFW_KEY_0 + (c - '0');
            }
            if (c == ',') return GLFW.GLFW_KEY_COMMA;
            if (c == '.') return GLFW.GLFW_KEY_PERIOD;
            if (c == '/') return GLFW.GLFW_KEY_SLASH;
            if (c == '=') return GLFW.GLFW_KEY_EQUAL;
            if (c == '-') return GLFW.GLFW_KEY_MINUS;
        }

        // Special keys
        return switch (keyName) {
            case "Enter" -> GLFW.GLFW_KEY_ENTER;
            case "Esc" -> GLFW.GLFW_KEY_ESCAPE;
            case "Del" -> GLFW.GLFW_KEY_DELETE;
            case "Backspace" -> GLFW.GLFW_KEY_BACKSPACE;
            case "Tab" -> GLFW.GLFW_KEY_TAB;
            case "Space" -> GLFW.GLFW_KEY_SPACE;
            case "Numpad +" -> GLFW.GLFW_KEY_KP_ADD;
            case "Numpad -" -> GLFW.GLFW_KEY_KP_SUBTRACT;
            case "Numpad 0" -> GLFW.GLFW_KEY_KP_0;
            case "Numpad Enter" -> GLFW.GLFW_KEY_KP_ENTER;
            default -> -1;
        };
    }

    /**
     * Validate whether a shortcut key combination is valid and allowed.
     * Rejects system keys and reserved combinations.
     *
     * @param key the key to validate
     * @return true if the key is valid and allowed, false otherwise
     */
    public static boolean isValidKeybind(ShortcutKey key) {
        if (key == null) {
            return false;
        }

        int keyCode = key.keyCode;

        // Reject system/reserved keys
        if (keyCode == GLFW.GLFW_KEY_F11 ||          // Fullscreen toggle
            keyCode == GLFW.GLFW_KEY_PRINT_SCREEN ||  // Screenshot
            keyCode == GLFW.GLFW_KEY_LEFT_SUPER ||    // Windows/Command key
            keyCode == GLFW.GLFW_KEY_RIGHT_SUPER ||   // Windows/Command key
            keyCode == GLFW.GLFW_KEY_PAUSE ||         // Pause/Break
            keyCode == GLFW.GLFW_KEY_SCROLL_LOCK) {   // Scroll Lock
            return false;
        }

        // Reject modifier-only combinations (no actual key)
        if (keyCode == GLFW.GLFW_KEY_LEFT_CONTROL ||
            keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL ||
            keyCode == GLFW.GLFW_KEY_LEFT_SHIFT ||
            keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT ||
            keyCode == GLFW.GLFW_KEY_LEFT_ALT ||
            keyCode == GLFW.GLFW_KEY_RIGHT_ALT) {
            return false;
        }

        return true;
    }

    /**
     * Get the key code for this shortcut.
     * Package-private for registry access.
     */
    int getKeyCode() {
        return keyCode;
    }

    /**
     * Check if Ctrl modifier is required.
     * Package-private for registry access.
     */
    boolean requiresCtrl() {
        return ctrl;
    }

    /**
     * Check if Shift modifier is required.
     * Package-private for registry access.
     */
    boolean requiresShift() {
        return shift;
    }

    /**
     * Check if Alt modifier is required.
     * Package-private for registry access.
     */
    boolean requiresAlt() {
        return alt;
    }
}
