package com.openmason.model.editable;

import java.util.Objects;

/**
 * Simple cubic geometry implementation for block models.
 *
 * <p>Represents a single cuboid shape with width, height, and depth dimensions,
 * positioned at a specific location in 3D space.
 *
 * <p>Default dimensions are 16x16x16 pixels (standard Minecraft/Stonebreak block size).
 *
 * <p>This class is mutable but provides validation on all setters to maintain
 * valid state at all times.
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Single Responsibility - only stores and validates geometry data</li>
 *   <li>KISS: Simple data class with validation</li>
 *   <li>YAGNI: No complex shapes or transformations yet</li>
 * </ul>
 *
 * @since 1.0
 */
public class CubeGeometry implements ModelGeometry {

    /** Default dimension for block models (16x16x16) */
    public static final int DEFAULT_DIMENSION = 16;

    private int width;
    private int height;
    private int depth;
    private double x;
    private double y;
    private double z;

    /**
     * Creates a default cube geometry (16x16x16 at origin).
     */
    public CubeGeometry() {
        this(DEFAULT_DIMENSION, DEFAULT_DIMENSION, DEFAULT_DIMENSION, 0, 0, 0);
    }

    /**
     * Creates a cube geometry with specified dimensions at origin.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param depth the depth in pixels
     * @throws IllegalArgumentException if any dimension is not positive
     */
    public CubeGeometry(int width, int height, int depth) {
        this(width, height, depth, 0, 0, 0);
    }

    /**
     * Creates a cube geometry with specified dimensions and position.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param depth the depth in pixels
     * @param x the X coordinate
     * @param y the Y coordinate
     * @param z the Z coordinate
     * @throws IllegalArgumentException if any dimension is not positive
     */
    public CubeGeometry(int width, int height, int depth, double x, double y, double z) {
        setDimensions(width, height, depth);
        setPosition(x, y, z);
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getDepth() {
        return depth;
    }

    @Override
    public double getX() {
        return x;
    }

    @Override
    public double getY() {
        return y;
    }

    @Override
    public double getZ() {
        return z;
    }

    @Override
    public void setDimensions(int width, int height, int depth) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException(
                String.format("Dimensions must be positive: %dx%dx%d", width, height, depth)
            );
        }
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    @Override
    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public ModelGeometry copy() {
        return new CubeGeometry(width, height, depth, x, y, z);
    }

    @Override
    public String getDescription() {
        return String.format("Cube %dx%dx%d at (%.1f, %.1f, %.1f)",
            width, height, depth, x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CubeGeometry)) return false;
        CubeGeometry that = (CubeGeometry) o;
        return width == that.width &&
               height == that.height &&
               depth == that.depth &&
               Double.compare(that.x, x) == 0 &&
               Double.compare(that.y, y) == 0 &&
               Double.compare(that.z, z) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, depth, x, y, z);
    }

    @Override
    public String toString() {
        return getDescription();
    }
}
