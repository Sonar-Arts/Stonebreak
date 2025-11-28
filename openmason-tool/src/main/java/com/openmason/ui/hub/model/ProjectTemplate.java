package com.openmason.ui.hub.model;

import java.util.HashMap;
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

        public ProjectTemplate build() {
            return new ProjectTemplate(this);
        }
    }
}
