package com.openmason.main.systems.rendering.model.gmr.subrenders.vertex;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import com.openmason.main.systems.viewport.viewportRendering.ViewportRenderPipeline;
import com.openmason.main.systems.viewport.viewportRendering.common.TranslationHandlerBase;
import com.openmason.main.systems.rendering.model.gmr.mesh.MeshManager;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

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
    private final FaceRenderer faceRenderer;
    private final GenericModelRenderer modelRenderer;
    private final ViewportRenderPipeline viewportRenderPipeline;

    /**
     * Creates a new VertexTranslationHandler.
     *
     * @param selectionState The vertex selection state
     * @param vertexRenderer The vertex renderer for visual updates
     * @param edgeRenderer The edge renderer for updating edge endpoints
     * @param faceRenderer The face renderer for updating face overlays
     * @param modelRenderer The block model renderer for updating the solid cube mesh
     * @param viewportState The viewport state for grid snapping settings
     * @param viewportRenderPipeline The render pipeline for marking edge data dirty
     * @param transformState The transform state for model space conversions
     */
    public VertexTranslationHandler(VertexSelectionState selectionState,
                                    VertexRenderer vertexRenderer,
                                    EdgeRenderer edgeRenderer,
                                    FaceRenderer faceRenderer,
                                    GenericModelRenderer modelRenderer,
                                    ViewportUIState viewportState,
                                    ViewportRenderPipeline viewportRenderPipeline,
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
        if (faceRenderer == null) {
            throw new IllegalArgumentException("FaceRenderer cannot be null");
        }
        if (modelRenderer == null) {
            throw new IllegalArgumentException("BlockModelRenderer cannot be null");
        }
        if (viewportRenderPipeline == null) {
            throw new IllegalArgumentException("ViewportRenderPipeline cannot be null");
        }

        this.selectionState = selectionState;
        this.vertexRenderer = vertexRenderer;
        this.edgeRenderer = edgeRenderer;
        this.faceRenderer = faceRenderer;
        this.modelRenderer = modelRenderer;
        this.viewportRenderPipeline = viewportRenderPipeline;
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

        // Calculate initial hit point for delta-based movement (no jump to mouse)
        calculateInitialDragHitPoint(mouseX, mouseY, planePoint, planeNormal);

        isDragging = true;
        hasMovedDuringDrag = false;

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

        // Get plane info from selection state
        Vector3f planeNormal = selectionState.getPlaneNormal();
        Vector3f planePoint = selectionState.getPlanePoint();

        if (planeNormal == null || planePoint == null) {
            return;
        }

        // Calculate delta from initial hit point to current mouse position (no jump)
        Vector3f worldDelta = calculateDragDelta(mouseX, mouseY, planePoint, planeNormal);

        if (worldDelta != null) {
            // Apply grid snapping to delta if enabled
            if (viewportState != null && viewportState.getGridSnappingEnabled().get()) {
                worldDelta = applyGridSnappingToDelta(worldDelta);
            }

            // Mark that actual movement occurred during this drag
            hasMovedDuringDrag = true;

            // Update selection state with delta (applies to all selected vertices)
            selectionState.updatePositionsByDelta(worldDelta);

            // Get all selected vertex indices
            Set<Integer> selectedIndices = selectionState.getSelectedVertexIndices();

            // Update each selected vertex
            for (int vertexIndex : selectedIndices) {
                // Get the new current position for this vertex
                Vector3f currentWorldPos = selectionState.getCurrentPosition(vertexIndex);
                if (currentWorldPos == null) continue;

                // Convert from world space to model space (from base class)
                Vector3f modelSpacePosition = worldToModelSpace(currentWorldPos);

                // Update visual preview (model space for VBO)
                vertexRenderer.updateVertexPosition(vertexIndex, modelSpacePosition);

                // Update connected edge endpoints using INDEX-BASED matching (model space for VBO)
                edgeRenderer.updateEdgesConnectedToVertexByIndex(vertexIndex, modelSpacePosition);

                // Update all face overlays that use this vertex (ensures overlays morph with geometry)
                if (!faceRenderer.isUsingTriangleMode()) {
                    updateAffectedFaceOverlays(vertexIndex);
                }
            }

            // REALTIME VISUAL UPDATE: Update ModelRenderer during drag (no merging)
            float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
            if (meshVertices != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(meshVertices);

                // In triangle mode, face overlays need to be rebuilt from GenericModelRenderer
                if (faceRenderer.isUsingTriangleMode()) {
                    faceRenderer.rebuildFromGenericModelRenderer();
                }
            }

            logger.trace("Dragging {} vertices by delta ({}, {}, {})",
                    selectedIndices.size(),
                    String.format("%.2f", worldDelta.x),
                    String.format("%.2f", worldDelta.y),
                    String.format("%.2f", worldDelta.z));
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
            // Remap edge vertex indices to use new vertex indices
            edgeRenderer.remapEdgeVertexIndices(indexRemapping);

            // Rebuild edge-to-vertex mapping with new vertex data
            float[] uniqueVertexPositions = vertexRenderer.getAllVertexPositions();
            if (uniqueVertexPositions != null) {
                edgeRenderer.buildEdgeToVertexMapping(uniqueVertexPositions);
                logger.debug("Merged vertices and rebuilt edge mapping after vertex drag");
            }

            // Rebuild face-to-vertex mapping with new vertex data
            if (uniqueVertexPositions != null && faceRenderer != null) {
                faceRenderer.buildFaceToVertexMapping(uniqueVertexPositions);
                logger.debug("Rebuilt face-to-vertex mapping after vertex merge");

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
                logger.debug("Committed merged vertex drag to ModelRenderer with mesh vertices");
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
                logger.debug("Committed vertex drag to ModelRenderer (no merge)");
            }
        }

        selectionState.endDrag();
        isDragging = false;
        clearInitialDragHitPoint();

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
        clearInitialDragHitPoint();

        // Get all selected indices before cancelling (cancelDrag reverts positions internally)
        Set<Integer> selectedIndices = selectionState.getSelectedVertexIndices();

        // Revert all vertices to original positions in state
        selectionState.cancelDrag();

        // Revert each selected vertex's visual representation
        for (int vertexIndex : selectedIndices) {
            Vector3f originalPosition = selectionState.getOriginalPosition(vertexIndex);

            if (originalPosition != null) {
                vertexRenderer.updateVertexPosition(vertexIndex, originalPosition);

                // Revert connected edges using INDEX-BASED matching
                edgeRenderer.updateEdgesConnectedToVertexByIndex(vertexIndex, originalPosition);

                // Revert all face overlays that use this vertex
                if (!faceRenderer.isUsingTriangleMode()) {
                    updateAffectedFaceOverlays(vertexIndex);
                }
            }
        }

        // REVERT: Update ModelRenderer with original mesh vertices
        float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
        if (meshVertices != null && modelRenderer != null) {
            modelRenderer.updateVertexPositions(meshVertices);
            // Rebuild face overlays in triangle mode after position revert
            if (faceRenderer.isUsingTriangleMode()) {
                faceRenderer.rebuildFromGenericModelRenderer();
            }
            logger.debug("Reverted ModelRenderer to original positions (cancel)");
        }

        isDragging = false;
        logger.debug("Cancelled vertex drag for {} vertices, reverted to original positions",
                selectedIndices.size());
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

    /**
     * Update all face overlays that use the modified vertex.
     * This ensures face overlays morph to match the new geometry shape.
     *
     * @param modifiedVertexIndex The index of the vertex that was modified
     */
    private void updateAffectedFaceOverlays(int modifiedVertexIndex) {
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

            // Check if this face uses the modified vertex
            boolean faceUsesVertex = false;
            for (int vIdx : faceVertexIndices) {
                if (vIdx == modifiedVertexIndex) {
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
                    logger.trace("Updated face {} overlay due to vertex {} modification", faceIdx, modifiedVertexIndex);
                }
            }
        }
    }
}
