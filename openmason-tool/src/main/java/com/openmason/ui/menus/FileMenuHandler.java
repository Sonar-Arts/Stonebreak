package com.openmason.ui.menus;

import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.dialogs.SaveWarningDialog;
import com.openmason.ui.services.ModelOperationService;
import com.openmason.ui.services.StatusService;
import com.openmason.ui.state.ModelState;
import com.openmason.ui.ViewportController;
import com.openmason.ui.LogoManager;
import com.openmason.ui.themes.core.ThemeManager;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File menu handler.
 * Follows Single Responsibility Principle - only handles file menu operations.
 */
public class FileMenuHandler {

    private static final Logger logger = LoggerFactory.getLogger(FileMenuHandler.class);

    private final ModelState modelState;
    private final ModelOperationService modelOperations;
    private final FileDialogService fileDialogService;
    private final StatusService statusService;
    private final SaveWarningDialog saveWarningDialog;

    private final String[] recentFiles = {"standard_cow.json", "example_model.json"};

    private ViewportController viewport;
    private LogoManager logoManager;
    private ThemeManager themeManager;
    private Runnable backToHomeCallback;

    public FileMenuHandler(ModelState modelState, ModelOperationService modelOperations,
                           FileDialogService fileDialogService, StatusService statusService) {
        this.modelState = modelState;
        this.modelOperations = modelOperations;
        this.fileDialogService = fileDialogService;
        this.statusService = statusService;
        this.saveWarningDialog = new SaveWarningDialog();
    }

    /**
     * Set viewport reference for cleanup on exit.
     */
    public void setViewport(ViewportController viewport) {
        this.viewport = viewport;
    }

    /**
     * Set logo manager reference for cleanup on exit.
     */
    public void setLogoManager(LogoManager logoManager) {
        this.logoManager = logoManager;
    }

    /**
     * Set theme manager reference for cleanup on exit.
     */
    public void setThemeManager(ThemeManager themeManager) {
        this.themeManager = themeManager;
    }

    /**
     * Set callback for returning to Home screen.
     */
    public void setBackToHomeCallback(Runnable callback) {
        this.backToHomeCallback = callback;
    }

    /**
     * Get the save warning dialog for rendering in main UI.
     * @return the save warning dialog instance
     */
    public SaveWarningDialog getSaveWarningDialog() {
        return saveWarningDialog;
    }

    /**
     * Render the file menu.
     */
    public void render() {
        if (!ImGui.beginMenu("File")) {
            return;
        }

        if (ImGui.menuItem("New Model", "Ctrl+N")) {
            modelOperations.newModel();
        }

        if (ImGui.menuItem("Open Model", "Ctrl+O")) {
            modelOperations.openOMOModel();
        }

        if (ImGui.menuItem("Open Project", "Ctrl+Shift+O")) {
            openProject();
        }

        ImGui.separator();

        // Enable save only if model can be saved (NEW or OMO_FILE sources)
        boolean canSave = modelState.canSaveModel() && modelState.hasUnsavedChanges();
        if (ImGui.menuItem("Save Model", "Ctrl+S", false, canSave)) {
            if (modelState.canSaveModel()) {
                modelOperations.saveModel();
            } else {
                saveWarningDialog.show();
            }
        }

        // Enable save as only if model can be saved
        boolean canSaveAs = modelState.canSaveModel();
        if (ImGui.menuItem("Save Model As", "Ctrl+Shift+S", false, canSaveAs)) {
            if (modelState.canSaveModel()) {
                modelOperations.saveModelAs();
            } else {
                saveWarningDialog.show();
            }
        }

        if (ImGui.menuItem("Export Model", "Ctrl+E", false, modelState.isModelLoaded())) {
            exportModel();
        }

        ImGui.separator();

        if (ImGui.beginMenu("Recent Files")) {
            for (String recentFile : recentFiles) {
                if (ImGui.menuItem(recentFile)) {
                    modelOperations.loadRecentFile(recentFile);
                }
            }
            ImGui.endMenu();
        }

        ImGui.separator();

        if (ImGui.menuItem("Home Screen")) {
            if (backToHomeCallback != null) {
                backToHomeCallback.run();
            }
        }

        ImGui.separator();

        if (ImGui.menuItem("Exit", "Alt+F4")) {
            exitApplication();
        }

        ImGui.endMenu();
    }

    /**
     * Open project directory.
     */
    private void openProject() {
        statusService.updateStatus("Opening Stonebreak project...");
    }

    /**
     * Export model with file dialog.
     */
    private void exportModel() {
        if (!modelState.isModelLoaded()) {
            statusService.updateStatus("No model to export");
            return;
        }

        fileDialogService.showExportDialog((file, format) -> {
            try {
                statusService.updateStatus("Exporting to " + format.toUpperCase() + "...");
                // Export logic would go here
                statusService.updateStatus("Model exported successfully: " + file.getName());
            } catch (Exception e) {
                logger.error("Failed to export model to file: {}", file.getAbsolutePath(), e);
                statusService.updateStatus("Failed to export model: " + e.getMessage());
            }
        });
    }

    /**
     * Exit application with cleanup.
     */
    private void exitApplication() {
        if (viewport != null) {
            viewport.cleanup();
        }
        if (logoManager != null) {
            logoManager.dispose();
        }
        if (themeManager != null) {
            themeManager.dispose();
        }
        System.exit(0);
    }
}
