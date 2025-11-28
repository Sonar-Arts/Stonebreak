package com.openmason.ui.properties.theming;

import com.openmason.ui.properties.interfaces.IThemeContext;
import com.openmason.ui.themes.core.ThemeDefinition;
import com.openmason.ui.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized theme context for property panel.
 */
public class PanelThemeContext implements IThemeContext {

    private static final Logger logger = LoggerFactory.getLogger(PanelThemeContext.class);

    private final ThemeManager themeManager;
    private final boolean available;
    private int pushedStyleVars = 0;

    /**
     * Create a theme context.
     */
    public PanelThemeContext(ThemeManager themeManager) {
        this.themeManager = themeManager;
        this.available = (themeManager != null);
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public ThemeDefinition getCurrentTheme() {
        if (!available) {
            return null;
        }
        try {
            return themeManager.getCurrentTheme();
        } catch (Exception e) {
            logger.debug("Error getting current theme", e);
            return null;
        }
    }

    @Override
    public void applyPanelStyle() {
        if (!available) {
            return;
        }

        try {
            ThemeDefinition theme = getCurrentTheme();
            if (theme != null) {
                ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8.0f, 8.0f);
                ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 4.0f, 6.0f);
                pushedStyleVars = 2;
            }
        } catch (Exception e) {
            logger.debug("Error applying panel style", e);
            pushedStyleVars = 0;
        }
    }

    @Override
    public void restorePanelStyle() {
        if (!available || pushedStyleVars == 0) {
            return;
        }

        try {
            ImGui.popStyleVar(pushedStyleVars);
            pushedStyleVars = 0;
        } catch (Exception e) {
            logger.debug("Error restoring panel style", e);
        }
    }

    @Override
    public void renderStatusText(String statusMessage, boolean isLoading, boolean isValidating) {
        if (statusMessage == null || statusMessage.isEmpty()) {
            return;
        }

        String messageLower = statusMessage.toLowerCase();

        // Determine status type and color
        StatusType type = determineStatusType(messageLower, isLoading, isValidating);

        // Render with appropriate color and icon
        switch (type) {
            case LOADING:
                renderColoredStatus("⏳ " + statusMessage, 0.0f, 1.0f, 1.0f); // Cyan
                break;
            case VALIDATING:
                renderColoredStatus("🔍 " + statusMessage, 1.0f, 1.0f, 0.0f); // Yellow
                break;
            case ERROR:
                float errorRed = 1.0f;
                float errorGreen = available ? 0.2f : 0.0f;
                renderColoredStatus("❌ " + statusMessage, errorRed, errorGreen, 0.2f);
                break;
            case SUCCESS:
                float successRed = available ? 0.2f : 0.0f;
                float successGreen = 1.0f;
                renderColoredStatus("✅ " + statusMessage, successRed, successGreen, 0.2f);
                break;
            case NORMAL:
            default:
                ImGui.text(statusMessage);
                break;
        }
    }

    /**
     * Determine the status type from the message.
     */
    private StatusType determineStatusType(String messageLower, boolean isLoading, boolean isValidating) {
        if (isLoading) {
            return StatusType.LOADING;
        }
        if (isValidating) {
            return StatusType.VALIDATING;
        }
        if (messageLower.contains("error") || messageLower.contains("failed")) {
            return StatusType.ERROR;
        }
        if (messageLower.contains("success") || messageLower.contains("completed")) {
            return StatusType.SUCCESS;
        }
        return StatusType.NORMAL;
    }

    /**
     * Render colored status text.
     */
    private void renderColoredStatus(String text, float r, float g, float b) {
        ImGui.pushStyleColor(ImGuiCol.Text, r, g, b, (float) 1.0);
        ImGui.text(text);
        ImGui.popStyleColor();
    }

    /**
     * Status type enumeration.
     */
    private enum StatusType {
        NORMAL,
        LOADING,
        VALIDATING,
        ERROR,
        SUCCESS
    }
}
