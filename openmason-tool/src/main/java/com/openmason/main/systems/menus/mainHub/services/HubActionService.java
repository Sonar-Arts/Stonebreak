package com.openmason.main.systems.menus.mainHub.services;

import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Handles user actions from hub (create project, open project, etc.).
 * Coordinates transitions and executes hub-level actions.
 */
public class HubActionService {

    private static final Logger logger = LoggerFactory.getLogger(HubActionService.class);

    private Runnable createProjectCallback;
    private Consumer<RecentProject> openProjectCallback;

    /**
     * Create a new project from the selected template.
     */
    public void createProjectFromTemplate(ProjectTemplate template) {
        if (template == null) {
            logger.warn("Cannot create project: template is null");
            return;
        }

        logger.info("Creating project from template: {}", template.getName());

        if (createProjectCallback != null) {
            createProjectCallback.run();
        } else {
            logger.warn("No create project callback registered");
        }
    }

    /**
     * Open a recent project.
     * Passes the project to the callback so the .OMP file can be loaded.
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
            logger.warn("No open project callback registered");
        }
    }

    /**
     * Set callback for project creation.
     */
    public void setCreateProjectCallback(Runnable callback) {
        this.createProjectCallback = callback;
    }

    /**
     * Set callback for opening projects.
     * Receives the RecentProject so the caller can load the .OMP file.
     */
    public void setOpenProjectCallback(Consumer<RecentProject> callback) {
        this.openProjectCallback = callback;
    }

}
