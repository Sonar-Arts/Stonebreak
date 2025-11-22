package com.openmason.ui.viewport;

import imgui.ImGui;
import imgui.ImGuiIO;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles keyboard shortcuts for viewport operations.
 * Follows Single Responsibility Principle - only handles keyboard shortcuts.
 * Follows Open/Closed Principle - can extend with new shortcuts without modifying existing code.
 */
public class ViewportKeyboardShortcuts {

    private static final Logger logger = LoggerFactory.getLogger(ViewportKeyboardShortcuts.class);

    private final ViewportActions actions;
    private final ViewportState state;

    /**
     * Constructor with dependency injection.
     * @param actions The viewport actions to execute
     * @param state The viewport state to access
     */
    public ViewportKeyboardShortcuts(ViewportActions actions, ViewportState state) {
        this.actions = actions;
        this.state = state;
    }

    /**
     * Handle viewport-specific keyboard shortcuts.
     * Should only be called when the viewport window is focused and not actively typing.
     */
    public void handleKeyboardShortcuts() {
        ImGuiIO io = ImGui.getIO();
        boolean ctrlPressed = io.getKeyCtrl();

        if (!ctrlPressed) {
            return; // All current shortcuts require Ctrl
        }

        // Ctrl+T: Toggle Transform Gizmo
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_T)) {
            actions.toggleGizmo();
        }

        // Ctrl+G: Toggle Grid
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_G)) {
            state.getGridVisible().set(!state.getGridVisible().get());
            actions.toggleGrid();
        }

        // Ctrl+X: Toggle Axes
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_X)) {
            state.getAxesVisible().set(!state.getAxesVisible().get());
            actions.toggleAxes();
        }

        // Ctrl+W: Toggle Wireframe
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_W)) {
            actions.toggleWireframe();
        }

        // Ctrl+R: Reset View
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_R)) {
            actions.resetView();
        }

        // Ctrl+F: Fit to View
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_F)) {
            actions.fitToView();
        }
    }

    /**
     * Check if input should be processed (not actively typing in a text field).
     * @return true if shortcuts should be processed
     */
    public static boolean shouldProcessShortcuts() {
        return !(ImGui.isAnyItemActive() && ImGui.getIO().getWantTextInput());
    }
}
