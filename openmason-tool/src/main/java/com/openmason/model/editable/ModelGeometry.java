package com.openmason.model.editable;

/**
 * Interface for model geometry definitions.
 *
 * <p>Represents the spatial and dimensional properties of a model.
 * Current implementation focuses on simple cuboid geometry, but the
 * interface allows for future extension to complex multi-part models.
 *
 * <p>Coordinates use the Minecraft/Stonebreak coordinate system:
 * <ul>
 *   <li>X: East (positive) / West (negative)</li>
 *   <li>Y: Up (positive) / Down (negative)</li>
 *   <li>Z: South (positive) / North (negative)</li>
 * </ul>
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Single Responsibility - only geometry data, no rendering logic</li>
 *   <li>SOLID: Open/Closed - extensible for future geometry types</li>
 *   <li>KISS: Simple interface for current needs</li>
 * </ul>
 *
 * @since 1.0
 */
public interface ModelGeometry {

    /**
     * Gets the width (X dimension) of this geometry.
     *
     * @return width in pixels (typically 16 for block models)
     */
    int getWidth();

    /**
     * Gets the height (Y dimension) of this geometry.
     *
     * @return height in pixels (typically 16 for block models)
     */
    int getHeight();

    /**
     * Gets the depth (Z dimension) of this geometry.
     *
     * @return depth in pixels (typically 16 for block models)
     */
    int getDepth();

    /**
     * Gets the X position offset from origin.
     *
     * @return X coordinate
     */
    double getX();

    /**
     * Gets the Y position offset from origin.
     *
     * @return Y coordinate
     */
    double getY();

    /**
     * Gets the Z position offset from origin.
     *
     * @return Z coordinate
     */
    double getZ();

    /**
     * Sets the dimensions of this geometry.
     *
     * @param width the width in pixels, must be positive
     * @param height the height in pixels, must be positive
     * @param depth the depth in pixels, must be positive
     * @throws IllegalArgumentException if any dimension is not positive
     */
    void setDimensions(int width, int height, int depth);

    /**
     * Sets the position of this geometry.
     *
     * @param x the X coordinate
     * @param y the Y coordinate
     * @param z the Z coordinate
     */
    void setPosition(double x, double y, double z);

    /**
     * Creates a deep copy of this geometry.
     *
     * @return a new ModelGeometry instance with the same properties
     */
    ModelGeometry copy();

    /**
     * Gets a human-readable description of this geometry.
     *
     * @return string representation (e.g., "Cube 16x16x16 at (0, 0, 0)")
     */
    String getDescription();
}
