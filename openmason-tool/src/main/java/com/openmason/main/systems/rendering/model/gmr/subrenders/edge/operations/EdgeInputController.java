package com.openmason.main.systems.rendering.model.gmr.subrenders.edge.operations;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.gmr.topology.MeshEdge;
import com.openmason.engine.rendering.model.gmr.topology.MeshTopology;
import com.openmason.main.systems.services.commands.MeshSnapshot;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.services.commands.SnapshotCommand;
import com.openmason.main.systems.viewport.input.InputContext;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.util.EdgeCutMath;
import com.openmason.main.systems.viewport.util.SnappingUtil;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeHoverDetector;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.TranslationCoordinator;
import com.openmason.main.systems.menus.textureCreator.keyboard.KeyCodeTranslator;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles all edge selection and manipulation input for the viewport.
 *
 * Responsibilities:
 * - Update edge hover detection via raycasting
 * - Handle edge selection (click to select, but ONLY if no vertex is hovered)
 * - Handle edge translation (drag to move via TranslationCoordinator)
 * - Handle ESC key (cancel drag or clear selection)
 * - Delegates translation to TranslationCoordinator for mutual exclusion
 *
 * Design:
 * - Single Responsibility: Edge interaction only
 * - Delegation: EdgeRenderer for hover, TranslationCoordinator for drag
 * - Priority: High (blocks gizmo and camera, but lower than vertex)
 * - CRITICAL: Checks vertex hover before selecting edge (vertices have higher priority)
 * - Returns true if input was handled (edge selected, dragging, etc.)
 */
public class EdgeInputController {

    private static final Logger logger = LoggerFactory.getLogger(EdgeInputController.class);

    private EdgeRenderer edgeRenderer = null;
    private EdgeSelectionState edgeSelectionState = null;
    private TranslationCoordinator translationCoordinator = null;
    private com.openmason.main.systems.viewport.state.TransformState transformState = null;
    private VertexRenderer vertexRenderer = null; // For priority check!
    private GenericModelRenderer modelRenderer = null; // For Ctrl+Click vertex insertion
    private com.openmason.main.systems.viewport.ViewportUIState viewportState = null; // Grid snapping

    // Undo/redo support (Ctrl+Click vertex insertion)
    private ModelCommandHistory commandHistory = null;
    private RendererSynchronizer synchronizer = null;

    /**
     * Set the edge renderer for hover detection.
     */
    public void setEdgeRenderer(EdgeRenderer edgeRenderer) {
        this.edgeRenderer = edgeRenderer;
        logger.debug("Edge renderer set in EdgeInputController");
    }

    /**
     * Set the edge selection state for selection management.
     */
    public void setEdgeSelectionState(EdgeSelectionState edgeSelectionState) {
        this.edgeSelectionState = edgeSelectionState;
        logger.debug("Edge selection state set in EdgeInputController");
    }

    /**
     * Set the translation coordinator for drag operations.
     * The coordinator ensures mutual exclusion between vertex/edge/face translation.
     */
    public void setTranslationCoordinator(TranslationCoordinator translationCoordinator) {
        this.translationCoordinator = translationCoordinator;
        logger.debug("Translation coordinator set in EdgeInputController");
    }

    /**
     * Set the transform state for model matrix access.
     */
    public void setTransformState(com.openmason.main.systems.viewport.state.TransformState transformState) {
        this.transformState = transformState;
        logger.debug("Transform state set in EdgeInputController");
    }

    /**
     * Set the vertex renderer for priority checking.
     * CRITICAL: Edge controller needs to check if a vertex is hovered before selecting an edge.
     */
    public void setVertexRenderer(VertexRenderer vertexRenderer) {
        this.vertexRenderer = vertexRenderer;
        logger.debug("Vertex renderer set in EdgeInputController for priority checking");
    }

    /**
     * Set the generic model renderer for Ctrl+Click vertex insertion on edges.
     */
    public void setModelRenderer(GenericModelRenderer modelRenderer) {
        this.modelRenderer = modelRenderer;
        logger.debug("Model renderer set in EdgeInputController for vertex insertion");
    }

    /**
     * Set the viewport UI state so vertex insertion uses the global grid snapping settings.
     */
    public void setViewportState(com.openmason.main.systems.viewport.ViewportUIState viewportState) {
        this.viewportState = viewportState;
    }

    /**
     * Set the command history for undo/redo recording of vertex insertions.
     */
    public void setCommandHistory(ModelCommandHistory commandHistory, RendererSynchronizer synchronizer) {
        this.commandHistory = commandHistory;
        this.synchronizer = synchronizer;
    }

