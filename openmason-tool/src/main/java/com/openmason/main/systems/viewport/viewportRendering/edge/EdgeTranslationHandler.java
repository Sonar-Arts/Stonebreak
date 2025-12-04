package com.openmason.main.systems.viewport.viewportRendering.edge;

import com.openmason.main.systems.rendering.model.ModelRenderer;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.RenderPipeline;
import com.openmason.main.systems.viewport.viewportRendering.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.common.TranslationHandlerBase;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles edge translation through plane-constrained dragging.
 * Extends TranslationHandlerBase for shared functionality (DRY, SOLID).
 * Uses Blender-style plane-constrained movement for intuitive 3D editing.
 */
public class EdgeTranslationHandler extends TranslationHandlerBase {

    private static final Logger logger = LoggerFactory.getLogger(EdgeTranslationHandler.class);

    private final EdgeSelectionState selectionState;
    private final EdgeRenderer edgeRenderer;
    private final VertexRenderer vertexRenderer;
    private final ModelRenderer modelRenderer;
    private final RenderPipeline renderPipeline;

    // Edge-specific drag state
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
        super(viewportState, transformState);

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

        this.selectionState = selectionState;
        this.edgeRenderer = edgeRenderer;
        this.vertexRenderer = vertexRenderer;
        this.modelRenderer = modelRenderer;
        this.renderPipeline = renderPipeline;
    }

    @Override
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

        // Calculate working plane based on camera orientation (from base class)
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

    @Override
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

            // Apply grid snapping to the delta if enabled (from base class)
            if (viewportState != null && viewportState.getGridSnappingEnabled().get()) {
                delta = applyGridSnappingToDelta(delta);
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

    @Override
    public void handleMouseRelease(float mouseX, float mouseY) {
        if (!isDragging) {
            return;
        }

        selectionState.endDrag();
        isDragging = false;

        logger.debug("Ended edge drag at edge {}", selectionState.getSelectedEdgeIndex());
    }

    @Override
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
     * Calculates the new edge midpoint position by intersecting mouse ray with working plane.
     */
    private Vector3f calculateEdgePosition(float mouseX, float mouseY) {
        Vector3f planeNormal = selectionState.getPlaneNormal();
        Vector3f planePoint = selectionState.getPlanePoint();

        if (planeNormal == null || planePoint == null) {
            logger.warn("Cannot calculate edge position: plane not defined");
            return null;
        }

        // Create ray using base class utility
        CoordinateSystem.Ray ray = createMouseRay(mouseX, mouseY);

        // Intersect ray with plane using base class utility
        return intersectRayPlane(ray, planePoint, planeNormal, selectionState.getMidpoint());
    }
}
