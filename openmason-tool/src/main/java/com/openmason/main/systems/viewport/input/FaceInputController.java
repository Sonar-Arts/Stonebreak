package com.openmason.main.systems.viewport.input;

import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.viewportRendering.face.FaceRenderer;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.edge.EdgeRenderer;
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

        // Start drag on selected face
        if (context.mouseInBounds && context.mouseClicked && faceSelectionState.hasSelection()) {
            int selectedFace = faceRenderer.getSelectedFaceIndex();
            int hoveredFace = faceRenderer.getHoveredFaceIndex();

            // Check if clicking on the selected face
            if (selectedFace >= 0 && selectedFace == hoveredFace) {
                // Start dragging via coordinator (ensures mutual exclusion)
                if (translationCoordinator != null &&
                    translationCoordinator.handleMousePress(context.mouseX, context.mouseY)) {
                    logger.debug("Started translation drag on face {}", selectedFace);
                    return true;
                }
            }
        }

        // Handle mouse click for face selection (if not dragging)
        // CRITICAL: Only select face if NOT hovering over a vertex OR edge (faces have lowest priority)
        if (context.mouseInBounds && context.mouseClicked) {
            int hoveredVertex = (vertexRenderer != null) ? vertexRenderer.getHoveredVertexIndex() : -1;
            int hoveredEdge = (edgeRenderer != null) ? edgeRenderer.getHoveredEdgeIndex() : -1;
            int hoveredFace = faceRenderer.getHoveredFaceIndex();

            // PRIORITY: Only select face if no vertex AND no edge is hovered
            if (hoveredVertex < 0 && hoveredEdge < 0 && hoveredFace >= 0) {
                // Clicking on a hovered face (and no vertex or edge) - select it
                Vector3f[] faceVertices = faceRenderer.getFaceVertices(hoveredFace);
                int[] vertexIndices = faceRenderer.getFaceVertexIndices(hoveredFace);

                if (faceVertices != null && faceVertices.length == 4 &&
                    vertexIndices != null && vertexIndices.length == 4) {
                    // Select face with all 4 corner vertices
                    faceSelectionState.selectFace(hoveredFace, faceVertices);
                    faceRenderer.setSelectedFace(hoveredFace);
                    logger.debug("Face {} selected with vertex indices [{}, {}, {}, {}]",
                            hoveredFace,
                            vertexIndices[0], vertexIndices[1], vertexIndices[2], vertexIndices[3]);

                    // Clear vertex and edge selection when selecting a face
                    clearVertexSelection();
                    clearEdgeSelection();

                    return true; // Block lower-priority controllers
                }
            } else {
                // Clicking on empty space (no vertex, no edge, no face) - deselect face if something was selected
                if (hoveredVertex < 0 && hoveredEdge < 0 && hoveredFace < 0 && faceSelectionState.hasSelection()) {
                    faceSelectionState.clearSelection();
                    faceRenderer.clearSelection();
                    logger.debug("Face selection cleared (clicked on empty space)");
                    return true; // Block lower-priority controllers
                }
            }
        }

        return false; // No face input handled
    }

    /**
     * Update face hover detection.
     * CRITICAL: Only block if vertex is hovered (vertices have highest priority).
     * Edge hover doesn't block face hover - only edge SELECTION blocks face selection.
     */
    private void updateFaceHover(InputContext context) {
        if (faceRenderer == null || !faceRenderer.isInitialized() || !faceRenderer.isEnabled()) {
            return;
        }

        // PRIORITY CHECK: Only update face hover if vertex is NOT hovered
        int hoveredVertex = (vertexRenderer != null) ? vertexRenderer.getHoveredVertexIndex() : -1;

        if (hoveredVertex >= 0) {
            // Vertex is hovered - clear face hover (vertex has highest priority)
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