    /**
     * Handle edge input.
     *
     * Priority: High (lower than vertex, higher than gizmo and camera)
     * - Vertex controller processes first (higher priority)
     * - Edge only selects if no vertex is hovered
     *
     * @param context Input context with mouse state
     * @return True if edge input was handled (blocks gizmo and camera)
     */
    public boolean handleInput(InputContext context) {
        if (edgeRenderer == null || !edgeRenderer.isInitialized() || !edgeRenderer.isEnabled() ||
            edgeSelectionState == null) {
            return false; // Edge system not available
        }

        // Update edge hover detection
        updateEdgeHover(context);

        // Handle ESC key to cancel drag or deselect edge
        if (KeyCodeTranslator.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            if (translationCoordinator != null && translationCoordinator.isDragging()) {
                // Cancel active drag (coordinator handles all translation types)
                translationCoordinator.cancelDrag();
                logger.debug("Translation drag cancelled (ESC key pressed)");
                return true;
            } else if (edgeSelectionState.hasSelection()) {
                // Clear selection
                edgeSelectionState.clearSelection();
                edgeRenderer.clearSelection();
                logger.debug("Edge selection cleared (ESC key pressed)");
                return true;
            }
        }

        // Handle edge translation (dragging)
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

        // Ctrl+Click on a hovered edge inserts a vertex at the clicked parametric position.
        // Checked BEFORE selection toggling so the click never doubles as a selection change.
        if (context.mouseInBounds && context.mouseClicked && context.ctrlDown) {
            if (handleCtrlClickInsertVertex(context)) {
                return true; // Consume the click — do not toggle selection
            }
        }

        // Handle mouse click for edge selection (if not dragging)
        // CRITICAL: Only select edge if NOT hovering over a vertex (vertices have priority)
        if (context.mouseInBounds && context.mouseClicked) {
            int hoveredVertex = (vertexRenderer != null) ? vertexRenderer.getHoveredVertexIndex() : -1;
            int hoveredEdge = edgeRenderer.getHoveredEdgeIndex();

            // PRIORITY: Only select edge if no vertex is hovered
            if (hoveredVertex < 0 && hoveredEdge >= 0) {
                // Clicking on a hovered edge (and no vertex)
                Vector3f[] endpoints = edgeRenderer.getEdgeEndpoints(hoveredEdge);
                int[] vertexIndices = edgeRenderer.getEdgeVertexIndices(hoveredEdge);

                if (endpoints != null && endpoints.length == 2 && vertexIndices != null && vertexIndices.length == 2) {
                    if (edgeSelectionState.isSelected(hoveredEdge)) {
                        // Click on selected → unselect it
                        edgeSelectionState.toggleEdge(hoveredEdge, endpoints[0], endpoints[1], vertexIndices[0], vertexIndices[1]);
                        edgeRenderer.updateSelectionSet(edgeSelectionState.getSelectedEdgeIndices());
                        logger.debug("Edge {} unselected (now {} selected)",
                                hoveredEdge, edgeSelectionState.getSelectionCount());
                    } else {
                        // Click on unselected → add to selection (multi-select)
                        edgeSelectionState.toggleEdge(hoveredEdge, endpoints[0], endpoints[1], vertexIndices[0], vertexIndices[1]);
                        edgeRenderer.updateSelectionSet(edgeSelectionState.getSelectedEdgeIndices());
                        logger.debug("Edge {} added to selection (now {} selected)",
                                hoveredEdge, edgeSelectionState.getSelectionCount());
                    }

                    // Clear vertex selection when selecting an edge (mutual exclusivity)
                    clearVertexSelection();

                    return true; // Block lower-priority controllers
                }
            }
            // Clicking on empty space → do nothing (use ESC to clear selection)
        }

        return false; // No edge input handled
    }

    /**
     * Insert a vertex on the hovered edge at the clicked parametric position (Ctrl+Click).
     * Reuses the knife tool's hover-with-parameter detection and grid snapping, then
     * subdivides via {@link GenericModelRenderer#subdivideEdgeAtParameter} with
     * snapshot-based undo.
     *
     * @param context Input context with mouse state
     * @return true if the click hit an edge (consumed), false to fall through
     */
    private boolean handleCtrlClickInsertVertex(InputContext context) {
        // Only in EDGE edit mode (hover detection below bypasses the mode-gated hover state)
        if (!EditModeManager.getInstance().isEdgeEditingAllowed()) {
            return false;
        }

        if (modelRenderer == null || !modelRenderer.isInitialized()) {
            return false;
        }

        // PRIORITY: vertices take precedence over edge operations
        int hoveredVertex = (vertexRenderer != null) ? vertexRenderer.getHoveredVertexIndex() : -1;
        if (hoveredVertex >= 0) {
            return false;
        }

        // Detect the hovered edge with its parametric t (knife tool mechanism)
        EdgeHoverDetector.EdgeHitResult hitResult = detectEdgeWithParameter(context);
        if (!hitResult.isHit()) {
            return false;
        }

        // Resolve the topology edge for unique vertex indices
        MeshTopology topology = modelRenderer.getTopology();
        if (topology == null) {
            return false;
        }
        MeshEdge edge = topology.getEdge(hitResult.edgeIndex());
        if (edge == null) {
            return false;
        }

        Vector3f posA = modelRenderer.getUniqueVertexPosition(edge.vertexA());
        Vector3f posB = modelRenderer.getUniqueVertexPosition(edge.vertexB());
        if (posA == null || posB == null) {
            return false;
        }

        // Apply grid snapping to the cut position and recompute t to match (same as knife)
        float t = hitResult.t();
        if (isGridSnappingEnabled()) {
            Vector3f cutPos = snapToGrid(EdgeCutMath.pointOnEdge(posA, posB, t));
            t = EdgeCutMath.recomputeT(cutPos, posA, posB);
        }

        // Capture snapshot before the topology change for undo
        MeshSnapshot before = (commandHistory != null && synchronizer != null)
            ? MeshSnapshot.capture(modelRenderer) : null;

        int newVertexIndex = modelRenderer.subdivideEdgeAtParameter(edge.vertexA(), edge.vertexB(), t);
        if (newVertexIndex < 0) {
            logger.warn("Ctrl+Click vertex insertion failed on edge {} at t={}", hitResult.edgeIndex(), t);
            return true; // Still consume — the user targeted an edge
        }

        if (before != null) {
            MeshSnapshot after = MeshSnapshot.capture(modelRenderer);
            commandHistory.pushCompleted(SnapshotCommand.custom(
                "Insert Vertex on Edge", before, after, modelRenderer, synchronizer));
        }

        logger.info("Inserted vertex {} on edge {} at t={}", newVertexIndex, hitResult.edgeIndex(), t);
        return true;
    }

