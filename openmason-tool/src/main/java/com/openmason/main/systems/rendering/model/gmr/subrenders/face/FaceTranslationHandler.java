package com.openmason.main.systems.rendering.model.gmr.subrenders.face;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.services.commands.VertexMoveCommand;
import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.ViewportRenderPipeline;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.common.TranslationHandlerBase;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles face translation through plane-constrained dragging.
 * Extends TranslationHandlerBase for shared functionality (DRY, SOLID).
 * Uses Blender-style plane-constrained movement for intuitive 3D editing.
 *
 * Moves all corner vertices of a face together, maintaining face shape.
 * Uses topology-backed triangle rendering via GenericModelRenderer.
 */
public class FaceTranslationHandler extends TranslationHandlerBase {

    private static final Logger logger = LoggerFactory.getLogger(FaceTranslationHandler.class);

    private final FaceSelectionState selectionState;
    private final FaceRenderer faceRenderer;
    private final VertexRenderer vertexRenderer;
    private final EdgeRenderer edgeRenderer;
    private final GenericModelRenderer modelRenderer;
    private final ViewportRenderPipeline viewportRenderPipeline;

    // Undo/redo support
    private ModelCommandHistory commandHistory;
    private RendererSynchronizer synchronizer;

    // Face-specific drag state - now supports multi-selection
    private Vector3f dragStartDelta = new Vector3f(); // Track accumulated translation
    private int[] currentVertexIndices = null; // ALL unique mesh vertices from ALL selected faces
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

    /**
     * Set the command history for undo/redo recording.
     */
    public void setCommandHistory(ModelCommandHistory commandHistory, RendererSynchronizer synchronizer) {
        this.commandHistory = commandHistory;
        this.synchronizer = synchronizer;
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
            int[] faceVerts = faceRenderer.getTriangleVertexIndicesForFace(faceIndex);
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

        // Store original positions for all vertices from GenericModelRenderer
        for (int vertIdx : currentVertexIndices) {
            Vector3f pos = modelRenderer.getVertexPosition(vertIdx);
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

        // Find ALL unique vertices in VertexRenderer for wireframe sync
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
            logger.debug("Found {} unique wireframe vertices for {} faces",
                wireframeVertexIndices.length, selectedFaces.size());
        } else {
            wireframeVertexIndices = null;
            wireframeOriginalPositions = null;
        }

        logger.debug("Started dragging {} faces on plane with normal ({}, {}, {}), {} unique vertices",
                selectedFaces.size(),
                String.format("%.2f", planeNormal.x),
                String.format("%.2f", planeNormal.y),
                String.format("%.2f", planeNormal.z),
                currentVertexIndices.length);

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

            // Apply delta to ALL vertices from ALL selected faces via GenericModelRenderer
            for (int vertIdx : currentVertexIndices) {
                Vector3f originalPos = vertexOriginalPositions.get(vertIdx);
                if (originalPos != null) {
                    Vector3f newPos = new Vector3f(originalPos).add(modelSpaceDelta);
                    modelRenderer.updateVertexPosition(vertIdx, newPos);
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

            // Rebuild face overlay from GenericModelRenderer
            faceRenderer.rebuildFromGenericModelRenderer();

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

        // GenericModelRenderer already has updated positions — rebuild face overlay to sync
        faceRenderer.rebuildFromGenericModelRenderer();
        logger.debug("Committed face drag");

        // Record undo command before clearing state (need original positions)
        if (hasMovedDuringDrag && commandHistory != null && synchronizer != null
                && currentVertexIndices != null) {
            java.util.Map<Integer, VertexMoveCommand.VertexDelta> deltas = new java.util.HashMap<>();
            for (int vertIdx : currentVertexIndices) {
                Vector3f originalPos = vertexOriginalPositions.get(vertIdx);
                if (originalPos != null) {
                    Vector3f currentPos = modelRenderer.getVertexPosition(vertIdx);
                    if (currentPos != null) {
                        deltas.put(vertIdx, new VertexMoveCommand.VertexDelta(
                            vertIdx, new Vector3f(originalPos), new Vector3f(currentPos)));
                    }
                }
            }
            if (!deltas.isEmpty()) {
                commandHistory.pushCompleted(new VertexMoveCommand(
                    deltas, "Move Face", modelRenderer, synchronizer));
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

        // Revert ALL vertices to their original positions in GenericModelRenderer
        for (int vertIdx : currentVertexIndices) {
            Vector3f originalPos = vertexOriginalPositions.get(vertIdx);
            if (originalPos != null) {
                modelRenderer.updateVertexPosition(vertIdx, originalPos);
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

        faceRenderer.rebuildFromGenericModelRenderer();
        logger.debug("Reverted {} vertices (cancel)", currentVertexIndices.length);

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
     * - Layer 2: Rebuild face overlay from GenericModelRenderer
     * - Layer 3: Update wireframe (VertexRenderer + EdgeRenderer)
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

        // Update GenericModelRenderer directly
        for (int i = 0; i < vertexIndices.length; i++) {
            if (vertexIndices[i] >= 0) {
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
            updateWireframe(delta);
        }

        logger.trace("Updated {} vertices for face {}",
            vertexIndices.length, selectionState.getSelectedFaceIndex());
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
     * Update wireframe vertices and edges.
     * Applies delta to stored original positions, updating both VertexRenderer and EdgeRenderer.
     *
     * @param delta Translation delta to apply, or null to revert to original positions
     */
    private void updateWireframe(Vector3f delta) {
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
}
