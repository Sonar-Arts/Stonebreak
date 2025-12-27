package com.openmason.main.systems.viewport.viewportRendering.face;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.RenderPipeline;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.edge.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.common.TranslationHandlerBase;
import com.openmason.main.systems.viewport.viewportRendering.mesh.MeshManager;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles face translation through plane-constrained dragging.
 * Extends TranslationHandlerBase for shared functionality (DRY, SOLID).
 * Uses Blender-style plane-constrained movement for intuitive 3D editing.
 *
 * Moves all 4 corner vertices of a face together, maintaining face shape.
 */
public class FaceTranslationHandler extends TranslationHandlerBase {

    private static final Logger logger = LoggerFactory.getLogger(FaceTranslationHandler.class);

    private final FaceSelectionState selectionState;
    private final FaceRenderer faceRenderer;
    private final VertexRenderer vertexRenderer;
    private final EdgeRenderer edgeRenderer;
    private final GenericModelRenderer modelRenderer;
    private final RenderPipeline renderPipeline;

    // Face-specific drag state
    private Vector3f dragStartDelta = new Vector3f(); // Track accumulated translation
    private int[] currentVertexIndices = null; // Track which vertices we're moving

    /**
     * Creates a new FaceTranslationHandler.
     *
     * @param selectionState The face selection state
     * @param faceRenderer The face renderer for visual updates
     * @param vertexRenderer The vertex renderer for updating corner vertices
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
                                   GenericModelRenderer modelRenderer,
                                   ViewportUIState viewportState,
                                   RenderPipeline renderPipeline,
                                   TransformState transformState) {
        super(viewportState, transformState);

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

        this.selectionState = selectionState;
        this.faceRenderer = faceRenderer;
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

        // Start dragging the selected face
        dragStartMouseX = mouseX;
        dragStartMouseY = mouseY;
        dragStartDelta.set(0, 0, 0);

        // Get vertex indices for this face
        int faceIndex = selectionState.getSelectedFaceIndex();
        currentVertexIndices = faceRenderer.getFaceVertexIndices(faceIndex);

        if (currentVertexIndices == null || currentVertexIndices.length != 4) {
            logger.warn("Cannot start drag: invalid face vertex indices");
            return false;
        }

        // Calculate working plane based on camera orientation (from base class)
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
        hasMovedDuringDrag = false;

        logger.debug("Started dragging face {} on plane with normal ({}, {}, {}), vertex indices: [{}, {}, {}, {}]",
                faceIndex,
                String.format("%.2f", planeNormal.x),
                String.format("%.2f", planeNormal.y),
                String.format("%.2f", planeNormal.z),
                currentVertexIndices[0], currentVertexIndices[1], currentVertexIndices[2], currentVertexIndices[3]);

        return true;
    }

    @Override
    public void handleMouseMove(float mouseX, float mouseY) {
        if (!isDragging || !selectionState.isDragging()) {
            return;
        }

        if (currentVertexIndices == null || currentVertexIndices.length != 4) {
            logger.warn("Cannot move face: vertex indices not set");
            return;
        }

        // Calculate the new centroid position using ray-plane intersection (world space)
        Vector3f worldSpacePosition = calculateFaceCentroid(mouseX, mouseY);

        if (worldSpacePosition != null) {
            // Mark that actual movement occurred during this drag
            hasMovedDuringDrag = true;

            // Calculate delta from original centroid
            Vector3f originalCentroid = selectionState.getCentroid();

            if (originalCentroid == null) {
                logger.warn("Cannot calculate delta: original centroid is null");
                return;
            }

            // Get original centroid from original vertices
            Vector3f[] originalVertices = selectionState.getOriginalVertices();
            if (originalVertices == null || originalVertices.length != 4) {
                logger.warn("Cannot calculate delta: original vertices invalid");
                return;
            }

            Vector3f trueOriginalCentroid = new Vector3f()
                .add(originalVertices[0])
                .add(originalVertices[1])
                .add(originalVertices[2])
                .add(originalVertices[3])
                .mul(0.25f);

            Vector3f delta = new Vector3f(worldSpacePosition).sub(trueOriginalCentroid);

            // Apply grid snapping to the delta if enabled (from base class)
            if (viewportState != null && viewportState.getGridSnappingEnabled().get()) {
                delta = applyGridSnappingToDelta(delta);
            }

            // Convert delta to model space
            Vector3f modelSpaceDelta = worldToModelSpaceDelta(delta);

            // Update selection state with delta (updates all 4 vertices)
            selectionState.updatePosition(modelSpaceDelta);

            // Get NEW positions (original + accumulated delta)
            Vector3f[] newVertices = selectionState.getCurrentVertices();

            if (newVertices == null || newVertices.length != 4) {
                logger.warn("Cannot update geometry: invalid new vertices");
                return;
            }

            // REALTIME 3-LAYER UPDATE:
            updateFaceAndConnectedGeometry(newVertices, currentVertexIndices);

            dragStartDelta.set(modelSpaceDelta);

            logger.trace("Dragging face {}, delta=({},{},{})",
                    selectionState.getSelectedFaceIndex(),
                    String.format("%.2f", modelSpaceDelta.x),
                    String.format("%.2f", modelSpaceDelta.y),
                    String.format("%.2f", modelSpaceDelta.z));
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
                logger.debug("Merged vertices and rebuilt edge mapping after face drag");
            }

            // Rebuild face-to-vertex mapping with new vertex data
            float[] facePositions = faceRenderer.getFacePositions();
            if (facePositions != null && uniqueVertexPositions != null) {
                faceRenderer.buildFaceToVertexMapping(uniqueVertexPositions);
                logger.debug("Rebuilt face-to-vertex mapping after merge");

                // Update ALL face overlays with new positions after merge
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
                logger.debug("Committed merged face drag to ModelRenderer with mesh vertices");
            }
        } else {
            // No merge occurred, use mesh vertices
            float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
            if (meshVertices != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(meshVertices);
                logger.debug("Committed face drag to ModelRenderer (no merge)");
            }
        }

        selectionState.endDrag();
        isDragging = false;
        currentVertexIndices = null;

        logger.debug("Ended face drag at face {}", selectionState.getSelectedFaceIndex());
    }

    @Override
    public void cancelDrag() {
        if (!isDragging) {
            return;
        }

        if (currentVertexIndices == null || currentVertexIndices.length != 4) {
            logger.warn("Cannot cancel drag: vertex indices not set");
            isDragging = false;
            return;
        }

        // Revert to original position in selection state
        selectionState.cancelDrag();

        // Get original positions (after cancel, current = original)
        Vector3f[] originalVertices = selectionState.getOriginalVertices();

        if (originalVertices != null && originalVertices.length == 4) {
            // Revert all 4 vertices by index (prevents unification)
            for (int i = 0; i < 4; i++) {
                if (currentVertexIndices[i] >= 0) {
                    vertexRenderer.updateVertexPosition(currentVertexIndices[i], originalVertices[i]);
                }
            }

            // REVERT: Update edge positions to original (same pattern as updateFaceAndConnectedGeometry)
            for (int i = 0; i < 4; i++) {
                int v1Index = currentVertexIndices[i];
                int v2Index = currentVertexIndices[(i + 1) % 4];
                if (v1Index >= 0 && v2Index >= 0) {
                    edgeRenderer.updateEdgesByVertexIndices(
                        v1Index, originalVertices[i],
                        v2Index, originalVertices[(i + 1) % 4]
                    );
                }
            }

            // Update all affected face overlays with original positions FIRST
            // (before rebuilding mappings, since mappings need face positions to be correct)
            updateAllAffectedFaceOverlays(currentVertexIndices);

            // NOW rebuild mappings after geometry is reverted
            float[] allVertexPositions = vertexRenderer.getAllVertexPositions();
            if (allVertexPositions != null) {
                edgeRenderer.buildEdgeToVertexMapping(allVertexPositions);
                faceRenderer.buildFaceToVertexMapping(allVertexPositions);
            }

            // REVERT: Update ModelRenderer with original mesh vertices
            float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
            if (meshVertices != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(meshVertices);
                logger.debug("Reverted ModelRenderer to original positions (cancel)");
            }
        }

        isDragging = false;
        currentVertexIndices = null;
        logger.debug("Cancelled face drag, reverted all changes to original positions");
    }

    /**
     * Update face and all connected geometry (vertices, edges, model, overlay).
     * Uses index-based updates to prevent vertex unification bug.
     *
     * REALTIME 4-LAYER UPDATE:
     * - Layer 1: Update vertices (4 corners)
     * - Layer 2: Update edges (all edges connected to these 4 vertices)
     * - Layer 3: Update solid model mesh
     * - Layer 4: Update face overlay (ensures overlay morphs with geometry)
     *
     * @param newVertices New positions of all 4 face corners
     * @param vertexIndices Indices of the 4 unique vertices
     */
    private void updateFaceAndConnectedGeometry(Vector3f[] newVertices, int[] vertexIndices) {
        if (newVertices == null || newVertices.length != 4) {
            logger.warn("Cannot update geometry: invalid new vertices");
            return;
        }

        if (vertexIndices == null || vertexIndices.length != 4) {
            logger.warn("Cannot update geometry: invalid vertex indices");
            return;
        }

        // LAYER 1: Update vertices (4 corners of face)
        for (int i = 0; i < 4; i++) {
            if (vertexIndices[i] >= 0) {
                vertexRenderer.updateVertexPosition(vertexIndices[i], newVertices[i]);
            }
        }

        // LAYER 2: Update edges (all edges connected to these 4 vertices)
        // Update edges connected to each vertex pair forming the face outline
        // Face outline: v0->v1, v1->v2, v2->v3, v3->v0
        for (int i = 0; i < 4; i++) {
            int v1Index = vertexIndices[i];
            int v2Index = vertexIndices[(i + 1) % 4];

            if (v1Index >= 0 && v2Index >= 0) {
                edgeRenderer.updateEdgesByVertexIndices(
                    v1Index, newVertices[i],
                    v2Index, newVertices[(i + 1) % 4]
                );
            }
        }

        // Also update diagonal edges if they exist (internal edges of the face)
        if (vertexIndices[0] >= 0 && vertexIndices[2] >= 0) {
            edgeRenderer.updateEdgesByVertexIndices(
                vertexIndices[0], newVertices[0],
                vertexIndices[2], newVertices[2]
            );
        }
        if (vertexIndices[1] >= 0 && vertexIndices[3] >= 0) {
            edgeRenderer.updateEdgesByVertexIndices(
                vertexIndices[1], newVertices[1],
                vertexIndices[3], newVertices[3]
            );
        }

        // LAYER 3: Update solid model mesh (realtime visual feedback)
        // Use mesh vertices (24 for cube) instead of unique vertices (8)
        float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
        if (meshVertices != null && modelRenderer != null) {
            modelRenderer.updateVertexPositions(meshVertices);
        }

        // LAYER 4: Update ALL face overlays affected by the modified vertices
        // CRITICAL: This ensures ALL face overlays (not just the selected one) morph
        // to match the new geometry when vertices are shared between faces
        updateAllAffectedFaceOverlays(vertexIndices);

        logger.trace("Updated face and connected geometry for vertices [{}, {}, {}, {}]",
                vertexIndices[0], vertexIndices[1], vertexIndices[2], vertexIndices[3]);
    }

