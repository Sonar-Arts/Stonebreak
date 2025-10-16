package com.openmason.ui.dialogs;

import com.openmason.ui.config.WindowConfig;
import com.openmason.ui.preferences.PreferencesManager;
import com.openmason.ui.services.StatusService;
import com.openmason.ui.state.UIVisibilityState;
import com.openmason.ui.themes.DensityManager;
import com.openmason.ui.themes.ImGuiHelpers;
import com.openmason.ui.themes.ThemeDefinition;
import com.openmason.ui.themes.ThemeManager;
import com.openmason.ui.viewport.OpenMason3DViewport;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImFloat;
import imgui.type.ImInt;

/**
 * Preferences dialog window.
 * Follows Single Responsibility Principle - only handles preferences window.
 */
public class PreferencesDialog {

    private final UIVisibilityState uiState;
    private final ThemeManager themeManager;
    private final PreferencesManager preferencesManager;
    private final StatusService statusService;
    private final WindowConfig windowConfig;

    // Camera settings
    private final ImFloat cameraMouseSensitivity;

    // Theme settings
    private final ImInt pendingThemeIndex = new ImInt(0);
    private final ImInt pendingDensityIndex = new ImInt(1);
    private boolean hasUnsavedThemeChanges = false;

    // Viewport reference
    private OpenMason3DViewport viewport;

    public PreferencesDialog(UIVisibilityState uiState, ThemeManager themeManager,
                             PreferencesManager preferencesManager, StatusService statusService,
                             ImFloat cameraMouseSensitivity) {
        this.uiState = uiState;
        this.themeManager = themeManager;
        this.preferencesManager = preferencesManager;
        this.statusService = statusService;
        this.cameraMouseSensitivity = cameraMouseSensitivity;
        this.windowConfig = WindowConfig.forAdvancedPreferences();
    }

    /**
     * Set viewport reference for camera settings.
     */
    public void setViewport(OpenMason3DViewport viewport) {
        this.viewport = viewport;
    }

