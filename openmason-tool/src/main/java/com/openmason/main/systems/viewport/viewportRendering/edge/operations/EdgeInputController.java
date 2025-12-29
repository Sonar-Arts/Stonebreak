package com.openmason.main.systems.viewport.viewportRendering.edge.operations;

import com.openmason.main.systems.viewport.input.InputContext;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.viewportRendering.edge.EdgeHoverDetector;
import com.openmason.main.systems.viewport.viewportRendering.edge.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.TranslationCoordinator;
import imgui.ImGui;
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
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
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

        // Start drag on selected edge
        if (context.mouseInBounds && context.mouseClicked && edgeSelectionState.hasSelection()) {
            int selectedEdge = edgeRenderer.getSelectedEdgeIndex();
            int hoveredEdge = edgeRenderer.getHoveredEdgeIndex();

            // Check if clicking on the selected edge
            if (selectedEdge >= 0 && selectedEdge == hoveredEdge) {
                // Start dragging via coordinator (ensures mutual exclusion)
                if (translationCoordinator != null &&
                    translationCoordinator.handleMousePress(context.mouseX, context.mouseY)) {
                    logger.debug("Started translation drag on edge {}", selectedEdge);
                    return true;
                }
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
                    if (context.shiftDown) {
                        // Shift+click: Toggle this edge in selection
                        edgeSelectionState.toggleEdge(hoveredEdge, endpoints[0], endpoints[1], vertexIndices[0], vertexIndices[1]);
                        edgeRenderer.updateSelectionSet(edgeSelectionState.getSelectedEdgeIndices());
                        logger.debug("Edge {} toggled in selection (now {} selected)",
                                hoveredEdge, edgeSelectionState.getSelectionCount());
                    } else {
                        // Normal click: Replace selection with this edge
                        edgeSelectionState.selectEdge(hoveredEdge, endpoints[0], endpoints[1], vertexIndices[0], vertexIndices[1]);
                        edgeRenderer.setSelectedEdge(hoveredEdge);
                        logger.debug("Edge {} selected (vertices {}, {}) with endpoints ({}, {}, {}) - ({}, {}, {})",
                                hoveredEdge, vertexIndices[0], vertexIndices[1],
                                String.format("%.2f", endpoints[0].x), String.format("%.2f", endpoints[0].y), String.format("%.2f", endpoints[0].z),
                                String.format("%.2f", endpoints[1].x), String.format("%.2f", endpoints[1].y), String.format("%.2f", endpoints[1].z));
                    }

                    // Clear vertex selection when selecting an edge (mutual exclusivity)
                    clearVertexSelection();

                    return true; // Block lower-priority controllers
                }
            } else {
                // Clicking on empty space (no vertex, no edge) - only clear if NOT holding Shift
                if (hoveredVertex < 0 && hoveredEdge < 0 && !context.shiftDown && edgeSelectionState.hasSelection()) {
                    edgeSelectionState.clearSelection();
                    edgeRenderer.clearSelection();
                    logger.debug("Edge selection cleared (clicked on empty space)");
                    return true; // Block lower-priority controllers
                }
            }
        }

        return false; // No edge input handled
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
