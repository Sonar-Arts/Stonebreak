package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.util.RaycastUtil;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoState;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Handles drag operations for all gizmo transform modes (translate, rotate, scale).
 * Computes new transform values from mouse movement and delegates application
 * to the {@link TransformApplier}.
 */
public class DragHandler {

    private final GizmoState gizmoState;
    private final TransformState transformState;
    private final TransformApplier transformApplier;

    // Gizmo world center (set externally for rotation pivot)
    private final Vector3f gizmoWorldCenter = new Vector3f();

    // Cached camera state
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f projectionMatrix = new Matrix4f();
    private int viewportWidth = 1;
    private int viewportHeight = 1;

    // External references needed during drag
    private ViewportUIState viewportState;
    private ITransformTarget transformTarget;

    public DragHandler(GizmoState gizmoState, TransformState transformState,
                       TransformApplier transformApplier) {
        if (gizmoState == null || transformState == null || transformApplier == null) {
            throw new IllegalArgumentException("Constructor arguments cannot be null");
        }
        this.gizmoState = gizmoState;
        this.transformState = transformState;
        this.transformApplier = transformApplier;
    }

    /**
     * Updates external state references. Call before processing drag each frame.
     */
    public void updateState(ViewportUIState viewportState, ITransformTarget transformTarget) {
        this.viewportState = viewportState;
        this.transformTarget = transformTarget;
    }

