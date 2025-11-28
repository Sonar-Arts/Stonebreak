package com.openmason.main.systems.viewport.viewportRendering.vertex;

import com.openmason.main.systems.rendering.model.blockmodel.BlockModelRenderer;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.gizmo.interaction.RaycastUtil;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.util.SnappingUtil;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.RenderPipeline;
import com.openmason.main.systems.viewport.viewportRendering.VertexRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles vertex translation through plane-constrained dragging.
 * Similar to GizmoInteractionHandler but for direct vertex manipulation.
 * Uses Blender-style plane-constrained movement for intuitive 3D editing.
 */
public class VertexTranslationHandler {

    private static final Logger logger = LoggerFactory.getLogger(VertexTranslationHandler.class);

    private final VertexSelectionState selectionState;
    private final VertexRenderer vertexRenderer;
    private final EdgeRenderer edgeRenderer;
    private final BlockModelRenderer blockModelRenderer;
    private ViewportUIState viewportState;
    private final RenderPipeline renderPipeline;
    private final TransformState transformState;

    // Cached camera matrices
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f projectionMatrix = new Matrix4f();
    private int viewportWidth = 1;
    private int viewportHeight = 1;

    // Drag state
    private boolean isDragging = false;
    private float dragStartMouseX = 0.0f;
    private float dragStartMouseY = 0.0f;

    /**
     * Creates a new VertexTranslationHandler.
     *
     * @param selectionState The vertex selection state
     * @param vertexRenderer The vertex renderer for visual updates
     * @param edgeRenderer The edge renderer for updating edge endpoints
     * @param blockModelRenderer The block model renderer for updating the solid cube mesh
     * @param viewportState The viewport state for grid snapping settings
     * @param renderPipeline The render pipeline for marking edge data dirty
     * @param transformState The transform state for model space conversions
     */
    public VertexTranslationHandler(VertexSelectionState selectionState,
                                    VertexRenderer vertexRenderer,
                                    EdgeRenderer edgeRenderer,
                                    BlockModelRenderer blockModelRenderer,
                                    ViewportUIState viewportState,
                                    RenderPipeline renderPipeline,
                                    TransformState transformState) {
        if (selectionState == null) {
            throw new IllegalArgumentException("VertexSelectionState cannot be null");
        }
        if (vertexRenderer == null) {
            throw new IllegalArgumentException("VertexRenderer cannot be null");
        }
        if (edgeRenderer == null) {
            throw new IllegalArgumentException("EdgeRenderer cannot be null");
        }
        if (blockModelRenderer == null) {
            throw new IllegalArgumentException("BlockModelRenderer cannot be null");
        }
        if (renderPipeline == null) {
            throw new IllegalArgumentException("RenderPipeline cannot be null");
        }
        if (transformState == null) {
            throw new IllegalArgumentException("TransformState cannot be null");
        }

        this.selectionState = selectionState;
        this.vertexRenderer = vertexRenderer;
        this.edgeRenderer = edgeRenderer;
        this.blockModelRenderer = blockModelRenderer;
        this.viewportState = viewportState;
        this.renderPipeline = renderPipeline;
        this.transformState = transformState;
    }

