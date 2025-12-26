package com.openmason.main.systems.viewport.viewportRendering.edge;

import com.openmason.main.systems.rendering.model.ModelRenderer;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.RenderPipeline;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.face.FaceRenderer;
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
    private final FaceRenderer faceRenderer;
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
     * @param faceRenderer The face renderer for updating face overlays
     * @param modelRenderer The block model renderer for updating the solid mesh
     * @param viewportState The viewport state for grid snapping settings
     * @param renderPipeline The render pipeline for marking data dirty
     * @param transformState The transform state for model space conversions
     */
    public EdgeTranslationHandler(EdgeSelectionState selectionState,
                                   EdgeRenderer edgeRenderer,
                                   VertexRenderer vertexRenderer,
                                   FaceRenderer faceRenderer,
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
        if (faceRenderer == null) {
            throw new IllegalArgumentException("FaceRenderer cannot be null");
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
        this.faceRenderer = faceRenderer;
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
        hasMovedDuringDrag = false;

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
            // Mark that actual movement occurred during this drag
            hasMovedDuringDrag = true;

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

            // FIX: Update geometry using index-based methods (prevents unification)
            updateEdgeAndConnectedGeometry(newPoint1, newPoint2, edgeIndex);

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

        // Only merge if actual movement occurred during the drag
        // This prevents accidental merges when clicking without moving
        java.util.Map<Integer, Integer> indexRemapping = new java.util.HashMap<>();
        if (hasMovedDuringDrag) {
            // TRUE MERGE: Remove duplicate vertices that ended up at the same position
            float mergeEpsilon = 0.001f; // 1mm threshold for merging
            indexRemapping = vertexRenderer.mergeOverlappingVertices(mergeEpsilon);
        }

        if (!indexRemapping.isEmpty()) {
            // Remap selection state vertex indices FIRST (before using them)
            selectionState.remapVertexIndices(indexRemapping);

            // Remap edge vertex indices to use new vertex indices
            edgeRenderer.remapEdgeVertexIndices(indexRemapping);

            // Rebuild edge-to-vertex mapping with new vertex data
            float[] uniqueVertexPositions = vertexRenderer.getAllVertexPositions();
            if (uniqueVertexPositions != null) {
                edgeRenderer.buildEdgeToVertexMapping(uniqueVertexPositions);
                logger.debug("Merged vertices and rebuilt edge mapping after edge drag");
            }

            // Rebuild face-to-vertex mapping with new vertex data
            if (uniqueVertexPositions != null && faceRenderer != null) {
                faceRenderer.buildFaceToVertexMapping(uniqueVertexPositions);
                logger.debug("Rebuilt face-to-vertex mapping after edge vertex merge");

                // Update all face overlays with new positions after merge
                for (int faceIdx = 0; faceIdx < faceRenderer.getFaceCount(); faceIdx++) {
                    int[] faceVertexIndices = faceRenderer.getFaceVertexIndices(faceIdx);
                    if (faceVertexIndices != null && faceVertexIndices.length == 4) {
                        Vector3f[] newPositions = new Vector3f[4];
                        boolean allValid = true;
                        for (int i = 0; i < 4; i++) {
                            newPositions[i] = vertexRenderer.getVertexPosition(faceVertexIndices[i]);
                            if (newPositions[i] == null) {
                                allValid = false;
                                break;
                            }
                        }
                        if (allValid) {
                            faceRenderer.updateFaceByVertexIndices(faceIdx, faceVertexIndices, newPositions);
                        }
                    }
                }
            }

            // COMMIT: Update ModelRenderer with EXPANDED vertex positions (8 vertices for cube)
            // Expand merged vertices to 8 for ModelRenderer compatibility
            float[] expandedPositions = vertexRenderer.getExpandedVertexPositions(indexRemapping);
            if (expandedPositions != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(expandedPositions);
                logger.debug("Committed merged edge drag to ModelRenderer with expanded positions");
            }
        } else {
            // No merge occurred, use regular positions
            float[] allVertexPositions = vertexRenderer.getAllVertexPositions();
            if (allVertexPositions != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(allVertexPositions);
                logger.debug("Committed edge drag to ModelRenderer (no merge)");
            }
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

        // Get vertex indices and original positions
        int vertexIndex1 = selectionState.getVertexIndex1();
        int vertexIndex2 = selectionState.getVertexIndex2();

        // Revert to original position in selection state
        selectionState.cancelDrag();

        // Get original positions (after cancel, current = original)
        Vector3f originalPoint1 = selectionState.getOriginalPoint1();
        Vector3f originalPoint2 = selectionState.getOriginalPoint2();

        if (originalPoint1 != null && originalPoint2 != null && vertexIndex1 >= 0 && vertexIndex2 >= 0) {
            // FIX: Revert specific vertices by index (prevents unification)
            vertexRenderer.updateVerticesByIndices(vertexIndex1, originalPoint1, vertexIndex2, originalPoint2);

            // FIX: Revert edges by vertex indices (prevents unification)
            edgeRenderer.updateEdgesByVertexIndices(vertexIndex1, originalPoint1, vertexIndex2, originalPoint2);

            // Revert all face overlays that use these vertices
            updateAffectedFaceOverlays(vertexIndex1, vertexIndex2);

            // REVERT: Update ModelRenderer with original positions (once!)
            float[] allVertexPositions = vertexRenderer.getAllVertexPositions();
            if (allVertexPositions != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(allVertexPositions);
                logger.debug("Reverted ModelRenderer to original positions (cancel)");
            }
        }

        isDragging = false;
        logger.debug("Cancelled edge drag, reverted all changes to original positions");
    }

    /**
     * Update edge and all connected geometry (vertices, edges, faces).
     * FIX: Now uses index-based updates to prevent vertex unification bug.
     * Updates only the specific unique vertices for this edge, not all vertices at those positions.
     *
     * @param newPoint1 New position of endpoint 1 (calculated)
     * @param newPoint2 New position of endpoint 2 (calculated)
     * @param edgeIndex Index of the selected edge
     */
    private void updateEdgeAndConnectedGeometry(Vector3f newPoint1, Vector3f newPoint2, int edgeIndex) {
        if (newPoint1 == null || newPoint2 == null) {
            logger.warn("Cannot update geometry: null positions");
            return;
        }

        // Get the unique vertex indices for this edge from selection state
        int vertexIndex1 = selectionState.getVertexIndex1();
        int vertexIndex2 = selectionState.getVertexIndex2();

        if (vertexIndex1 < 0 || vertexIndex2 < 0) {
            logger.warn("Cannot update geometry: invalid vertex indices {}, {}", vertexIndex1, vertexIndex2);
            return;
        }

        // FIX: Update specific vertices by index (prevents unification)
        vertexRenderer.updateVerticesByIndices(vertexIndex1, newPoint1, vertexIndex2, newPoint2);

        // FIX: Update edges by vertex indices (prevents unification)
        edgeRenderer.updateEdgesByVertexIndices(vertexIndex1, newPoint1, vertexIndex2, newPoint2);

        // Update all face overlays that use these vertices (ensures overlays morph with geometry)
        updateAffectedFaceOverlays(vertexIndex1, vertexIndex2);

        // REALTIME VISUAL UPDATE: Update ModelRenderer during drag (no merging)
        // This provides visual feedback showing how the final cube will look
        float[] allVertexPositions = vertexRenderer.getAllVertexPositions();
        if (allVertexPositions != null && modelRenderer != null) {
            modelRenderer.updateVertexPositions(allVertexPositions);
        }

        logger.trace("Updated edge {} and connected geometry using indices {} and {} (prevents unification)",
                edgeIndex, vertexIndex1, vertexIndex2);
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

    /**
     * Update all face overlays that use either vertex of the modified edge.
     * This ensures face overlays morph to match the new geometry shape.
     *
     * @param vertexIndex1 The index of the first vertex of the edge
     * @param vertexIndex2 The index of the second vertex of the edge
     */
    private void updateAffectedFaceOverlays(int vertexIndex1, int vertexIndex2) {
        if (faceRenderer == null || !faceRenderer.isInitialized()) {
            return;
        }

        int faceCount = faceRenderer.getFaceCount();
        if (faceCount == 0) {
            return;
        }

        // Iterate through all faces and update those that use either vertex
        for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
            int[] faceVertexIndices = faceRenderer.getFaceVertexIndices(faceIdx);

            if (faceVertexIndices == null || faceVertexIndices.length != 4) {
                continue;
            }

            // Check if this face uses either vertex
            boolean faceUsesVertex = false;
            for (int vIdx : faceVertexIndices) {
                if (vIdx == vertexIndex1 || vIdx == vertexIndex2) {
                    faceUsesVertex = true;
                    break;
                }
            }

            if (faceUsesVertex) {
                // Get current positions of all 4 vertices of this face
                Vector3f[] newPositions = new Vector3f[4];
                for (int i = 0; i < 4; i++) {
                    newPositions[i] = vertexRenderer.getVertexPosition(faceVertexIndices[i]);
                    if (newPositions[i] == null) {
                        logger.warn("Cannot update face {}: vertex {} position is null", faceIdx, faceVertexIndices[i]);
                        break;
                    }
                }

                // Update the face overlay with new vertex positions
                if (newPositions[0] != null && newPositions[1] != null &&
                    newPositions[2] != null && newPositions[3] != null) {
                    faceRenderer.updateFaceByVertexIndices(faceIdx, faceVertexIndices, newPositions);
                    logger.trace("Updated face {} overlay due to edge vertices {}, {} modification",
                                faceIdx, vertexIndex1, vertexIndex2);
                }
            }
        }
    }
}