    /**
     * Updates the cached camera matrices.
     */
    public void updateCamera(Matrix4f view, Matrix4f projection, int width, int height) {
        this.viewMatrix.set(view);
        this.projectionMatrix.set(projection);
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    /**
     * Sets the gizmo's world-space center for rotation pivot.
     */
    public void setGizmoWorldCenter(Vector3f center) {
        if (center != null) {
            this.gizmoWorldCenter.set(center);
        }
    }

    /**
     * Processes a drag operation based on the current gizmo mode.
     *
     * @param mouseX Current mouse X position
     * @param mouseY Current mouse Y position
     * @param activeTarget The active part target (may be null for model-level transforms)
     */
    public void handleDrag(float mouseX, float mouseY, ITransformTarget activeTarget) {
        AxisConstraint constraint = gizmoState.getActiveConstraint();
        if (constraint == AxisConstraint.NONE) {
            return;
        }

        Vector2f startMouse = gizmoState.getDragStartMousePos();
        Vector2f mouseDelta = new Vector2f(mouseX - startMouse.x, mouseY - startMouse.y);

        switch (gizmoState.getCurrentMode()) {
            case TRANSLATE -> handleTranslateDrag(mouseDelta, constraint, activeTarget);
            case ROTATE -> handleRotateDrag(mouseX, mouseY, constraint, activeTarget);
            case SCALE -> handleScaleDrag(mouseDelta, constraint, activeTarget);
        }
    }

    // --- Translate ---

    private void handleTranslateDrag(Vector2f mouseDelta, AxisConstraint constraint,
                                     ITransformTarget activeTarget) {
        Vector3f startPos = gizmoState.getDragStartObjectPos();
        Vector3f newPos = new Vector3f(startPos);

        if (constraint.isSingleAxis()) {
            Vector3f axis = getAxisVector(constraint);
            float movement = RaycastUtil.projectScreenDeltaOntoAxis(
                    mouseDelta, axis, viewMatrix, projectionMatrix, viewportWidth, viewportHeight
            );

            if (gizmoState.isSnapEnabled()) {
                movement = snapToIncrement(movement, gizmoState.getSnapIncrement());
            }

            newPos.add(axis.x * movement, axis.y * movement, axis.z * movement);

        } else if (constraint.isPlane()) {
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

        transformApplier.applyPosition(newPos, activeTarget, transformTarget, startPos, viewportState);
    }

    // --- Rotate ---

    private void handleRotateDrag(float mouseX, float mouseY, AxisConstraint constraint,
                                  ITransformTarget activeTarget) {
        CoordinateSystem.Ray currentRay = RaycastUtil.createRayFromScreen(
                mouseX, mouseY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix
        );

        Vector3f axis = getAxisVector(constraint);
        Vector3f center = new Vector3f(gizmoWorldCenter);

        float t = RaycastUtil.intersectRayPlane(currentRay, center, axis);
        if (Float.isInfinite(t)) {
            return;
        }

        Vector3f currentPoint = RaycastUtil.getPointOnRay(currentRay, t);

        Vector2f startMouse = gizmoState.getDragStartMousePos();
        CoordinateSystem.Ray startRay = RaycastUtil.createRayFromScreen(
                startMouse.x, startMouse.y, viewportWidth, viewportHeight, viewMatrix, projectionMatrix
        );
        float tStart = RaycastUtil.intersectRayPlane(startRay, center, axis);
        if (Float.isInfinite(tStart)) {
            return;
        }

        Vector3f startPoint = RaycastUtil.getPointOnRay(startRay, tStart);

        Vector3f toStart = new Vector3f(startPoint).sub(center).normalize();
        Vector3f toCurrent = new Vector3f(currentPoint).sub(center).normalize();

        float angle = (float) Math.acos(Math.max(-1.0f, Math.min(1.0f, toStart.dot(toCurrent))));

        Vector3f cross = new Vector3f();
        toStart.cross(toCurrent, cross);
        if (cross.dot(axis) < 0) {
            angle = -angle;
        }

        float angleDegrees = (float) Math.toDegrees(angle);

        if (gizmoState.isSnapEnabled()) {
            angleDegrees = snapToIncrement(angleDegrees, gizmoState.getSnapIncrement());
        }

        Vector3f startRot = gizmoState.getDragStartObjectRotation();
        Vector3f newRot = new Vector3f(startRot);

        if (constraint == AxisConstraint.X) {
            newRot.x += angleDegrees;
        } else if (constraint == AxisConstraint.Y) {
            newRot.y += angleDegrees;
        } else if (constraint == AxisConstraint.Z) {
            newRot.z += angleDegrees;
        }

        transformApplier.applyRotation(newRot, activeTarget, transformTarget);
    }

    // --- Scale ---

    private void handleScaleDrag(Vector2f mouseDelta, AxisConstraint constraint,
                                 ITransformTarget activeTarget) {
        Vector3f startScale = gizmoState.getDragStartObjectScale();

        boolean shouldScaleUniformly = gizmoState.isUniformScaling() || constraint == AxisConstraint.NONE;

        if (shouldScaleUniformly) {
            float scaleFactor = 1.0f + (mouseDelta.y * 0.05f);

            if (gizmoState.isSnapEnabled()) {
                scaleFactor = snapToIncrement(scaleFactor, gizmoState.getSnapIncrement() * 0.1f);
            }

            float newScaleX = startScale.x * scaleFactor;
            float newScaleY = startScale.y * scaleFactor;
            float newScaleZ = startScale.z * scaleFactor;
            transformApplier.applyScale(newScaleX, newScaleY, newScaleZ, activeTarget, transformTarget);
        } else {
            Vector3f axis = getAxisVector(constraint);
            float movement = RaycastUtil.projectScreenDeltaOntoAxis(
                    mouseDelta, axis, viewMatrix, projectionMatrix, viewportWidth, viewportHeight
            );

            float scaleFactor = 1.0f + (movement * 0.1f);

            if (gizmoState.isSnapEnabled()) {
                scaleFactor = snapToIncrement(scaleFactor, gizmoState.getSnapIncrement() * 0.1f);
            }

            float newScaleX = startScale.x;
            float newScaleY = startScale.y;
            float newScaleZ = startScale.z;

            switch (constraint) {
                case X -> newScaleX = startScale.x * scaleFactor;
                case Y -> newScaleY = startScale.y * scaleFactor;
                case Z -> newScaleZ = startScale.z * scaleFactor;
            }

            transformApplier.applyScale(newScaleX, newScaleY, newScaleZ, activeTarget, transformTarget);
        }
    }

    // --- Axis helpers ---

    static Vector3f getAxisVector(AxisConstraint constraint) {
        return switch (constraint) {
            case X -> new Vector3f(1, 0, 0);
            case Y -> new Vector3f(0, 1, 0);
            case Z -> new Vector3f(0, 0, 1);
            default -> new Vector3f(0, 0, 0);
        };
    }

    static Vector3f getPlaneAxis1(AxisConstraint constraint) {
        return switch (constraint) {
            case XY, XZ -> new Vector3f(1, 0, 0);
            case YZ -> new Vector3f(0, 1, 0);
            default -> new Vector3f(0, 0, 0);
        };
    }

    static Vector3f getPlaneAxis2(AxisConstraint constraint) {
        return switch (constraint) {
            case XY -> new Vector3f(0, 1, 0);
            case XZ -> new Vector3f(0, 0, 1);
            case YZ -> new Vector3f(0, 0, 1);
            default -> new Vector3f(0, 0, 0);
        };
    }

    static float snapToIncrement(float value, float increment) {
        if (increment <= 0.0f) {
            return value;
        }
        return Math.round(value / increment) * increment;
    }
}
