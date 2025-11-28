package com.openmason.ui.hub.services;

import com.openmason.ui.hub.model.ProjectTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Business logic for template management.
 * Provides template data with filtering and search capabilities.
 */
public class TemplateService {

    private static final Logger logger = LoggerFactory.getLogger(TemplateService.class);

    private final List<ProjectTemplate> templates;

    public TemplateService() {
        this.templates = createBuiltInTemplates();
        logger.info("Initialized TemplateService with {} templates", templates.size());
    }

    /**
     * Get all available templates.
     */
    public List<ProjectTemplate> getAllTemplates() {
        return new ArrayList<>(templates);
    }

    /**
     * Search templates by name or description.
     */
    public List<ProjectTemplate> search(String query) {
        if (query == null || query.isEmpty()) {
            return getAllTemplates();
        }

        String lowerQuery = query.toLowerCase();
        return templates.stream()
                .filter(t -> t.getName().toLowerCase().contains(lowerQuery) ||
                           t.getDescription().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
    }

    /**
     * Create built-in templates for Phase 1.
     */
    private List<ProjectTemplate> createBuiltInTemplates() {
        List<ProjectTemplate> templates = new ArrayList<>();

        // Blank Template - single universal template
        templates.add(new ProjectTemplate.Builder()
                .id("blank-template")
                .name("Blank Template")
                .description("Start with a blank project. Access all Open Mason tools and create whatever you need.")
                .category("General")
                .type(ProjectTemplate.TemplateType.CUSTOM)
                .metadata("Tools", "All")
                .build()
        );

        return templates;
    }
}
