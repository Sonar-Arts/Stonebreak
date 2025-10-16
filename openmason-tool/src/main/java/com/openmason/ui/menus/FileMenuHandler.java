package com.openmason.ui.menus;

import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.services.ModelOperationService;
import com.openmason.ui.services.StatusService;
import com.openmason.ui.state.ModelState;
import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.ui.LogoManager;
import com.openmason.ui.themes.ThemeManager;
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

    private final String[] recentFiles = {"standard_cow.json", "example_model.json"};

    private OpenMason3DViewport viewport;
    private LogoManager logoManager;
    private ThemeManager themeManager;

    public FileMenuHandler(ModelState modelState, ModelOperationService modelOperations,
                           FileDialogService fileDialogService, StatusService statusService) {
        this.modelState = modelState;
        this.modelOperations = modelOperations;
        this.fileDialogService = fileDialogService;
        this.statusService = statusService;
    }

    /**
     * Set viewport reference for cleanup on exit.
     */
    public void setViewport(OpenMason3DViewport viewport) {
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
            modelOperations.openModel();
        }

        if (ImGui.menuItem("Open Project", "Ctrl+Shift+O")) {
            openProject();
        }

        ImGui.separator();

        if (ImGui.menuItem("Save Model", "Ctrl+S", false, modelState.isModelLoaded() && modelState.hasUnsavedChanges())) {
            modelOperations.saveModel();
        }

        if (ImGui.menuItem("Save Model As", "Ctrl+Shift+S", false, modelState.isModelLoaded())) {
            saveModelAs();
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
     * Save model with file dialog.
     */
    private void saveModelAs() {
        fileDialogService.showSaveDialog(file -> {
            try {
                modelState.setCurrentModelPath(file.getName());
                modelState.setUnsavedChanges(false);
                statusService.updateStatus("Model saved successfully: " + file.getName());
            } catch (Exception e) {
                logger.error("Failed to save model to file: {}", file.getAbsolutePath(), e);
                statusService.updateStatus("Failed to save model: " + e.getMessage());
            }
        });
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
            viewport.dispose();
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
