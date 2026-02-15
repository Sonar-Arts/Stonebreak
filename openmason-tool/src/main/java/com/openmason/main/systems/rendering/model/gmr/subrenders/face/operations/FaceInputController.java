package com.openmason.main.systems.rendering.model.gmr.subrenders.face.operations;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.services.commands.MeshSnapshot;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.services.commands.SnapshotCommand;
import com.openmason.main.systems.viewport.input.InputContext;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.TranslationCoordinator;
import imgui.ImGui;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles all face selection and manipulation input for the viewport.
 *
 * Responsibilities:
 * - Update face hover detection via raycasting
 * - Handle face selection (click to select, but ONLY if no vertex or edge is hovered)
 * - Handle face translation (drag to move via TranslationCoordinator)
 * - Handle ESC key (cancel drag or clear selection)
 * - Delegates translation to TranslationCoordinator for mutual exclusion
 *
 * Design:
 * - Single Responsibility: Face interaction only
 * - Delegation: FaceRenderer for hover, TranslationCoordinator for drag
 * - Priority: LOWEST (blocks only gizmo and camera, lower than vertex and edge)
 * - CRITICAL: Checks vertex AND edge hover before selecting face (faces have lowest priority)
 * - Returns true if input was handled (face selected, dragging, etc.)
 */
public class FaceInputController {

    private static final Logger logger = LoggerFactory.getLogger(FaceInputController.class);

    private FaceRenderer faceRenderer = null;
    private FaceSelectionState faceSelectionState = null;
    private TranslationCoordinator translationCoordinator = null;
    private com.openmason.main.systems.viewport.state.TransformState transformState = null;
    private VertexRenderer vertexRenderer = null; // For priority check!
    private EdgeRenderer edgeRenderer = null; // For priority check!
    private GenericModelRenderer modelRenderer = null;

    // Undo/redo support
    private ModelCommandHistory commandHistory;
    private RendererSynchronizer synchronizer;

    /**
     * Set the face renderer for hover detection.
     */
    public void setFaceRenderer(FaceRenderer faceRenderer) {
        this.faceRenderer = faceRenderer;
        logger.debug("Face renderer set in FaceInputController");
    }

    /**
     * Set the face selection state for selection management.
     */
    public void setFaceSelectionState(FaceSelectionState faceSelectionState) {
        this.faceSelectionState = faceSelectionState;
        logger.debug("Face selection state set in FaceInputController");
    }

    /**
     * Set the translation coordinator for drag operations.
     * The coordinator ensures mutual exclusion between vertex/edge/face translation.
     */
    public void setTranslationCoordinator(TranslationCoordinator translationCoordinator) {
        this.translationCoordinator = translationCoordinator;
        logger.debug("Translation coordinator set in FaceInputController");
    }

    /**
     * Set the transform state for model matrix access.
     */
    public void setTransformState(com.openmason.main.systems.viewport.state.TransformState transformState) {
        this.transformState = transformState;
        logger.debug("Transform state set in FaceInputController");
    }

    /**
     * Set the vertex renderer for priority checking.
     * CRITICAL: Face controller needs to check if a vertex is hovered before selecting a face.
     */
    public void setVertexRenderer(VertexRenderer vertexRenderer) {
        this.vertexRenderer = vertexRenderer;
        logger.debug("Vertex renderer set in FaceInputController for priority checking");
    }

    /**
     * Set the edge renderer for priority checking.
     * CRITICAL: Face controller needs to check if an edge is hovered before selecting a face.
     */
    public void setEdgeRenderer(EdgeRenderer edgeRenderer) {
        this.edgeRenderer = edgeRenderer;
        logger.debug("Edge renderer set in FaceInputController for priority checking");
    }

    /**
     * Set the generic model renderer for face deletion operations.
     */
    public void setModelRenderer(GenericModelRenderer modelRenderer) {
        this.modelRenderer = modelRenderer;
        logger.debug("Model renderer set in FaceInputController");
    }

