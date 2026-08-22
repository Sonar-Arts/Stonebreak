package com.openmason.main.systems;

import com.openmason.main.systems.menus.FileMenuHandler;
import com.openmason.main.systems.menus.mainHub.services.RecentProjectsService;
import com.openmason.main.systems.project.OMPFormat;
import com.openmason.main.systems.project.ProjectService;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Project lifecycle for the main editor shell: owns the {@link ProjectService} and the
 * hub-driven new/open flows, wires the File menu's open/save/save-as/recent-projects
 * and dirty-state (unsaved changes / exit) handling, fires project-session
 * boundaries, and re-roots the project browser whenever the project path changes.
 * Extracted from {@link MainImGuiInterface}; the save/open flows are unchanged.
 */
public final class ProjectLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(ProjectLifecycle.class);

    private final ProjectService projectService;
    private final ModelState modelState;
    private final UIVisibilityState uiVisibilityState;
    private final ModelOperationService modelOperations;
    private final StatusService statusService;

    // Wired after the shell's components exist
    private ViewportController viewport3D;
    private FileMenuHandler fileMenuHandler;

    /** Re-roots the project browser after the current project path changed. */
    private Runnable onProjectPathChanged;

    /**
     * Invoked whenever the project session changes (new, open, or return to the hub).
     * The Scene Viewer uses it to drop the open scene — a scene references models by
     * project-relative path, so carrying one into a different project would leave it
     * pointing at files that are not there.
     */
    private Runnable onProjectSessionReset;

    public ProjectLifecycle(ProjectService projectService, ModelState modelState,
                            UIVisibilityState uiVisibilityState,
                            ModelOperationService modelOperations, StatusService statusService) {
        if (projectService == null) {
            throw new IllegalArgumentException("ProjectService cannot be null");
        }
        this.projectService = projectService;
        this.modelState = modelState;
        this.uiVisibilityState = uiVisibilityState;
        this.modelOperations = modelOperations;
        this.statusService = statusService;
    }

    public ProjectService getProjectService() {
        return projectService;
    }

    /** The viewport whose camera/transform state is persisted in the project file. */
    public void setViewport(ViewportController viewport3D) {
        this.viewport3D = viewport3D;
    }

    /** Called when the current project path changed (open, save-as, new, clear). */
    public void setOnProjectPathChanged(Runnable callback) {
        this.onProjectPathChanged = callback;
    }

    /**
     * Wire the File menu's project actions (open/save/save-as, recent projects, unsaved
     * changes and exit prompts). A session boundary (File > Open Project) drops the
     * outgoing scene BEFORE the new project loads, so openProject can restore the
     * incoming one. Save As is only a path change: the session continues, the scene
     * stays, the browser re-roots.
     */
    public void bindFileMenu(FileMenuHandler fileMenuHandler) {
        this.fileMenuHandler = fileMenuHandler;
        if (fileMenuHandler == null) {
            return;
        }
        fileMenuHandler.setProjectService(projectService);
        fileMenuHandler.setOnProjectSessionBoundary(this::notifyProjectSessionReset);
        fileMenuHandler.setOnProjectPathChanged(this::refreshProjectBrowserRoot);
    }

    /**
     * Supplier of the open project's root folder (the directory the .OMP lives in),
     * or null when no project is loaded. Shared with other save dialogs (e.g. the
     * texture editor) so their file dialogs start in the project root too.
     */
    public Supplier<String> getProjectDirectorySupplier() {
        return () -> {
            if (projectService == null || !projectService.hasCurrentProject()) {
                return null;
            }
            String ompPath = projectService.getCurrentProjectPath();
            if (ompPath == null || ompPath.isBlank()) {
                return null;
            }
            Path parent = Path.of(ompPath).getParent();
            return parent != null ? parent.toString() : null;
        };
    }

    /**
     * Wire the project file's scene node to the scene layer: {@code saveSupplier} is read
     * on every project save (which scene is open + which centre tab is in front), and
     * {@code restoreHook} runs after a project opens (null reference = pre-1.2 file).
     */
    public void setSceneSessionHooks(Supplier<OMPFormat.SceneReference> saveSupplier,
                                     Consumer<OMPFormat.SceneReference> restoreHook) {
        if (projectService != null) {
            projectService.setSceneStateSupplier(saveSupplier);
            projectService.setSceneRestoreHook(restoreHook);
        }
    }

    /**
     * Set the recent projects service for tracking project open/save in the hub.
     */
    public void setRecentProjectsService(RecentProjectsService recentProjectsService) {
        if (fileMenuHandler != null) {
            fileMenuHandler.setRecentProjectsService(recentProjectsService);
        }
    }

    /**
     * Set callback for application exit. Wires the unsaved changes dialog.
     *
     * @param exitCallback called to perform the actual application exit
     */
    public void setExitCallback(Runnable exitCallback) {
        if (fileMenuHandler != null) {
            fileMenuHandler.setExitCallback(exitCallback);
        }
    }

    /**
     * Request application exit through the file menu handler.
     * Shows the unsaved changes dialog if there are unsaved changes.
     */
    public void requestExit() {
        if (fileMenuHandler != null) {
            fileMenuHandler.requestExit();
        }
    }

    /** Register the listener fired on every project-session boundary. */
    public void setOnProjectSessionReset(Runnable callback) {
        this.onProjectSessionReset = callback;
    }

    /** Fired on every project-session boundary. */
    public void notifyProjectSessionReset() {
        if (onProjectSessionReset != null) {
            onProjectSessionReset.run();
        }
    }

    /**
     * Forget the current project for a fresh session (new blank project from the hub):
     * clears the service, fires the session boundary and re-roots the browser.
     */
    public void clearForNewSession() {
        if (projectService != null) {
            projectService.clearCurrentProject();
        }
        notifyProjectSessionReset();
        refreshProjectBrowserRoot();
    }

    /**
     * Create a blank project and pre-save it to {@code ompFilePath} so the
     * file exists on disk the moment the editor opens. Assumes the editor was
     * just reset to a fresh blank session.
     *
     * @return true if the project file was written
     */
    public boolean saveNewProject(String projectName, String ompFilePath) {
        if (projectService == null || viewport3D == null) {
            logger.warn("Cannot create project: service or viewport not initialized");
            return false;
        }
        boolean success = projectService.saveProjectAs(ompFilePath, viewport3D, modelState,
                uiVisibilityState, projectName);
        if (success) {
            statusService.updateStatus("Project created: " + projectName);
            logger.info("New project pre-saved: {}", ompFilePath);
        } else {
            statusService.updateStatus("Failed to create project: " + ompFilePath);
        }
        refreshProjectBrowserRoot();
        return success;
    }

    /**
     * Open a project from the Project Hub by loading an .OMP file.
     * Called when the user selects a recent project from the hub.
     *
     * @param ompFilePath the path to the .OMP project file
     */
    public void openProjectFromHub(String ompFilePath) {
        if (projectService == null || viewport3D == null) {
            logger.warn("Cannot open project: service or viewport not initialized");
            return;
        }

        // Drop the previous project's scene first: its models are resolved against the
        // old project root and would otherwise linger, referencing files that are gone.
        notifyProjectSessionReset();

        boolean success = projectService.openProject(ompFilePath, viewport3D, modelState,
                uiVisibilityState, modelOperations);
        if (success) {
            statusService.updateStatus("Project opened: " + projectService.getCurrentProjectName());
            logger.info("Project loaded from hub: {}", ompFilePath);
        } else {
            statusService.updateStatus("Failed to open project: " + ompFilePath);
        }
        refreshProjectBrowserRoot();
    }

    /** Re-root the project browser after the current project path changed. */
    private void refreshProjectBrowserRoot() {
        if (onProjectPathChanged != null) {
            onProjectPathChanged.run();
        }
    }
}
