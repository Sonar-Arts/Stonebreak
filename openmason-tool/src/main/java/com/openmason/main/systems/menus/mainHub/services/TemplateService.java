package com.openmason.main.systems.menus.mainHub.services;

import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory.Shape;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Business logic for template management: the templates the plugins contributed (#379), with
 * filtering and search. The built-in ones are {@link #blankTemplate()} (core) and
 * {@link #newBiomeTemplate()} (the built-in Stonebreak plugin).
 */
public class TemplateService {

    private static final Logger logger = LoggerFactory.getLogger(TemplateService.class);

    private volatile Supplier<List<ProjectTemplate>> source;

    /** The built-in templates only (no plugin host). */
    public TemplateService() {
        List<ProjectTemplate> builtins = List.of(blankTemplate(), newBiomeTemplate());
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

    /** A biome's block set (Stonebreak). */
    public static ProjectTemplate newBiomeTemplate() {
        return new ProjectTemplate.Builder()
                .id("new-biome")
                .name("New Biome")
                .description("Start a biome's block set: blank Log, Planks, Stairs, Leaves, Sapling and Grass models, "
                        + "ready to shape and texture.")
                .category("Biome")
                .type(ProjectTemplate.TemplateType.BLOCK_SET)
                .metadata("Models", "Log, Planks, Stairs, Leaves, Sapling, Grass")
                .model("Log", Shape.CUBE)
                .model("Planks", Shape.CUBE)
                // The game's own stair shape (all wood stairs share it), without the wood texture.
                .modelFromGameBlock("Stairs", "SB_Oak_Stairs.sbo")
                .model("Leaves", Shape.CUBE)
                .model("Sapling", Shape.CROSS)
                .model("Grass", Shape.CUBE)
                .openOnCreate("Log")
                .build();
    }
}
