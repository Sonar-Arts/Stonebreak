package com.openmason.main.systems.menus;

import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import imgui.ImGui;

/**
 * Edit menu handler.
 * Follows Single Responsibility Principle - only handles edit menu operations.
 */
public class EditMenuHandler {

    private final UIVisibilityState uiState;

    private ViewportController viewport;

    public EditMenuHandler(UIVisibilityState uiState) {
        this.uiState = uiState;
    }

    /**
     * Set viewport reference for undo/redo access to the model command history.
     */
    public void setViewport(ViewportController viewport) {
        this.viewport = viewport;
    }

    /**
     * Render the edit menu.
     */
    public void render() {
        if (!ImGui.beginMenu("Edit")) {
            return;
        }

        ModelCommandHistory history = viewport != null ? viewport.getCommandHistory() : null;
        boolean canUndo = history != null && history.canUndo();
        boolean canRedo = history != null && history.canRedo();

        if (ImGui.menuItem("Undo", "Ctrl+Z", false, canUndo)) {
            history.undo();
        }

        if (ImGui.menuItem("Redo", "Ctrl+Y", false, canRedo)) {
            history.redo();
        }

        ImGui.separator();

        if (ImGui.menuItem("Preferences...")) {
            uiState.showPreferences();
        }

        ImGui.endMenu();
    }
}
