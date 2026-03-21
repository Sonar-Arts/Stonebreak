package com.openmason.main.systems.menus.mainHub.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate.TemplateType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Business logic for recent projects management.
 * Persists recent projects as JSON to ~/.openmason/recent-projects.json.
 * Uses Jackson's tree model (JsonNode/ObjectNode) to avoid module reflection issues.
 */
public class RecentProjectsService {

    private static final Logger logger = LoggerFactory.getLogger(RecentProjectsService.class);
    private static final int MAX_RECENT_PROJECTS = 20;
    private static final String RECENT_PROJECTS_FILE = "recent-projects.json";
    private static final String CONFIG_DIR = ".openmason";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** Default template used for deserialized recent project entries. */
    private static final ProjectTemplate DEFAULT_TEMPLATE = new ProjectTemplate.Builder()
            .id("default")
            .name("Default")
            .type(TemplateType.BASIC_3D_MODEL)
            .build();

    private final List<RecentProject> recentProjects;
    private final Path recentProjectsFilePath;
    private final ObjectMapper objectMapper;

    public RecentProjectsService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        Path userHome = Paths.get(System.getProperty("user.home"));
        this.recentProjectsFilePath = userHome.resolve(CONFIG_DIR).resolve(RECENT_PROJECTS_FILE);

        this.recentProjects = loadRecentProjects();
        logger.info("Initialized RecentProjectsService with {} projects", recentProjects.size());
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
     * Add or update a project in the recent list.
     * If a project with the same path already exists, updates its last-opened time.
     * Caps the list at MAX_RECENT_PROJECTS entries.
     */
    public void addProject(RecentProject project) {
        if (project == null || project.getPath() == null || project.getPath().isBlank()) {
            return;
        }

        // Remove existing entry with same path (upsert behavior)
        recentProjects.removeIf(p -> p.getPath().equals(project.getPath()));

        // Add at the beginning
        recentProjects.addFirst(project);

        // Cap size
        while (recentProjects.size() > MAX_RECENT_PROJECTS) {
            recentProjects.removeLast();
        }

        saveRecentProjects();
    }

    /**
     * Add a project by path and name (convenience method).
     */
    public void addProject(String name, String path) {
        RecentProject project = new RecentProject.Builder(LocalDateTime.now(), DEFAULT_TEMPLATE)
                .id(UUID.randomUUID().toString())
                .name(name)
                .path(path)
                .description("")
                .build();
        addProject(project);
    }

    /**
     * Remove a project by ID.
     */
    public void removeProject(String id) {
        if (id == null) return;
        boolean removed = recentProjects.removeIf(p -> p.getId().equals(id));
        if (removed) {
            saveRecentProjects();
            logger.debug("Removed recent project: {}", id);
        }
    }

    /**
     * Load recent projects from JSON file using tree model.
     */
    private List<RecentProject> loadRecentProjects() {
        if (!Files.exists(recentProjectsFilePath)) {
            return new ArrayList<>();
        }

        try {
            JsonNode root = objectMapper.readTree(recentProjectsFilePath.toFile());
            if (root == null || !root.isArray()) {
                return new ArrayList<>();
            }

            List<RecentProject> projects = new ArrayList<>();
            for (JsonNode node : root) {
                try {
                    String id = text(node, "id", UUID.randomUUID().toString());
                    String name = text(node, "name", "Untitled");
                    String path = text(node, "path", "");
                    String lastOpenedStr = text(node, "lastOpened", null);
                    String description = text(node, "description", "");

                    LocalDateTime lastOpened = lastOpenedStr != null
                            ? LocalDateTime.parse(lastOpenedStr, TIMESTAMP_FORMAT)
                            : LocalDateTime.now();

                    RecentProject project = new RecentProject.Builder(lastOpened, DEFAULT_TEMPLATE)
                            .id(id)
                            .name(name)
                            .path(path)
                            .description(description)
                            .build();
                    projects.add(project);
                } catch (Exception e) {
                    logger.warn("Skipping invalid recent project entry: {}", e.getMessage());
                }
            }
            logger.debug("Loaded {} recent projects from {}", projects.size(), recentProjectsFilePath);
            return projects;
        } catch (IOException e) {
            logger.error("Failed to load recent projects from {}", recentProjectsFilePath, e);
            return new ArrayList<>();
        }
    }

    /**
     * Save recent projects to JSON file using tree model.
     */
    private void saveRecentProjects() {
        try {
            Path parentDir = recentProjectsFilePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            ArrayNode array = objectMapper.createArrayNode();
            for (RecentProject project : recentProjects) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("id", project.getId());
                node.put("name", project.getName());
                node.put("path", project.getPath());
                node.put("lastOpened", project.getLastOpened().format(TIMESTAMP_FORMAT));
                node.put("description", project.getDescription());
                if (project.getSourceTemplate() != null) {
                    node.put("sourceTemplate", project.getSourceTemplate().getName());
                }
                array.add(node);
            }

            objectMapper.writeValue(recentProjectsFilePath.toFile(), array);
            logger.debug("Saved {} recent projects to {}", recentProjects.size(), recentProjectsFilePath);
        } catch (IOException e) {
            logger.error("Failed to save recent projects to {}", recentProjectsFilePath, e);
        }
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : defaultValue;
    }
}
