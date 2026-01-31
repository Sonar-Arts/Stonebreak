package com.openmason.main.systems.viewport.viewportRendering.common;

import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.util.RaycastUtil;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.util.SnappingUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for translation handlers.
 * Provides common functionality for plane-constrained dragging.
 * Follows DRY principle by centralizing shared logic between
 * VertexTranslationHandler and EdgeTranslationHandler.
 *
 * Uses Template Method pattern for drag behavior.
 */
public abstract class TranslationHandlerBase implements ITranslationHandler {

    private static final Logger logger = LoggerFactory.getLogger(TranslationHandlerBase.class);

    // Dependencies
    protected ViewportUIState viewportState;
    protected final TransformState transformState;

    // Cached camera matrices
    protected Matrix4f viewMatrix = new Matrix4f();
    protected Matrix4f projectionMatrix = new Matrix4f();
    protected int viewportWidth = 1;
    protected int viewportHeight = 1;

    // Drag state
    protected boolean isDragging = false;
    protected boolean hasMovedDuringDrag = false;
    protected float dragStartMouseX = 0.0f;
    protected float dragStartMouseY = 0.0f;
    protected Vector3f initialDragHitPoint = null; // Initial plane hit point for delta calculation

    /**
     * Creates a new TranslationHandlerBase.
     *
     * @param viewportState The viewport state for grid snapping settings
     * @param transformState The transform state for model space conversions
     */
    protected TranslationHandlerBase(ViewportUIState viewportState, TransformState transformState) {
        if (transformState == null) {
            throw new IllegalArgumentException("TransformState cannot be null");
        }

        this.viewportState = viewportState;
        this.transformState = transformState;
    }

    /**
     * Update viewport state for snapping settings.
     */
    public void updateViewportState(ViewportUIState viewportState) {
        this.viewportState = viewportState;
    }

    @Override
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

    @Override
    public boolean isDragging() {
        return isDragging;
    }

    /**
     * Selects the optimal working plane based on camera direction.
     * Uses Blender-style plane selection: choose plane perpendicular to dominant camera axis.
     *
     * @param cameraDirection The camera's forward direction vector
     * @return Normal vector of the optimal working plane
     */
    protected Vector3f selectOptimalPlane(Vector3f cameraDirection) {
        // Find dominant camera axis by checking absolute values
        Vector3f absDir = new Vector3f(
                Math.abs(cameraDirection.x),
                Math.abs(cameraDirection.y),
                Math.abs(cameraDirection.z)
        );

        // Choose plane perpendicular to dominant axis
        if (absDir.x > absDir.y && absDir.x > absDir.z) {
            return new Vector3f(1, 0, 0); // YZ plane
        } else if (absDir.y > absDir.z) {
            return new Vector3f(0, 1, 0); // XZ plane
        } else {
            return new Vector3f(0, 0, 1); // XY plane
        }
    }

    /**
     * Gets the camera's forward direction vector from the view matrix.
     *
     * @return Camera forward direction, or null if view matrix is not set
     */
    protected Vector3f getCameraDirection() {
        if (viewMatrix == null) {
            return null;
        }

        // Extract forward vector from view matrix (negative Z axis in view space)
        Matrix4f invView = new Matrix4f(viewMatrix).invert();
        return new Vector3f(-invView.m20(), -invView.m21(), -invView.m22()).normalize();
    }

    /**
     * Applies grid snapping to a position.
     *
     * @param position The position to snap
     * @return Snapped position
     */
    protected Vector3f applyGridSnapping(Vector3f position) {
        if (viewportState == null) {
            return position;
        }

        float increment = viewportState.getGridSnappingIncrement().get();

        return new Vector3f(
                SnappingUtil.snapToGrid(position.x, increment),
                SnappingUtil.snapToGrid(position.y, increment),
                SnappingUtil.snapToGrid(position.z, increment)
        );
    }

    /**
     * Applies grid snapping to a delta vector.
     *
     * @param delta The delta to snap
     * @return Snapped delta
     */
    protected Vector3f applyGridSnappingToDelta(Vector3f delta) {
        if (viewportState == null) {
            return delta;
        }

        float increment = viewportState.getGridSnappingIncrement().get();

        return new Vector3f(
                SnappingUtil.snapToGrid(delta.x, increment),
                SnappingUtil.snapToGrid(delta.y, increment),
                SnappingUtil.snapToGrid(delta.z, increment)
        );
    }

    /**
     * Creates a ray from mouse position using current camera matrices.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @return Ray from mouse position into world space
     */
    protected CoordinateSystem.Ray createMouseRay(float mouseX, float mouseY) {
        return CoordinateSystem.createWorldRayFromScreen(
                mouseX, mouseY,
                viewportWidth, viewportHeight,
                viewMatrix, projectionMatrix
        );
    }

