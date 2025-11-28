package com.openmason.main.systems.menus.textureCreator.dialogs;

import imgui.ImColor;
import imgui.ImGui;
import imgui.ImVec2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Modal dialog for choosing how to import .OMT files.
 */
public class OMTImportDialog {

    private static final Logger logger = LoggerFactory.getLogger(OMTImportDialog.class);

    /**
     * Import mode choice.
     */
    public enum ImportMode {
        NONE,           // No choice made (cancelled)
        FLATTEN,        // Flatten to single layer
        IMPORT_ALL      // Import all layers
    }

    // Dialog state
    private boolean isOpen = false;
    private String pendingFilePath = null;
    private ImportMode confirmedChoice = ImportMode.NONE;
    private boolean needsPositioning = false;

    // Dialog dimensions
    private static final float DIALOG_WIDTH = 480.0f;
    private static final float DIALOG_HEIGHT = 220.0f;

    /**
     * Create OMT import dialog.
     */
    public OMTImportDialog() {
        logger.debug("OMT import dialog created");
    }

    /**
     * Show the dialog for a specific .OMT file.
     *
     * @param filePath path to the .OMT file being imported
     */
    public void show(String filePath) {
        isOpen = true;
        pendingFilePath = filePath;
        confirmedChoice = ImportMode.NONE;
        needsPositioning = true; // Center on first open
        logger.debug("OMT import dialog opened for: {}", filePath);
    }

    /**
     * Check if dialog is currently open.
     *
     * @return true if open, false otherwise
     */
    public boolean isOpen() {
        return isOpen;
    }

    /**
     * Get confirmed import mode selection.
     * Returns the selected mode if user made a choice, NONE otherwise.
     * Resets to NONE after being read.
     *
     * @return selected import mode or NONE
     */
    public ImportMode getConfirmedChoice() {
        ImportMode result = confirmedChoice;
        confirmedChoice = ImportMode.NONE; // Reset after reading
        return result;
    }

    /**
     * Get the pending file path.
     *
     * @return file path that is pending import
     */
    public String getPendingFilePath() {
        return pendingFilePath;
    }

    /**
     * Render the dialog.
     * Call this every frame from the main render loop.
     */
    public void render() {
        if (!isOpen) {
            return;
        }

        // Set initial size and position only on first open
        if (needsPositioning) {
            ImGui.setNextWindowSize(DIALOG_WIDTH, DIALOG_HEIGHT);
            ImGui.setNextWindowPos(
                ImGui.getMainViewport().getSizeX() / 2.0f - DIALOG_WIDTH / 2.0f,
                ImGui.getMainViewport().getSizeY() / 2.0f - DIALOG_HEIGHT / 2.0f
            );
            needsPositioning = false;
        }

        if (ImGui.beginPopupModal("Import .OMT File")) {

            // Header
            ImGui.spacing();
            String headerText = "Import .OMT File";
            ImVec2 headerSize = ImGui.calcTextSize(headerText);
            ImGui.setCursorPosX((DIALOG_WIDTH - headerSize.x) / 2.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImColor.rgba(80, 80, 80, 255));
            ImGui.text(headerText);
            ImGui.popStyleColor();
            ImGui.spacing();

            // Show filename
            if (pendingFilePath != null) {
                String fileName = new File(pendingFilePath).getName();
                ImVec2 fileNameSize = ImGui.calcTextSize(fileName);
                ImGui.setCursorPosX((DIALOG_WIDTH - fileNameSize.x) / 2.0f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImColor.rgba(100, 150, 255, 255));
                ImGui.text(fileName);
                ImGui.popStyleColor();
            }

            ImGui.spacing();
            ImGui.spacing();

            // Instruction text
            String subText = "How would you like to import this file?";
            ImVec2 subSize = ImGui.calcTextSize(subText);
            ImGui.setCursorPosX((DIALOG_WIDTH - subSize.x) / 2.0f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImColor.rgba(140, 140, 140, 255));
            ImGui.text(subText);
            ImGui.popStyleColor();

            ImGui.spacing();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
            ImGui.spacing();

            // Buttons
            float buttonWidth = 200.0f;
            float buttonHeight = 32.0f;
            float buttonSpacing = 12.0f;

            // Center the two main buttons
            float totalButtonWidth = buttonWidth * 2 + buttonSpacing;
            float buttonStartX = (DIALOG_WIDTH - totalButtonWidth) / 2.0f;

            ImGui.setCursorPosX(buttonStartX);

            // Flatten button
            if (ImGui.button("Flatten to Single Layer", buttonWidth, buttonHeight)) {
                confirmedChoice = ImportMode.FLATTEN;
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.info("User chose to flatten .OMT file: {}", pendingFilePath);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Combine all layers into one new layer");
            }

            ImGui.sameLine(0, buttonSpacing);

            // Import All button
            if (ImGui.button("Import All Layers", buttonWidth, buttonHeight)) {
                confirmedChoice = ImportMode.IMPORT_ALL;
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.info("User chose to import all layers from .OMT file: {}", pendingFilePath);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Add each layer separately with original names");
            }

            ImGui.spacing();
            ImGui.spacing();

            // Cancel button (centered)
            float cancelWidth = 120.0f;
            float cancelStartX = (DIALOG_WIDTH - cancelWidth) / 2.0f;
            ImGui.setCursorPosX(cancelStartX);

            if (ImGui.button("Cancel", cancelWidth, buttonHeight)) {
                confirmedChoice = ImportMode.NONE;
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.debug("OMT import dialog cancelled");
            }

            // Handle ESC key to close
            if (ImGui.isKeyPressed(ImGui.getKeyIndex(imgui.flag.ImGuiKey.Escape))) {
                confirmedChoice = ImportMode.NONE;
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.endPopup();
        }

        // Open the modal popup if we just set isOpen to true
        if (isOpen && !ImGui.isPopupOpen("Import .OMT File")) {
            ImGui.openPopup("Import .OMT File");
        }
    }

    /**
     * Close the dialog (cleanup if needed).
     */
    public void close() {
        isOpen = false;
        confirmedChoice = ImportMode.NONE;
        pendingFilePath = null;
    }
}
