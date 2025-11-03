package com.openmason.ui.hub.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable recent project entry.
 * Contains metadata for previously opened projects (mock data for Phase 1).
 *
 * Uses Builder pattern for flexible construction following best practices.
 */
public class RecentProject {

    private final String id;
    private final String name;
    private final String path;
    private final LocalDateTime lastOpened;
    private final String thumbnailPath;
    private final String description;
    private final ProjectTemplate sourceTemplate;

    private RecentProject(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Project ID cannot be null");
        this.name = Objects.requireNonNull(builder.name, "Project name cannot be null");
        this.path = builder.path != null ? builder.path : "";
        this.lastOpened = Objects.requireNonNull(builder.lastOpened, "Last opened time cannot be null");
        this.thumbnailPath = builder.thumbnailPath;
        this.description = builder.description != null ? builder.description : "";
        this.sourceTemplate = builder.sourceTemplate;
    }

    // Getters

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public LocalDateTime getLastOpened() {
        return lastOpened;
    }

    public String getThumbnailPath() {
        return thumbnailPath;
    }

    public String getDescription() {
        return description;
    }

    public ProjectTemplate getSourceTemplate() {
        return sourceTemplate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecentProject that = (RecentProject) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "RecentProject{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", path='" + path + '\'' +
                ", lastOpened=" + lastOpened +
                '}';
    }

    /**
     * Builder for constructing RecentProject instances.
     */
    public static class Builder {
        private String id;
        private String name;
        private String path;
        private LocalDateTime lastOpened;
        private String thumbnailPath;
        private String description;
        private ProjectTemplate sourceTemplate;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder lastOpened(LocalDateTime lastOpened) {
            this.lastOpened = lastOpened;
            return this;
        }

        public Builder thumbnailPath(String thumbnailPath) {
            this.thumbnailPath = thumbnailPath;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder sourceTemplate(ProjectTemplate sourceTemplate) {
            this.sourceTemplate = sourceTemplate;
            return this;
        }

        public RecentProject build() {
            return new RecentProject(this);
        }
    }
}
