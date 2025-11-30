package com.openmason.main.systems.viewport.viewportRendering.edge;

import com.openmason.main.systems.rendering.model.ModelRenderer;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.gizmo.interaction.RaycastUtil;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
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
 * Handles edge translation through plane-constrained dragging.
 * Follows the same pattern as VertexTranslationHandler (DRY, SOLID).
 * Uses Blender-style plane-constrained movement for intuitive 3D editing.
 */
public class EdgeTranslationHandler {

    private static final Logger logger = LoggerFactory.getLogger(EdgeTranslationHandler.class);

    private final EdgeSelectionState selectionState;
    private final EdgeRenderer edgeRenderer;
    private final VertexRenderer vertexRenderer;
    private final ModelRenderer modelRenderer;
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
    private Vector3f dragStartDelta = new Vector3f(); // Track accumulated translation

    /**
     * Creates a new EdgeTranslationHandler.
     *
     * @param selectionState The edge selection state
     * @param edgeRenderer The edge renderer for visual updates
     * @param vertexRenderer The vertex renderer for updating connected vertices
     * @param modelRenderer The block model renderer for updating the solid mesh
     * @param viewportState The viewport state for grid snapping settings
     * @param renderPipeline The render pipeline for marking data dirty
     * @param transformState The transform state for model space conversions
     */
    public EdgeTranslationHandler(EdgeSelectionState selectionState,
                                   EdgeRenderer edgeRenderer,
                                   VertexRenderer vertexRenderer,
                                   ModelRenderer modelRenderer,
                                   ViewportUIState viewportState,
                                   RenderPipeline renderPipeline,
                                   TransformState transformState) {
        if (selectionState == null) {
            throw new IllegalArgumentException("EdgeSelectionState cannot be null");
        }
        if (edgeRenderer == null) {
            throw new IllegalArgumentException("EdgeRenderer cannot be null");
        }
        if (vertexRenderer == null) {
            throw new IllegalArgumentException("VertexRenderer cannot be null");
        }
        if (modelRenderer == null) {
            throw new IllegalArgumentException("BlockModelRenderer cannot be null");
        }
        if (renderPipeline == null) {
            throw new IllegalArgumentException("RenderPipeline cannot be null");
        }
        if (transformState == null) {
            throw new IllegalArgumentException("TransformState cannot be null");
        }

        this.selectionState = selectionState;
        this.edgeRenderer = edgeRenderer;
        this.vertexRenderer = vertexRenderer;
        this.modelRenderer = modelRenderer;
        this.viewportState = viewportState;
        this.renderPipeline = renderPipeline;
        this.transformState = transformState;
    }

    /**
     * Update viewport state for snapping settings.
     */
    public void updateViewportState(ViewportUIState viewportState) {
        this.viewportState = viewportState;
    }

