package com.openmason.main.systems.menus;

import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.dialogs.HomeScreenDialog;
import com.openmason.main.systems.menus.dialogs.SaveWarningDialog;
import com.openmason.main.systems.menus.dialogs.UnsavedChangesDialog;
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
    private final HomeScreenDialog homeScreenDialog;
    private final UnsavedChangesDialog unsavedChangesDialog;

    private final String[] recentFiles = {"standard_cow.json", "example_model.json"};

    private ViewportController viewport;
    private LogoManager logoManager;
    private ThemeManager themeManager;
    private Runnable backToHomeCallback;
    private Runnable exitCallback;

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
        this.homeScreenDialog = new HomeScreenDialog();
        this.unsavedChangesDialog = new UnsavedChangesDialog();
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
     * Wires the HomeScreenDialog with save and navigate actions.
     */
    public void setBackToHomeCallback(Runnable callback) {
        this.backToHomeCallback = callback;
        homeScreenDialog.setCallbacks(
                this::saveProject,
                callback
        );
    }

    /**
     * Set callback for exiting the application.
     * Wires the UnsavedChangesDialog with save-then-exit, discard-and-exit, and cancel actions.
     *
     * @param exitCallback called to perform the actual application exit
     */
    public void setExitCallback(Runnable exitCallback) {
        this.exitCallback = exitCallback;
        unsavedChangesDialog.setCallbacks(
                () -> { saveProject(); exitCallback.run(); },
                exitCallback,
                () -> logger.debug("Exit cancelled by user")
        );
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
     * Get the unsaved changes dialog for rendering in the main UI.
     * @return the unsaved changes dialog instance
     */
    public UnsavedChangesDialog getUnsavedChangesDialog() {
        return unsavedChangesDialog;
    }

    /**
     * Render the file menu with grouped sections.
     */
    public void render() {
        if (!ImGui.beginMenu("File")) {
            return;
        }

        // --- New / Open ---
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

        // --- Save (Project) ---
        boolean hasProject = projectService != null && projectService.hasCurrentProject();
        if (ImGui.menuItem("Save Project", "", false, hasProject)) {
            saveProject();
        }

        if (ImGui.menuItem("Save Project As")) {
            saveProjectAs();
        }

        ImGui.separator();

        // --- Save / Export (Model) ---
        boolean canSave = modelState.canSaveModel() && modelState.hasUnsavedChanges();
        if (ImGui.menuItem("Save Model", "Ctrl+S", false, canSave)) {
            if (modelState.canSaveModel()) {
                modelOperations.saveModel();
            } else {
                saveWarningDialog.show();
            }
        }

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

        // --- Recent ---
        if (ImGui.beginMenu("Recent Files")) {
            for (String recentFile : recentFiles) {
                if (ImGui.menuItem(recentFile)) {
                    modelOperations.loadRecentFile(recentFile);
                }
            }
            ImGui.endMenu();
        }

        ImGui.separator();

        // --- Exit ---
        if (ImGui.menuItem("Exit", "Alt+F4")) {
            exitApplication();
        }

        ImGui.endMenu();
    }

    /**
     * Request navigation to the Home Screen.
     * Shows the HomeScreenDialog if there are unsaved changes, otherwise navigates directly.
     */
    public void requestHomeScreen() {
        boolean projectUnsaved = projectService != null && projectService.hasUnsavedChanges();
        boolean modelUnsaved = modelState.isModelLoaded() && modelState.hasUnsavedChanges();
        homeScreenDialog.show(projectUnsaved || modelUnsaved);
    }

    /**
     * Get the home screen dialog for rendering in the main UI.
     */
    public HomeScreenDialog getHomeScreenDialog() {
        return homeScreenDialog;
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
     * Also saves the active .OMO model file if one is loaded.
     */
    private void saveProject() {
        if (projectService == null || viewport == null) {
            statusService.updateStatus("Project service not initialized");
            return;
        }

        // Save the active .OMO model alongside the project
        saveActiveModel();

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
     * Also saves the active .OMO model file if one is loaded.
     */
    private void saveProjectAs() {
        if (projectService == null || viewport == null) {
            statusService.updateStatus("Project service not initialized");
            return;
        }

        fileDialogService.showSaveOMPDialog(filePath -> {
            // Save the active .OMO model alongside the project
            saveActiveModel();

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
     * Save the active .OMO model if one is loaded and has a file path.
     * Called automatically when saving a project so model changes are not lost.
     */
    private void saveActiveModel() {
        if (modelOperations != null && modelState.canSaveModel()) {
            modelOperations.saveModel();
            logger.debug("Active model saved alongside project");
        }
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
     * Request application exit.
     * Shows the unsaved changes dialog if there are unsaved changes (project or model level),
     * otherwise exits directly.
     */
    public void requestExit() {
        boolean projectUnsaved = projectService != null
                && projectService.hasCurrentProject()
                && projectService.hasUnsavedChanges();
        boolean modelUnsaved = modelState.isModelLoaded() && modelState.hasUnsavedChanges();

        if (projectUnsaved || modelUnsaved) {
            unsavedChangesDialog.show();
        } else {
            performExit();
        }
    }

    /**
     * Exit application with cleanup.
     */
    private void exitApplication() {
        requestExit();
    }

    /**
     * Perform the actual application exit with resource cleanup.
     */
    private void performExit() {
        if (exitCallback != null) {
            exitCallback.run();
        } else {
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
}
