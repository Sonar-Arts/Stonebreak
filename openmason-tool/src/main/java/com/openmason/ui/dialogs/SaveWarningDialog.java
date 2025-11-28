package com.openmason.ui.dialogs;

import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Warning dialog for attempting to save browser models.
 */
public class SaveWarningDialog {

    private static final Logger logger = LoggerFactory.getLogger(SaveWarningDialog.class);

    private boolean isOpen = false;

    /**
     * Show the save warning dialog.
     */
    public void show() {
        this.isOpen = true;
        logger.debug("Save warning dialog opened");
    }

    /**
     * Render the save warning dialog.
     * Call this every frame from your main render loop.
     */
    public void render() {
        if (!isOpen) {
            return;
        }

        // Center the modal
        ImGui.setNextWindowSize(450, 180);
        ImGui.setNextWindowPos(
                ImGui.getMainViewport().getCenterX() - 225,
                ImGui.getMainViewport().getCenterY() - 90
        );

        // Open modal popup
        if (ImGui.beginPopupModal("Cannot Save Browser Model", ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove)) {
            ImGui.textWrapped("Browser models are read-only references from Stonebreak's repository.");
            ImGui.spacing();
            ImGui.textWrapped("To create a custom .OMO file, use 'File > New Model' to create a new editable model.");
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Center the OK button
            float buttonWidth = 120;
            float windowWidth = 450;
            ImGui.setCursorPosX((windowWidth - buttonWidth) * 0.5f);

            if (ImGui.button("OK", 120, 0)) {
                logger.debug("Save warning dialog dismissed");
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.endPopup();
        }

        // Open the popup (only needs to be called once)
        if (isOpen && !ImGui.isPopupOpen("Cannot Save Browser Model")) {
            ImGui.openPopup("Cannot Save Browser Model");
        }
    }

    /**
     * Check if dialog is currently open.
     * @return true if open
     */
    public boolean isOpen() {
        return isOpen;
    }
}
