package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.main.systems.viewport.viewportRendering.edge.EdgeTranslationHandler;
import com.openmason.main.systems.viewport.viewportRendering.face.FaceTranslationHandler;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexTranslationHandler;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates translation operations between vertex, edge, and face handlers.
 * Ensures mutual exclusion - only one handler can be active at a time.
 * Prevents conflicts and maintains consistency across translation operations.
 *
 * <p>Design Principles:</p>
 * <ul>
 *   <li><b>Single Responsibility</b>: Coordinates translation state across handlers</li>
 *   <li><b>Open/Closed</b>: Extensible for new handler types without modification</li>
 *   <li><b>Dependency Inversion</b>: Depends on handler abstractions, not concrete implementations</li>
 * </ul>
 *
 * <p>State Management:</p>
 * <ul>
 *   <li>Only one handler can be dragging at a time (mutual exclusion)</li>
 *   <li>Automatic cancellation of other handlers when one starts dragging</li>
 *   <li>Unified camera and viewport state updates</li>
 * </ul>
 */
public class TranslationCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(TranslationCoordinator.class);

    private final VertexTranslationHandler vertexHandler;
    private final EdgeTranslationHandler edgeHandler;
    private final FaceTranslationHandler faceHandler;

    // Track which handler is currently active
    private ActiveHandler activeHandler = ActiveHandler.NONE;

    /**
     * Enum representing which handler is currently active.
     */
    private enum ActiveHandler {
        NONE,
        VERTEX,
        EDGE,
        FACE
    }

    /**
     * Creates a new TranslationCoordinator.
     *
     * @param vertexHandler The vertex translation handler
     * @param edgeHandler The edge translation handler
     * @param faceHandler The face translation handler
     */
    public TranslationCoordinator(VertexTranslationHandler vertexHandler,
                                   EdgeTranslationHandler edgeHandler,
                                   FaceTranslationHandler faceHandler) {
        if (vertexHandler == null) {
            throw new IllegalArgumentException("VertexTranslationHandler cannot be null");
        }
        if (edgeHandler == null) {
            throw new IllegalArgumentException("EdgeTranslationHandler cannot be null");
        }
        if (faceHandler == null) {
            throw new IllegalArgumentException("FaceTranslationHandler cannot be null");
        }

        this.vertexHandler = vertexHandler;
        this.edgeHandler = edgeHandler;
        this.faceHandler = faceHandler;
    }

    /**
     * Updates camera matrices for all handlers.
     * Should be called each frame before processing input.
     *
     * @param view View matrix
     * @param projection Projection matrix
     * @param width Viewport width
     * @param height Viewport height
     */
    public void updateCamera(Matrix4f view, Matrix4f projection, int width, int height) {
        vertexHandler.updateCamera(view, projection, width, height);
        edgeHandler.updateCamera(view, projection, width, height);
        faceHandler.updateCamera(view, projection, width, height);
    }

    /**
     * Handles mouse press event, delegating to the appropriate handler.
     * Ensures mutual exclusion by cancelling other handlers if one starts dragging.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @return true if any handler started dragging, false otherwise
     */
    public boolean handleMousePress(float mouseX, float mouseY) {
        // If already dragging, don't allow starting another drag
        if (activeHandler != ActiveHandler.NONE) {
            logger.trace("Mouse press ignored: {} handler is already active", activeHandler);
            return false;
        }

        // Try vertex handler first (most specific)
        if (vertexHandler.handleMousePress(mouseX, mouseY)) {
            activeHandler = ActiveHandler.VERTEX;
            cancelOtherHandlers(ActiveHandler.VERTEX);
            logger.debug("Vertex translation started");
            return true;
        }

        // Try edge handler second
        if (edgeHandler.handleMousePress(mouseX, mouseY)) {
            activeHandler = ActiveHandler.EDGE;
            cancelOtherHandlers(ActiveHandler.EDGE);
            logger.debug("Edge translation started");
            return true;
        }

        // Try face handler last (least specific)
        if (faceHandler.handleMousePress(mouseX, mouseY)) {
            activeHandler = ActiveHandler.FACE;
            cancelOtherHandlers(ActiveHandler.FACE);
            logger.debug("Face translation started");
            return true;
        }

        return false;
    }

    /**
     * Handles mouse move event, delegating to the active handler only.
     *
     * @param mouseX Current mouse X position
     * @param mouseY Current mouse Y position
     */
    public void handleMouseMove(float mouseX, float mouseY) {
        switch (activeHandler) {
            case VERTEX:
                vertexHandler.handleMouseMove(mouseX, mouseY);
                break;
            case EDGE:
                edgeHandler.handleMouseMove(mouseX, mouseY);
                break;
            case FACE:
                faceHandler.handleMouseMove(mouseX, mouseY);
                break;
            case NONE:
                // No active handler, nothing to do
                break;
        }
    }

    /**
     * Handles mouse release event, delegating to the active handler.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     */
    public void handleMouseRelease(float mouseX, float mouseY) {
        switch (activeHandler) {
            case VERTEX:
                vertexHandler.handleMouseRelease(mouseX, mouseY);
                break;
            case EDGE:
                edgeHandler.handleMouseRelease(mouseX, mouseY);
                break;
            case FACE:
                faceHandler.handleMouseRelease(mouseX, mouseY);
                break;
            case NONE:
                // No active handler, nothing to do
                break;
        }

        // Clear active handler after release
        activeHandler = ActiveHandler.NONE;
        logger.trace("Translation ended, coordinator reset");
    }

    /**
     * Cancels the current drag operation across all handlers.
     * Useful for keyboard shortcuts (ESC key) or error recovery.
     */
    public void cancelDrag() {
        if (activeHandler == ActiveHandler.NONE) {
            return;
        }

        logger.debug("Cancelling active {} translation", activeHandler);

        switch (activeHandler) {
            case VERTEX:
                vertexHandler.cancelDrag();
                break;
            case EDGE:
                edgeHandler.cancelDrag();
                break;
            case FACE:
                faceHandler.cancelDrag();
                break;
            case NONE:
                break;
        }

        activeHandler = ActiveHandler.NONE;
    }

    /**
     * Checks if any translation handler is currently dragging.
     *
     * @return true if any handler is dragging, false otherwise
     */
    public boolean isDragging() {
        return activeHandler != ActiveHandler.NONE;
    }

    /**
     * Gets the currently active handler type.
     *
     * @return The active handler type
     */
    public String getActiveHandlerType() {
        return activeHandler.name();
    }

    /**
     * Cancels all handlers except the specified one.
     * Ensures mutual exclusion between handlers.
     *
     * @param except The handler to keep active
     */
    private void cancelOtherHandlers(ActiveHandler except) {
        if (except != ActiveHandler.VERTEX && vertexHandler.isDragging()) {
            vertexHandler.cancelDrag();
            logger.trace("Cancelled vertex handler (switching to {})", except);
        }
        if (except != ActiveHandler.EDGE && edgeHandler.isDragging()) {
            edgeHandler.cancelDrag();
            logger.trace("Cancelled edge handler (switching to {})", except);
        }
        if (except != ActiveHandler.FACE && faceHandler.isDragging()) {
            faceHandler.cancelDrag();
            logger.trace("Cancelled face handler (switching to {})", except);
        }
    }

    /**
     * Gets the vertex translation handler.
     * Useful for direct access when needed.
     *
     * @return The vertex translation handler
     */
    public VertexTranslationHandler getVertexHandler() {
        return vertexHandler;
    }

    /**
     * Gets the edge translation handler.
     * Useful for direct access when needed.
     *
     * @return The edge translation handler
     */
    public EdgeTranslationHandler getEdgeHandler() {
        return edgeHandler;
    }

    /**
     * Gets the face translation handler.
     * Useful for direct access when needed.
     *
     * @return The face translation handler
     */
    public FaceTranslationHandler getFaceHandler() {
        return faceHandler;
    }
}
