package com.openmason.main.systems.menus;

import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.dialogs.HomeScreenDialog;
import com.openmason.main.systems.menus.dialogs.UnsavedChangesDialog;
import com.openmason.main.systems.menus.mainHub.services.RecentProjectsService;
import com.openmason.main.systems.project.ProjectService;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import com.openmason.main.systems.viewport.ViewportKeybindActions;
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
    private final HomeScreenDialog homeScreenDialog;
    private final UnsavedChangesDialog unsavedChangesDialog;

    private ViewportController viewport;
    private LogoManager logoManager;
    private ThemeManager themeManager;
    private Runnable backToHomeCallback;
    private Runnable exitCallback;

    private ProjectService projectService;
    private Runnable onProjectPathChanged;
    private UIVisibilityState uiVisibilityState;
    private RecentProjectsService recentProjectsService;

    /**
     * What a project save, exit or Home needs from the UI workspace (#293), supplied by the app so
     * this handler need not know the editor. The UI documents' own New/Open/Save/Export live in
     * the UI workspace's toolbar, not this menu.
     */
    public interface UiMenuHooks {

        boolean anyDirty();

        /**
         * Saves every UI document with unsaved work (and the editor's view state of the rest)
         * that has, or can derive, a location.
         *
         * @return one line per document that was not saved (empty when all were)
         */
        java.util.List<String> saveAllInPlace();

        /** Drops open UI documents without asking (the user chose to discard). */
        void discardAll();
    }

    private UiMenuHooks uiHooks;

    public void setUiHooks(UiMenuHooks hooks) {
        this.uiHooks = hooks;
    }

    public FileMenuHandler(ModelState modelState, ModelOperationService modelOperations,
                           FileDialogService fileDialogService, StatusService statusService) {
        this.modelState = modelState;
        this.modelOperations = modelOperations;
        this.fileDialogService = fileDialogService;
        this.statusService = statusService;
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
                () -> {
                    if (uiHooks != null) {
                        uiHooks.discardAll(); // "Don't Save" also drops UI recovery snapshots
                    }
                    exitCallback.run();
                },
                () -> logger.debug("Exit cancelled by user")
        );
    }

    /**
     * Set project service for project-level save/load operations.
     */
    // Scene actions, supplied by the app so this handler need not know the scene layer.
    private Runnable onNewScene;
    private Runnable onOpenScene;
    private Runnable onSaveScene;
    private Runnable onSaveSceneAs;
    private java.util.function.BooleanSupplier sceneDirtySupplier;

    /**
     * Fired just before a different project is opened, so the scene layer can drop the
     * outgoing project's scene. Separate from {@link #onProjectPathChanged}: that one
     * also fires on Save As, where the session continues and the scene must survive.
     */
    private Runnable onProjectSessionBoundary;

    /** Saves the open scene alongside the project, the way {@link #saveActiveModel} saves the model. */
    private Runnable onSaveOpenScene;

    public void setSceneActions(Runnable newScene, Runnable openScene,
                                Runnable saveScene, Runnable saveSceneAs,
                                java.util.function.BooleanSupplier sceneDirty) {
        this.onNewScene = newScene;
        this.onOpenScene = openScene;
        this.onSaveScene = saveScene;
        this.onSaveSceneAs = saveSceneAs;
        this.sceneDirtySupplier = sceneDirty;
    }

    public void setOnProjectSessionBoundary(Runnable callback) {
        this.onProjectSessionBoundary = callback;
    }

    public void setOnSaveOpenScene(Runnable callback) {
        this.onSaveOpenScene = callback;
    }

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
     * Set the callback fired whenever the current project path changes
     * (open, save-as), so dependents like the Project Browser can re-root.
     */
    public void setOnProjectPathChanged(Runnable callback) {
        this.onProjectPathChanged = callback;
    }

    private void notifyProjectPathChanged() {
        if (onProjectPathChanged != null) {
            onProjectPathChanged.run();
        }
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
        if (ImGui.menuItem("New Model")) {
            modelOperations.newModel();
        }

        if (ImGui.menuItem("Open Model...", shortcut(ViewportKeybindActions.OPEN_MODEL))) {
            modelOperations.openOMOModel();
        }

        if (ImGui.menuItem("Open Project...")) {
            openProject();
        }

        ImGui.separator();

        // --- Save (Model) ---
        boolean canSave = modelState.canSaveModel() && modelState.hasUnsavedChanges();
        if (ImGui.menuItem("Save Model", shortcut(ViewportKeybindActions.SAVE_MODEL), false, canSave)) {
            modelOperations.saveModel();
        }

        boolean canSaveAs = modelState.canSaveModel();
        if (ImGui.menuItem("Save Model As...", "", false, canSaveAs)) {
            modelOperations.saveModelAs();
        }

        ImGui.separator();

        // --- Scene ---
        if (ImGui.menuItem("New Scene")) {
            if (onNewScene != null) onNewScene.run();
        }

        if (ImGui.menuItem("Open Scene...")) {
            if (onOpenScene != null) onOpenScene.run();
        }

        boolean sceneDirty = sceneDirtySupplier != null && sceneDirtySupplier.getAsBoolean();
        if (ImGui.menuItem("Save Scene", "", false, sceneDirty)) {
            if (onSaveScene != null) onSaveScene.run();
        }

        if (ImGui.menuItem("Save Scene As...")) {
            if (onSaveSceneAs != null) onSaveSceneAs.run();
        }

        ImGui.separator();

        // --- Save (Project) ---
        boolean hasProject = projectService != null && projectService.hasCurrentProject();
        if (ImGui.menuItem("Save Project", "", false, hasProject)) {
            saveProject();
        }

        if (ImGui.menuItem("Save Project As...")) {
            saveProjectAs();
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
        homeScreenDialog.show(projectUnsaved || modelUnsaved || isSceneDirty() || isUiDirty());
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

        // Opening replaces the session, so unsaved work gets the same prompt as exit.
        if (hasAnyUnsavedChanges()) {
            unsavedChangesDialog.showOnce(
                    () -> { saveProject(); showOpenProjectDialog(); },
                    this::showOpenProjectDialog,
                    () -> logger.debug("Open project cancelled by user"));
            return;
        }
        showOpenProjectDialog();
    }

    private void showOpenProjectDialog() {
        fileDialogService.showOpenOMPDialog(filePath -> {
            // Drop the outgoing project's scene BEFORE opening: its models resolve
            // against the old root, and openProject restores the new project's own scene.
            if (onProjectSessionBoundary != null) {
                onProjectSessionBoundary.run();
            }
            boolean success = projectService.openProject(filePath, viewport, modelState,
                    uiVisibilityState, modelOperations);
            if (success) {
                statusService.updateStatus("Project opened: " + projectService.getCurrentProjectName());
                addToRecentProjects(projectService.getCurrentProjectName(), filePath);
                notifyProjectPathChanged();
            } else {
                statusService.updateStatus("Failed to open project");
            }
        });
    }

    /** Project, active model, or open scene dirty. */
    private boolean hasAnyUnsavedChanges() {
        boolean projectUnsaved = projectService != null
                && projectService.hasCurrentProject()
                && projectService.hasUnsavedChanges();
        boolean modelUnsaved = modelState.isModelLoaded() && modelState.hasUnsavedChanges();
        return projectUnsaved || modelUnsaved || isSceneDirty() || isUiDirty();
    }

    private boolean isUiDirty() {
        return uiHooks != null && uiHooks.anyDirty();
    }

    private boolean isSceneDirty() {
        return sceneDirtySupplier != null && sceneDirtySupplier.getAsBoolean();
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

        // Save the active .OMO model and the open scene alongside the project
        saveActiveModel();
        saveOpenScene();

        boolean success = projectService.saveProject(viewport, modelState, uiVisibilityState);
        if (success) {
            statusService.updateStatus("Project saved: " + projectService.getCurrentProjectName() + uiSaveSuffix());
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
            // Save the active .OMO model and the open scene alongside the project
            saveActiveModel();
            saveOpenScene();

            boolean success = projectService.saveProjectAs(filePath, viewport, modelState,
                    uiVisibilityState, null);
            if (success) {
                statusService.updateStatus("Project saved as: " + projectService.getCurrentProjectName()
                        + uiSaveSuffix());
                addToRecentProjects(projectService.getCurrentProjectName(), projectService.getCurrentProjectPath());
                notifyProjectPathChanged();
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
     * Save the open scene if the app wired a handler for it. The handler decides whether
     * there is anything to save (a dirty scene that already has a file); a never-saved
     * scene is left alone rather than interrupting the project save with a dialog.
     */
    private void saveOpenScene() {
        if (onSaveOpenScene != null) {
            onSaveOpenScene.run();
        }
        uiSaveFailures = uiHooks != null ? uiHooks.saveAllInPlace() : java.util.List.of();
    }

    /** UI documents the last project save could not save, shown with its status (never silently). */
    private java.util.List<String> uiSaveFailures = java.util.List.of();

    private String uiSaveSuffix() {
        if (uiSaveFailures == null || uiSaveFailures.isEmpty()) {
            return "";
        }
        return " - but " + uiSaveFailures.size() + " UI document(s) were NOT saved: " + String.join("; ", uiSaveFailures);
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
     * Request application exit.
     * Shows the unsaved changes dialog if there are unsaved changes (project or model level),
     * otherwise exits directly.
     */
    public void requestExit() {
        if (hasAnyUnsavedChanges()) {
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

    /** Shortcut column from the keybind registry, so a rebind never leaves it stale. */
    private static String shortcut(String actionId) {
        return KeybindRegistry.getInstance().getShortcutDisplayName(actionId);
    }
}
