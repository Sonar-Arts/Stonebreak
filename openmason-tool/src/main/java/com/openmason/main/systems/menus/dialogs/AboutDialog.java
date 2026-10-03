package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.stateHandling.HelpWindowVisibilityState;
import imgui.ImGui;
import com.openmason.main.systems.themes.utils.ThemeColors;
import imgui.flag.ImGuiCol;

/**
 * About dialog window.
 * Uses interface-based design to support multiple tools.
 */
public class AboutDialog {

    private final HelpWindowVisibilityState visibilityState;
    private final LogoManager logoManager;
    private final String toolName;

    /**
     * Create about dialog.
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

        ModalDialogs.openIfNeeded(popupId());
        if (ModalDialogs.begin(popupId(), 360)) {
            // Render large logo at the top
            if (logoManager != null) {
                logoManager.renderAboutLogo();
                ImGui.spacing();
            }

            // Application title and version
            ThemeColors.push(ImGuiCol.Text, ImGuiCol.CheckMark);
            ImGui.text(toolName);
            ImGui.popStyleColor();
            ImGui.sameLine();
            ImGui.text("v0.0.5");

            // Simple description
            ImGui.textDisabled("Part of the OpenMason Toolset");

            ModalDialogs.buttonsBegin();
            if (ModalDialogs.closeButton()) {
                visibilityState.getShowAboutWindow().set(false);
                ImGui.closeCurrentPopup();
            }
            ModalDialogs.end();
        } else {
            visibilityState.getShowAboutWindow().set(false);
        }
    }

    private String popupId() {
        return "About " + toolName + "##aboutDialog";
    }
}