    /**
     * Set the command history for undo/redo recording.
     */
    public void setCommandHistory(ModelCommandHistory commandHistory, RendererSynchronizer synchronizer) {
        this.commandHistory = commandHistory;
        this.synchronizer = synchronizer;
    }

    /**
     * Handle face input.
     *
     * Priority: LOWEST (lower than vertex and edge, higher than gizmo and camera)
     * - Vertex controller processes first (highest priority)
     * - Edge controller processes second (high priority)
     * - Face only selects if no vertex AND no edge is hovered
     *
     * @param context Input context with mouse state
     * @return True if face input was handled (blocks gizmo and camera)
     */
    public boolean handleInput(InputContext context) {
        if (faceRenderer == null || !faceRenderer.isInitialized() || !faceRenderer.isEnabled() ||
            faceSelectionState == null) {
            return false; // Face system not available
        }

        // Update face hover detection
        updateFaceHover(context);

        // Handle ESC key to cancel drag or deselect face
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            if (translationCoordinator != null && translationCoordinator.isDragging()) {
                // Cancel active drag (coordinator handles all translation types)
                translationCoordinator.cancelDrag();
                logger.debug("Translation drag cancelled (ESC key pressed)");
                return true;
            } else if (faceSelectionState.hasSelection()) {
                // Clear selection
                faceSelectionState.clearSelection();
                faceRenderer.clearSelection();
                logger.debug("Face selection cleared (ESC key pressed)");
                return true;
            }
        }

