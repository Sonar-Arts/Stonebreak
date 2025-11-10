package com.openmason.ui.dialogs;

import com.openmason.ui.LogoManager;
import com.openmason.ui.state.HelpWindowVisibilityState;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;

/**
 * About dialog window.
 * Uses interface-based design to support multiple tools.
 * Follows Single Responsibility Principle - only handles about window rendering.
 */
public class AboutDialog {

    private final HelpWindowVisibilityState visibilityState;
    private final LogoManager logoManager;
    private final String toolName;

    /**
     * Create about dialog.
     *
     * @param visibilityState the visibility state interface for managing about window
     * @param logoManager optional logo manager (null to skip logo rendering)
     * @param toolName the name of the tool (e.g., "Model Viewer", "Texture Creator")
     */
    public AboutDialog(HelpWindowVisibilityState visibilityState, LogoManager logoManager, String toolName) {
        this.visibilityState = visibilityState;
        this.logoManager = logoManager;
        this.toolName = toolName;
    }

    /**
     * Render the about window.
     */
    public void render() {
        if (!visibilityState.getShowAboutWindow().get()) {
            return;
        }

        // Set fixed size for about dialog to prevent flickering during drag
        ImGui.setNextWindowSize(400, 300, imgui.flag.ImGuiCond.FirstUseEver);

        if (ImGui.begin("About " + toolName, visibilityState.getShowAboutWindow(),
                ImGuiWindowFlags.NoCollapse)) {

            // Render large logo at the top
            if (logoManager != null) {
                logoManager.renderAboutLogo();
                ImGui.spacing();
            }

            // Application title and version
            ImGui.textColored(0.2f, 0.6f, 1.0f, 1.0f, toolName);
            ImGui.sameLine();
            ImGui.text("v0.0.1");

            // Simple description
            ImGui.textDisabled("Part of the OpenMason Toolset");
            ImGui.spacing();
            ImGui.spacing();

            // Close button
            float windowWidth = ImGui.getWindowSize().x;
            float buttonWidth = 80.0f;
            ImGui.setCursorPosX((windowWidth - buttonWidth) * 0.5f);

            if (ImGui.button("Close", buttonWidth, 0)) {
                visibilityState.getShowAboutWindow().set(false);
            }
        }
        ImGui.end();
    }
}
