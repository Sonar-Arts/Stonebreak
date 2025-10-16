package com.openmason.ui.menus;

import com.openmason.ui.state.UIVisibilityState;
import com.openmason.ui.themes.application.DensityManager;
import com.openmason.ui.themes.core.ThemeDefinition;
import com.openmason.ui.themes.core.ThemeManager;
import imgui.ImGui;

/**
 * Theme menu handler.
 * Follows Single Responsibility Principle - only handles theme menu operations.
 */
public class ThemeMenuHandler {

    private final UIVisibilityState uiState;
    private final ThemeManager themeManager;

    public ThemeMenuHandler(UIVisibilityState uiState, ThemeManager themeManager) {
        this.uiState = uiState;
        this.themeManager = themeManager;
    }

    /**
     * Render the theme menu.
     */
    public void render() {
        if (!ImGui.beginMenu("Theme")) {
            return;
        }

        // Quick theme switching
        for (ThemeDefinition theme : themeManager.getAvailableThemes()) {
            boolean isCurrentTheme = theme == themeManager.getCurrentTheme();
            if (ImGui.menuItem(theme.getName(), "", isCurrentTheme)) {
                themeManager.applyTheme(theme);
            }
        }

        ImGui.separator();

        // Density controls
        if (ImGui.beginMenu("UI Density")) {
            for (DensityManager.UIDensity density : DensityManager.UIDensity.values()) {
                boolean isCurrentDensity = density == themeManager.getCurrentDensity();
                if (ImGui.menuItem(density.getDisplayName(), "", isCurrentDensity)) {
                    themeManager.setUIDensity(density);
                }
            }
            ImGui.endMenu();
        }

        ImGui.separator();

        if (ImGui.menuItem("Advanced Theme Settings...")) {
            uiState.showPreferences();
        }

        if (ImGui.menuItem("Reset to Defaults")) {
            themeManager.applyTheme("dark");
            themeManager.setUIDensity(DensityManager.UIDensity.NORMAL);
        }

        ImGui.endMenu();
    }
}
