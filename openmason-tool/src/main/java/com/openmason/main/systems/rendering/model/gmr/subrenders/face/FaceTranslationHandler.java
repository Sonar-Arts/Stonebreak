package com.openmason.main.systems.rendering.model.gmr.subrenders.face;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.ViewportRenderPipeline;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.common.TranslationHandlerBase;
import com.openmason.main.systems.rendering.model.gmr.mesh.MeshManager;
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
    private final ViewportRenderPipeline viewportRenderPipeline;

    // Face-specific drag state - now supports multi-selection
    private Vector3f dragStartDelta = new Vector3f(); // Track accumulated translation
    private int[] currentVertexIndices = null; // ALL unique vertices from ALL selected faces (mesh indices in triangle mode)
    private java.util.Map<Integer, Vector3f> vertexOriginalPositions = new java.util.HashMap<>(); // Original positions per vertex index

    // Wireframe drag state (tracks ALL face vertices in VertexRenderer for all selected faces)
    private int[] wireframeVertexIndices = null; // VertexRenderer indices for ALL face vertices
    private Vector3f[] wireframeOriginalPositions = null; // Original positions at drag start

    /**
     * Creates a new FaceTranslationHandler.
     *
     * @param selectionState The face selection state
     * @param faceRenderer The face renderer for visual updates
     * @param vertexRenderer The vertex renderer for updating corner vertices
     * @param edgeRenderer The edge renderer for updating connected edges
     * @param modelRenderer The block model renderer for updating the solid mesh
     * @param viewportState The viewport state for grid snapping settings
     * @param viewportRenderPipeline The render pipeline for marking data dirty
     * @param transformState The transform state for model space conversions
     */
    public FaceTranslationHandler(FaceSelectionState selectionState,
                                   FaceRenderer faceRenderer,
                                   VertexRenderer vertexRenderer,
                                   EdgeRenderer edgeRenderer,
                                   GenericModelRenderer modelRenderer,
                                   ViewportUIState viewportState,
                                   ViewportRenderPipeline viewportRenderPipeline,
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
        if (viewportRenderPipeline == null) {
            throw new IllegalArgumentException("ViewportRenderPipeline cannot be null");
        }

        this.selectionState = selectionState;
        this.faceRenderer = faceRenderer;
        this.vertexRenderer = vertexRenderer;
        this.edgeRenderer = edgeRenderer;
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

        // Start dragging all selected faces
        dragStartMouseX = mouseX;
        dragStartMouseY = mouseY;
        dragStartDelta.set(0, 0, 0);
        vertexOriginalPositions.clear();

        // Collect ALL unique vertex indices from ALL selected faces
        java.util.Set<Integer> allVertexIndicesSet = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> selectedFaces = selectionState.getSelectedFaceIndices();

        for (int faceIndex : selectedFaces) {
            int[] faceVerts;
            if (faceRenderer.isUsingTriangleMode()) {
                faceVerts = faceRenderer.getTriangleVertexIndicesForFace(faceIndex);
            } else {
                faceVerts = faceRenderer.getFaceVertexIndices(faceIndex);
            }

            if (faceVerts != null) {
                for (int vertIdx : faceVerts) {
                    allVertexIndicesSet.add(vertIdx);
                }
            }
        }

        if (allVertexIndicesSet.isEmpty()) {
            logger.warn("Cannot start drag: no vertex indices found for {} selected faces", selectedFaces.size());
            return false;
        }

        currentVertexIndices = allVertexIndicesSet.stream().mapToInt(Integer::intValue).toArray();

        // Store original positions for all vertices
        for (int vertIdx : currentVertexIndices) {
            Vector3f pos;
            if (faceRenderer.isUsingTriangleMode() && modelRenderer != null) {
                pos = modelRenderer.getVertexPosition(vertIdx);
            } else {
                pos = vertexRenderer.getVertexPosition(vertIdx);
            }
            if (pos != null) {
                vertexOriginalPositions.put(vertIdx, new Vector3f(pos));
            }
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

        // Calculate initial hit point for delta-based movement (no jump to mouse)
        calculateInitialDragHitPoint(mouseX, mouseY, planePoint, planeNormal);

        isDragging = true;
        hasMovedDuringDrag = false;

        // In triangle mode, find ALL unique vertices in VertexRenderer for wireframe sync
        if (faceRenderer.isUsingTriangleMode() && modelRenderer != null) {
            java.util.Set<Integer> uniqueIndicesSet = new java.util.LinkedHashSet<>();
            java.util.List<Vector3f> wireframePositionsList = new java.util.ArrayList<>();

            for (int meshIndex : currentVertexIndices) {
                int uniqueIndex = modelRenderer.getUniqueIndexForMeshVertex(meshIndex);
                if (uniqueIndex >= 0 && !uniqueIndicesSet.contains(uniqueIndex)) {
                    uniqueIndicesSet.add(uniqueIndex);
                    Vector3f pos = modelRenderer.getUniqueVertexPosition(uniqueIndex);
                    if (pos != null) {
                        wireframePositionsList.add(new Vector3f(pos));
                    }
                }
            }

            if (!uniqueIndicesSet.isEmpty()) {
                wireframeVertexIndices = uniqueIndicesSet.stream().mapToInt(Integer::intValue).toArray();
                wireframeOriginalPositions = wireframePositionsList.toArray(new Vector3f[0]);
                logger.debug("Found {} unique wireframe vertices for {} faces in triangle mode",
                    wireframeVertexIndices.length, selectedFaces.size());
            } else {
                wireframeVertexIndices = null;
                wireframeOriginalPositions = null;
            }
        } else {
            // Quad mode: wireframe vertices are the same as currentVertexIndices
            wireframeVertexIndices = currentVertexIndices.clone();
            wireframeOriginalPositions = new Vector3f[currentVertexIndices.length];
            for (int i = 0; i < currentVertexIndices.length; i++) {
                Vector3f pos = vertexOriginalPositions.get(currentVertexIndices[i]);
                wireframeOriginalPositions[i] = pos != null ? new Vector3f(pos) : new Vector3f();
            }
        }

        logger.debug("Started dragging {} faces on plane with normal ({}, {}, {}), {} unique vertices (triangle mode: {})",
                selectedFaces.size(),
                String.format("%.2f", planeNormal.x),
                String.format("%.2f", planeNormal.y),
                String.format("%.2f", planeNormal.z),
                currentVertexIndices.length,
                faceRenderer.isUsingTriangleMode());

        return true;
    }

    @Override
    public void handleMouseMove(float mouseX, float mouseY) {
        if (!isDragging || !selectionState.isDragging()) {
            return;
        }

        if (currentVertexIndices == null || currentVertexIndices.length == 0) {
            logger.warn("Cannot move faces: vertex indices not set");
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

            // Update selection state with delta
            selectionState.updatePosition(modelSpaceDelta);

            // Apply delta to ALL vertices from ALL selected faces
            boolean isTriangleMode = faceRenderer.isUsingTriangleMode();

            for (int vertIdx : currentVertexIndices) {
                Vector3f originalPos = vertexOriginalPositions.get(vertIdx);
                if (originalPos != null) {
                    Vector3f newPos = new Vector3f(originalPos).add(modelSpaceDelta);

                    if (isTriangleMode && modelRenderer != null) {
                        // Update in GenericModelRenderer (mesh vertices)
                        modelRenderer.updateVertexPosition(vertIdx, newPos);
                    } else {
                        // Update in VertexRenderer (unique vertices)
                        vertexRenderer.updateVertexPosition(vertIdx, newPos);
                    }
                }
            }

            // Update wireframe (VertexRenderer + EdgeRenderer) to stay in sync
            if (wireframeVertexIndices != null && wireframeOriginalPositions != null) {
                for (int i = 0; i < wireframeVertexIndices.length; i++) {
                    if (wireframeOriginalPositions[i] != null) {
                        Vector3f newPos = new Vector3f(wireframeOriginalPositions[i]).add(modelSpaceDelta);
                        vertexRenderer.updateVertexPosition(wireframeVertexIndices[i], newPos);
                        edgeRenderer.updateEdgesConnectedToVertexByIndex(wireframeVertexIndices[i], newPos);
                    }
                }
            }

            // Rebuild face overlay in triangle mode, update in quad mode
            if (isTriangleMode) {
                faceRenderer.rebuildFromGenericModelRenderer();
            } else {
                // Update all affected face overlays
                updateAllAffectedFaceOverlays(currentVertexIndices);

                // Update solid model mesh (realtime visual feedback)
                float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
                if (meshVertices != null && modelRenderer != null) {
                    modelRenderer.updateVertexPositions(meshVertices);
                }
            }

            dragStartDelta.set(modelSpaceDelta);

            logger.trace("Dragging {} faces, delta=({},{},{}), {} vertices",
                    selectionState.getSelectionCount(),
                    String.format("%.2f", modelSpaceDelta.x),
                    String.format("%.2f", modelSpaceDelta.y),
                    String.format("%.2f", modelSpaceDelta.z),
                    currentVertexIndices.length);
        }
    }

    @Override
    public void handleMouseRelease(float mouseX, float mouseY) {
        if (!isDragging) {
            return;
        }

        boolean isTriangleMode = faceRenderer.isUsingTriangleMode();

        if (isTriangleMode) {
            // TRIANGLE MODE: GenericModelRenderer already has updated positions
            // Just rebuild face overlay to sync
            faceRenderer.rebuildFromGenericModelRenderer();
            logger.debug("Committed face drag in triangle mode");
        } else {
            // QUAD MODE: Original vertex merge logic
            // Only merge if actual movement occurred during the drag
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
        }

        selectionState.endDrag();
        isDragging = false;
        clearInitialDragHitPoint();
        currentVertexIndices = null;
        vertexOriginalPositions.clear();
        wireframeVertexIndices = null;
        wireframeOriginalPositions = null;

        logger.debug("Ended face drag for {} faces", selectionState.getSelectionCount());
    }

    @Override
    public void cancelDrag() {
        if (!isDragging) {
            return;
        }
        clearInitialDragHitPoint();

        if (currentVertexIndices == null || currentVertexIndices.length == 0) {
            logger.warn("Cannot cancel drag: vertex indices not set");
            isDragging = false;
            vertexOriginalPositions.clear();
            return;
        }

        // Revert to original position in selection state
        selectionState.cancelDrag();

        boolean isTriangleMode = faceRenderer.isUsingTriangleMode();

        // Revert ALL vertices to their original positions
        for (int vertIdx : currentVertexIndices) {
            Vector3f originalPos = vertexOriginalPositions.get(vertIdx);
            if (originalPos != null) {
                if (isTriangleMode && modelRenderer != null) {
                    modelRenderer.updateVertexPosition(vertIdx, originalPos);
                } else {
                    vertexRenderer.updateVertexPosition(vertIdx, originalPos);
                }
            }
        }

        // Revert wireframe to original positions
        if (wireframeVertexIndices != null && wireframeOriginalPositions != null) {
            for (int i = 0; i < wireframeVertexIndices.length; i++) {
                if (wireframeOriginalPositions[i] != null) {
                    vertexRenderer.updateVertexPosition(wireframeVertexIndices[i], wireframeOriginalPositions[i]);
                    edgeRenderer.updateEdgesConnectedToVertexByIndex(wireframeVertexIndices[i], wireframeOriginalPositions[i]);
                }
            }
        }

        if (isTriangleMode) {
            faceRenderer.rebuildFromGenericModelRenderer();
            logger.debug("Reverted {} vertices in triangle mode (cancel)", currentVertexIndices.length);
        } else {
            // Update all affected face overlays with original positions
            updateAllAffectedFaceOverlays(currentVertexIndices);

            // Rebuild mappings after geometry is reverted
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
        vertexOriginalPositions.clear();
        wireframeVertexIndices = null;
        wireframeOriginalPositions = null;
        logger.debug("Cancelled face drag, reverted all changes to original positions");
    }

    /**
     * Update face and all connected geometry (vertices, edges, model, overlay).
     * Uses index-based updates to prevent vertex unification bug.
     *
     * REALTIME UPDATE:
     * - Layer 1: Update vertices in GenericModelRenderer
     * - Layer 2: Update edges connected to these vertices (quad mode only)
     * - Layer 3: Rebuild face overlay (triangle mode) or update face overlays (quad mode)
     *
     * @param newVertices New positions of all face vertices
     * @param vertexIndices Indices of the vertices
     */
    private void updateFaceAndConnectedGeometry(Vector3f[] newVertices, int[] vertexIndices) {
        if (newVertices == null || newVertices.length < 3) {
            logger.warn("Cannot update geometry: invalid new vertices");
            return;
        }

        if (vertexIndices == null || vertexIndices.length < 3) {
            logger.warn("Cannot update geometry: invalid vertex indices");
            return;
        }

        if (newVertices.length != vertexIndices.length) {
            logger.warn("Cannot update geometry: vertex count mismatch ({} vs {})",
                newVertices.length, vertexIndices.length);
            return;
        }

        boolean isTriangleMode = faceRenderer.isUsingTriangleMode();

        if (isTriangleMode) {
            // TRIANGLE MODE: Update GenericModelRenderer directly
            // Each vertex index refers to a mesh vertex in GenericModelRenderer
            for (int i = 0; i < vertexIndices.length; i++) {
                if (vertexIndices[i] >= 0 && modelRenderer != null) {
                    modelRenderer.updateVertexPosition(vertexIndices[i], newVertices[i]);
                }
            }

            // Rebuild face overlay from updated GenericModelRenderer
            faceRenderer.rebuildFromGenericModelRenderer();

            // Update wireframe (VertexRenderer + EdgeRenderer) with translation delta
            Vector3f[] originalVerts = selectionState.getOriginalVertices();
            Vector3f[] currentVerts = selectionState.getCurrentVertices();
            if (originalVerts != null && currentVerts != null && originalVerts.length > 0 && currentVerts.length > 0) {
                Vector3f delta = new Vector3f(currentVerts[0]).sub(originalVerts[0]);
                updateTriangleModeWireframe(delta);
            }

            logger.trace("Updated {} vertices in triangle mode for face {}",
                vertexIndices.length, selectionState.getSelectedFaceIndex());

        } else {
            // QUAD MODE: Original 4-vertex logic
            // LAYER 1: Update vertices (4 corners of face)
            for (int i = 0; i < vertexIndices.length; i++) {
                if (vertexIndices[i] >= 0) {
                    vertexRenderer.updateVertexPosition(vertexIndices[i], newVertices[i]);
                }
            }

            // LAYER 2: Update edges (all edges connected to these vertices)
            // Update edges connected to each vertex pair forming the face outline
            for (int i = 0; i < vertexIndices.length; i++) {
                int v1Index = vertexIndices[i];
                int v2Index = vertexIndices[(i + 1) % vertexIndices.length];

                if (v1Index >= 0 && v2Index >= 0) {
                    edgeRenderer.updateEdgesByVertexIndices(
                        v1Index, newVertices[i],
                        v2Index, newVertices[(i + 1) % vertexIndices.length]
                    );
                }
            }

            // Also update diagonal edges if they exist (for quads)
            if (vertexIndices.length == 4) {
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
            }

            // LAYER 3: Update solid model mesh (realtime visual feedback)
            float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
            if (meshVertices != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(meshVertices);
            }

            // LAYER 4: Update ALL face overlays affected by the modified vertices
            updateAllAffectedFaceOverlays(vertexIndices);

            logger.trace("Updated face and connected geometry for {} vertices in quad mode",
                vertexIndices.length);
        }
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
     * Update wireframe vertices and edges in triangle mode.
     * Applies delta to stored original positions, updating both VertexRenderer and EdgeRenderer.
     *
     * @param delta Translation delta to apply, or null to revert to original positions
     */
    private void updateTriangleModeWireframe(Vector3f delta) {
        if (wireframeVertexIndices == null || wireframeOriginalPositions == null ||
            wireframeVertexIndices.length == 0) {
            return;
        }

        for (int i = 0; i < wireframeVertexIndices.length; i++) {
            if (wireframeVertexIndices[i] >= 0 && wireframeOriginalPositions[i] != null) {
                Vector3f newPos = (delta != null)
                    ? new Vector3f(wireframeOriginalPositions[i]).add(delta)
                    : wireframeOriginalPositions[i];

                vertexRenderer.updateVertexPosition(wireframeVertexIndices[i], newPos);
                edgeRenderer.updateEdgesConnectedToVertexByIndex(wireframeVertexIndices[i], newPos);
            }
        }
    }

    /**
     * Update ALL face overlays that use any of the modified vertices.
     * This ensures that when a face is dragged, OTHER faces that share the same
     * vertices also have their overlays updated to match the new geometry.
     *
     * <p>Note: This method is only used in quad mode. In triangle mode,
     * rebuildFromGenericModelRenderer() handles face overlay updates.
     *
     * @param modifiedVertexIndices Indices of the vertices that were modified
     */
    private void updateAllAffectedFaceOverlays(int[] modifiedVertexIndices) {
        if (faceRenderer == null || !faceRenderer.isInitialized()) {
            return;
        }

        // This method is only for quad mode
        if (faceRenderer.isUsingTriangleMode()) {
            return;
        }

        if (modifiedVertexIndices == null || modifiedVertexIndices.length < 3) {
            return;
        }

        int faceCount = faceRenderer.getFaceCount();
        if (faceCount == 0) {
            return;
        }

        // Iterate through ALL faces and update any that use any of the modified vertices
        for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
            int[] faceVertexIndices = faceRenderer.getFaceVertexIndices(faceIdx);

            if (faceVertexIndices == null || faceVertexIndices.length < 3) {
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
                // Get current positions of all vertices of this face
                Vector3f[] newPositions = new Vector3f[faceVertexIndices.length];
                boolean allValid = true;
                for (int i = 0; i < faceVertexIndices.length; i++) {
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
