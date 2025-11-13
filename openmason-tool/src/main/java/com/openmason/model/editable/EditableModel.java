package com.openmason.model.editable;

import java.nio.file.Path;

/**
 * Interface for models that can be edited in Open Mason.
 *
 * <p>This interface provides the contract for any model that supports:
 * <ul>
 *   <li>Name and metadata editing</li>
 *   <li>Geometry manipulation (future: resize, reposition)</li>
 *   <li>Texture management (future: painting, UV mapping)</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe for UI operations.
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Interface Segregation - only essential editing methods</li>
 *   <li>SOLID: Dependency Inversion - depend on abstraction, not concrete types</li>
 *   <li>YAGNI: Only methods needed for current functionality</li>
 * </ul>
 *
 * @since 1.0
 */
public interface EditableModel {

    /**
     * Gets the display name of this model.
     *
     * @return the model's name, never null
     */
    String getName();

    /**
     * Sets the display name of this model.
     *
     * @param name the new name, must not be null or empty
     * @throws IllegalArgumentException if name is null or empty
     */
    void setName(String name);

    /**
     * Gets the geometry definition of this model.
     *
     * @return the model's geometry, never null
     */
    ModelGeometry getGeometry();

    /**
     * Sets the geometry definition of this model.
     *
     * @param geometry the new geometry, must not be null
     * @throws IllegalArgumentException if geometry is null
     */
    void setGeometry(ModelGeometry geometry);

    /**
     * Gets the path to the texture file (.OMT) associated with this model.
     *
     * @return the texture file path, or null if no texture is set
     */
    Path getTexturePath();

    /**
     * Sets the texture file path for this model.
     *
     * @param texturePath the path to a .OMT file, or null to clear texture
     */
    void setTexturePath(Path texturePath);

    /**
     * Checks if this model has unsaved changes.
     *
     * @return true if the model has been modified since last save
     */
    boolean isDirty();

    /**
     * Marks this model as clean (no unsaved changes).
     * Called after successful save operations.
     */
    void markClean();

    /**
     * Gets the file path where this model is saved.
     *
     * @return the .OMO file path, or null if never saved
     */
    Path getFilePath();

    /**
     * Sets the file path where this model is saved.
     *
     * @param filePath the .OMO file path, or null to clear
     */
    void setFilePath(Path filePath);

    /**
     * Gets the model type identifier.
     * Used for serialization and determining which editor to use.
     *
     * @return the model type (e.g., "SINGLE_CUBE", "MULTI_PART")
     */
    String getModelType();
}
