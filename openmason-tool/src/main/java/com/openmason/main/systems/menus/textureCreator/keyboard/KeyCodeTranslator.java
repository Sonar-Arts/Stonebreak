package com.openmason.main.systems.menus.textureCreator.keyboard;

import imgui.flag.ImGuiKey;
import org.lwjgl.glfw.GLFW;

/**
 * Translates GLFW key codes to {@link ImGuiKey} values.
 *
 * <p>imgui 1.92 removed support for user-defined key indices: input query
 * functions ({@code ImGui.isKeyPressed}, {@code ImGui.isKeyDown}, ...) now
 * assert unless given an {@code ImGuiKey} value. The codebase stores keybinds
 * as GLFW codes, so every input query must translate first. This utility is the
 * single place that mapping lives.
 *
 * @author Open Mason Team
 */
public final class KeyCodeTranslator {

    private KeyCodeTranslator() {
        // Static utility — not instantiable.
    }

    /**
     * Translate a GLFW key code to its corresponding {@link ImGuiKey} value.
     *
     * @param glfwKeyCode the GLFW key code
     * @return the matching ImGuiKey value, or {@link ImGuiKey#None} if unmapped
     */
    public static int toImGuiKey(int glfwKeyCode) {
        // Contiguous ranges map directly via offset arithmetic.
        if (glfwKeyCode >= GLFW.GLFW_KEY_A && glfwKeyCode <= GLFW.GLFW_KEY_Z) {
            return ImGuiKey.A + (glfwKeyCode - GLFW.GLFW_KEY_A);
        }
        if (glfwKeyCode >= GLFW.GLFW_KEY_0 && glfwKeyCode <= GLFW.GLFW_KEY_9) {
            return ImGuiKey._0 + (glfwKeyCode - GLFW.GLFW_KEY_0);
        }
        if (glfwKeyCode >= GLFW.GLFW_KEY_KP_0 && glfwKeyCode <= GLFW.GLFW_KEY_KP_9) {
            return ImGuiKey.Keypad0 + (glfwKeyCode - GLFW.GLFW_KEY_KP_0);
        }
        if (glfwKeyCode >= GLFW.GLFW_KEY_F1 && glfwKeyCode <= GLFW.GLFW_KEY_F12) {
            return ImGuiKey.F1 + (glfwKeyCode - GLFW.GLFW_KEY_F1);
        }

        return switch (glfwKeyCode) {
            case GLFW.GLFW_KEY_ENTER -> ImGuiKey.Enter;
            case GLFW.GLFW_KEY_ESCAPE -> ImGuiKey.Escape;
            case GLFW.GLFW_KEY_DELETE -> ImGuiKey.Delete;
            case GLFW.GLFW_KEY_BACKSPACE -> ImGuiKey.Backspace;
            case GLFW.GLFW_KEY_TAB -> ImGuiKey.Tab;
            case GLFW.GLFW_KEY_SPACE -> ImGuiKey.Space;
            case GLFW.GLFW_KEY_COMMA -> ImGuiKey.Comma;
            case GLFW.GLFW_KEY_PERIOD -> ImGuiKey.Period;
            case GLFW.GLFW_KEY_SLASH -> ImGuiKey.Slash;
            case GLFW.GLFW_KEY_EQUAL -> ImGuiKey.Equal;
            case GLFW.GLFW_KEY_MINUS -> ImGuiKey.Minus;
            case GLFW.GLFW_KEY_KP_ADD -> ImGuiKey.KeypadAdd;
            case GLFW.GLFW_KEY_KP_SUBTRACT -> ImGuiKey.KeypadSubtract;
            case GLFW.GLFW_KEY_KP_ENTER -> ImGuiKey.KeypadEnter;
            case GLFW.GLFW_KEY_LEFT_CONTROL -> ImGuiKey.LeftCtrl;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> ImGuiKey.RightCtrl;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> ImGuiKey.LeftShift;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> ImGuiKey.RightShift;
            case GLFW.GLFW_KEY_LEFT_ALT -> ImGuiKey.LeftAlt;
            case GLFW.GLFW_KEY_RIGHT_ALT -> ImGuiKey.RightAlt;
            default -> ImGuiKey.None;
        };
    }

    /**
     * Convenience wrapper around {@code ImGui.isKeyPressed} that accepts a GLFW
     * key code. Returns {@code false} for unmapped keys instead of asserting.
     *
     * @param glfwKeyCode the GLFW key code
     * @return true if the key was pressed this frame
     */
    public static boolean isKeyPressed(int glfwKeyCode) {
        int imguiKey = toImGuiKey(glfwKeyCode);
        return imguiKey != ImGuiKey.None && imgui.ImGui.isKeyPressed(imguiKey);
    }

    /**
     * Convenience wrapper around {@code ImGui.isKeyDown} that accepts a GLFW
     * key code. Returns {@code false} for unmapped keys instead of asserting.
     *
     * @param glfwKeyCode the GLFW key code
     * @return true if the key is currently held down
     */
    public static boolean isKeyDown(int glfwKeyCode) {
        int imguiKey = toImGuiKey(glfwKeyCode);
        return imguiKey != ImGuiKey.None && imgui.ImGui.isKeyDown(imguiKey);
    }
}
