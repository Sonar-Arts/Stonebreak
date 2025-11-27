package com.openmason.ui.viewport.gizmo.interaction;

import com.openmason.ui.viewport.coordinates.CoordinateSystem;
import com.openmason.ui.viewport.gizmo.GizmoState;
import com.openmason.ui.viewport.state.TransformState;
import com.openmason.ui.viewport.state.ViewportState;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Handles mouse interaction with the transform gizmo.
 * Manages hover detection, click handling, and drag operations.
 */
public class GizmoInteractionHandler {

    private final GizmoState gizmoState;
    private final TransformState transformState;
    private ViewportState viewportState;

    // Cached camera matrices
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f projectionMatrix = new Matrix4f();
    private int viewportWidth = 1;
    private int viewportHeight = 1;

    /**
     * Creates a new GizmoInteractionHandler.
     */
    public GizmoInteractionHandler(GizmoState gizmoState, TransformState transformState, ViewportState viewportState) {
        if (gizmoState == null) {
            throw new IllegalArgumentException("GizmoState cannot be null");
        }
        if (transformState == null) {
            throw new IllegalArgumentException("TransformState cannot be null");
        }

        this.gizmoState = gizmoState;
        this.transformState = transformState;
        this.viewportState = viewportState;
    }

    /**
     * Update viewport state for snapping settings.
     * Should be called whenever viewport state changes.
     */
    public void updateViewportState(ViewportState viewportState) {
        this.viewportState = viewportState;
    }

    /**
     * Updates the camera matrices used for raycasting.
     * Should be called each frame before processing input.
     */
    public void updateCamera(Matrix4f view, Matrix4f projection, int width, int height) {
        if (view == null || projection == null) {
            throw new IllegalArgumentException("Matrices cannot be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Viewport dimensions must be positive");
        }

        this.viewMatrix.set(view);
        this.projectionMatrix.set(projection);
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    /**
     * Handles mouse movement for hover detection or drag operations.
     *
     * @param mouseX Mouse X position in screen space
     * @param mouseY Mouse Y position in screen space
     * @param gizmoParts List of interactive gizmo parts for hit testing
     */
    public void handleMouseMove(float mouseX, float mouseY, List<GizmoPart> gizmoParts) {
        if (gizmoParts == null) {
            throw new IllegalArgumentException("GizmoParts cannot be null");
        }

        if (!gizmoState.isEnabled()) {
            return; // Gizmo disabled
        }

        if (gizmoState.isDragging()) {
            // Handle drag operation
            handleDrag(mouseX, mouseY);
        } else {
            // Handle hover detection
            handleHover(mouseX, mouseY, gizmoParts);
        }
    }

    /**
     * Handles mouse press to start a drag operation.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @return true if a gizmo part was clicked, false otherwise
     */
    public boolean handleMousePress(float mouseX, float mouseY) {
        if (!gizmoState.isEnabled()) {
            return false;
        }

        GizmoPart hoveredPart = gizmoState.getHoveredPart();
        if (hoveredPart != null) {
            // Start drag operation
            Vector3f position = new Vector3f(
                transformState.getPositionX(),
                transformState.getPositionY(),
                transformState.getPositionZ()
            );
            Vector3f rotation = new Vector3f(
                transformState.getRotationX(),
                transformState.getRotationY(),
                transformState.getRotationZ()
            );
            Vector3f scale = new Vector3f(
                transformState.getScaleX(),
                transformState.getScaleY(),
                transformState.getScaleZ()
            );

            gizmoState.startDrag(
                hoveredPart,
                mouseX,
                mouseY,
                position,
                rotation,
                scale
            );
            return true;
        }

        return false;
    }

    /**
     * Handles mouse release to end a drag operation.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     */
    public void handleMouseRelease(float mouseX, float mouseY) {
        if (!gizmoState.isEnabled()) {
            return;
        }

        if (gizmoState.isDragging()) {
            gizmoState.endDrag();
        }
    }

    /**
     * Handles hover detection by raycasting against gizmo parts.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param gizmoParts List of gizmo parts to test
     */
    private void handleHover(float mouseX, float mouseY, List<GizmoPart> gizmoParts) {
        // Create ray from mouse position
        CoordinateSystem.Ray ray = RaycastUtil.createRayFromScreen(
            mouseX,
            mouseY,
            viewportWidth,
            viewportHeight,
            viewMatrix,
            projectionMatrix
        );

        // Find closest intersected part
        GizmoPart closestPart = null;
        float closestDistance = Float.POSITIVE_INFINITY;

        for (GizmoPart part : gizmoParts) {
            float distance = intersectPart(ray, part);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestPart = part;
            }
        }

        // Update hover state
        gizmoState.setHoveredPart(closestPart);
    }

