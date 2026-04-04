package com.openmason.main.systems.rendering.model.gmr.subrenders.vertex;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.services.commands.MeshSnapshot;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.services.commands.SnapshotCommand;
import com.openmason.main.systems.services.commands.VertexMoveCommand;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Handles vertex translation through plane-constrained dragging.
 * Extends TranslationHandlerBase for shared functionality (DRY, SOLID).
 * Uses Blender-style plane-constrained movement for intuitive 3D editing.
 *
 * <p>Vertex positions are computed as: original (from selection state) + model-space delta.
 * The VertexRenderer is the single source of truth for current positions during a drag.</p>
 */
public class VertexTranslationHandler extends TranslationHandlerBase {

    private static final Logger logger = LoggerFactory.getLogger(VertexTranslationHandler.class);

    private final VertexSelectionState selectionState;
    private final VertexRenderer vertexRenderer;
    private final EdgeRenderer edgeRenderer;
    private final FaceRenderer faceRenderer;
    private final GenericModelRenderer modelRenderer;
    private final ViewportRenderPipeline viewportRenderPipeline;

    // Undo/redo support
    private ModelCommandHistory commandHistory;
    private RendererSynchronizer synchronizer;
    private MeshSnapshot preDragSnapshot;

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

        // Start dragging the selected vertex
        dragStartMouseX = mouseX;
        dragStartMouseY = mouseY;

        // Calculate working plane based on camera orientation (from base class)
        Vector3f cameraDirection = getCameraDirection();
        Vector3f vertexPosition = selectionState.getOriginalPosition();

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

        // Capture mesh state before drag for snapshot-based undo (needed if merge occurs)
        if (commandHistory != null && synchronizer != null) {
            preDragSnapshot = MeshSnapshot.capture(modelRenderer);
        }

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
        Vector3f delta = calculateDragDelta(mouseX, mouseY, planePoint, planeNormal);

