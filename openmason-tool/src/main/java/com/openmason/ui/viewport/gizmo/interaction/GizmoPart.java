package com.openmason.ui.viewport.gizmo.interaction;

import org.joml.Vector3f;

/**
 * Immutable data for a single gizmo part (arrow, plane, circle, etc.).
 */
public final class GizmoPart {
    private final AxisConstraint constraint;
    private final Vector3f color;
    private final PartType type;
    private final Vector3f center; // world-space center
    private final float interactionRadius; // hit radius

    /** Gizmo part types for rendering and interaction. */
    public enum PartType {
        ARROW,      // translation arrow
        PLANE,      // translation plane
        CIRCLE,     // rotation arc
        BOX,        // scale handle
        CENTER      // uniform scale cube
    }

    /**
     * Create a GizmoPart.
     */
    public GizmoPart(AxisConstraint constraint, Vector3f color, PartType type,
                     Vector3f center, float interactionRadius) {
        // Validate inputs
        if (constraint == null) {
            throw new IllegalArgumentException("Constraint cannot be null");
        }
        if (color == null) {
            throw new IllegalArgumentException("Color cannot be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("Type cannot be null");
        }
        if (center == null) {
            throw new IllegalArgumentException("Center cannot be null");
        }
        if (interactionRadius <= 0.0f) {
            throw new IllegalArgumentException("Interaction radius must be positive");
        }

        this.constraint = constraint;
        this.color = new Vector3f(color); // defensive copy
        this.type = type;
        this.center = new Vector3f(center); // defensive copy
        this.interactionRadius = interactionRadius;
    }

    /** Returns the axis constraint. */
    public AxisConstraint getConstraint() {
        return constraint;
    }

    /** Returns a defensive copy of the color. */
    public Vector3f getColor() {
        return new Vector3f(color);
    }

    /** Returns the part type. */
    public PartType getType() {
        return type;
    }

    /** Returns a defensive copy of the world-space center. */
    public Vector3f getCenter() {
        return new Vector3f(center);
    }

    /** Returns the interaction radius. */
    public float getInteractionRadius() {
        return interactionRadius;
    }

    @Override
    public String toString() {
        return String.format("GizmoPart{constraint=%s, type=%s, color=(%.2f,%.2f,%.2f), center=(%.2f,%.2f,%.2f), radius=%.2f}",
                constraint, type, color.x, color.y, color.z, center.x, center.y, center.z, interactionRadius);
    }
}
