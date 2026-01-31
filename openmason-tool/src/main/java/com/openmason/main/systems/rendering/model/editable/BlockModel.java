package com.openmason.main.systems.rendering.model.editable;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Concrete implementation of a simple single-cube block model.
 *
 * @deprecated Legacy block model support only. Used exclusively for loading old .OMO files (v1.0-2.0).
 *             Modern models should provide complete mesh topology via .OMO files instead of relying on
 *             single-cube dimensions. See {@link com.openmason.main.systems.rendering.model.io.omo.OMOFormat}
 *             for modern format with embedded mesh data.
 */
@Deprecated(since = "2.1", forRemoval = true)
public class BlockModel implements EditableModel {

    /** Model type identifier for single-cube blocks */
    public static final String MODEL_TYPE = "SINGLE_CUBE";

    /** Default name for new models */
    private static final String DEFAULT_NAME = "Untitled Block";

    private String name;
    private ModelGeometry geometry;
    private Path texturePath;
    private Path filePath;
    private boolean dirty;

    /**
     * Creates a new block model with default properties.
     * - Name: "Untitled Block"
     * - Geometry: 16x16x16 cube at origin
     * - No texture or file path
     * - Marked as dirty (unsaved)
     */
    public BlockModel() {
        this(DEFAULT_NAME, new CubeGeometry(), null);
    }

    /**
     * Creates a new block model with specified properties.
     */
    public BlockModel(String name, ModelGeometry geometry, Path texturePath) {
        setName(name);
        setGeometry(geometry);
        this.texturePath = texturePath;
        this.filePath = null;
        this.dirty = true; // New models are always dirty
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Model name cannot be null or empty");
        }
        if (!Objects.equals(this.name, name)) {
            this.name = name;
            markDirty();
        }
    }

    @Override
    public ModelGeometry getGeometry() {
        return geometry;
    }

    @Override
    public void setGeometry(ModelGeometry geometry) {
        if (geometry == null) {
            throw new IllegalArgumentException("Geometry cannot be null");
        }
        if (!Objects.equals(this.geometry, geometry)) {
            this.geometry = geometry;
            markDirty();
        }
    }

    @Override
    public Path getTexturePath() {
        return texturePath;
    }

    @Override
    public void setTexturePath(Path texturePath) {
        if (!Objects.equals(this.texturePath, texturePath)) {
            this.texturePath = texturePath;
            markDirty();
        }
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markClean() {
        this.dirty = false;
    }

    /**
     * Marks this model as dirty (having unsaved changes).
     * Called automatically when properties are modified.
     */
    private void markDirty() {
        this.dirty = true;
    }

    @Override
    public Path getFilePath() {
        return filePath;
    }

    @Override
    public void setFilePath(Path filePath) {
        // Setting file path doesn't mark dirty - it's part of save operation
        this.filePath = filePath;
    }

    @Override
    public String getModelType() {
        return MODEL_TYPE;
    }

    /**
     * Gets a display-friendly representation of this model.
     *
     * @return string like "Untitled Block (16x16x16) [unsaved]"
     */
    public String getDisplayName() {
        StringBuilder sb = new StringBuilder(name);
        sb.append(" (").append(geometry.getDescription()).append(")");
        if (isDirty()) {
            sb.append(" [unsaved]");
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockModel that)) return false;
        return Objects.equals(name, that.name) &&
               Objects.equals(geometry, that.geometry) &&
               Objects.equals(texturePath, that.texturePath) &&
               Objects.equals(filePath, that.filePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, geometry, texturePath, filePath);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
