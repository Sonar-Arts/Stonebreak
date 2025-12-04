package com.openmason.main.systems.viewport.viewportRendering.vertex;

import com.openmason.main.systems.rendering.model.ModelRenderer;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
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
 * Handles vertex translation through plane-constrained dragging.
 * Extends TranslationHandlerBase for shared functionality (DRY, SOLID).
 * Uses Blender-style plane-constrained movement for intuitive 3D editing.
 */
public class VertexTranslationHandler extends TranslationHandlerBase {

    private static final Logger logger = LoggerFactory.getLogger(VertexTranslationHandler.class);

    private final VertexSelectionState selectionState;
    private final VertexRenderer vertexRenderer;
    private final EdgeRenderer edgeRenderer;
    private final ModelRenderer modelRenderer;
    private final RenderPipeline renderPipeline;

    /**
     * Creates a new VertexTranslationHandler.
     *
     * @param selectionState The vertex selection state
     * @param vertexRenderer The vertex renderer for visual updates
     * @param edgeRenderer The edge renderer for updating edge endpoints
     * @param modelRenderer The block model renderer for updating the solid cube mesh
     * @param viewportState The viewport state for grid snapping settings
     * @param renderPipeline The render pipeline for marking edge data dirty
     * @param transformState The transform state for model space conversions
     */
    public VertexTranslationHandler(VertexSelectionState selectionState,
                                    VertexRenderer vertexRenderer,
                                    EdgeRenderer edgeRenderer,
                                    ModelRenderer modelRenderer,
                                    ViewportUIState viewportState,
                                    RenderPipeline renderPipeline,
                                    TransformState transformState) {
        super(viewportState, transformState);

        if (selectionState == null) {
            throw new IllegalArgumentException("VertexSelectionState cannot be null");
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

        this.selectionState = selectionState;
        this.vertexRenderer = vertexRenderer;
        this.edgeRenderer = edgeRenderer;
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

        // Start dragging the selected vertex
        dragStartMouseX = mouseX;
        dragStartMouseY = mouseY;

        // Calculate working plane based on camera orientation (from base class)
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

    @Override
    public void handleMouseMove(float mouseX, float mouseY) {
        if (!isDragging || !selectionState.isDragging()) {
            return;
        }

        // Calculate new vertex position using ray-plane intersection (world space)
        Vector3f worldSpacePosition = calculateVertexPosition(mouseX, mouseY);

        if (worldSpacePosition != null) {
            // Apply grid snapping if enabled (from base class)
            if (viewportState != null && viewportState.getGridSnappingEnabled().get()) {
                worldSpacePosition = applyGridSnapping(worldSpacePosition);
            }

            // Convert from world space to model space (from base class)
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

            // NOTE: ModelRenderer update deferred to handleMouseRelease() for performance
            // During drag, only update lightweight vertex/edge renderers (points and lines)
            // This avoids expensive GPU uploads and mesh regeneration on every frame

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

    @Override
    public void handleMouseRelease(float mouseX, float mouseY) {
        if (!isDragging) {
            return;
        }

        // COMMIT: Update ModelRenderer mesh with final vertex positions (once!)
        // This is the only GPU upload for the entire drag operation
        float[] allVertexPositions = vertexRenderer.getAllVertexPositions();
        if (allVertexPositions != null && modelRenderer != null) {
            modelRenderer.updateVertexPositions(allVertexPositions);
            logger.debug("Committed vertex drag to ModelRenderer (final GPU upload)");
        }

        selectionState.endDrag();
        isDragging = false;

        logger.debug("Ended vertex drag at position ({}, {}, {})",
                String.format("%.2f", selectionState.getCurrentPosition().x),
                String.format("%.2f", selectionState.getCurrentPosition().y),
                String.format("%.2f", selectionState.getCurrentPosition().z));
    }

    @Override
    public void cancelDrag() {
        if (!isDragging) {
            return;
        }

        // Revert to original position
        selectionState.cancelDrag();

        // Get current and original positions for reversion
        int vertexIndex = selectionState.getSelectedVertexIndex();
        Vector3f originalPosition = selectionState.getOriginalPosition();
        Vector3f currentPosition = vertexRenderer.getVertexPosition(vertexIndex);

        // Update visual to original position
        if (originalPosition != null) {
            vertexRenderer.updateVertexPosition(vertexIndex, originalPosition);

            // Revert connected edges
            if (currentPosition != null) {
                edgeRenderer.updateEdgesConnectedToVertex(currentPosition, originalPosition);
            }

            // REVERT: Update ModelRenderer with original positions (once!)
            float[] allVertexPositions = vertexRenderer.getAllVertexPositions();
            if (allVertexPositions != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(allVertexPositions);
                logger.debug("Reverted ModelRenderer to original positions (cancel)");
            }
        }

        isDragging = false;
        logger.debug("Cancelled vertex drag, reverted to original position");
    }

    /**
     * Calculates the new vertex position by intersecting mouse ray with working plane.
     */
    private Vector3f calculateVertexPosition(float mouseX, float mouseY) {
        Vector3f planeNormal = selectionState.getPlaneNormal();
        Vector3f planePoint = selectionState.getPlanePoint();

        if (planeNormal == null || planePoint == null) {
            logger.warn("Cannot calculate vertex position: plane not defined");
            return null;
        }

        // Create ray using base class utility
        CoordinateSystem.Ray ray = createMouseRay(mouseX, mouseY);

        // Intersect ray with plane using base class utility
        return intersectRayPlane(ray, planePoint, planeNormal, selectionState.getCurrentPosition());
    }
}
