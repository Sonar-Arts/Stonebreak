package com.openmason.main.systems.viewport.viewportRendering.face;

import com.openmason.main.systems.rendering.model.ModelRenderer;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.gizmo.interaction.RaycastUtil;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.util.SnappingUtil;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.FaceRenderer;
import com.openmason.main.systems.viewport.viewportRendering.RenderPipeline;
import com.openmason.main.systems.viewport.viewportRendering.VertexRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles face translation through plane-constrained dragging.
 * Follows the same pattern as EdgeTranslationHandler and VertexTranslationHandler (DRY, SOLID).
 * Uses camera-optimal plane-constrained movement for intuitive 3D editing.
 * Manages 4-vertex face movement with delta-based updates.
 */
public class FaceTranslationHandler {

    private static final Logger logger = LoggerFactory.getLogger(FaceTranslationHandler.class);

    private final FaceSelectionState selectionState;
    private final FaceRenderer faceRenderer;
    private final VertexRenderer vertexRenderer;
    private final EdgeRenderer edgeRenderer;
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

    /**
     * Creates a new FaceTranslationHandler.
     *
     * @param selectionState The face selection state
     * @param faceRenderer The face renderer for visual updates
     * @param vertexRenderer The vertex renderer for updating connected vertices
     * @param edgeRenderer The edge renderer for updating connected edges
     * @param modelRenderer The block model renderer for updating the solid mesh
     * @param viewportState The viewport state for grid snapping settings
     * @param renderPipeline The render pipeline for marking data dirty
     * @param transformState The transform state for model space conversions
     */
    public FaceTranslationHandler(FaceSelectionState selectionState,
                                   FaceRenderer faceRenderer,
                                   VertexRenderer vertexRenderer,
                                   EdgeRenderer edgeRenderer,
                                   ModelRenderer modelRenderer,
                                   ViewportUIState viewportState,
                                   RenderPipeline renderPipeline,
                                   TransformState transformState) {
        if (selectionState == null) {
            throw new IllegalArgumentException("FaceSelectionState cannot be null");
        }
        if (faceRenderer == null) {
            throw new IllegalArgumentException("FaceRenderer cannot be null");
        }
        if (vertexRenderer == null) {
            throw new IllegalArgumentException("VertexRenderer cannot be null");
        }
        if (edgeRenderer == null) {
            throw new IllegalArgumentException("EdgeRenderer cannot be null");
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
        this.faceRenderer = faceRenderer;
        this.vertexRenderer = vertexRenderer;
        this.edgeRenderer = edgeRenderer;
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
     * Handles mouse press to start face drag.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @return true if drag was started, false otherwise
     */
    public boolean handleMousePress(float mouseX, float mouseY) {
        logger.info("FaceTranslationHandler.handleMousePress called - hasSelection: {}", selectionState.hasSelection());

        if (!selectionState.hasSelection()) {
            logger.warn("No face selected, cannot start drag");
            return false;
        }

        if (isDragging) {
            logger.warn("Already dragging, ignoring mouse press");
            return false; // Already dragging
        }

        // Start dragging the selected face
        dragStartMouseX = mouseX;
        dragStartMouseY = mouseY;

        // Calculate working plane based on camera orientation
        Vector3f cameraDirection = getCameraDirection();
        Vector3f faceCentroid = selectionState.getCentroid();

        if (cameraDirection == null || faceCentroid == null) {
            logger.warn("Cannot start drag: camera direction or face centroid is null");
            return false;
        }

        Vector3f planeNormal = selectOptimalPlane(cameraDirection);
        Vector3f planePoint = new Vector3f(faceCentroid);

        // Start drag in selection state
        selectionState.startDrag(planeNormal, planePoint);
        isDragging = true;

        logger.debug("Started dragging face {} on plane with normal ({}, {}, {})",
                selectionState.getSelectedFaceIndex(),
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
        int faceIndex = selectionState.getSelectedFaceIndex();
        Vector3f[] oldVertices = faceRenderer.getFaceVertices(faceIndex);

        if (oldVertices == null || oldVertices.length != 4) {
            logger.warn("Cannot get current face vertices for face {}", faceIndex);
            return;
        }

        // Calculate the new position using ray-plane intersection (world space)
        Vector3f worldSpacePosition = calculateFacePosition(mouseX, mouseY);

        if (worldSpacePosition != null) {
            // Calculate delta from original centroid
            Vector3f[] originalVertices = selectionState.getOriginalVertices();
            if (originalVertices == null) {
                logger.warn("Cannot get original vertices");
                return;
            }

            Vector3f originalCentroid = new Vector3f(originalVertices[0])
                    .add(originalVertices[1])
                    .add(originalVertices[2])
                    .add(originalVertices[3])
                    .mul(0.25f);

            Vector3f delta = new Vector3f(worldSpacePosition).sub(originalCentroid);

            // Apply grid snapping to the delta if enabled
            if (viewportState != null && viewportState.getGridSnappingEnabled().get()) {
                delta = applyGridSnapping(delta);
            }

            // Convert delta to model space
            Vector3f modelSpaceDelta = worldToModelSpaceDelta(delta);

            // Update selection state with delta
            selectionState.updatePosition(modelSpaceDelta);

            // Calculate NEW positions (original + accumulated delta)
            Vector3f[] newVertices = selectionState.getCurrentVertices();

            if (newVertices == null || newVertices.length != 4) {
                logger.warn("Cannot get new vertices after update");
                return;
            }

            // Update geometry: OLD (from renderer) → NEW (calculated)
            // This is critical - we update from LAST position, not ORIGINAL position
            updateFaceAndConnectedGeometry(oldVertices, newVertices, faceIndex);

            logger.trace("Dragging face {} with delta ({}, {}, {})",
                    faceIndex,
                    String.format("%.2f", modelSpaceDelta.x),
                    String.format("%.2f", modelSpaceDelta.y),
                    String.format("%.2f", modelSpaceDelta.z));
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

        logger.debug("Ended face drag at face {}", selectionState.getSelectedFaceIndex());
    }

    /**
     * Cancels the current drag operation, reverting to original position.
     * Reverts all changes made during the drag:
     * 1. Selected face position
     * 2. All 4 vertex positions (and all duplicates)
     * 3. All edges connected to those vertices
     * 4. Block model mesh
     * This mirrors EdgeTranslationHandler approach but handles 4 vertices instead of 2.
     */
    public void cancelDrag() {
        if (!isDragging) {
            return;
        }

        // Get positions before cancelling
        Vector3f[] currentVertices = selectionState.getCurrentVertices();

        // Revert to original position in selection state
        selectionState.cancelDrag();

        // Get original positions (after cancel, current = original)
        Vector3f[] originalVertices = selectionState.getOriginalVertices();
        int faceIndex = selectionState.getSelectedFaceIndex();

        if (originalVertices != null && currentVertices != null &&
            originalVertices.length == 4 && currentVertices.length == 4 &&
            faceIndex >= 0) {

            // Revert ALL 4 vertices (current → original)
            for (int i = 0; i < 4; i++) {
                vertexRenderer.updateVerticesByPosition(currentVertices[i], originalVertices[i]);
            }

            // Revert ALL connected edges (for all 4 vertices)
            for (int i = 0; i < 4; i++) {
                edgeRenderer.updateEdgesConnectedToVertex(currentVertices[i], originalVertices[i]);
            }

            // Update block model mesh with reverted vertex positions
            float[] allVertexPositions = vertexRenderer.getAllVertexPositions();
            if (allVertexPositions != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(allVertexPositions);
            }
        }

        isDragging = false;
        logger.debug("Cancelled face drag, reverted all changes to original positions");
    }

    /**
     * Update face and all connected geometry (vertices, edges, mesh).
     * This unified method ensures updates happen in the correct order:
     * 1. Update face renderer (selected face visual)
     * 2. Update ALL vertices at all 4 old positions → new positions (handles duplicates)
     * 3. Update ALL edges connected to those vertices (handles face-based edge cloning)
     * 4. Update block model mesh (textured cube faces)
     *
     * CRITICAL: Uses OLD positions from renderer (not original), so incremental updates work correctly.
     *
     * @param oldVertices Current positions of 4 vertices (from renderer)
     * @param newVertices New positions of 4 vertices (calculated)
     * @param faceIndex Index of the selected face
     */
    private void updateFaceAndConnectedGeometry(Vector3f[] oldVertices, Vector3f[] newVertices,
                                                 int faceIndex) {
        if (oldVertices == null || newVertices == null ||
            oldVertices.length != 4 || newVertices.length != 4) {
            logger.warn("Cannot update geometry: invalid vertex arrays");
            return;
        }

        // Step 1: Update face renderer
        faceRenderer.updateFacePosition(faceIndex, newVertices);

        // Step 2: Update ALL 4 vertices (handles duplicate vertices across faces)
        for (int i = 0; i < 4; i++) {
            vertexRenderer.updateVerticesByPosition(oldVertices[i], newVertices[i]);
        }

        // Step 3: Update ALL edges connected to all 4 vertices
        // This finds all edge endpoints at the old positions and updates them to the new positions
        // Handles the fact that a cube has 24 edge instances (4 per face × 6 faces)
        for (int i = 0; i < 4; i++) {
            edgeRenderer.updateEdgesConnectedToVertex(oldVertices[i], newVertices[i]);
        }

        // Step 4: CRITICAL - Update BlockModelRenderer mesh (the actual textured cube faces)
        // This ensures the solid mesh stays synchronized with vertex/edge positions
        float[] allVertexPositions = vertexRenderer.getAllVertexPositions();
        if (allVertexPositions != null && modelRenderer != null) {
            modelRenderer.updateVertexPositions(allVertexPositions);
        }

        logger.trace("Updated face {} and all connected geometry (vertices, edges, mesh)", faceIndex);
    }

    /**
     * Selects the optimal working plane based on camera direction.
     * Uses camera-optimal plane selection: choose plane perpendicular to dominant camera axis.
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
     * Calculates the new face centroid position by intersecting mouse ray with working plane.
     */
    private Vector3f calculateFacePosition(float mouseX, float mouseY) {
        Vector3f planeNormal = selectionState.getPlaneNormal();
        Vector3f planePoint = selectionState.getPlanePoint();

        if (planeNormal == null || planePoint == null) {
            logger.warn("Cannot calculate face position: plane not defined");
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
            return selectionState.getCentroid();
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
     * Only transforms the direction, not the position (w=0.0).
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
     * Check if currently dragging a face.
     */
    public boolean isDragging() {
        return isDragging;
    }
}