    /**
     * Updates the camera matrices used for raycasting.
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
     * Handles mouse press to start edge drag.
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

        // Start dragging the selected edge
        dragStartMouseX = mouseX;
        dragStartMouseY = mouseY;
        dragStartDelta.set(0, 0, 0);

        // Calculate working plane based on camera orientation
        Vector3f cameraDirection = getCameraDirection();
        Vector3f edgeMidpoint = selectionState.getMidpoint();

        if (cameraDirection == null || edgeMidpoint == null) {
            logger.warn("Cannot start drag: camera direction or edge midpoint is null");
            return false;
        }

        Vector3f planeNormal = selectOptimalPlane(cameraDirection);
        Vector3f planePoint = new Vector3f(edgeMidpoint);

        // Start drag in selection state
        selectionState.startDrag(planeNormal, planePoint);
        isDragging = true;

        logger.debug("Started dragging edge {} on plane with normal ({}, {}, {})",
                selectionState.getSelectedEdgeIndex(),
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

        // CRITICAL: Get OLD positions from renderer BEFORE updating
        // After the first frame, positions are no longer at "original" - they're at the last updated positions
        int edgeIndex = selectionState.getSelectedEdgeIndex();
        Vector3f[] oldEndpoints = edgeRenderer.getEdgeEndpoints(edgeIndex);

        if (oldEndpoints == null || oldEndpoints.length != 2) {
            logger.warn("Cannot get current edge endpoints for edge {}", edgeIndex);
            return;
        }

        Vector3f oldPoint1 = oldEndpoints[0];
        Vector3f oldPoint2 = oldEndpoints[1];

        // Calculate the new position using ray-plane intersection (world space)
        Vector3f worldSpacePosition = calculateEdgePosition(mouseX, mouseY);

        if (worldSpacePosition != null) {
            // Calculate delta from original midpoint
            Vector3f originalMidpoint = new Vector3f(
                selectionState.getOriginalPoint1()
            ).add(selectionState.getOriginalPoint2()).mul(0.5f);

            Vector3f delta = new Vector3f(worldSpacePosition).sub(originalMidpoint);

            // Apply grid snapping to the delta if enabled
            if (viewportState != null && viewportState.getGridSnappingEnabled().get()) {
                delta = applyGridSnapping(delta);
            }

            // Convert delta to model space
            Vector3f modelSpaceDelta = worldToModelSpaceDelta(delta);

            // Update selection state with delta
            selectionState.updatePosition(modelSpaceDelta);

            // Calculate NEW positions (original + accumulated delta)
            Vector3f newPoint1 = selectionState.getCurrentPoint1();
            Vector3f newPoint2 = selectionState.getCurrentPoint2();

            // Update geometry: OLD (from renderer) → NEW (calculated)
            // This is critical - we update from LAST position, not ORIGINAL position
            updateEdgeAndConnectedGeometry(oldPoint1, oldPoint2, newPoint1, newPoint2, edgeIndex);

            dragStartDelta.set(modelSpaceDelta);

            logger.trace("Dragging edge {} from ({},{},{}) ({},{},{}) to ({},{},{}) ({},{},{})",
                    edgeIndex,
                    String.format("%.2f", oldPoint1.x), String.format("%.2f", oldPoint1.y), String.format("%.2f", oldPoint1.z),
                    String.format("%.2f", oldPoint2.x), String.format("%.2f", oldPoint2.y), String.format("%.2f", oldPoint2.z),
                    String.format("%.2f", newPoint1.x), String.format("%.2f", newPoint1.y), String.format("%.2f", newPoint1.z),
                    String.format("%.2f", newPoint2.x), String.format("%.2f", newPoint2.y), String.format("%.2f", newPoint2.z));
        }
    }

    /**
     * Handles mouse release to end drag.
     */
    public void handleMouseRelease(float mouseX, float mouseY) {
        if (!isDragging) {
            return;
        }

        selectionState.endDrag();
        isDragging = false;

        logger.debug("Ended edge drag at edge {}", selectionState.getSelectedEdgeIndex());
    }

    /**
     * Cancels the current drag operation, reverting to original position.
     * Reverts all changes made during the drag:
     * 1. Selected edge position
     * 2. Both endpoint vertices (and all duplicates)
     * 3. All edges connected to those vertices
     * 4. Block model mesh
     * This mirrors VertexTranslationHandler approach but handles edge-specific updates.
     */
    public void cancelDrag() {
        if (!isDragging) {
            return;
        }

        // Get positions before cancelling
        Vector3f currentPoint1 = selectionState.getCurrentPoint1();
        Vector3f currentPoint2 = selectionState.getCurrentPoint2();

        // Revert to original position in selection state
        selectionState.cancelDrag();

        // Get original positions (after cancel, current = original)
        Vector3f originalPoint1 = selectionState.getOriginalPoint1();
        Vector3f originalPoint2 = selectionState.getOriginalPoint2();
        int edgeIndex = selectionState.getSelectedEdgeIndex();

        if (originalPoint1 != null && originalPoint2 != null && currentPoint1 != null && currentPoint2 != null && edgeIndex >= 0) {
            // Revert ALL vertices at endpoint 1 (current → original)
            vertexRenderer.updateVerticesByPosition(currentPoint1, originalPoint1);

            // Revert ALL vertices at endpoint 2 (current → original)
            vertexRenderer.updateVerticesByPosition(currentPoint2, originalPoint2);

            // Revert ALL edges connected to endpoint 1
            edgeRenderer.updateEdgesConnectedToVertex(currentPoint1, originalPoint1);

            // Revert ALL edges connected to endpoint 2
            edgeRenderer.updateEdgesConnectedToVertex(currentPoint2, originalPoint2);

            // Update block model mesh with reverted vertex positions
            float[] allVertexPositions = vertexRenderer.getAllVertexPositions();
            if (allVertexPositions != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(allVertexPositions);
            }
        }

        isDragging = false;
        logger.debug("Cancelled edge drag, reverted all changes to original positions");
    }

