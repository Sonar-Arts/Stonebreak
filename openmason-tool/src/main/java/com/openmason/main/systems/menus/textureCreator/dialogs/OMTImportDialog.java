package com.openmason.main.systems.menus.textureCreator.dialogs;

import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import imgui.ImColor;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
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
    private static final String POPUP_ID = "Import OMT File##importOmt";

    // Dialog dimensions
    private static final float DIALOG_WIDTH = 480.0f;

    // Reference-viewport centre for modal positioning (main viewport when docked,
    // the popped-out editor host window's viewport when windowed). -1 = main viewport.
    private float refCenterX = -1.0f;
    private float refCenterY = -1.0f;

    /**
     * Create OMT import dialog.
     */
    public OMTImportDialog() {
        logger.debug("OMT import dialog created");
    }

    /**
     * Set the reference-viewport centre used for modal positioning.
     * Called each frame by the texture editor's render path (docked = main
     * viewport, windowed = the popped-out editor host window's viewport).
     *
     * @param centerX reference viewport centre X
     * @param centerY reference viewport centre Y
     */
    public void setReferenceViewportCenter(float centerX, float centerY) {
        this.refCenterX = centerX;
        this.refCenterY = centerY;
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

        ModalDialogs.Anchor anchor = refCenterX >= 0 && refCenterY >= 0
                ? new ModalDialogs.Anchor(refCenterX, refCenterY) : null;
        if (ModalDialogs.begin(POPUP_ID, anchor, DIALOG_WIDTH)) {

            // Header (theme Text color — legible in every theme)
            ImGui.spacing();
            String headerText = "Import .OMT File";
            ImVec2 headerSize = ImGui.calcTextSize(headerText);
            ImGui.setCursorPosX((DIALOG_WIDTH - headerSize.x) / 2.0f);
            ImVec2 headerPos = ImGui.getCursorScreenPos();
            ImGui.text(headerText);
            ImGui.getWindowDrawList().addText(headerPos.x + 0.5f, headerPos.y,
                    ImGui.getColorU32(ImGuiCol.Text), headerText);
            ImGui.spacing();

            // Show filename (theme accent)
            if (pendingFilePath != null) {
                String fileName = new File(pendingFilePath).getName();
                ImVec2 fileNameSize = ImGui.calcTextSize(fileName);
                ImGui.setCursorPosX((DIALOG_WIDTH - fileNameSize.x) / 2.0f);
                ImVec4 accent = getAccentColor();
                ImGui.pushStyleColor(ImGuiCol.Text, ImColor.rgba(accent.x, accent.y, accent.z, 1.0f));
                ImGui.text(fileName);
                ImGui.popStyleColor();
            }

            ImGui.spacing();
            ImGui.spacing();

            // Instruction text
            String subText = "How would you like to import this file?";
            ImVec2 subSize = ImGui.calcTextSize(subText);
            ImGui.setCursorPosX((DIALOG_WIDTH - subSize.x) / 2.0f);
            ImGui.textDisabled(subText);

            ImGui.spacing();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
            ImGui.spacing();

            ModalDialogs.buttonsBegin();
            if (ModalDialogs.primary("Import All Layers", true)) {
                confirmedChoice = ImportMode.IMPORT_ALL;
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.info("User chose to import all layers from .OMT file: {}", pendingFilePath);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Add each layer separately with original names");
            }
            if (ModalDialogs.secondary("Flatten to Single Layer")) {
                confirmedChoice = ImportMode.FLATTEN;
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.info("User chose to flatten .OMT file: {}", pendingFilePath);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Combine all layers into one new layer");
            }
            if (ModalDialogs.cancel()) {
                confirmedChoice = ImportMode.NONE;
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.debug("OMT import dialog cancelled");
            }

            ModalDialogs.end();
        }

        // Open the modal popup if we just set isOpen to true
        if (isOpen && !ImGui.isPopupOpen(POPUP_ID)) {
            ImGui.openPopup(POPUP_ID);
        }
    }

    /**
     * Get the accent color from the current theme (HeaderActive, as in NewTextureDialog).
     */
    private ImVec4 getAccentColor() {
        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        if (accent == null || (accent.x == 0 && accent.y == 0 && accent.z == 0)) {
            accent = new ImVec4(0.36f, 0.61f, 0.84f, 1.0f);
        }
        return accent;
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