    /**
     * Tests intersection between a ray and a gizmo part.
     *
     * @param ray The ray to test
     * @param part The gizmo part
     * @return Distance to intersection, or Float.POSITIVE_INFINITY if no hit
     */
    private float intersectPart(CoordinateSystem.Ray ray, GizmoPart part) {
        switch (part.getType()) {
            case ARROW:
            case BOX:
            case CENTER:
                // Use sphere intersection for arrows and boxes
                return RaycastUtil.intersectRaySphere(
                    ray,
                    part.getCenter(),
                    part.getInteractionRadius()
                );

            case PLANE:
                // Plane handles use larger interaction radius
                return RaycastUtil.intersectRaySphere(
                    ray,
                    part.getCenter(),
                    part.getInteractionRadius()
                );

            case CIRCLE:
                // Circle uses torus approximation
                Vector3f normal = getCircleNormal(part.getConstraint());
                return RaycastUtil.intersectRayCircle(
                    ray,
                    part.getCenter(),
                    normal,
                    part.getInteractionRadius(),
                    part.getInteractionRadius() * 0.1f // Thickness
                );

            default:
                return Float.POSITIVE_INFINITY;
        }
    }

    /**
     * Handles drag operations by updating the transform state.
     *
     * @param mouseX Current mouse X position
     * @param mouseY Current mouse Y position
     */
    private void handleDrag(float mouseX, float mouseY) {
        AxisConstraint constraint = gizmoState.getActiveConstraint();
        if (constraint == AxisConstraint.NONE) {
            return;
        }

        // Calculate mouse delta from drag start
        Vector2f startMouse = gizmoState.getDragStartMousePos();
        Vector2f mouseDelta = new Vector2f(mouseX - startMouse.x, mouseY - startMouse.y);

        // Handle based on current mode
        switch (gizmoState.getCurrentMode()) {
            case TRANSLATE:
                handleTranslateDrag(mouseDelta, constraint);
                break;

            case ROTATE:
                handleRotateDrag(mouseX, mouseY, constraint);
                break;

            case SCALE:
                handleScaleDrag(mouseDelta, constraint);
                break;
        }
    }

    /**
     * Handles translation drag operation.
     *
     * <p><b>Coordinate System:</b> Mouse delta is in ImGui screen space (Y+ down).
     * RaycastUtil handles the conversion to world space via the unified CoordinateSystem.
     *
     * @param mouseDelta Mouse movement since drag start (in ImGui screen space)
     * @param constraint Active axis/plane constraint
     */
    private void handleTranslateDrag(Vector2f mouseDelta, AxisConstraint constraint) {
        Vector3f startPos = gizmoState.getDragStartObjectPos();
        Vector3f newPos = new Vector3f(startPos);

        // No coordinate system correction needed - RaycastUtil handles it via CoordinateSystem
        if (constraint.isSingleAxis()) {
            // Single axis translation
            Vector3f axis = getAxisVector(constraint);
            float movement = RaycastUtil.projectScreenDeltaOntoAxis(
                mouseDelta,
                axis,
                viewMatrix,
                projectionMatrix,
                viewportWidth,
                viewportHeight
            );

            // Apply snap if enabled
            if (gizmoState.isSnapEnabled()) {
                movement = snapToIncrement(movement, gizmoState.getSnapIncrement());
            }

            newPos.add(axis.x * movement, axis.y * movement, axis.z * movement);

        } else if (constraint.isPlane()) {
            // Plane translation (more complex, simplified here)
            Vector3f axis1 = getPlaneAxis1(constraint);
            Vector3f axis2 = getPlaneAxis2(constraint);

            float move1 = RaycastUtil.projectScreenDeltaOntoAxis(
                mouseDelta, axis1, viewMatrix, projectionMatrix, viewportWidth, viewportHeight
            );
            float move2 = RaycastUtil.projectScreenDeltaOntoAxis(
                mouseDelta, axis2, viewMatrix, projectionMatrix, viewportWidth, viewportHeight
            );

            if (gizmoState.isSnapEnabled()) {
                float snap = gizmoState.getSnapIncrement();
                move1 = snapToIncrement(move1, snap);
                move2 = snapToIncrement(move2, snap);
            }

            newPos.add(axis1.x * move1, axis1.y * move1, axis1.z * move1);
            newPos.add(axis2.x * move2, axis2.y * move2, axis2.z * move2);
        }

        // Apply grid snapping from viewport state if available
        if (viewportState != null && viewportState.isGridSnappingEnabled()) {
            transformState.setPosition(newPos.x, newPos.y, newPos.z,
                                     true, viewportState.getGridSnappingIncrement());
        } else {
            transformState.setPosition(newPos.x, newPos.y, newPos.z);
        }
    }

