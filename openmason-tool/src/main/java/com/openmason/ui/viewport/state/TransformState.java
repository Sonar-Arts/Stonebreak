package com.openmason.ui.viewport.state;

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
    private float scale = 1.0f;

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
     * Reset all transforms to default.
     */
    public void reset() {
        positionX = 0.0f;
        positionY = 0.0f;
        positionZ = 0.0f;
        rotationX = 0.0f;
        rotationY = 0.0f;
        rotationZ = 0.0f;
        scale = 1.0f;
        gizmoEnabled = false;
        dirty = true;
        logger.trace("Transform state reset to defaults");
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
     * Set rotation (in degrees).
     */
    public void setRotation(float x, float y, float z) {
        this.rotationX = x;
        this.rotationY = y;
        this.rotationZ = z;
        this.dirty = true;
    }

    /**
     * Set scale with constraints.
     */
    public void setScale(float scale) {
        this.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
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
     * Position is only applied when gizmo is enabled.
     */
    private void updateTransformMatrix() {
        transformMatrix.identity();

        // Only apply position transforms when gizmo is enabled
        if (gizmoEnabled) {
            transformMatrix.translate(positionX, positionY, positionZ);
        }

        transformMatrix
            .rotateXYZ(
                (float) Math.toRadians(rotationX),
                (float) Math.toRadians(rotationY),
                (float) Math.toRadians(rotationZ)
            )
            .scale(scale);

        dirty = false;

        if (gizmoEnabled) {
            logger.trace("Updated transform matrix (gizmo enabled): pos=({},{},{}), rot=({},{},{}), scale={}, determinant={}",
                        String.format("%.1f", positionX), String.format("%.1f", positionY), String.format("%.1f", positionZ),
                        String.format("%.1f", rotationX), String.format("%.1f", rotationY), String.format("%.1f", rotationZ),
                        String.format("%.2f", scale), String.format("%.3f", transformMatrix.determinant()));
        } else {
            logger.trace("Updated transform matrix (gizmo disabled): rot=({},{},{}), scale={}, determinant={}",
                        String.format("%.1f", rotationX), String.format("%.1f", rotationY), String.format("%.1f", rotationZ),
                        String.format("%.2f", scale), String.format("%.3f", transformMatrix.determinant()));
        }
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
    public float getScale() { return scale; }
    public boolean isGizmoEnabled() { return gizmoEnabled; }
    public boolean isDirty() { return dirty; }

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
        return String.format("TransformState{pos=(%.1f,%.1f,%.1f), rot=(%.1f,%.1f,%.1f), scale=%.2f, gizmo=%s}",
                           positionX, positionY, positionZ, rotationX, rotationY, rotationZ, scale, gizmoEnabled);
    }
}
