package com.openmason.main.systems.viewport;

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
    private final ViewportUIState state;
    private final com.openmason.main.systems.keybinds.KeybindRegistry registry;

    /**
     * Constructor with dependency injection.
     * @param actions The viewport actions to execute
     * @param state The viewport state to access
     * @param registry The keybind registry for customizable shortcuts
     */
    public ViewportKeyboardShortcuts(ViewportActions actions, ViewportUIState state,
                                    com.openmason.main.systems.keybinds.KeybindRegistry registry) {
        this.actions = actions;
        this.state = state;
        this.registry = registry;
    }

    /**
     * Handle viewport-specific keyboard shortcuts.
     * Now uses the keybind registry for customizable shortcuts.
     * Should only be called when the viewport window is focused and not actively typing.
     */
    public void handleKeyboardShortcuts() {
        // Iterate through all viewport actions in the registry
        for (com.openmason.main.systems.keybinds.KeybindAction action : registry.getActionsByCategory("Viewport")) {
            com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey key =
                    registry.getKeybind(action.getId());
            if (key.isPressed()) {
                action.execute();
                return; // Only execute first matching shortcut
            }
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