    /**
     * Run edge hover detection with parameter using EdgeHoverDetector.
     */
    private EdgeHoverDetector.EdgeHitResult detectEdgeWithParameter(InputContext context) {
        float[] edgePositions = edgeRenderer.getEdgePositions();
        int edgeCount = edgeRenderer.getEdgeCount();
        float lineWidth = edgeRenderer.getLineWidth();

        if (edgePositions == null || edgeCount == 0) {
            return EdgeHoverDetector.EdgeHitResult.NONE;
        }

        Matrix4f modelMatrix = (transformState != null)
            ? transformState.getTransformMatrix()
            : new Matrix4f();

        return EdgeHoverDetector.detectHoveredEdgeWithParameter(
            context.mouseX, context.mouseY,
            context.viewportWidth, context.viewportHeight,
            context.viewMatrix, context.projectionMatrix,
            modelMatrix,
            edgePositions, edgeCount, lineWidth
        );
    }

    /**
     * @return true if grid snapping is currently enabled in the viewport
     */
    private boolean isGridSnappingEnabled() {
        return viewportState != null && viewportState.getGridSnappingEnabled().get();
    }

    /**
     * Snap a position to the grid using the viewport's global snapping increment.
     */
    private Vector3f snapToGrid(Vector3f pos) {
        float increment = viewportState.getGridSnappingIncrement().get();
        return new Vector3f(
            SnappingUtil.snapToGrid(pos.x, increment),
            SnappingUtil.snapToGrid(pos.y, increment),
            SnappingUtil.snapToGrid(pos.z, increment)
        );
    }

    /**
     * Update edge hover detection.
     * Performs hover detection and updates the renderer's hover state.
     * PRIORITY: Only update edge hover if vertex is NOT hovered (vertices have higher priority).
     * Only processes hover when EditMode is EDGE.
     */
    private void updateEdgeHover(InputContext context) {
        if (edgeRenderer == null || !edgeRenderer.isInitialized() || !edgeRenderer.isEnabled()) {
            return;
        }

        // Skip hover detection if not in EDGE edit mode
        if (!EditModeManager.getInstance().isEdgeEditingAllowed()) {
            edgeRenderer.setHoveredEdgeIndex(-1);
            return;
        }

        // PRIORITY CHECK: Only update edge hover if vertex is NOT hovered
        int hoveredVertex = (vertexRenderer != null) ? vertexRenderer.getHoveredVertexIndex() : -1;
        if (hoveredVertex >= 0) {
            edgeRenderer.setHoveredEdgeIndex(-1); // Clear edge hover - vertex has priority
            return;
        }

        // Get edge data from renderer
        float[] edgePositions = edgeRenderer.getEdgePositions();
        int edgeCount = edgeRenderer.getEdgeCount();
        float lineWidth = edgeRenderer.getLineWidth();

        if (edgePositions == null || edgeCount == 0) {
            return;
        }

        // Get model matrix for proper edge transformation
        Matrix4f modelMatrix = (transformState != null)
                ? transformState.getTransformMatrix()
                : new Matrix4f(); // Identity if no transform state

        // Detect hovered edge using screen-space point-to-line distance detection
        int newHoveredEdge = EdgeHoverDetector.detectHoveredEdge(
                context.mouseX,
                context.mouseY,
                context.viewportWidth,
                context.viewportHeight,
                context.viewMatrix,
                context.projectionMatrix,
                modelMatrix,
                edgePositions,
                edgeCount,
                lineWidth
        );

        // Update renderer's hover state
        edgeRenderer.setHoveredEdgeIndex(newHoveredEdge);
    }

    /**
     * Clear vertex selection when selecting an edge.
     * This maintains mutual exclusivity between vertex and edge selection.
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
}
