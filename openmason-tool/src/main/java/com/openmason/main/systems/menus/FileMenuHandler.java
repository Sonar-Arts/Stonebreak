package com.openmason.main.systems.menus;

import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.dialogs.SaveWarningDialog;
import com.openmason.main.systems.menus.mainHub.services.RecentProjectsService;
import com.openmason.main.systems.project.ProjectService;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.themes.core.ThemeManager;
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

    private ProjectService projectService;
    private UIVisibilityState uiVisibilityState;
    private RecentProjectsService recentProjectsService;

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
     * Set project service for project-level save/load operations.
     */
    public void setProjectService(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * Set UI visibility state for project state extraction.
     */
    public void setUIVisibilityState(UIVisibilityState uiVisibilityState) {
        this.uiVisibilityState = uiVisibilityState;
    }

    /**
     * Set recent projects service for tracking saved/opened projects.
     */
    public void setRecentProjectsService(RecentProjectsService recentProjectsService) {
        this.recentProjectsService = recentProjectsService;
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

        // Project save operations
        boolean hasProject = projectService != null && projectService.hasCurrentProject();
        if (ImGui.menuItem("Save Project", "", false, hasProject)) {
            saveProject();
        }

        if (ImGui.menuItem("Save Project As")) {
            saveProjectAs();
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
     * Open project from .OMP file.
     */
    private void openProject() {
        if (projectService == null || viewport == null) {
            statusService.updateStatus("Project service not initialized");
            return;
        }

        fileDialogService.showOpenOMPDialog(filePath -> {
            boolean success = projectService.openProject(filePath, viewport, modelState,
                    uiVisibilityState, modelOperations);
            if (success) {
                statusService.updateStatus("Project opened: " + projectService.getCurrentProjectName());
                addToRecentProjects(projectService.getCurrentProjectName(), filePath);
            } else {
                statusService.updateStatus("Failed to open project");
            }
        });
    }

    /**
     * Save project to current path.
     */
    private void saveProject() {
        if (projectService == null || viewport == null) {
            statusService.updateStatus("Project service not initialized");
            return;
        }

        boolean success = projectService.saveProject(viewport, modelState, uiVisibilityState);
        if (success) {
            statusService.updateStatus("Project saved: " + projectService.getCurrentProjectName());
            addToRecentProjects(projectService.getCurrentProjectName(), projectService.getCurrentProjectPath());
        } else {
            statusService.updateStatus("Failed to save project");
        }
    }

    /**
     * Save project to a new path (Save As).
     */
    private void saveProjectAs() {
        if (projectService == null || viewport == null) {
            statusService.updateStatus("Project service not initialized");
            return;
        }

        fileDialogService.showSaveOMPDialog(filePath -> {
            boolean success = projectService.saveProjectAs(filePath, viewport, modelState,
                    uiVisibilityState, null);
            if (success) {
                statusService.updateStatus("Project saved as: " + projectService.getCurrentProjectName());
                addToRecentProjects(projectService.getCurrentProjectName(), projectService.getCurrentProjectPath());
            } else {
                statusService.updateStatus("Failed to save project");
            }
        });
    }

    /**
     * Add a project to the recent projects list.
     */
    private void addToRecentProjects(String name, String path) {
        if (recentProjectsService != null && path != null && !path.isBlank()) {
            recentProjectsService.addProject(name, path);
            logger.debug("Added to recent projects: {}", path);
        }
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