    /**
     * Intersects a ray with a plane and returns the intersection point.
     *
     * @param ray Ray to intersect
     * @param planePoint Point on the plane
     * @param planeNormal Normal vector of the plane
     * @param fallbackPosition Position to return if no valid intersection
     * @return Intersection point, or fallbackPosition if no valid intersection
     */
    protected Vector3f intersectRayPlane(CoordinateSystem.Ray ray, Vector3f planePoint,
                                         Vector3f planeNormal, Vector3f fallbackPosition) {
        float t = RaycastUtil.intersectRayPlane(ray, planePoint, planeNormal);

        if (Float.isInfinite(t) || t < 0) {
            return fallbackPosition;
        }

        Vector3f intersection = RaycastUtil.getPointOnRay(ray, t);

        // Fallback check: if plane is nearly parallel to view direction
        Vector3f cameraDirection = getCameraDirection();
        if (cameraDirection != null) {
            float dotProduct = Math.abs(planeNormal.dot(cameraDirection));
            if (dotProduct < 0.1f) {
                // Plane is nearly parallel - use XY plane as fallback
                logger.trace("Plane parallel to camera (dot={}), using XY fallback",
                        String.format("%.3f", dotProduct));
                planeNormal = new Vector3f(0, 0, 1);
                t = RaycastUtil.intersectRayPlane(ray, planePoint, planeNormal);
                if (!Float.isInfinite(t) && t >= 0) {
                    intersection = RaycastUtil.getPointOnRay(ray, t);
                }
            }
        }

        return intersection;
    }

    /**
     * Converts a world-space position to model-space.
     *
     * @param worldPos Position in world space
     * @return Position in model space
     */
    protected Vector3f worldToModelSpace(Vector3f worldPos) {
        Matrix4f modelMatrix = transformState.getTransformMatrix();
        Matrix4f inverseModelMatrix = new Matrix4f(modelMatrix).invert();

        Vector4f worldPos4 = new Vector4f(worldPos.x, worldPos.y, worldPos.z, 1.0f);
        Vector4f modelPos4 = inverseModelMatrix.transform(worldPos4);

        return new Vector3f(modelPos4.x, modelPos4.y, modelPos4.z);
    }

    /**
     * Converts a world-space delta to model-space delta.
     * Only transforms the direction, not the position.
     *
     * @param worldDelta Delta in world space
     * @return Delta in model space
     */
    protected Vector3f worldToModelSpaceDelta(Vector3f worldDelta) {
        Matrix4f modelMatrix = transformState.getTransformMatrix();
        Matrix4f inverseModelMatrix = new Matrix4f(modelMatrix).invert();

        // Transform delta as a direction (w=0)
        Vector4f worldDelta4 = new Vector4f(worldDelta.x, worldDelta.y, worldDelta.z, 0.0f);
        Vector4f modelDelta4 = inverseModelMatrix.transform(worldDelta4);

        return new Vector3f(modelDelta4.x, modelDelta4.y, modelDelta4.z);
    }

    /**
     * Validates camera matrices before use.
     *
     * @return true if matrices are valid, false otherwise
     */
    protected boolean validateCameraMatrices() {
        if (viewMatrix == null || projectionMatrix == null) {
            logger.warn("Camera matrices not set");
            return false;
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            logger.warn("Invalid viewport dimensions: {}x{}", viewportWidth, viewportHeight);
            return false;
        }
        return true;
    }

    /**
     * Calculates and stores the initial hit point on the drag plane.
     * Call this in handleMousePress after setting up the plane.
     * This enables delta-based movement (no jump to mouse position).
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param planePoint Point on the drag plane
     * @param planeNormal Normal of the drag plane
     */
    protected void calculateInitialDragHitPoint(float mouseX, float mouseY,
                                                 Vector3f planePoint, Vector3f planeNormal) {
        CoordinateSystem.Ray ray = createMouseRay(mouseX, mouseY);
        initialDragHitPoint = intersectRayPlane(ray, planePoint, planeNormal, new Vector3f(planePoint));
        logger.trace("Initial drag hit point: ({}, {}, {})",
                String.format("%.2f", initialDragHitPoint.x),
                String.format("%.2f", initialDragHitPoint.y),
                String.format("%.2f", initialDragHitPoint.z));
    }

    /**
     * Calculates the movement delta from initial hit point to current mouse position.
     * Returns the world-space delta that should be applied to original positions.
     *
     * @param mouseX Current mouse X position
     * @param mouseY Current mouse Y position
     * @param planePoint Point on the drag plane
     * @param planeNormal Normal of the drag plane
     * @return Delta from initial hit point to current hit point, or null if invalid
     */
    protected Vector3f calculateDragDelta(float mouseX, float mouseY,
                                          Vector3f planePoint, Vector3f planeNormal) {
        if (initialDragHitPoint == null) {
            logger.warn("Initial drag hit point not set");
            return null;
        }

        CoordinateSystem.Ray ray = createMouseRay(mouseX, mouseY);
        Vector3f currentHitPoint = intersectRayPlane(ray, planePoint, planeNormal, initialDragHitPoint);

        // Return delta from initial to current
        return new Vector3f(currentHitPoint).sub(initialDragHitPoint);
    }

    /**
     * Clears the initial drag hit point.
     * Call this when drag ends or is cancelled.
     */
    protected void clearInitialDragHitPoint() {
        initialDragHitPoint = null;
    }
}