    /**
     * Update viewport state for snapping settings.
     * Should be called whenever viewport state changes.
     */
    public void updateViewportState(ViewportUIState viewportState) {
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
     * Handles mouse press to start vertex drag.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @return true if drag was started, false otherwise
     */
    public boolean handleMousePress(float mouseX, float mouseY) {
        if (!selectionState.hasSelection()) {
            return false;
        }

        if (isDragging) {
            return false; // Already dragging
        }

        // Start dragging the selected vertex
        dragStartMouseX = mouseX;
        dragStartMouseY = mouseY;

        // Calculate working plane based on camera orientation
        Vector3f cameraDirection = getCameraDirection();
        Vector3f vertexPosition = selectionState.getCurrentPosition();

        if (cameraDirection == null || vertexPosition == null) {
            logger.warn("Cannot start drag: camera direction or vertex position is null");
            return false;
        }

        Vector3f planeNormal = selectOptimalPlane(cameraDirection);
        Vector3f planePoint = new Vector3f(vertexPosition);

        // Start drag in selection state
        selectionState.startDrag(planeNormal, planePoint);
        isDragging = true;

        logger.debug("Started dragging vertex {} on plane with normal ({}, {}, {})",
                selectionState.getSelectedVertexIndex(),
                String.format("%.2f", planeNormal.x),
                String.format("%.2f", planeNormal.y),
                String.format("%.2f", planeNormal.z));

        return true;
    }

    /**
     * Handles mouse movement during drag.
     *
     * @param mouseX Current mouse X position
     * @param mouseY Current mouse Y position
     */
    public void handleMouseMove(float mouseX, float mouseY) {
        if (!isDragging || !selectionState.isDragging()) {
            return;
        }

        // Calculate new vertex position using ray-plane intersection (world space)
        Vector3f worldSpacePosition = calculateVertexPosition(mouseX, mouseY);

        if (worldSpacePosition != null) {
            // Apply grid snapping if enabled (in world space)
            if (viewportState != null && viewportState.getGridSnappingEnabled().get()) {
                worldSpacePosition = applyGridSnapping(worldSpacePosition);
            }

            // Convert from world space to model space
            // Vertices are stored in model space and transformed by model matrix in shader
            Vector3f modelSpacePosition = worldToModelSpace(worldSpacePosition);

            // Get old position BEFORE updating (needed for edge update)
            int vertexIndex = selectionState.getSelectedVertexIndex();
            Vector3f oldModelSpacePosition = vertexRenderer.getVertexPosition(vertexIndex);

            // Update selection state (world space for display)
            selectionState.updatePosition(worldSpacePosition);

            // Update visual preview (model space for VBO)
            vertexRenderer.updateVertexPosition(vertexIndex, modelSpacePosition);

            // Update connected edge endpoints directly (model space for VBO)
            // Pass old and new positions to find all matching endpoints
            if (oldModelSpacePosition != null) {
                edgeRenderer.updateEdgesConnectedToVertex(oldModelSpacePosition, modelSpacePosition);
            }

            // CRITICAL: Update BlockModelRenderer mesh (the actual solid cube faces)
            // This ensures the textured cube mesh moves with the dragged vertices
            float[] allVertexPositions = vertexRenderer.getAllVertexPositions();
            if (allVertexPositions != null && blockModelRenderer != null) {
                blockModelRenderer.updateVertexPositions(allVertexPositions);
            }

            logger.trace("Dragging vertex {} to world ({}, {}, {}) → model ({}, {}, {})",
                    vertexIndex,
                    String.format("%.2f", worldSpacePosition.x),
                    String.format("%.2f", worldSpacePosition.y),
                    String.format("%.2f", worldSpacePosition.z),
                    String.format("%.2f", modelSpacePosition.x),
                    String.format("%.2f", modelSpacePosition.y),
                    String.format("%.2f", modelSpacePosition.z));
        }
    }

    /**
     * Handles mouse release to end drag.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     */
    public void handleMouseRelease(float mouseX, float mouseY) {
        if (!isDragging) {
            return;
        }

        selectionState.endDrag();
        isDragging = false;

        logger.debug("Ended vertex drag at position ({}, {}, {})",
                String.format("%.2f", selectionState.getCurrentPosition().x),
                String.format("%.2f", selectionState.getCurrentPosition().y),
                String.format("%.2f", selectionState.getCurrentPosition().z));
    }

    /**
     * Cancels the current drag operation, reverting to original position.
     */
    public void cancelDrag() {
        if (!isDragging) {
            return;
        }

        // Revert to original position
        selectionState.cancelDrag();

        // Update visual to original position
        int vertexIndex = selectionState.getSelectedVertexIndex();
        Vector3f originalPosition = selectionState.getOriginalPosition();
        if (originalPosition != null) {
            vertexRenderer.updateVertexPosition(vertexIndex, originalPosition);
        }

        isDragging = false;
        logger.debug("Cancelled vertex drag, reverted to original position");
    }

    /**
     * Selects the optimal working plane based on camera direction.
     * Uses Blender-style plane selection: choose plane perpendicular to dominant camera axis.
     *
     * @param cameraDirection The camera's forward direction vector
     * @return Normal vector of the optimal working plane
     */
    private Vector3f selectOptimalPlane(Vector3f cameraDirection) {
        // Find dominant camera axis by checking absolute values
        Vector3f absDir = new Vector3f(
                Math.abs(cameraDirection.x),
                Math.abs(cameraDirection.y),
                Math.abs(cameraDirection.z)
        );

        // Choose plane perpendicular to dominant axis
        // If camera faces +X, use YZ plane (normal = X axis)
        // If camera faces +Y, use XZ plane (normal = Y axis)
        // If camera faces +Z, use XY plane (normal = Z axis)
        if (absDir.x > absDir.y && absDir.x > absDir.z) {
            return new Vector3f(1, 0, 0); // YZ plane
        } else if (absDir.y > absDir.z) {
            return new Vector3f(0, 1, 0); // XZ plane
        } else {
            return new Vector3f(0, 0, 1); // XY plane
        }
    }

    /**
     * Calculates the new vertex position by intersecting mouse ray with working plane.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @return New vertex position, or null if no intersection
     */
    private Vector3f calculateVertexPosition(float mouseX, float mouseY) {
        // Get working plane from selection state
        Vector3f planeNormal = selectionState.getPlaneNormal();
        Vector3f planePoint = selectionState.getPlanePoint();

        if (planeNormal == null || planePoint == null) {
            logger.warn("Cannot calculate vertex position: plane not defined");
            return null;
        }

        // Create ray from mouse position
        CoordinateSystem.Ray ray = CoordinateSystem.createWorldRayFromScreen(
                mouseX, mouseY,
                viewportWidth, viewportHeight,
                viewMatrix, projectionMatrix
        );

        // Intersect ray with working plane
        float t = RaycastUtil.intersectRayPlane(ray, planePoint, planeNormal);

        if (Float.isInfinite(t) || t < 0) {
            // No intersection or behind camera - keep current position
            return selectionState.getCurrentPosition();
        }

        // Get intersection point (new vertex position)
        Vector3f newPosition = RaycastUtil.getPointOnRay(ray, t);

        // Fallback check: if plane is nearly parallel to view direction, use fallback plane
        Vector3f cameraDirection = getCameraDirection();
        if (cameraDirection != null) {
            float dotProduct = Math.abs(planeNormal.dot(cameraDirection));
            if (dotProduct < 0.1f) {
                // Plane is nearly parallel to camera - use XY plane as fallback
                logger.trace("Plane parallel to camera (dot={}), using XY fallback", String.format("%.3f", dotProduct));
                planeNormal = new Vector3f(0, 0, 1);
                t = RaycastUtil.intersectRayPlane(ray, planePoint, planeNormal);
                if (!Float.isInfinite(t) && t >= 0) {
                    newPosition = RaycastUtil.getPointOnRay(ray, t);
                }
            }
        }

        return newPosition;
    }

    /**
     * Applies grid snapping to a position.
     *
     * @param position The position to snap
     * @return Snapped position
     */
    private Vector3f applyGridSnapping(Vector3f position) {
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
     * Gets the camera's forward direction vector from the view matrix.
     *
     * @return Camera forward direction, or null if view matrix is not set
     */
    private Vector3f getCameraDirection() {
        if (viewMatrix == null) {
            return null;
        }

        // Extract forward vector from view matrix (negative Z axis in view space)
        // View matrix inverse gives camera world transform
        Matrix4f invView = new Matrix4f(viewMatrix).invert();

        // Forward direction is -Z column of camera transform
        return new Vector3f(-invView.m20(), -invView.m21(), -invView.m22()).normalize();
    }

    /**
     * Converts a world-space position to model-space.
     * Applies the inverse of the model transform matrix.
     *
     * @param worldPos Position in world space
     * @return Position in model space
     */
    private Vector3f worldToModelSpace(Vector3f worldPos) {
        // Get the model transform matrix
        Matrix4f modelMatrix = transformState.getTransformMatrix();

        // Invert the model matrix to go from world space to model space
        Matrix4f inverseModelMatrix = new Matrix4f(modelMatrix).invert();

        // Transform the world position to model space
        Vector4f worldPos4 = new Vector4f(worldPos.x, worldPos.y, worldPos.z, 1.0f);
        Vector4f modelPos4 = inverseModelMatrix.transform(worldPos4);

        // Return as Vector3f (perspective divide by w if needed, but w should be 1.0)
        return new Vector3f(modelPos4.x, modelPos4.y, modelPos4.z);
    }

    /**
     * Check if currently dragging a vertex.
     *
     * @return true if dragging, false otherwise
     */
    public boolean isDragging() {
        return isDragging;
    }
}
