package com.openmason.main.systems.rendering.model.editable;

import java.util.Objects;

/**
 * Simple cubic geometry implementation for block models.
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
     * Creates a cube geometry with specified dimensions and position.
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
        if (!(o instanceof CubeGeometry that)) return false;
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
