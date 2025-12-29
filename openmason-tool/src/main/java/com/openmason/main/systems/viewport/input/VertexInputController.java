package com.openmason.main.systems.viewport.input;

import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.TranslationCoordinator;
import imgui.ImGui;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles all vertex selection and manipulation input for the viewport.
 *
 * Responsibilities:
 * - Update vertex hover detection via raycasting
 * - Handle vertex selection (click to select)
 * - Handle vertex translation (drag to move via TranslationCoordinator)
 * - Handle ESC key (cancel drag or clear selection)
 * - Delegates translation to TranslationCoordinator for mutual exclusion
 *
 * Design:
 * - Single Responsibility: Vertex interaction only
 * - Delegation: VertexRenderer for hover, TranslationCoordinator for drag
 * - Priority: Highest (most precise editing, blocks all other input)
 * - Returns true if input was handled (vertex selected, dragging, etc.)
 */
public class VertexInputController {

    private static final Logger logger = LoggerFactory.getLogger(VertexInputController.class);

    private VertexRenderer vertexRenderer = null;
    private VertexSelectionState vertexSelectionState = null;
    private TranslationCoordinator translationCoordinator = null;
    private com.openmason.main.systems.viewport.state.TransformState transformState = null;

    /**
     * Set the vertex renderer for hover detection.
     */
    public void setVertexRenderer(VertexRenderer vertexRenderer) {
        this.vertexRenderer = vertexRenderer;
        logger.debug("Vertex renderer set in VertexInputController");
    }

    /**
     * Set the vertex selection state for selection management.
     */
    public void setVertexSelectionState(VertexSelectionState vertexSelectionState) {
        this.vertexSelectionState = vertexSelectionState;
        logger.debug("Vertex selection state set in VertexInputController");
    }

    /**
     * Set the translation coordinator for drag operations.
     * The coordinator ensures mutual exclusion between vertex/edge/face translation.
     */
    public void setTranslationCoordinator(TranslationCoordinator translationCoordinator) {
        this.translationCoordinator = translationCoordinator;
        logger.debug("Translation coordinator set in VertexInputController");
    }

    /**
     * Set the transform state for model matrix access.
     */
    public void setTransformState(com.openmason.main.systems.viewport.state.TransformState transformState) {
        this.transformState = transformState;
        logger.debug("Transform state set in VertexInputController");
    }

    /**
     * Handle vertex input.
     *
     * @param context Input context with mouse state
     * @return True if vertex input was handled (blocks lower-priority controllers)
     */
    public boolean handleInput(InputContext context) {
        if (vertexRenderer == null || !vertexRenderer.isInitialized() || !vertexRenderer.isEnabled() ||
            vertexSelectionState == null) {
            return false; // Vertex system not available
        }

        // Update vertex hover detection
        updateVertexHover(context);

        // Handle ESC key to cancel drag or deselect vertex
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            if (translationCoordinator != null && translationCoordinator.isDragging()) {
                // Cancel active drag (coordinator handles all translation types)
                translationCoordinator.cancelDrag();
                logger.debug("Translation drag cancelled (ESC key pressed)");
                return true;
            } else if (vertexSelectionState.hasSelection()) {
                // Clear selection
                vertexSelectionState.clearSelection();
                vertexRenderer.clearSelection();
                logger.debug("Vertex selection cleared (ESC key pressed)");
                return true;
            }
        }

        // Handle vertex translation (dragging)
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

        // Start drag on selected vertex
        if (context.mouseInBounds && context.mouseClicked && vertexSelectionState.hasSelection()) {
            int selectedVertex = vertexRenderer.getSelectedVertexIndex();
            int hoveredVertex = vertexRenderer.getHoveredVertexIndex();

            // Check if clicking on the selected vertex
            if (selectedVertex >= 0 && selectedVertex == hoveredVertex) {
                // Start dragging via coordinator (ensures mutual exclusion)
                if (translationCoordinator != null &&
                    translationCoordinator.handleMousePress(context.mouseX, context.mouseY)) {
                    logger.debug("Started translation drag on vertex {}", selectedVertex);
                    return true;
                }
            }
        }

        // Handle mouse click for vertex selection (if not dragging)
        if (context.mouseInBounds && context.mouseClicked) {
            int hoveredVertex = vertexRenderer.getHoveredVertexIndex();

            if (hoveredVertex >= 0) {
                // Clicking on a hovered vertex
                Vector3f vertexPosition = vertexRenderer.getVertexPosition(hoveredVertex);
                if (vertexPosition != null) {
                    if (context.shiftDown) {
                        // Shift+click: Toggle this vertex in selection
                        vertexSelectionState.toggleVertex(hoveredVertex, vertexPosition);
                        vertexRenderer.updateSelectionSet(vertexSelectionState.getSelectedVertexIndices());
                        logger.debug("Vertex {} toggled in selection (now {} selected)",
                                hoveredVertex, vertexSelectionState.getSelectionCount());
                    } else {
                        // Normal click: Replace selection with this vertex
                        vertexSelectionState.selectVertex(hoveredVertex, vertexPosition);
                        vertexRenderer.setSelectedVertex(hoveredVertex);
                        logger.debug("Vertex {} selected at position ({}, {}, {})",
                                hoveredVertex,
                                String.format("%.2f", vertexPosition.x),
                                String.format("%.2f", vertexPosition.y),
                                String.format("%.2f", vertexPosition.z));
                    }
                    return true; // Block lower-priority controllers
                }
            } else {
                // Clicking on empty space - only clear if NOT holding Shift
                if (!context.shiftDown && vertexSelectionState.hasSelection()) {
                    vertexSelectionState.clearSelection();
                    vertexRenderer.clearSelection();
                    logger.debug("Vertex selection cleared (clicked on empty space)");
                    return true; // Block lower-priority controllers
                }
            }
        }

        return false; // No vertex input handled
    }

    /**
     * Update vertex hover detection.
     */
    private void updateVertexHover(InputContext context) {
        if (vertexRenderer == null || !vertexRenderer.isInitialized() || !vertexRenderer.isEnabled()) {
            return;
        }

        // Get model matrix for proper vertex transformation
        Matrix4f modelMatrix = (transformState != null)
                ? transformState.getTransformMatrix()
                : new Matrix4f(); // Identity if no transform state

        // Update vertex hover with camera matrices AND model matrix for raycasting
        vertexRenderer.handleMouseMove(
                context.mouseX,
                context.mouseY,
                context.viewMatrix,
                context.projectionMatrix,
                modelMatrix,
                context.viewportWidth,
                context.viewportHeight
        );
    }
}
