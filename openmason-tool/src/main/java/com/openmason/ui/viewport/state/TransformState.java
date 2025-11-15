package com.openmason.ui.viewport.state;

import com.openmason.ui.viewport.util.SnappingUtil;
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

    // Grid constraints
    private static final float GRID_SIZE = 10.0f;
    private static final float MIN_SCALE = 0.1f;
    private static final float MAX_SCALE = 3.0f;

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
     * Create default transform state.
     */
    public TransformState() {
        reset();
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
        this.positionX = Math.max(-GRID_SIZE, Math.min(GRID_SIZE, x));
        this.positionY = Math.max(-GRID_SIZE, Math.min(GRID_SIZE, y));
        this.positionZ = Math.max(-GRID_SIZE, Math.min(GRID_SIZE, z));
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
        this.positionX = Math.max(-GRID_SIZE, Math.min(GRID_SIZE, x));
        this.positionY = Math.max(-GRID_SIZE, Math.min(GRID_SIZE, y));
        this.positionZ = Math.max(-GRID_SIZE, Math.min(GRID_SIZE, z));
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
        this.scaleX = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        this.scaleY = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        this.scaleZ = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        this.dirty = true;
    }

    /**
     * Set non-uniform scale with constraints (per-axis).
     */
    public void setScale(float x, float y, float z) {
        this.scaleX = Math.max(MIN_SCALE, Math.min(MAX_SCALE, x));
        this.scaleY = Math.max(MIN_SCALE, Math.min(MAX_SCALE, y));
        this.scaleZ = Math.max(MIN_SCALE, Math.min(MAX_SCALE, z));
        this.dirty = true;
    }

    /**
     * Set scale for a specific axis.
     * @param axis 'x', 'y', or 'z'
     * @param value Scale value with constraints
     */
    public void setScaleAxis(char axis, float value) {
        float constrainedValue = Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
        switch (Character.toLowerCase(axis)) {
            case 'x' -> this.scaleX = constrainedValue;
            case 'y' -> this.scaleY = constrainedValue;
            case 'z' -> this.scaleZ = constrainedValue;
            default -> throw new IllegalArgumentException("Invalid axis: " + axis + " (must be 'x', 'y', or 'z')");
        }
        this.dirty = true;
    }

    /**
     * Set all transform values at once.
     */
    public void setTransform(float posX, float posY, float posZ, float rotX, float rotY, float rotZ, float scale) {
        setPosition(posX, posY, posZ);
        setRotation(rotX, rotY, rotZ);
        setScale(scale);
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
    public boolean isDirty() { return dirty; }

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

    // Constraint getters
    public static float getMinScale() { return MIN_SCALE; }
    public static float getMaxScale() { return MAX_SCALE; }
    public static float getGridSize() { return GRID_SIZE; }

    /**
     * Validate if scale is within bounds.
     */
    public boolean isScaleValid(float testScale) {
        return testScale >= MIN_SCALE && testScale <= MAX_SCALE;
    }

    @Override
    public String toString() {
        return String.format("TransformState{pos=(%.1f,%.1f,%.1f), rot=(%.1f,%.1f,%.1f), scale=(%.2f,%.2f,%.2f), gizmo=%s}",
                           positionX, positionY, positionZ, rotationX, rotationY, rotationZ, scaleX, scaleY, scaleZ, gizmoEnabled);
    }
}
