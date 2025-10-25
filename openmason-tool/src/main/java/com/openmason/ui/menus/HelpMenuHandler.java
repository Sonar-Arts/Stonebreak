package com.openmason.ui.menus;

import com.openmason.ui.state.UIVisibilityState;
import imgui.ImGui;

/**
 * Help menu handler.
 * Follows Single Responsibility Principle - only handles help menu operations.
 */
public class HelpMenuHandler {

    private final UIVisibilityState uiState;

    public HelpMenuHandler(UIVisibilityState uiState) {
        this.uiState = uiState;
    }

    /**
     * Render the help menu.
     */
    public void render() {
        if (!ImGui.beginMenu("Help")) {
            return;
        }

        if (ImGui.menuItem("About OpenMason")) {
            uiState.showAbout();
        }

        ImGui.endMenu();
    }
}
