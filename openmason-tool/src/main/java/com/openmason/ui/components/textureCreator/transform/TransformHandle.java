package com.openmason.ui.components.textureCreator.transform;

/**
 * Immutable representation of a transform handle (corner, edge, rotation, or center).
 * Each handle has a type, position, hit box size, and determines cursor appearance.
 * <p>
 * Following SOLID principles:
 * - Single Responsibility: Represents handle data and provides hit detection
 * - Immutable design for thread safety and predictable behavior
 */
public final class TransformHandle {

    /**
     * Type of transform handle determining the operation it performs
     */
    public enum Type {
        // Corner handles for scaling (8 total)
        CORNER_TOP_LEFT,
        CORNER_TOP_RIGHT,
        CORNER_BOTTOM_LEFT,
        CORNER_BOTTOM_RIGHT,

        // Edge handles for stretching (4 total)
        EDGE_TOP,
        EDGE_RIGHT,
        EDGE_BOTTOM,
        EDGE_LEFT,

        // Special handles
        ROTATION,  // Top center, above the selection
        CENTER     // Center point for moving
    }

    /**
     * Cursor types for visual feedback
     */
    public enum CursorType {
        MOVE,           // Four-way arrow for movement
        RESIZE_NS,      // North-south resize
        RESIZE_EW,      // East-west resize
        RESIZE_NESW,    // Northeast-southwest diagonal
        RESIZE_NWSE,    // Northwest-southeast diagonal
        ROTATE          // Rotation cursor
    }

    private final Type type;
    private final double x;
    private final double y;
    private final double hitRadius;

    /**
     * Creates a new transform handle
     *
     * @param type The type of handle
     * @param x The x-coordinate of the handle center
     * @param y The y-coordinate of the handle center
     * @param hitRadius The radius of the circular hit detection area
     */
    public TransformHandle(Type type, double x, double y, double hitRadius) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.hitRadius = hitRadius;
    }

    /**
     * Checks if a point is within this handle's hit radius using circular distance-based detection.
     * This provides more precise and intuitive handle selection compared to rectangular detection.
     *
     * @param px The x-coordinate to test
     * @param py The y-coordinate to test
     * @return true if the point is within the hit radius
     */
    public boolean contains(double px, double py) {
        double dx = px - x;
        double dy = py - y;
        double distanceSquared = dx * dx + dy * dy;
        double radiusSquared = hitRadius * hitRadius;
        return distanceSquared <= radiusSquared;
    }

    /**
     * Gets the cursor type appropriate for this handle
     *
     * @return The cursor type to display when hovering over this handle
     */
    public CursorType getCursorType() {
        return switch (type) {
            case CORNER_TOP_LEFT, CORNER_BOTTOM_RIGHT -> CursorType.RESIZE_NWSE;
            case CORNER_TOP_RIGHT, CORNER_BOTTOM_LEFT -> CursorType.RESIZE_NESW;
            case EDGE_TOP, EDGE_BOTTOM -> CursorType.RESIZE_NS;
            case EDGE_LEFT, EDGE_RIGHT -> CursorType.RESIZE_EW;
            case ROTATION -> CursorType.ROTATE;
            case CENTER -> CursorType.MOVE;
        };
    }

    /**
     * Determines if this handle is a corner handle
     *
     * @return true if this is a corner handle
     */
    public boolean isCorner() {
        return type == Type.CORNER_TOP_LEFT || type == Type.CORNER_TOP_RIGHT ||
               type == Type.CORNER_BOTTOM_LEFT || type == Type.CORNER_BOTTOM_RIGHT;
    }

    /**
     * Determines if this handle is an edge handle
     *
     * @return true if this is an edge handle
     */
    public boolean isEdge() {
        return type == Type.EDGE_TOP || type == Type.EDGE_RIGHT ||
               type == Type.EDGE_BOTTOM || type == Type.EDGE_LEFT;
    }

    /**
     * Determines if this handle is the rotation handle
     *
     * @return true if this is the rotation handle
     */
    public boolean isRotation() {
        return type == Type.ROTATION;
    }

    /**
     * Determines if this handle is the center handle
     *
     * @return true if this is the center handle
     */
    public boolean isCenter() {
        return type == Type.CENTER;
    }

    // Getters
    public Type getType() { return type; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getHitRadius() { return hitRadius; }

    @Override
    public String toString() {
        return String.format("TransformHandle{type=%s, x=%.1f, y=%.1f}", type, x, y);
    }
}
