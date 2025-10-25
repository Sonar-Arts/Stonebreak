package com.openmason.ui.viewport.gizmo.interaction;

import org.joml.Vector3f;

/**
 * Immutable data class representing a single interactive part of the gizmo.
 * Each part corresponds to a clickable/hoverable component (arrow, plane, circle, etc.).
 *
 * <p>This class follows SOLID principles:
 * - Single Responsibility: Only holds data about a gizmo part
 * - Immutable: Thread-safe and prevents unintended modification
 * - Focused: Only contains properties needed for interaction
 */
public final class GizmoPart {
    private final AxisConstraint constraint;
    private final Vector3f color;
    private final PartType type;
    private final Vector3f center; // World space center for interaction
    private final float interactionRadius; // For hit detection

    /**
     * Type of gizmo part for rendering and interaction.
     */
    public enum PartType {
        ARROW,      // Translation arrow
        PLANE,      // Translation plane square
        CIRCLE,     // Rotation arc
        BOX,        // Scale handle
        CENTER      // Center uniform scale cube
    }

    /**
     * Constructs a new immutable GizmoPart.
     *
     * @param constraint The axis/plane constraint this part applies
     * @param color The RGB color for rendering (must not be null)
     * @param type The type of gizmo part (must not be null)
     * @param center The world-space center position (must not be null)
     * @param interactionRadius The radius for hit detection (must be positive)
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public GizmoPart(AxisConstraint constraint, Vector3f color, PartType type,
                     Vector3f center, float interactionRadius) {
        // SAFE: Validate all inputs
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
        this.color = new Vector3f(color); // Defensive copy
        this.type = type;
        this.center = new Vector3f(center); // Defensive copy
        this.interactionRadius = interactionRadius;
    }

    /**
     * Gets the axis constraint for this part.
     *
     * @return The constraint (never null)
     */
    public AxisConstraint getConstraint() {
        return constraint;
    }

    /**
     * Gets the base color for rendering this part.
     *
     * @return A new Vector3f with the color (never null)
     */
    public Vector3f getColor() {
        return new Vector3f(color); // Defensive copy
    }

    /**
     * Gets the type of this gizmo part.
     *
     * @return The part type (never null)
     */
    public PartType getType() {
        return type;
    }

    /**
     * Gets the world-space center position of this part.
     *
     * @return A new Vector3f with the center position (never null)
     */
    public Vector3f getCenter() {
        return new Vector3f(center); // Defensive copy
    }

    /**
     * Gets the interaction radius for hit detection.
     *
     * @return The radius in world units (always positive)
     */
    public float getInteractionRadius() {
        return interactionRadius;
    }

    @Override
    public String toString() {
        return String.format("GizmoPart{constraint=%s, type=%s, color=(%.2f,%.2f,%.2f), center=(%.2f,%.2f,%.2f), radius=%.2f}",
                constraint, type, color.x, color.y, color.z, center.x, center.y, center.z, interactionRadius);
    }
}
