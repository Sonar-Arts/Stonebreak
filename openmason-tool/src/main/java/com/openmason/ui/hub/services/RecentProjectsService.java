package com.openmason.ui.hub.services;

import com.openmason.ui.hub.model.ProjectTemplate;
import com.openmason.ui.hub.model.RecentProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Business logic for recent projects management.
 * Provides mock recent project data for Phase 1 (no actual file I/O).
 */
public class RecentProjectsService {

    private static final Logger logger = LoggerFactory.getLogger(RecentProjectsService.class);

    private final List<RecentProject> recentProjects;
    private final TemplateService templateService;

    public RecentProjectsService(TemplateService templateService) {
        this.templateService = templateService;
        this.recentProjects = createMockRecentProjects();
        logger.info("Initialized RecentProjectsService with {} mock projects", recentProjects.size());
    }

    /**
     * Get all recent projects, sorted by last opened date (most recent first).
     */
    public List<RecentProject> getRecentProjects() {
        return recentProjects.stream()
                .sorted(Comparator.comparing(RecentProject::getLastOpened).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Search recent projects by name or description.
     */
    public List<RecentProject> search(String query) {
        if (query == null || query.isEmpty()) {
            return getRecentProjects();
        }

        String lowerQuery = query.toLowerCase();
        return recentProjects.stream()
                .filter(p -> p.getName().toLowerCase().contains(lowerQuery) ||
                           p.getDescription().toLowerCase().contains(lowerQuery))
                .sorted(Comparator.comparing(RecentProject::getLastOpened).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Get project by ID.
     */
    public RecentProject getProjectById(String id) {
        return recentProjects.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * Create mock recent projects for Phase 1.
     * Returns empty list - will be populated when actual project saving is implemented.
     */
    private List<RecentProject> createMockRecentProjects() {
        List<RecentProject> projects = new ArrayList<>();
        // No mock projects - only saved projects will appear here in the future
        return projects;
    }
}