    /**
     * Handles rotation drag operation.
     *
     * @param mouseX Current mouse X
     * @param mouseY Current mouse Y
     * @param constraint Active axis constraint
     */
    private void handleRotateDrag(float mouseX, float mouseY, AxisConstraint constraint) {
        // Create current ray
        CoordinateSystem.Ray currentRay = RaycastUtil.createRayFromScreen(
            mouseX, mouseY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix
        );

        // Get rotation axis and circle center
        Vector3f axis = getAxisVector(constraint);
        Vector3f center = new Vector3f(
            transformState.getPositionX(),
            transformState.getPositionY(),
            transformState.getPositionZ()
        );

        // Intersect with rotation plane
        float t = RaycastUtil.intersectRayPlane(currentRay, center, axis);
        if (Float.isInfinite(t)) {
            return; // No intersection
        }

        // Get intersection point
        Vector3f currentPoint = RaycastUtil.getPointOnRay(currentRay, t);

        // Also get start point
        Vector2f startMouse = gizmoState.getDragStartMousePos();
        CoordinateSystem.Ray startRay = RaycastUtil.createRayFromScreen(
            startMouse.x, startMouse.y, viewportWidth, viewportHeight, viewMatrix, projectionMatrix
        );
        float tStart = RaycastUtil.intersectRayPlane(startRay, center, axis);
        if (Float.isInfinite(tStart)) {
            return;
        }

        Vector3f startPoint = RaycastUtil.getPointOnRay(startRay, tStart);

        // Calculate angle difference
        Vector3f toStart = new Vector3f(startPoint).sub(center).normalize();
        Vector3f toCurrent = new Vector3f(currentPoint).sub(center).normalize();

        float angle = (float) Math.acos(Math.max(-1.0f, Math.min(1.0f, toStart.dot(toCurrent))));

        // Determine sign using cross product
        Vector3f cross = new Vector3f();
        toStart.cross(toCurrent, cross);
        if (cross.dot(axis) < 0) {
            angle = -angle;
        }

        // Convert to degrees
        float angleDegrees = (float) Math.toDegrees(angle);

        // Apply snap if enabled
        if (gizmoState.isSnapEnabled()) {
            angleDegrees = snapToIncrement(angleDegrees, gizmoState.getSnapIncrement());
        }

        // Apply to rotation
        Vector3f startRot = gizmoState.getDragStartObjectRotation();
        Vector3f newRot = new Vector3f(startRot);

        if (constraint == AxisConstraint.X) {
            newRot.x += angleDegrees;
        } else if (constraint == AxisConstraint.Y) {
            newRot.y += angleDegrees;
        } else if (constraint == AxisConstraint.Z) {
            newRot.z += angleDegrees;
        }

        transformState.setRotation(newRot.x, newRot.y, newRot.z);
    }

