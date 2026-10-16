package com.openmason.main.systems.menus.mainHub.services;

import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Business logic for template management: the templates the plugins contributed (#379), with
 * filtering and search. The core one is {@link #blankTemplate()}; game templates come with their
 * game target's plugin (Stonebreak's New Biome: {@code StonebreakGameTarget.newBiomeTemplate()}).
 */
public class TemplateService {

    private static final Logger logger = LoggerFactory.getLogger(TemplateService.class);

    private volatile Supplier<List<ProjectTemplate>> source;

    /** The core templates only (no plugin host). */
    public TemplateService() {
        List<ProjectTemplate> builtins = List.of(blankTemplate());
        this.source = () -> builtins;
    }

    /** Templates come from {@code source} (the registered templates), read on every query. */
    public void setSource(Supplier<List<ProjectTemplate>> source) {
        this.source = java.util.Objects.requireNonNull(source, "source");
        logger.info("Templates now come from the plugin registry");
    }

    /**
     * Get all available templates.
     */
    public List<ProjectTemplate> getAllTemplates() {
        return new ArrayList<>(source.get());
    }

    /**
     * Search templates by name or description.
     */
    public List<ProjectTemplate> search(String query) {
        if (query == null || query.isEmpty()) {
            return getAllTemplates();
        }

        String lowerQuery = query.toLowerCase();
        return source.get().stream()
                .filter(t -> t.getName().toLowerCase().contains(lowerQuery) ||
                           t.getDescription().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
    }

    /** Blank Template - the universal starting point (core). */
    public static ProjectTemplate blankTemplate() {
        return new ProjectTemplate.Builder()
                .id("blank-template")
                .name("Blank Template")
                .description("Start with a blank project. Access all Open Mason tools and create whatever you need.")
                .category("General")
                .type(ProjectTemplate.TemplateType.CUSTOM)
                .metadata("Tools", "All")
                .build();
    }
}
