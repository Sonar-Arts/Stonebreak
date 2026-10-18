package com.openmason.main.systems.menus;

import com.openmason.main.systems.menus.dialogs.SBEEditorWindow;
import com.openmason.main.systems.menus.dialogs.SBEExportWindow;
import com.openmason.main.systems.menus.dialogs.SBOEditorWindow;
import com.openmason.main.systems.menus.dialogs.SBOExportWindow;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Tools menu's game-asset commands (Export SBO/SBE, SBO/SBE Editor), contributed as menu
 * items by the built-in Stonebreak plugin until #382 moves them into {@code openmason-plugin-stonebreak}.
 * The editors' own entries (Texture, Animation, Scripting, UI) are contributed by their plugins.
 */
public class AssetExportMenuActions {

    private static final Logger logger = LoggerFactory.getLogger(AssetExportMenuActions.class);

    private SBOExportWindow sboExportWindow;
    private SBEExportWindow sbeExportWindow;
    private SBOEditorWindow sboEditorWindow;
    private SBEEditorWindow sbeEditorWindow;
    private ModelState modelState;
    private StatusService statusService;
    private ModelOperationService modelOperations;

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
     * Set the SBO editor window reference. The Tools menu's "SBO Editor..."
     * entry opens a file dialog and routes the selection here.
     */
    public void setSBOEditorWindow(SBOEditorWindow sboEditorWindow) {
        this.sboEditorWindow = sboEditorWindow;
    }

    /**
     * Set the SBE editor window reference. The Tools menu's "SBE Editor..."
     * entry opens a file dialog and routes the selection here.
     */
    public void setSBEEditorWindow(SBEEditorWindow sbeEditorWindow) {
        this.sbeEditorWindow = sbeEditorWindow;
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
     * Set model operation service for auto-saving the OMO before export.
     * Allows the export flow to sync in-memory edits to disk so Stonebreak
     * exports always reflect the latest model state.
     */
    public void setModelOperations(ModelOperationService modelOperations) {
        this.modelOperations = modelOperations;
    }

    /**
     * Export needs a model with an .OMO on disk, or a saveable (new) model that the export routes
     * through a save-first prompt ({@link #prepareForExport}).
     */
    public boolean canExport() {
        return modelState != null
                && modelState.isModelLoaded()
                && (modelState.hasOMOFile() || modelState.canSaveModel());
    }

    /** Tools → SBO Editor...: pick a .sbo and open it. */
    public void openSboEditor() {
        if (sboEditorWindow != null) {
            sboEditorWindow.openWithDialog();
        } else {
            logger.warn("SBO editor window not initialized");
            if (statusService != null) statusService.updateStatus("SBO editor not available");
        }
    }

    /** Tools → SBE Editor...: pick a .sbe and open it. */
    public void openSbeEditor() {
        if (sbeEditorWindow != null) {
            sbeEditorWindow.openWithDialog();
        } else {
            logger.warn("SBE editor window not initialized");
            if (statusService != null) statusService.updateStatus("SBE editor not available");
        }
    }

    /**
     * Open the SBO export window.
     */
    public void exportSBO() {
        if (!prepareForExport("SBO")) return;

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
    public void exportSBE() {
        if (!prepareForExport("sbe")) return;

        if (sbeExportWindow != null) {
            sbeExportWindow.show();
        } else {
            logger.warn("SBE export window not initialized");
            if (statusService != null) statusService.updateStatus("SBE export not available");
        }
    }

    /**
     * Validate preconditions and auto-sync the on-disk OMO for Stonebreak exports.
     *
     * <p>The SBO/SBE serializers read the .OMO file from disk, so the file must
     * both exist and reflect the current in-memory model. If the model has an
     * OMO path already (loaded from a project or previously saved), we flush
     * any pending edits to disk before the export dialog opens. If there is no
     * OMO path yet (a fresh unsaved model), we trigger the standard save flow
     * so the user can pick a location — the export can be re-invoked after.
     *
     * @param formatName display name for status messages
     * @return true if the export dialog should open
     */
    private boolean prepareForExport(String formatName) {
        if (modelState == null || !modelState.isModelLoaded()) {
            if (statusService != null) statusService.updateStatus("No model to export");
            return false;
        }

        String omoPath = modelState.getCurrentOMOFilePath();
        boolean hasOmoPath = omoPath != null && !omoPath.isBlank();

        if (!hasOmoPath) {
            // No OMO on disk yet — route through the normal save flow. The save
            // dialog is async, so the user invokes export again afterwards.
            if (modelOperations != null) {
                if (statusService != null) {
                    statusService.updateStatus("Save model as .OMO before exporting to ." + formatName);
                }
                modelOperations.saveModel();
            } else if (statusService != null) {
                statusService.updateStatus("Save model as .OMO before exporting to ." + formatName);
            }
            return false;
        }

        // Sync in-memory edits to the existing OMO so the export embeds fresh bytes.
        // Only save when dirty — avoids spurious disk writes on clean reloads.
        if (modelOperations != null && modelState.hasUnsavedChanges()) {
            modelOperations.saveModel();
        }
        return true;
    }
}
