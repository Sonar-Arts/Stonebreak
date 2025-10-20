package com.openmason.ui.viewport.gizmo.interaction;

import com.openmason.ui.viewport.gizmo.GizmoState;
import com.openmason.ui.viewport.state.TransformState;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Handles mouse interaction with the transform gizmo.
 * Manages hover detection, click handling, and drag operations.
 *
 * <p>This class follows SOLID principles:
 * - Single Responsibility: Only handles gizmo interaction logic
 * - Dependency Inversion: Depends on abstractions (GizmoState, TransformState)
 * - SAFE: Validates all inputs and handles edge cases
 */
public class GizmoInteractionHandler {

    private final GizmoState gizmoState;
    private final TransformState transformState;

    // Cached camera matrices
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f projectionMatrix = new Matrix4f();
    private int viewportWidth = 1;
    private int viewportHeight = 1;

    /**
     * Creates a new GizmoInteractionHandler.
     *
     * @param gizmoState The gizmo state to manage (must not be null)
     * @param transformState The transform state to modify (must not be null)
     * @throws IllegalArgumentException if any parameter is null
     */
    public GizmoInteractionHandler(GizmoState gizmoState, TransformState transformState) {
        if (gizmoState == null) {
            throw new IllegalArgumentException("GizmoState cannot be null");
        }
        if (transformState == null) {
            throw new IllegalArgumentException("TransformState cannot be null");
        }

        this.gizmoState = gizmoState;
        this.transformState = transformState;
    }

    /**
     * Updates the camera matrices used for raycasting.
     * Should be called each frame before processing input.
     *
     * @param view View matrix
     * @param projection Projection matrix
     * @param width Viewport width
     * @param height Viewport height
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
                transformState.getScale(),
                transformState.getScale(),
                transformState.getScale()
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
        RaycastUtil.Ray ray = RaycastUtil.createRayFromScreen(
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
    private float intersectPart(RaycastUtil.Ray ray, GizmoPart part) {
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
     * @param mouseDelta Mouse movement since drag start
     * @param constraint Active axis/plane constraint
     */
    private void handleTranslateDrag(Vector2f mouseDelta, AxisConstraint constraint) {
        Vector3f startPos = gizmoState.getDragStartObjectPos();
        Vector3f newPos = new Vector3f(startPos);

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

        transformState.setPosition(newPos.x, newPos.y, newPos.z);
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
        RaycastUtil.Ray currentRay = RaycastUtil.createRayFromScreen(
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
        RaycastUtil.Ray startRay = RaycastUtil.createRayFromScreen(
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
     * Note: TransformState only supports uniform scaling currently.
     *
     * @param mouseDelta Mouse movement since drag start
     * @param constraint Active axis constraint
     */
    private void handleScaleDrag(Vector2f mouseDelta, AxisConstraint constraint) {
        Vector3f startScale = gizmoState.getDragStartObjectScale();

        // Calculate scale factor from mouse movement
        // Use vertical mouse movement (more intuitive)
        float scaleFactor = 1.0f + (mouseDelta.y * 0.01f);

        // Apply snap if enabled
        if (gizmoState.isSnapEnabled()) {
            scaleFactor = snapToIncrement(scaleFactor, gizmoState.getSnapIncrement() * 0.1f);
        }

        // TransformState only supports uniform scaling
        // So all axis constraints result in uniform scaling
        float newScaleValue = startScale.x * scaleFactor;

        transformState.setScale(newScaleValue);
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
