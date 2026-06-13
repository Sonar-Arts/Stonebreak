package com.openmason.main.systems.menus.mainHub.services;

import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Handles user actions from the hub (create project, open project, pick a
 * folder). Coordinates transitions and executes hub-level actions through
 * callbacks wired by the application shell.
 */
public class HubActionService {

    private static final Logger logger = LoggerFactory.getLogger(HubActionService.class);

    private BiConsumer<String, String> createProjectCallback;
    private Consumer<RecentProject> openProjectCallback;
    private Consumer<Consumer<String>> folderPicker;

    /**
     * Create a new project with the given name in the given directory. The
     * application pre-saves the project file and opens the editor.
     */
    public void createProject(String name, String directory) {
        if (name == null || name.isBlank() || directory == null || directory.isBlank()) {
            logger.warn("Cannot create project: name and directory are required");
            return;
        }
        logger.info("Creating project '{}' in {}", name, directory);
        if (createProjectCallback != null) {
            createProjectCallback.accept(name.trim(), directory.trim());
        } else {
            logger.warn("No create-project callback registered");
        }
    }

    /**
     * Open a recent project, passing it to the callback so the .OMP file can be
     * loaded.
     */
    public void openRecentProject(RecentProject project) {
        if (project == null) {
            logger.warn("Cannot open project: project is null");
            return;
        }
        logger.info("Opening recent project: {} ({})", project.getName(), project.getPath());
        if (openProjectCallback != null) {
            openProjectCallback.accept(project);
        } else {
            logger.warn("No open-project callback registered");
        }
    }

    /** Open the native folder picker; the chosen directory is sent to {@code onChosen}. */
    public void pickFolder(Consumer<String> onChosen) {
        if (folderPicker != null) {
            folderPicker.accept(onChosen);
        } else {
            logger.warn("No folder picker registered");
        }
    }

    /** Set the callback that creates+pre-saves a project (name, directory). */
    public void setCreateProjectCallback(BiConsumer<String, String> callback) {
        this.createProjectCallback = callback;
    }

    /** Set the callback for opening recent projects. */
    public void setOpenProjectCallback(Consumer<RecentProject> callback) {
        this.openProjectCallback = callback;
    }

    /** Set the folder-picker bridge: given a result consumer, show the dialog. */
    public void setFolderPicker(Consumer<Consumer<String>> picker) {
        this.folderPicker = picker;
    }
}
