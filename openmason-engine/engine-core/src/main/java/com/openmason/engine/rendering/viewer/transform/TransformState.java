package com.openmason.engine.rendering.viewer.transform;

import com.openmason.engine.rendering.viewer.math.SnappingUtil;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages model transformation state (position, rotation, scale).
 * Includes constraint validation and matrix generation.
 * Mutable state object with dirty tracking for performance.
 */
public class TransformState {

    private static final Logger logger = LoggerFactory.getLogger(TransformState.class);

    /** Clamping policy. Defaults to the model editor's historical grid/scale limits. */
    private final TransformLimits limits;

    // Transform values
    private float positionX = 0.0f;
    private float positionY = 0.0f;
    private float positionZ = 0.0f;
    private float rotationX = 0.0f;
    private float rotationY = 0.0f;
    private float rotationZ = 0.0f;
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;
    private float scaleZ = 1.0f;

    // Gizmo state
    private boolean gizmoEnabled = false;

    // Cached transform matrix
    private final Matrix4f transformMatrix = new Matrix4f();
    private boolean dirty = true;

    /**
     * Create a transform state using the model editor's historical limits
     * ({@link TransformLimits#EDITOR_DEFAULT}: position clamped to the ±10 grid,
     * scale to [0.1, 3.0]).
     */
    public TransformState() {
        this(TransformLimits.EDITOR_DEFAULT);
    }

    /**
     * Create a transform state with an explicit clamping policy — e.g.
     * {@link TransformLimits#UNBOUNDED} for a scene instance, which is placed
     * across a layout far larger than the editor grid.
     */
    public TransformState(TransformLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        reset();
    }

    /** The clamping policy in force for this transform. */
    public TransformLimits getLimits() {
        return limits;
    }

    /**
     * Reset all transform values to defaults while preserving gizmo state.
     * This resets position to origin, rotation to zero, and scale to 1.0,
     * but keeps the gizmo enabled/disabled state unchanged to maintain
     * consistency with GizmoState.
     */
    public void reset() {
        positionX = 0.0f;
        positionY = 0.0f;
        positionZ = 0.0f;
        rotationX = 0.0f;
        rotationY = 0.0f;
        rotationZ = 0.0f;
        scaleX = 1.0f;
        scaleY = 1.0f;
        scaleZ = 1.0f;
        // NOTE: gizmoEnabled is NOT reset to preserve sync with GizmoState
        // The gizmo visual remains in the same enabled/disabled state
        dirty = true;
        logger.trace("Transform state reset to defaults (position=origin, rotation=0, scale=1, gizmoEnabled={})", gizmoEnabled);
    }

    /**
     * Reset position only (preserve rotation and scale).
     */
    public void resetPosition() {
        positionX = 0.0f;
        positionY = 0.0f;
        positionZ = 0.0f;
        dirty = true;
        logger.debug("Position reset to origin");
    }

    /**
     * Set position with grid constraints.
     */
    public void setPosition(float x, float y, float z) {
        this.positionX = limits.clampPosition(x);
        this.positionY = limits.clampPosition(y);
        this.positionZ = limits.clampPosition(z);
        this.dirty = true;
    }

    /**
     * Set position with optional grid snapping and grid constraints.
     * If snapping is enabled, the position will be snapped to the nearest grid increment
     * before applying grid size constraints.
     *
     * @param x              the x position
     * @param y              the y position
     * @param z              the z position
     * @param snapEnabled    whether grid snapping is enabled
     * @param snapIncrement  the grid snapping increment (only used if snapEnabled is true)
     */
    public void setPosition(float x, float y, float z, boolean snapEnabled, float snapIncrement) {
        // Apply snapping if enabled
        if (snapEnabled && snapIncrement > 0) {
            x = SnappingUtil.snapToGrid(x, snapIncrement);
            y = SnappingUtil.snapToGrid(y, snapIncrement);
            z = SnappingUtil.snapToGrid(z, snapIncrement);
            logger.trace("Grid snapping applied: ({}, {}, {}) with increment {}",
                        String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z), snapIncrement);
        }

        // Apply grid constraints
        this.positionX = limits.clampPosition(x);
        this.positionY = limits.clampPosition(y);
        this.positionZ = limits.clampPosition(z);
        this.dirty = true;

