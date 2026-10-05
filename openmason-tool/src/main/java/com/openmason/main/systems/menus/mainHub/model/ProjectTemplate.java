package com.openmason.main.systems.menus.mainHub.model;

import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable project template definition.
 * Represents a template that users can create new projects from.
 * Uses Builder pattern for flexible construction following best practices.
 */
public class ProjectTemplate {

    private final String id;
    private final String name;
    private final String description;
    private final String category;
    private final TemplateType type;
    private final Map<String, String> metadata;
    private final List<TemplateModel> models;
    private final String openOnCreate;

    /**
     * One part of a template model: a primitive of {@code size} (full extents, model
     * units — 1 = one block) centred at {@code position}.
     */
    public record TemplatePart(String name, PartShapeFactory.Shape shape, Vector3fc size, Vector3fc position) {
        public TemplatePart {
            Objects.requireNonNull(name, "Part name cannot be null");
            Objects.requireNonNull(shape, "Part shape cannot be null");
            size = new Vector3f(Objects.requireNonNull(size, "Part size cannot be null"));
            position = new Vector3f(Objects.requireNonNull(position, "Part position cannot be null"));
        }
    }

    /**
     * A blank model the template writes into a new project as {@code <name>.omo}. Its
     * geometry is either built from primitive {@code parts}, or copied from the mesh of one
     * of the game's block {@code .sbo} files ({@code gameBlockSbo}, a file name under
     * {@code sbo/blocks/}) with its materials dropped.
     */
    public record TemplateModel(String name, List<TemplatePart> parts, String gameBlockSbo) {
        public TemplateModel {
            Objects.requireNonNull(name, "Model name cannot be null");
            parts = parts == null ? List.of() : List.copyOf(parts);
            if (parts.isEmpty() == (gameBlockSbo == null)) {
                throw new IllegalArgumentException(
                        "Template model '" + name + "' needs either parts or an .sbo source, not both");
            }
        }
    }

    /**
     * Template types for different project categories.
     */
    public enum TemplateType {
        BASIC_3D_MODEL,
        ADVANCED_3D_MODEL,
        TEXTURE_PACK,
        BLOCK_SET,
        FULL_GAME_TEMPLATE,
        CUSTOM
    }

    private ProjectTemplate(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Template ID cannot be null");
        this.name = Objects.requireNonNull(builder.name, "Template name cannot be null");
        this.description = builder.description != null ? builder.description : "";
        this.category = builder.category != null ? builder.category : "General";
        this.type = Objects.requireNonNull(builder.type, "Template type cannot be null");
        this.metadata = new HashMap<>(builder.metadata);
        this.models = List.copyOf(builder.models);
        this.openOnCreate = builder.openOnCreate;
    }

    // Getters

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public TemplateType getType() {
        return type;
    }

    public Map<String, String> getMetadata() {
        return new HashMap<>(metadata);
    }

    public String getMetadataValue(String key) {
        return metadata.get(key);
    }

    /** Models written into a project created from this template (may be empty). */
    public List<TemplateModel> getModels() {
        return models;
    }

    /** Name of the model to open after creation, or null to open an empty editor. */
    public String getOpenOnCreate() {
        return openOnCreate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectTemplate that = (ProjectTemplate) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ProjectTemplate{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", category='" + category + '\'' +
                ", type=" + type +
                '}';
    }

    /**
     * Builder for constructing ProjectTemplate instances.
     */
    public static class Builder {
        private String id;
        private String name;
        private String description;
        private String category;
        private TemplateType type;
        private final Map<String, String> metadata = new HashMap<>();
        private final List<TemplateModel> models = new ArrayList<>();
        private String openOnCreate;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder type(TemplateType type) {
            this.type = type;
            return this;
        }

        public Builder metadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata.putAll(metadata);
            return this;
        }

        /** A single-part model: one block-sized {@code shape} named after the model. */
        public Builder model(String name, PartShapeFactory.Shape shape) {
            return model(name, new TemplatePart(name, shape, new Vector3f(1, 1, 1), new Vector3f()));
        }

        public Builder model(String name, TemplatePart... parts) {
            this.models.add(new TemplateModel(name, List.of(parts), null));
            return this;
        }

        /** A model whose geometry is copied, untextured, from a game block {@code .sbo}. */
        public Builder modelFromGameBlock(String name, String gameBlockSbo) {
            this.models.add(new TemplateModel(name, null, gameBlockSbo));
            return this;
        }

        public Builder openOnCreate(String modelName) {
            this.openOnCreate = modelName;
            return this;
        }

        public ProjectTemplate build() {
            return new ProjectTemplate(this);
        }
    }
}
