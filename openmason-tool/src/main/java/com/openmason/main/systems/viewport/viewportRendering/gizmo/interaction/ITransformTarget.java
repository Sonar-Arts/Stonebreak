package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import org.joml.Vector3f;

/**
 * Abstraction for the gizmo's transform target.
 * Allows the gizmo to operate on either the whole model transform
 * or an individual part's local transform, without the interaction handler
 * needing to know which one.
 *
 * <p>Follows Dependency Inversion — the gizmo depends on this abstraction,
 * not on concrete TransformState or ModelPartManager classes.
 */
public interface ITransformTarget {

    /**
     * Get the current position of the target.
     *
     * @return Position as (x, y, z)
     */
    Vector3f getPosition();

    /**
     * Get the current rotation of the target (Euler degrees).
     *
     * @return Rotation as (x, y, z) in degrees
     */
    Vector3f getRotation();

    /**
     * Get the current scale of the target.
     *
     * @return Scale as (x, y, z)
     */
    Vector3f getScale();

    /**
     * Set the position of the target.
     *
     * @param x Position X
     * @param y Position Y
     * @param z Position Z
     */
    void setPosition(float x, float y, float z);

    /**
     * Set the position with optional grid snapping.
     *
     * @param x    Position X
     * @param y    Position Y
     * @param z    Position Z
     * @param snap Whether to snap to grid
     * @param snapIncrement Grid increment
     */
    void setPosition(float x, float y, float z, boolean snap, float snapIncrement);

    /**
     * Set the rotation of the target (Euler degrees).
     *
     * @param x Rotation X in degrees
     * @param y Rotation Y in degrees
     * @param z Rotation Z in degrees
     */
    void setRotation(float x, float y, float z);

    /**
     * Set the scale of the target.
     *
     * @param x Scale X
     * @param y Scale Y
     * @param z Scale Z
     */
    void setScale(float x, float y, float z);

    /**
     * Get the world-space center of this target for gizmo positioning.
     *
     * @return World-space center
     */
    Vector3f getWorldCenter();

    /**
     * Check if this target is active (has something selected to transform).
     *
     * @return true if the target is valid and transformable
     */
    boolean isActive();

    /**
     * Get a display name for this target (for UI/logging).
     *
     * @return Human-readable target name
     */
    String getTargetName();
}
