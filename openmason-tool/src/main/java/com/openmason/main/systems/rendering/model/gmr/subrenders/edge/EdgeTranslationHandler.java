package com.openmason.main.systems.rendering.model.gmr.subrenders.edge;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.RenderPipeline;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import com.openmason.main.systems.viewport.viewportRendering.common.TranslationHandlerBase;
import com.openmason.main.systems.rendering.model.gmr.mesh.MeshManager;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

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
    private final GenericModelRenderer modelRenderer;
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
                                   GenericModelRenderer modelRenderer,
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

        // Calculate initial hit point for delta-based movement (no jump to mouse)
        calculateInitialDragHitPoint(mouseX, mouseY, planePoint, planeNormal);

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

        // Get plane info from selection state
        Vector3f planeNormal = selectionState.getPlaneNormal();
        Vector3f planePoint = selectionState.getPlanePoint();

        if (planeNormal == null || planePoint == null) {
            return;
        }

        // Calculate delta from initial hit point to current mouse position (no jump)
        Vector3f delta = calculateDragDelta(mouseX, mouseY, planePoint, planeNormal);

        if (delta != null) {
            // Mark that actual movement occurred during this drag
            hasMovedDuringDrag = true;

            // Apply grid snapping to the delta if enabled (from base class)
            if (viewportState != null && viewportState.getGridSnappingEnabled().get()) {
                delta = applyGridSnappingToDelta(delta);
            }

            // Convert delta to model space
            Vector3f modelSpaceDelta = worldToModelSpaceDelta(delta);

            // Update selection state with delta (applies to all selected edges)
            selectionState.updatePosition(modelSpaceDelta);

            // Get all unique vertex indices from all selected edges
            Set<Integer> allVertexIndices = selectionState.getAllSelectedVertexIndices();

            // Update each unique vertex position
            for (int vertexIndex : allVertexIndices) {
                // Get the original position for this vertex from any edge that uses it
                Vector3f originalPos = getOriginalVertexPosition(vertexIndex);
                if (originalPos != null) {
                    Vector3f newPos = new Vector3f(originalPos).add(modelSpaceDelta);
                    vertexRenderer.updateVertexPosition(vertexIndex, newPos);
                    edgeRenderer.updateEdgesConnectedToVertexByIndex(vertexIndex, newPos);
                }
            }

            // Update affected face overlays
            for (int vertexIndex : allVertexIndices) {
                if (!faceRenderer.isUsingTriangleMode()) {
                    updateAffectedFaceOverlaysForVertex(vertexIndex);
                }
            }

            // REALTIME VISUAL UPDATE: Update ModelRenderer during drag
            float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
            if (meshVertices != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(meshVertices);
                if (faceRenderer.isUsingTriangleMode()) {
                    faceRenderer.rebuildFromGenericModelRenderer();
                }
            }

            dragStartDelta.set(modelSpaceDelta);

            logger.trace("Dragging {} edges by delta ({}, {}, {})",
                    selectionState.getSelectionCount(),
                    String.format("%.2f", modelSpaceDelta.x),
                    String.format("%.2f", modelSpaceDelta.y),
                    String.format("%.2f", modelSpaceDelta.z));
        }
    }

    /**
     * Get the original position of a vertex from the selected edges.
     * Searches all selected edges for one that contains this vertex.
     */
    private Vector3f getOriginalVertexPosition(int vertexIndex) {
        for (int edgeIndex : selectionState.getSelectedEdgeIndices()) {
            int[] edgeVerts = selectionState.getEdgeVertexIndices(edgeIndex);
            Vector3f[] originalEndpoints = selectionState.getOriginalEndpoints(edgeIndex);
            if (edgeVerts != null && originalEndpoints != null) {
                if (edgeVerts[0] == vertexIndex) {
                    return new Vector3f(originalEndpoints[0]);
                } else if (edgeVerts[1] == vertexIndex) {
                    return new Vector3f(originalEndpoints[1]);
                }
            }
        }
        return null;
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

            // COMMIT: Update ModelRenderer with mesh vertices
            // Use all mesh vertices (24 for cube net format)
            float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
            if (meshVertices != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(meshVertices);
                // Rebuild face overlays in triangle mode after position update
                if (faceRenderer.isUsingTriangleMode()) {
                    faceRenderer.rebuildFromGenericModelRenderer();
                }
                logger.debug("Committed merged edge drag to ModelRenderer with mesh vertices");
            }
        } else {
            // No merge occurred, use mesh vertices
            float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
            if (meshVertices != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(meshVertices);
                // Rebuild face overlays in triangle mode after position update
                if (faceRenderer.isUsingTriangleMode()) {
                    faceRenderer.rebuildFromGenericModelRenderer();
                }
                logger.debug("Committed edge drag to ModelRenderer (no merge)");
            }
        }

        selectionState.endDrag();
        isDragging = false;
        clearInitialDragHitPoint();

        logger.debug("Ended edge drag at edge {}", selectionState.getSelectedEdgeIndex());
    }

    @Override
    public void cancelDrag() {
        if (!isDragging) {
            return;
        }
        clearInitialDragHitPoint();

        // Get all unique vertex indices before cancelling
        Set<Integer> allVertexIndices = selectionState.getAllSelectedVertexIndices();

        // Store original positions before cancel (cancel will revert currentEndpoints to originalEndpoints)
        java.util.Map<Integer, Vector3f> originalPositions = new java.util.HashMap<>();
        for (int vertexIndex : allVertexIndices) {
            Vector3f originalPos = getOriginalVertexPosition(vertexIndex);
            if (originalPos != null) {
                originalPositions.put(vertexIndex, originalPos);
            }
        }

        // Revert to original position in selection state
        selectionState.cancelDrag();

        // Revert each vertex to its original position
        for (int vertexIndex : allVertexIndices) {
            Vector3f originalPos = originalPositions.get(vertexIndex);
            if (originalPos != null) {
                vertexRenderer.updateVertexPosition(vertexIndex, originalPos);
                edgeRenderer.updateEdgesConnectedToVertexByIndex(vertexIndex, originalPos);
                if (!faceRenderer.isUsingTriangleMode()) {
                    updateAffectedFaceOverlaysForVertex(vertexIndex);
                }
            }
        }

        // REVERT: Update ModelRenderer with original mesh vertices
        float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
        if (meshVertices != null && modelRenderer != null) {
            modelRenderer.updateVertexPositions(meshVertices);
            if (faceRenderer.isUsingTriangleMode()) {
                faceRenderer.rebuildFromGenericModelRenderer();
            }
            logger.debug("Reverted ModelRenderer to original positions (cancel)");
        }

        isDragging = false;
        logger.debug("Cancelled edge drag for {} edges, reverted all changes to original positions",
                allVertexIndices.size() / 2);
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
        // Use mesh vertices (24 for cube) instead of unique vertices (8)
        float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
        if (meshVertices != null && modelRenderer != null) {
            modelRenderer.updateVertexPositions(meshVertices);

            // In triangle mode, face overlays need to be rebuilt from GenericModelRenderer
            // since updateFaceByVertexIndices() only works in quad mode
            if (faceRenderer.isUsingTriangleMode()) {
                faceRenderer.rebuildFromGenericModelRenderer();
            }
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
        updateAffectedFaceOverlaysForVertex(vertexIndex1);
        updateAffectedFaceOverlaysForVertex(vertexIndex2);
    }

    /**
     * Update all face overlays that use a specific vertex.
     * This ensures face overlays morph to match the new geometry shape.
     *
     * @param vertexIndex The index of the vertex that was modified
     */
    private void updateAffectedFaceOverlaysForVertex(int vertexIndex) {
        if (faceRenderer == null || !faceRenderer.isInitialized()) {
            return;
        }

        int faceCount = faceRenderer.getFaceCount();
        if (faceCount == 0) {
            return;
        }

        // Iterate through all faces and update those that use this vertex
        for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
            int[] faceVertexIndices = faceRenderer.getFaceVertexIndices(faceIdx);

            if (faceVertexIndices == null || faceVertexIndices.length != 4) {
                continue;
            }

            // Check if this face uses this vertex
            boolean faceUsesVertex = false;
            for (int vIdx : faceVertexIndices) {
                if (vIdx == vertexIndex) {
                    faceUsesVertex = true;
                    break;
                }
            }

            if (faceUsesVertex) {
                // Get current positions of all 4 vertices of this face
                Vector3f[] newPositions = new Vector3f[4];
                boolean allValid = true;
                for (int i = 0; i < 4; i++) {
                    newPositions[i] = vertexRenderer.getVertexPosition(faceVertexIndices[i]);
                    if (newPositions[i] == null) {
                        logger.warn("Cannot update face {}: vertex {} position is null", faceIdx, faceVertexIndices[i]);
                        allValid = false;
                        break;
                    }
                }

                // Update the face overlay with new vertex positions
                if (allValid) {
                    faceRenderer.updateFaceByVertexIndices(faceIdx, faceVertexIndices, newPositions);
                    logger.trace("Updated face {} overlay due to vertex {} modification", faceIdx, vertexIndex);
                }
            }
        }
    }
}