    /**
     * Handles scale drag operation.
     * Supports both uniform and per-axis scaling based on gizmo state.
     *
     * <p><b>Coordinate System:</b> Mouse delta is in ImGui screen space (Y+ down).
     * RaycastUtil handles the conversion to world space via the unified CoordinateSystem.
     *
     * @param mouseDelta Mouse movement since drag start (in ImGui screen space)
     * @param constraint Active axis constraint
     */
    private void handleScaleDrag(Vector2f mouseDelta, AxisConstraint constraint) {
        Vector3f startScale = gizmoState.getDragStartObjectScale();

        // Check if uniform scaling is enabled or if center box is dragged
        boolean shouldScaleUniformly = gizmoState.isUniformScaling() || constraint == AxisConstraint.NONE;

        if (shouldScaleUniformly) {
            // Uniform scaling: apply proportional scale factor to all axes
            // This preserves the model's shape while scaling uniformly
            // Use vertical mouse movement (more intuitive)
            // Note: mouseDelta.y is in screen space where Y+ is down,
            // but we want drag-down to decrease scale, so negate
            // Lower multiplier (0.05f) for slower, more controlled scaling
            float scaleFactor = 1.0f + (mouseDelta.y * 0.05f);

            // Apply snap if enabled
            if (gizmoState.isSnapEnabled()) {
                scaleFactor = snapToIncrement(scaleFactor, gizmoState.getSnapIncrement() * 0.1f);
            }

            // Apply the same scale factor to all three axes proportionally
            float newScaleX = startScale.x * scaleFactor;
            float newScaleY = startScale.y * scaleFactor;
            float newScaleZ = startScale.z * scaleFactor;
            transformState.setScale(newScaleX, newScaleY, newScaleZ);
        } else {
            // Per-axis scaling: project mouse movement onto the specific axis direction
            Vector3f axis = getAxisVector(constraint);
            float movement = RaycastUtil.projectScreenDeltaOntoAxis(
                mouseDelta,
                axis,
                viewMatrix,
                projectionMatrix,
                viewportWidth,
                viewportHeight
            );

            // Convert movement to scale factor (increased rate for responsiveness)
            float scaleFactor = 1.0f + (movement * 0.1f);

            // Apply snap if enabled
            if (gizmoState.isSnapEnabled()) {
                scaleFactor = snapToIncrement(scaleFactor, gizmoState.getSnapIncrement() * 0.1f);
            }

            // Update only the active axis
            float newScaleX = startScale.x;
            float newScaleY = startScale.y;
            float newScaleZ = startScale.z;

            switch (constraint) {
                case X -> newScaleX = startScale.x * scaleFactor;
                case Y -> newScaleY = startScale.y * scaleFactor;
                case Z -> newScaleZ = startScale.z * scaleFactor;
            }

            transformState.setScale(newScaleX, newScaleY, newScaleZ);
        }
    }

    /**
     * Gets the world-space axis vector for a constraint.
     *
     * @param constraint The axis constraint
     * @return Axis direction vector
     */
    private Vector3f getAxisVector(AxisConstraint constraint) {
        return switch (constraint) {
            case X -> new Vector3f(1, 0, 0);
            case Y -> new Vector3f(0, 1, 0);
            case Z -> new Vector3f(0, 0, 1);
            default -> new Vector3f(0, 0, 0);
        };
    }

    /**
     * Gets the first axis of a plane constraint.
     */
    private Vector3f getPlaneAxis1(AxisConstraint constraint) {
        return switch (constraint) {
            case XY, XZ -> new Vector3f(1, 0, 0); // X
            case YZ -> new Vector3f(0, 1, 0);     // Y
            default -> new Vector3f(0, 0, 0);
        };
    }

    /**
     * Gets the second axis of a plane constraint.
     */
    private Vector3f getPlaneAxis2(AxisConstraint constraint) {
        return switch (constraint) {
            case XY -> new Vector3f(0, 1, 0); // Y
            case XZ -> new Vector3f(0, 0, 1); // Z
            case YZ -> new Vector3f(0, 0, 1); // Z
            default -> new Vector3f(0, 0, 0);
        };
    }

    /**
     * Gets the normal vector for a rotation circle.
     */
    private Vector3f getCircleNormal(AxisConstraint constraint) {
        // Rotation circles are perpendicular to their rotation axis
        return getAxisVector(constraint);
    }

    /**
     * Snaps a value to the nearest increment.
     *
     * @param value The value to snap
     * @param increment The snap increment
     * @return Snapped value
     */
    private float snapToIncrement(float value, float increment) {
        if (increment <= 0.0f) {
            return value;
        }
        return Math.round(value / increment) * increment;
    }
}