        // Handle X key to delete selected face
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_X) && !ImGui.getIO().getKeyCtrl()) {
            if (faceSelectionState.getSelectionCount() == 1 && modelRenderer != null) {
                int faceId = faceSelectionState.getSelectedFaceIndices().iterator().next();

                MeshSnapshot before = (commandHistory != null && synchronizer != null)
                    ? MeshSnapshot.capture(modelRenderer) : null;

                boolean success = modelRenderer.deleteFace(faceId);
                if (success) {
                    if (before != null) {
                        MeshSnapshot after = MeshSnapshot.capture(modelRenderer);
                        commandHistory.pushCompleted(
                            SnapshotCommand.faceDeletion(before, after, modelRenderer, synchronizer));
                    }
                    faceSelectionState.clearSelection();
                    faceRenderer.clearSelection();
                    logger.info("Face {} deleted (X key)", faceId);
                } else {
                    logger.warn("Face deletion failed for face {}", faceId);
                }
                return true;
            }
        }

        // Handle face translation (dragging)
        if (translationCoordinator != null && translationCoordinator.isDragging()) {
            // Continue dragging via coordinator
            translationCoordinator.handleMouseMove(context.mouseX, context.mouseY);

            // End drag on mouse release
            if (context.mouseReleased) {
                translationCoordinator.handleMouseRelease(context.mouseX, context.mouseY);
                logger.debug("Translation drag ended");
            }

            return true; // Block lower-priority controllers while dragging
        }

        // NOTE: Click-to-drag removed. Use G key for grab mode (Blender-style).

        // Handle mouse click for face selection (if not dragging)
        // CRITICAL: Only select face if NOT hovering over a vertex OR edge (faces have lowest priority)
        if (context.mouseInBounds && context.mouseClicked) {
            int hoveredVertex = (vertexRenderer != null) ? vertexRenderer.getHoveredVertexIndex() : -1;
            int hoveredEdge = (edgeRenderer != null) ? edgeRenderer.getHoveredEdgeIndex() : -1;
            int hoveredFace = faceRenderer.getHoveredFaceIndex();

            // PRIORITY: Only select face if no vertex AND no edge is hovered
            if (hoveredVertex < 0 && hoveredEdge < 0 && hoveredFace >= 0) {
                // Clicking on a hovered face (and no vertex or edge)
                // Get vertices and indices from topology-backed triangle data
                Vector3f[] faceVertices = faceRenderer.getTriangleVertexPositionsForFace(hoveredFace);
                int[] vertexIndices = faceRenderer.getTriangleVertexIndicesForFace(hoveredFace);

                if (faceVertices != null && faceVertices.length >= 3 &&
                    vertexIndices != null && vertexIndices.length >= 3) {

                    if (faceSelectionState.isSelected(hoveredFace)) {
                        // Click on selected → unselect it
                        faceSelectionState.toggleFace(hoveredFace, faceVertices, vertexIndices);
                        faceRenderer.updateSelectionSet(faceSelectionState.getSelectedFaceIndices());
                        logger.debug("Face {} unselected (now {} selected)",
                                hoveredFace, faceSelectionState.getSelectionCount());
                    } else {
                        // Click on unselected → add to selection (multi-select)
                        faceSelectionState.toggleFace(hoveredFace, faceVertices, vertexIndices);
                        faceRenderer.updateSelectionSet(faceSelectionState.getSelectedFaceIndices());
                        logger.debug("Face {} added to selection (now {} selected)",
                                hoveredFace, faceSelectionState.getSelectionCount());
                    }

                    // Clear vertex and edge selection when selecting a face (mutual exclusivity)
                    clearVertexSelection();
                    clearEdgeSelection();

                    return true; // Block lower-priority controllers
                }
            }
            // Clicking on empty space → do nothing (use ESC to clear selection)
        }

        return false; // No face input handled
    }

    /**
     * Update face hover detection.
     * PRIORITY: Only update face hover if neither vertex nor edge is hovered.
     * Vertices have highest priority, edges have second priority, faces have lowest.
     * Only processes hover when EditMode is FACE.
     */
    private void updateFaceHover(InputContext context) {
        if (faceRenderer == null || !faceRenderer.isInitialized() || !faceRenderer.isEnabled()) {
            return;
        }

        // Skip hover detection if not in FACE edit mode
        if (!EditModeManager.getInstance().isFaceEditingAllowed()) {
            faceRenderer.clearHover();
            return;
        }

        // PRIORITY CHECK: Only update face hover if vertex is NOT hovered
        int hoveredVertex = (vertexRenderer != null) ? vertexRenderer.getHoveredVertexIndex() : -1;
        if (hoveredVertex >= 0) {
            // Vertex is hovered - clear face hover (vertex has highest priority)
            faceRenderer.clearHover();
            return;
        }

        // PRIORITY CHECK: Only update face hover if edge is NOT hovered
        int hoveredEdge = (edgeRenderer != null) ? edgeRenderer.getHoveredEdgeIndex() : -1;
        if (hoveredEdge >= 0) {
            // Edge is hovered - clear face hover (edge has higher priority than face)
            faceRenderer.clearHover();
            return;
        }

        // Get model matrix for proper face transformation
        Matrix4f modelMatrix = (transformState != null)
                ? transformState.getTransformMatrix()
                : new Matrix4f(); // Identity if no transform state

        // Update face hover with camera matrices AND model matrix for raycasting
        faceRenderer.handleMouseMove(
                context.mouseX,
                context.mouseY,
                context.viewMatrix,
                context.projectionMatrix,
                modelMatrix,
                context.viewportWidth,
                context.viewportHeight
        );
    }

    /**
     * Clear vertex selection when selecting a face.
     * This maintains mutual exclusivity between vertex and face selection.
     */
    private void clearVertexSelection() {
        if (vertexRenderer != null && vertexRenderer.isInitialized()) {
            com.openmason.main.systems.viewport.state.VertexSelectionState vertexSelectionState =
                    new com.openmason.main.systems.viewport.state.VertexSelectionState();
            if (vertexSelectionState.hasSelection()) {
                vertexSelectionState.clearSelection();
                vertexRenderer.clearSelection();
            }
        }
    }

    /**
     * Clear edge selection when selecting a face.
     * This maintains mutual exclusivity between edge and face selection.
     */
    private void clearEdgeSelection() {
        if (edgeRenderer != null && edgeRenderer.isInitialized()) {
            com.openmason.main.systems.viewport.state.EdgeSelectionState edgeSelectionState =
                    new com.openmason.main.systems.viewport.state.EdgeSelectionState();
            if (edgeSelectionState.hasSelection()) {
                edgeSelectionState.clearSelection();
                edgeRenderer.clearSelection();
            }
        }
    }
}
