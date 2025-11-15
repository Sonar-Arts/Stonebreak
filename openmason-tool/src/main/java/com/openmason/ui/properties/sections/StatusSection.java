package com.openmason.ui.properties.sections;

import com.openmason.ui.properties.interfaces.IPanelSection;
import com.openmason.ui.properties.interfaces.IThemeContext;
import imgui.ImGui;

/**
 * Status message display section component.
 * Shows current status with appropriate icons and colors.
 * Follows SRP - single responsibility of status display.
 */
public class StatusSection implements IPanelSection {

    private final IThemeContext themeContext;
    private String statusMessage = "";
    private boolean loadingInProgress = false;
    private boolean validationInProgress = false;
    private boolean visible = true;

    /**
     * Create a status section.
     *
     * @param themeContext The theme context for colored status rendering
     */
    public StatusSection(IThemeContext themeContext) {
        this.themeContext = themeContext;
    }

    @Override
    public void render() {
        if (!visible) {
            return;
        }

        ImGui.separator();

        // Render status using theme-aware colors
        themeContext.renderStatusText(statusMessage, loadingInProgress, validationInProgress);
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public String getSectionName() {
        return "Status";
    }

    // Public API

    /**
     * Set the status message.
     *
     * @param message The status message to display
     */
    public void setStatusMessage(String message) {
        this.statusMessage = message != null ? message : "";
    }

    /**
     * Set the loading state.
     *
     * @param loading true if loading is in progress
     */
    public void setLoading(boolean loading) {
        this.loadingInProgress = loading;
    }

    /**
     * Set the validation state.
     *
     * @param validating true if validation is in progress
     */
    public void setValidating(boolean validating) {
        this.validationInProgress = validating;
    }

    /**
     * Set visibility of this section.
     *
     * @param visible true to show, false to hide
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * Get the current status message.
     *
     * @return The status message
     */
    public String getStatusMessage() {
        return statusMessage;
    }

    /**
     * Check if loading is in progress.
     *
     * @return true if loading
     */
    public boolean isLoading() {
        return loadingInProgress;
    }

    /**
     * Check if validation is in progress.
     *
     * @return true if validating
     */
    public boolean isValidating() {
        return validationInProgress;
    }
}
