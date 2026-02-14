package com.openmason.main.systems.viewport.input;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.TranslationCoordinator;
import imgui.ImGui;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Set;

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
    private GenericModelRenderer modelRenderer = null;

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
     * Set the generic model renderer for edge insertion operations.
     */
    public void setModelRenderer(GenericModelRenderer modelRenderer) {
        this.modelRenderer = modelRenderer;
        logger.debug("Model renderer set in VertexInputController");
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

        // Handle J key to insert edge between 2 selected vertices (face split)
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_J)) {
            if (vertexSelectionState.getSelectionCount() == 2 && modelRenderer != null) {
                Set<Integer> selected = vertexSelectionState.getSelectedVertexIndices();
                Iterator<Integer> it = selected.iterator();
                int vertA = it.next();
                int vertB = it.next();

                boolean success = modelRenderer.insertEdgeBetweenVertices(vertA, vertB);
                if (success) {
                    vertexSelectionState.clearSelection();
                    vertexRenderer.clearSelection();
                    logger.info("Edge inserted between vertices {} and {} (J key)", vertA, vertB);
                } else {
                    logger.warn("Edge insertion failed between vertices {} and {}", vertA, vertB);
                }
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

        // NOTE: Click-to-drag removed. Use G key for grab mode (Blender-style).

        // Handle mouse click for vertex selection (if not dragging)
        if (context.mouseInBounds && context.mouseClicked) {
            int hoveredVertex = vertexRenderer.getHoveredVertexIndex();

            if (hoveredVertex >= 0) {
                // Clicking on a hovered vertex
                Vector3f vertexPosition = vertexRenderer.getVertexPosition(hoveredVertex);
                if (vertexPosition != null) {
                    if (vertexSelectionState.isSelected(hoveredVertex)) {
                        // Click on selected → unselect it
                        vertexSelectionState.toggleVertex(hoveredVertex, vertexPosition);
                        vertexRenderer.updateSelectionSet(vertexSelectionState.getSelectedVertexIndices());
                        logger.debug("Vertex {} unselected (now {} selected)",
                                hoveredVertex, vertexSelectionState.getSelectionCount());
                    } else {
                        // Click on unselected → add to selection (multi-select)
                        vertexSelectionState.toggleVertex(hoveredVertex, vertexPosition);
                        vertexRenderer.updateSelectionSet(vertexSelectionState.getSelectedVertexIndices());
                        logger.debug("Vertex {} added to selection (now {} selected)",
                                hoveredVertex, vertexSelectionState.getSelectionCount());
                    }
                    return true; // Block lower-priority controllers
                }
            }
            // Clicking on empty space → do nothing (use ESC to clear selection)
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
