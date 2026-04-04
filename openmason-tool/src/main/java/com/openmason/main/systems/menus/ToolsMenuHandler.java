package com.openmason.main.systems.menus;

import com.openmason.main.systems.menus.dialogs.SBEExportWindow;
import com.openmason.main.systems.menus.dialogs.SBOExportWindow;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tools menu handler.
 */
public class ToolsMenuHandler {

    private static final Logger logger = LoggerFactory.getLogger(ToolsMenuHandler.class);

    private Runnable openTextureEditorCallback;
    private SBOExportWindow sboExportWindow;
    private SBEExportWindow sbeExportWindow;
    private ModelState modelState;
    private StatusService statusService;

    /**
     * Set the callback for opening the texture editor.
     */
    public void setOpenTextureEditorCallback(Runnable callback) {
        this.openTextureEditorCallback = callback;
    }

    /**
     * Set the SBO export window reference for triggering exports from the menu.
     */
    public void setSBOExportWindow(SBOExportWindow sboExportWindow) {
        this.sboExportWindow = sboExportWindow;
    }

    /**
     * Set the SBE export window reference for triggering exports from the menu.
     */
    public void setSBEExportWindow(SBEExportWindow sbeExportWindow) {
        this.sbeExportWindow = sbeExportWindow;
    }

    /**
     * Set model state for menu item enable/disable checks.
     */
    public void setModelState(ModelState modelState) {
        this.modelState = modelState;
    }

    /**
     * Set status service for user feedback.
     */
    public void setStatusService(StatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * Render the tools menu.
     */
    public void render() {
        if (!ImGui.beginMenu("Tools")) {
            return;
        }

        if (ImGui.menuItem("Texture Editor")) {
            if (openTextureEditorCallback != null) {
                openTextureEditorCallback.run();
            }
        }

        ImGui.separator();

        boolean canExport = modelState != null
                && modelState.isModelLoaded()
                && modelState.canSaveModel();
        if (ImGui.menuItem("Export SBO", "Ctrl+Shift+E", false, canExport)) {
            exportSBO();
        }

        if (ImGui.menuItem("Export SBE", "", false, canExport)) {
            exportSBE();
        }

        ImGui.endMenu();
    }

    /**
     * Open the SBO export window.
     */
    private void exportSBO() {
        if (!validateExportPreconditions("SBO")) return;

        if (sboExportWindow != null) {
            sboExportWindow.show();
        } else {
            logger.warn("SBO export window not initialized");
            if (statusService != null) statusService.updateStatus("SBO export not available");
        }
    }

    /**
     * Open the SBE export window.
     */
    private void exportSBE() {
        if (!validateExportPreconditions("SBE")) return;

        if (sbeExportWindow != null) {
            sbeExportWindow.show();
        } else {
            logger.warn("SBE export window not initialized");
            if (statusService != null) statusService.updateStatus("SBE export not available");
        }
    }

    /**
     * Common validation for Stonebreak export formats.
     *
     * @param formatName display name for status messages
     * @return true if preconditions are met
     */
    private boolean validateExportPreconditions(String formatName) {
        if (modelState == null || !modelState.isModelLoaded()) {
            if (statusService != null) statusService.updateStatus("No model to export");
            return false;
        }

        String omoPath = modelState.getCurrentOMOFilePath();
        if (omoPath == null || omoPath.isBlank()) {
            if (statusService != null) statusService.updateStatus("Save model as .OMO before exporting to ." + formatName);
            return false;
        }

        return true;
    }
}
