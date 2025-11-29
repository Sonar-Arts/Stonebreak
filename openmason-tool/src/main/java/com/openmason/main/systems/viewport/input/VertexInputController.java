package com.openmason.main.systems.viewport.input;

import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.viewport.viewportRendering.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexTranslationHandler;
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
 * - Handle vertex translation (drag to move)
 * - Handle ESC key (cancel drag or clear selection)
 * - Delegates translation to VertexTranslationHandler
 *
 * Design:
 * - Single Responsibility: Vertex interaction only
 * - Delegation: VertexRenderer for hover, VertexTranslationHandler for drag
 * - Priority: Highest (most precise editing, blocks all other input)
 * - Returns true if input was handled (vertex selected, dragging, etc.)
 */
public class VertexInputController {

    private static final Logger logger = LoggerFactory.getLogger(VertexInputController.class);

    private VertexRenderer vertexRenderer = null;
    private VertexSelectionState vertexSelectionState = null;
    private VertexTranslationHandler vertexTranslationHandler = null;
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
     * Set the vertex translation handler for drag operations.
     */
    public void setVertexTranslationHandler(VertexTranslationHandler vertexTranslationHandler) {
        this.vertexTranslationHandler = vertexTranslationHandler;
        logger.debug("Vertex translation handler set in VertexInputController");
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

        // Update translation handler camera if available
        if (vertexTranslationHandler != null) {
            vertexTranslationHandler.updateCamera(
                    context.viewMatrix,
                    context.projectionMatrix,
                    context.viewportWidth,
                    context.viewportHeight
            );
        }

        // Handle ESC key to cancel drag or deselect vertex
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            if (vertexTranslationHandler != null && vertexTranslationHandler.isDragging()) {
                // Cancel active drag
                vertexTranslationHandler.cancelDrag();
                logger.debug("Vertex drag cancelled (ESC key pressed)");
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
        if (vertexTranslationHandler != null && vertexTranslationHandler.isDragging()) {
            // Continue dragging
            vertexTranslationHandler.handleMouseMove(context.mouseX, context.mouseY);

            // End drag on mouse release
            if (context.mouseReleased) {
                vertexTranslationHandler.handleMouseRelease(context.mouseX, context.mouseY);
                logger.debug("Vertex drag ended");
            }

            return true; // Block lower-priority controllers while dragging
        }

        // Start drag on selected vertex
        if (context.mouseInBounds && context.mouseClicked && vertexSelectionState.hasSelection()) {
            int selectedVertex = vertexRenderer.getSelectedVertexIndex();
            int hoveredVertex = vertexRenderer.getHoveredVertexIndex();

            // Check if clicking on the selected vertex
            if (selectedVertex >= 0 && selectedVertex == hoveredVertex) {
                // Start dragging the selected vertex
                if (vertexTranslationHandler != null &&
                    vertexTranslationHandler.handleMousePress(context.mouseX, context.mouseY)) {
                    logger.debug("Started dragging vertex {}", selectedVertex);
                    return true;
                }
            }
        }

        // Handle mouse click for vertex selection (if not dragging)
        if (context.mouseInBounds && context.mouseClicked) {
            int hoveredVertex = vertexRenderer.getHoveredVertexIndex();

            if (hoveredVertex >= 0) {
                // Clicking on a hovered vertex - select it
                Vector3f vertexPosition = vertexRenderer.getVertexPosition(hoveredVertex);
                if (vertexPosition != null) {
                    vertexSelectionState.selectVertex(hoveredVertex, vertexPosition);
                    vertexRenderer.setSelectedVertex(hoveredVertex);
                    logger.debug("Vertex {} selected at position ({}, {}, {})",
                            hoveredVertex,
                            String.format("%.2f", vertexPosition.x),
                            String.format("%.2f", vertexPosition.y),
                            String.format("%.2f", vertexPosition.z));
                    return true; // Block lower-priority controllers
                }
            } else {
                // Clicking on empty space - deselect if something was selected
                if (vertexSelectionState.hasSelection()) {
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
