package com.openmason.engine.rendering.viewer.gizmo.interaction;

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

    // ==================================================================================
    // Drag lifecycle and group fallback.
    //
    // These exist so the gizmo never has to ask "am I holding a PartTransformTarget?".
    // They used to be seven `instanceof PartTransformTarget` downcasts spread across
    // TransformApplier and GizmoInteractionHandler, which meant the gizmo — an otherwise
    // target-agnostic widget — could only ever grow special cases for one concrete
    // target. The defaults below are the behaviour every other target already had by
    // falling through those checks, so overriding nothing preserves today's semantics.
    // ==================================================================================

    /**
     * Whether this target currently refuses transforms (e.g. every selected part is
     * locked). A locked target aborts the drag before it starts.
     *
     * @return true to reject the drag; default false
     */
    default boolean isLocked() {
        return false;
    }

    /**
     * Whether a finished gizmo drag of this target should be reported to the host's
     * {@code TransformUndoSink} for undo recording.
     *
     * <p>Per-target rather than per-host, because a single gizmo swaps between targets
     * whose undo stories differ: a scene's instance target opts in (a placed instance has
     * no other undo mechanism), while the editor's part/bone/socket targets stay out —
     * the editor's sink records into the <em>model-level</em> transform, so reporting
     * their drags there would make undo move the whole model. If one of those targets
     * later gains its own drag undo, it opts in here without touching the others.
     *
     * @return true to report finished drags of this target; default false
     */
    default boolean recordsDragsForUndo() {
        return false;
    }

    /** Called when a drag begins, so the target can snapshot state for undo/multi-select. */
    default void beginDrag() {
        // no-op by default
    }

    /** Called when a drag ends, so the target can release any snapshots it took. */
    default void endDrag() {
        // no-op by default
    }

    /**
     * Whether this target can absorb a <em>model-level</em> drag — one started while the
     * target itself is inactive (nothing selected).
     *
     * <p>When false, such a drag falls through to the viewport's own model
     * {@code TransformState} instead, which is the behaviour for every target that does
     * not manage a group.
     *
     * @return true if the {@code applyGroup*} methods below are meaningful; default false
     */
    default boolean supportsGroupFallback() {
        return false;
    }

    /** Snapshot every member before a group (model-level) drag. */
    default void beginGroupDrag() {
        // no-op by default
    }

    /**
     * Translate every unlocked group member by a shared delta.
     *
     * <p>A delta rather than an absolute position on purpose: it keeps the group
     * cohesive, and lets the caller grid-snap the offset once rather than snapping each
     * member independently (which would deform the arrangement).
     */
    default void applyGroupTranslationDelta(Vector3f delta) {
        // no-op by default
    }

    /** Apply a rotation to every unlocked group member. */
    default void applyGroupRotation(Vector3f rotation) {
        // no-op by default
    }

    /** Apply a scale to every unlocked group member. */
    default void applyGroupScale(float x, float y, float z) {
        // no-op by default
    }
}