        logger.trace("Position set to: ({}, {}, {}), dirty=true",
                    String.format("%.2f", positionX), String.format("%.2f", positionY), String.format("%.2f", positionZ));
    }

    /**
     * Set rotation (in degrees).
     */
    public void setRotation(float x, float y, float z) {
        this.rotationX = x;
        this.rotationY = y;
        this.rotationZ = z;
        this.dirty = true;
    }

    /**
     * Set uniform scale with constraints (all axes).
     */
    public void setScale(float scale) {
        this.scaleX = limits.clampScale(scale);
        this.scaleY = limits.clampScale(scale);
        this.scaleZ = limits.clampScale(scale);
        this.dirty = true;
    }

    /**
     * Set non-uniform scale with constraints (per-axis).
     */
    public void setScale(float x, float y, float z) {
        this.scaleX = limits.clampScale(x);
        this.scaleY = limits.clampScale(y);
        this.scaleZ = limits.clampScale(z);
        this.dirty = true;
    }

    /**
     * Get transform matrix (updates if dirty).
     */
    public Matrix4f getTransformMatrix() {
        if (dirty) {
            updateTransformMatrix();
        }
        return new Matrix4f(transformMatrix); // Return copy for safety
    }

    /**
     * Update the cached transform matrix.
     * Always includes position, rotation, and scale transforms.
     */
    private void updateTransformMatrix() {
        transformMatrix.identity();

        // Always apply position transforms (gizmo enabled or not)
        // The model's position should always be included in the transform
        transformMatrix.translate(positionX, positionY, positionZ);

        transformMatrix
            .rotateXYZ(
                (float) Math.toRadians(rotationX),
                (float) Math.toRadians(rotationY),
                (float) Math.toRadians(rotationZ)
            )
            .scale(scaleX, scaleY, scaleZ);

        dirty = false;

        logger.trace("Updated transform matrix: pos=({},{},{}), rot=({},{},{}), scale=({},{},{}), determinant={}",
                    String.format("%.1f", positionX), String.format("%.1f", positionY), String.format("%.1f", positionZ),
                    String.format("%.1f", rotationX), String.format("%.1f", rotationY), String.format("%.1f", rotationZ),
                    String.format("%.2f", scaleX), String.format("%.2f", scaleY), String.format("%.2f", scaleZ),
                    String.format("%.3f", transformMatrix.determinant()));
    }

    /**
     * Set gizmo enabled state.
     */
    public void setGizmoEnabled(boolean enabled) {
        if (this.gizmoEnabled != enabled) {
            this.gizmoEnabled = enabled;
            this.dirty = true; // Gizmo state affects transform matrix
            logger.debug("Gizmo enabled state changed to: {}", enabled);
        }
    }

    // Getters
    public float getPositionX() { return positionX; }
    public float getPositionY() { return positionY; }
    public float getPositionZ() { return positionZ; }
    public float getRotationX() { return rotationX; }
    public float getRotationY() { return rotationY; }
    public float getRotationZ() { return rotationZ; }
    public float getScaleX() { return scaleX; }
    public float getScaleY() { return scaleY; }
    public float getScaleZ() { return scaleZ; }
    public float getScale() { return scaleX; } // Backward compatibility - returns X scale
    public boolean isGizmoEnabled() { return gizmoEnabled; }

    /**
     * Get gizmo position as a Vector3f.
     * @return Vector3f containing current position, or null if gizmo is disabled
     */
    public org.joml.Vector3f getGizmoPosition() {
        if (!gizmoEnabled) {
            return null;
        }
        return new org.joml.Vector3f(positionX, positionY, positionZ);
    }

    // Constraint getters.
    //
    // Static, so they can only report the EDITOR_DEFAULT policy — they predate limits
    // being per-instance. Callers that care about a specific transform's bounds should
    // use getLimits() instead.
    /** @deprecated use {@code getLimits().minScale()}; this always reports the editor default. */
    @Deprecated
    public static float getMinScale() { return TransformLimits.EDITOR_DEFAULT.minScale(); }

    /** @deprecated use {@code getLimits().maxScale()}; this always reports the editor default. */
    @Deprecated
    public static float getMaxScale() { return TransformLimits.EDITOR_DEFAULT.maxScale(); }

    @Override
    public String toString() {
        return String.format("TransformState{pos=(%.1f,%.1f,%.1f), rot=(%.1f,%.1f,%.1f), scale=(%.2f,%.2f,%.2f), gizmo=%s}",
                           positionX, positionY, positionZ, rotationX, rotationY, rotationZ, scaleX, scaleY, scaleZ, gizmoEnabled);
    }
}
