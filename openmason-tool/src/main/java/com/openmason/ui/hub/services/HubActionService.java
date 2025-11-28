package com.openmason.ui.hub.services;

import com.openmason.ui.hub.model.ProjectTemplate;
import com.openmason.ui.hub.model.RecentProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles user actions from hub (create project, open project, etc.).
 * Coordinates transitions and executes hub-level actions.
 */
public class HubActionService {

    private static final Logger logger = LoggerFactory.getLogger(HubActionService.class);

    private Runnable createProjectCallback;
    private Runnable openProjectCallback;

    /**
     * Create a new project from the selected template.
     * Phase 1: Direct transition to appropriate tool.
     * Future: Create actual project files and open them.
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
     * Phase 1: Transition to appropriate tool based on project type.
     * Future: Load actual project files.
     */
    public void openRecentProject(RecentProject project) {
        if (project == null) {
            logger.warn("Cannot open project: project is null");
            return;
        }

        logger.info("Opening recent project: {}", project.getName());

        if (openProjectCallback != null) {
            openProjectCallback.run();
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
     */
    public void setOpenProjectCallback(Runnable callback) {
        this.openProjectCallback = callback;
    }

}