    /**
     * Render the preferences window.
     */
    public void render() {
        if (!uiState.getShowPreferencesWindow().get()) {
            return;
        }

        ImGuiHelpers.configureWindowConstraints(windowConfig);

        if (ImGui.begin("Preferences", uiState.getShowPreferencesWindow(), ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGuiHelpers.configureWindowSize(windowConfig);
            ImGuiHelpers.configureWindowPosition(windowConfig);

            // Initialize pending values from current settings when window appears
            if (ImGui.isWindowAppearing()) {
                initializePendingValues();
            }

            renderThemeSettings();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            renderCameraSettings();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            renderButtons();
        }
        ImGui.end();
    }

    /**
     * Initialize pending theme values from current settings.
     */
    private void initializePendingValues() {
        ThemeDefinition currentTheme = themeManager.getCurrentTheme();
        if (currentTheme != null) {
            for (int i = 0; i < themeManager.getAvailableThemes().size(); i++) {
                if (themeManager.getAvailableThemes().get(i).getId().equals(currentTheme.getId())) {
                    pendingThemeIndex.set(i);
                    break;
                }
            }
        }
        DensityManager.UIDensity currentDensity = themeManager.getCurrentDensity();
        pendingDensityIndex.set(currentDensity.ordinal());
        hasUnsavedThemeChanges = false;
    }

    /**
     * Render theme settings section.
     */
    private void renderThemeSettings() {
        ImGui.text("Theme Settings");
        ImGui.separator();

        // Theme selection
        ImGui.text("Theme:");
        ImGui.sameLine();
        ImGui.setNextItemWidth(200.0f);
        String[] themeNames = themeManager.getAvailableThemes().stream()
                .map(ThemeDefinition::getName)
                .toArray(String[]::new);

        if (ImGui.combo("##themeSelect", pendingThemeIndex, themeNames)) {
            hasUnsavedThemeChanges = true;
        }

        // UI Density selection
        ImGui.text("UI Density:");
        ImGui.sameLine();
        ImGui.setNextItemWidth(200.0f);
        String[] densityNames = new String[DensityManager.UIDensity.values().length];
        int idx = 0;
        for (DensityManager.UIDensity density : DensityManager.UIDensity.values()) {
            densityNames[idx++] = density.getDisplayName();
        }

        if (ImGui.combo("##densitySelect", pendingDensityIndex, densityNames)) {
            hasUnsavedThemeChanges = true;
        }

        // Show unsaved changes indicator
        if (hasUnsavedThemeChanges) {
            ImGui.spacing();
            ImGui.textColored(1.0f, 0.8f, 0.0f, 1.0f, "* You have unsaved theme changes");
        }
    }

    /**
     * Render camera settings section.
     */
    private void renderCameraSettings() {
        ImGui.text("Camera Settings");
        ImGui.separator();

        ImGui.text("Camera Drag Speed:");
        ImGui.sameLine();
        ImGui.setNextItemWidth(100.0f);
        if (ImGui.inputFloat("##cameraDragSpeed", cameraMouseSensitivity, 0.1f, 1.0f, "%.1f")) {
            // Clamp the value to reasonable range
            float newValue = Math.max(0.1f, Math.min(10.0f, cameraMouseSensitivity.get()));
            cameraMouseSensitivity.set(newValue);
            applyCameraMouseSensitivity(newValue);
        }
    }

    /**
     * Render action buttons.
     */
    private void renderButtons() {
        if (ImGui.button("Apply Theme Changes")) {
            applyThemeChanges();
        }
        ImGui.sameLine();

        if (ImGui.button("Reset All to Defaults")) {
            resetToDefaults();
        }
        ImGui.sameLine();

        if (ImGui.button("Close")) {
            closeWindow();
        }
    }

    /**
     * Apply pending theme changes.
     */
    private void applyThemeChanges() {
        ThemeDefinition selectedTheme = themeManager.getAvailableThemes().get(pendingThemeIndex.get());
        themeManager.applyTheme(selectedTheme);

        DensityManager.UIDensity selectedDensity = DensityManager.UIDensity.values()[pendingDensityIndex.get()];
        themeManager.setUIDensity(selectedDensity);

        hasUnsavedThemeChanges = false;
        statusService.updateStatus("Theme changes applied: " + selectedTheme.getName() +
                " / " + selectedDensity.getDisplayName());
    }

    /**
     * Reset all settings to defaults.
     */
    private void resetToDefaults() {
        // Reset theme to default
        themeManager.applyTheme("dark");
        themeManager.setUIDensity(DensityManager.UIDensity.NORMAL);

        // Update pending values to match
        pendingThemeIndex.set(0);
        pendingDensityIndex.set(1);
        hasUnsavedThemeChanges = false;

        // Reset camera to default
        if (preferencesManager != null) {
            preferencesManager.resetCameraToDefaults();
            float defaultSensitivity = preferencesManager.getCameraMouseSensitivity();
            cameraMouseSensitivity.set(defaultSensitivity);
            applyCameraMouseSensitivity(defaultSensitivity);
        } else {
            cameraMouseSensitivity.set(3.0f);
            applyCameraMouseSensitivity(3.0f);
        }

        statusService.updateStatus("All settings reset to defaults");
    }

    /**
     * Close the preferences window.
     */
    private void closeWindow() {
        if (hasUnsavedThemeChanges) {
            statusService.updateStatus("Warning: Unsaved theme changes discarded");
            hasUnsavedThemeChanges = false;
        }
        uiState.getShowPreferencesWindow().set(false);
    }

    /**
     * Apply camera mouse sensitivity setting.
     */
    private void applyCameraMouseSensitivity(float sensitivity) {
        if (viewport != null && viewport.getCamera() != null) {
            viewport.getCamera().setMouseSensitivity(sensitivity);
        }

        if (preferencesManager != null) {
            preferencesManager.setCameraMouseSensitivity(sensitivity);
        }
    }
}