    /**
     * Update edge and all connected geometry (vertices, edges, faces).
     * This unified method ensures updates happen in the correct order:
     * 1. Update ALL vertices at both old endpoint positions → new positions (handles duplicates)
     * 2. Update ALL edges connected to those vertices (handles face-based edge cloning)
     * 3. Update block model mesh (textured cube faces)
     *
     * CRITICAL: Uses OLD positions from renderer (not original), so incremental updates work correctly.
     *
     * @param oldPoint1 Current position of endpoint 1 (from renderer)
     * @param oldPoint2 Current position of endpoint 2 (from renderer)
     * @param newPoint1 New position of endpoint 1 (calculated)
     * @param newPoint2 New position of endpoint 2 (calculated)
     * @param edgeIndex Index of the selected edge
     */
    private void updateEdgeAndConnectedGeometry(Vector3f oldPoint1, Vector3f oldPoint2,
                                                 Vector3f newPoint1, Vector3f newPoint2,
                                                 int edgeIndex) {
        if (oldPoint1 == null || oldPoint2 == null || newPoint1 == null || newPoint2 == null) {
            logger.warn("Cannot update geometry: null positions");
            return;
        }

        // Step 1: Update ALL vertices at endpoint 1 position (handles duplicate vertices across faces)
        vertexRenderer.updateVerticesByPosition(oldPoint1, newPoint1);

        // Step 2: Update ALL vertices at endpoint 2 position (handles duplicate vertices across faces)
        vertexRenderer.updateVerticesByPosition(oldPoint2, newPoint2);

        // Step 3: Update ALL edges connected to endpoint 1
        // This finds all edge endpoints at the old position and updates them to the new position
        // Handles the fact that a cube has 24 edge instances (4 per face × 6 faces)
        edgeRenderer.updateEdgesConnectedToVertex(oldPoint1, newPoint1);

        // Step 4: Update ALL edges connected to endpoint 2
        edgeRenderer.updateEdgesConnectedToVertex(oldPoint2, newPoint2);

        // Step 5: CRITICAL - Update BlockModelRenderer mesh (the actual textured cube faces)
        // This ensures the solid mesh stays synchronized with vertex/edge positions
        float[] allVertexPositions = vertexRenderer.getAllVertexPositions();
        if (allVertexPositions != null && modelRenderer != null) {
            modelRenderer.updateVertexPositions(allVertexPositions);
        }

        logger.trace("Updated edge {} and all connected geometry (vertices, edges, faces)", edgeIndex);
    }

    /**
     * Selects the optimal working plane based on camera direction.
     */
    private Vector3f selectOptimalPlane(Vector3f cameraDirection) {
        Vector3f absDir = new Vector3f(
                Math.abs(cameraDirection.x),
                Math.abs(cameraDirection.y),
                Math.abs(cameraDirection.z)
        );

        if (absDir.x > absDir.y && absDir.x > absDir.z) {
            return new Vector3f(1, 0, 0); // YZ plane
        } else if (absDir.y > absDir.z) {
            return new Vector3f(0, 1, 0); // XZ plane
        } else {
            return new Vector3f(0, 0, 1); // XY plane
        }
    }

    /**
     * Calculates the new edge midpoint position by intersecting mouse ray with working plane.
     */
    private Vector3f calculateEdgePosition(float mouseX, float mouseY) {
        Vector3f planeNormal = selectionState.getPlaneNormal();
        Vector3f planePoint = selectionState.getPlanePoint();

        if (planeNormal == null || planePoint == null) {
            logger.warn("Cannot calculate edge position: plane not defined");
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
            // No intersection - keep current position
            return selectionState.getMidpoint();
        }

        // Get intersection point
        Vector3f newPosition = RaycastUtil.getPointOnRay(ray, t);

        // Fallback for parallel planes
        Vector3f cameraDirection = getCameraDirection();
        if (cameraDirection != null) {
            float dotProduct = Math.abs(planeNormal.dot(cameraDirection));
            if (dotProduct < 0.1f) {
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
     * Applies grid snapping to a delta vector.
     */
    private Vector3f applyGridSnapping(Vector3f delta) {
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
     * Gets the camera's forward direction vector from the view matrix.
     */
    private Vector3f getCameraDirection() {
        if (viewMatrix == null) {
            return null;
        }

        Matrix4f invView = new Matrix4f(viewMatrix).invert();
        return new Vector3f(-invView.m20(), -invView.m21(), -invView.m22()).normalize();
    }

    /**
     * Converts a world-space delta to model-space delta.
     * Only transforms the direction, not the position.
     */
    private Vector3f worldToModelSpaceDelta(Vector3f worldDelta) {
        Matrix4f modelMatrix = transformState.getTransformMatrix();
        Matrix4f inverseModelMatrix = new Matrix4f(modelMatrix).invert();

        // Transform delta as a direction (w=0)
        Vector4f worldDelta4 = new Vector4f(worldDelta.x, worldDelta.y, worldDelta.z, 0.0f);
        Vector4f modelDelta4 = inverseModelMatrix.transform(worldDelta4);

        return new Vector3f(modelDelta4.x, modelDelta4.y, modelDelta4.z);
    }

    /**
     * Check if currently dragging an edge.
     */
    public boolean isDragging() {
        return isDragging;
    }
}