    /**
     * Calculates the new face centroid position by intersecting mouse ray with working plane.
     */
    private Vector3f calculateFaceCentroid(float mouseX, float mouseY) {
        Vector3f planeNormal = selectionState.getPlaneNormal();
        Vector3f planePoint = selectionState.getPlanePoint();

        if (planeNormal == null || planePoint == null) {
            logger.warn("Cannot calculate face position: plane not defined");
            return null;
        }

        // Create ray using base class utility
        CoordinateSystem.Ray ray = createMouseRay(mouseX, mouseY);

        // Intersect ray with plane using base class utility
        return intersectRayPlane(ray, planePoint, planeNormal, selectionState.getCentroid());
    }

    /**
     * Update ALL face overlays that use any of the modified vertices.
     * This ensures that when a face is dragged, OTHER faces that share the same
     * vertices also have their overlays updated to match the new geometry.
     *
     * @param modifiedVertexIndices Indices of the 4 vertices that were modified
     */
    private void updateAllAffectedFaceOverlays(int[] modifiedVertexIndices) {
        if (faceRenderer == null || !faceRenderer.isInitialized()) {
            return;
        }

        if (modifiedVertexIndices == null || modifiedVertexIndices.length != 4) {
            return;
        }

        int faceCount = faceRenderer.getFaceCount();
        if (faceCount == 0) {
            return;
        }

        // Iterate through ALL faces and update any that use any of the 4 modified vertices
        for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
            int[] faceVertexIndices = faceRenderer.getFaceVertexIndices(faceIdx);

            if (faceVertexIndices == null || faceVertexIndices.length != 4) {
                continue;
            }

            // Check if this face uses any of the modified vertices
            boolean faceUsesModifiedVertex = false;
            for (int faceVIdx : faceVertexIndices) {
                for (int modVIdx : modifiedVertexIndices) {
                    if (faceVIdx == modVIdx) {
                        faceUsesModifiedVertex = true;
                        break;
                    }
                }
                if (faceUsesModifiedVertex) break;
            }

            if (faceUsesModifiedVertex) {
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
                    logger.trace("Updated face {} overlay due to shared vertices with dragged face", faceIdx);
                }
            }
        }
    }
}
