package com.openmason.main.systems.menus;

import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import imgui.ImGui;

/**
 * Edit menu handler.
 * Follows Single Responsibility Principle - only handles edit menu operations.
 */
public class EditMenuHandler {

    private final UIVisibilityState uiState;

    public EditMenuHandler(UIVisibilityState uiState, StatusService statusService) {
        this.uiState = uiState;
    }

    /**
     * Render the edit menu.
     */
    public void render() {
        if (!ImGui.beginMenu("Edit")) {
            return;
        }

        if (ImGui.menuItem("Preferences", "Ctrl+,")) {
            uiState.showPreferences();
        }

        ImGui.endMenu();
    }
}