        if (delta != null) {
            // Apply grid snapping to delta if enabled
            if (viewportState != null && viewportState.getGridSnappingEnabled().get()) {
                delta = applyGridSnappingToDelta(delta);
            }

            // Convert delta to model space (vertex positions are in model space)
            Vector3f modelSpaceDelta = worldToModelSpaceDelta(delta);

            // Mark that actual movement occurred during this drag
            hasMovedDuringDrag = true;

            // Get all selected vertex indices
            Set<Integer> selectedIndices = selectionState.getSelectedVertexIndices();

            // Compute new position for each vertex from original + delta,
            // then push directly to the renderer (single source of truth)
            for (int vertexIndex : selectedIndices) {
                Vector3f original = selectionState.getOriginalPosition(vertexIndex);
                if (original == null) continue;

                Vector3f newPos = new Vector3f(original).add(modelSpaceDelta);

                // Update visual preview (model space for VBO)
                vertexRenderer.updateVertexPosition(vertexIndex, newPos);

                // Update connected edge endpoints using INDEX-BASED matching (model space for VBO)
                edgeRenderer.updateEdgesConnectedToVertexByIndex(vertexIndex, newPos);
            }

            // REALTIME VISUAL UPDATE: Update ModelRenderer during drag (no merging)
            float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
            if (meshVertices != null && modelRenderer != null) {
                modelRenderer.updateVertexPositions(meshVertices);

                // Face overlays need to be rebuilt from GenericModelRenderer
                faceRenderer.rebuildFromGenericModelRenderer();
            }

            logger.trace("Dragging {} vertices by delta ({}, {}, {})",
                    selectedIndices.size(),
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

        // COMMIT: Update ModelRenderer with mesh vertices
        // Topology rebuild in updateVertexPositions() handles edge/face updates via observer pattern
        float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
        if (meshVertices != null && modelRenderer != null) {
            modelRenderer.updateVertexPositions(meshVertices);
            faceRenderer.rebuildFromGenericModelRenderer();
            logger.debug("Committed vertex drag to ModelRenderer{}", !indexRemapping.isEmpty() ? " (with merge)" : "");
        }

        // Record undo command after commit
        if (hasMovedDuringDrag && commandHistory != null && synchronizer != null) {
            if (!indexRemapping.isEmpty() && preDragSnapshot != null) {
                // Merge changed topology — use snapshot-based undo
                MeshSnapshot postDragSnapshot = MeshSnapshot.capture(modelRenderer);
                commandHistory.pushCompleted(SnapshotCommand.vertexMerge(
                    preDragSnapshot, postDragSnapshot, modelRenderer, synchronizer));
            } else {
                // No merge — use delta-based undo (lighter weight)
                Set<Integer> uniqueIndices = selectionState.getSelectedVertexIndices();
                Map<Integer, VertexMoveCommand.VertexDelta> deltas = new HashMap<>();
                for (int uniqueIndex : uniqueIndices) {
                    int[] meshIndices = modelRenderer.getMeshIndicesForUniqueVertex(uniqueIndex);
                    if (meshIndices == null || meshIndices.length == 0) continue;
                    int meshIndex = meshIndices[0];

                    Vector3f originalPos = selectionState.getOriginalPosition(uniqueIndex);
                    Vector3f currentPos = modelRenderer.getVertexPosition(meshIndex);

                    if (originalPos != null && currentPos != null) {
                        deltas.put(meshIndex, new VertexMoveCommand.VertexDelta(
                            meshIndex, new Vector3f(originalPos), new Vector3f(currentPos)));
                    }
                }
                if (!deltas.isEmpty()) {
                    commandHistory.pushCompleted(new VertexMoveCommand(
                        deltas, "Move Vertex", modelRenderer, synchronizer));
                }
            }
        }
        preDragSnapshot = null;

        // Commit final positions from the renderer (single source of truth) back to selection state
        Set<Integer> selectedIndices = selectionState.getSelectedVertexIndices();
        Map<Integer, Vector3f> committedPositions = new HashMap<>();
        for (int vertexIndex : selectedIndices) {
            Vector3f pos = vertexRenderer.getVertexPosition(vertexIndex);
            if (pos != null) {
                committedPositions.put(vertexIndex, pos);
            }
        }
        selectionState.endDrag(committedPositions);

        isDragging = false;
        clearInitialDragHitPoint();

        Vector3f finalPos = selectionState.getOriginalPosition();
        if (finalPos != null) {
            logger.debug("Ended vertex drag at position ({}, {}, {})",
                    String.format("%.2f", finalPos.x),
                    String.format("%.2f", finalPos.y),
                    String.format("%.2f", finalPos.z));
        }
    }

    @Override
    public void cancelDrag() {
        if (!isDragging) {
            return;
        }
        clearInitialDragHitPoint();

        // Get all selected indices before cancelling
        Set<Integer> selectedIndices = selectionState.getSelectedVertexIndices();

        // Revert each selected vertex to original position from selection state
        for (int vertexIndex : selectedIndices) {
            Vector3f originalPosition = selectionState.getOriginalPosition(vertexIndex);

            if (originalPosition != null) {
                vertexRenderer.updateVertexPosition(vertexIndex, originalPosition);

                // Revert connected edges using INDEX-BASED matching
                edgeRenderer.updateEdgesConnectedToVertexByIndex(vertexIndex, originalPosition);
            }
        }

        // Clear drag state in selection
        selectionState.cancelDrag();

        // REVERT: Update ModelRenderer with original mesh vertices
        float[] meshVertices = MeshManager.getInstance().getAllMeshVertices();
        if (meshVertices != null && modelRenderer != null) {
            modelRenderer.updateVertexPositions(meshVertices);
            faceRenderer.rebuildFromGenericModelRenderer();
            logger.debug("Reverted ModelRenderer to original positions (cancel)");
        }

        isDragging = false;
        preDragSnapshot = null;
        logger.debug("Cancelled vertex drag for {} vertices, reverted to original positions",
                selectedIndices.size());
    }
}
