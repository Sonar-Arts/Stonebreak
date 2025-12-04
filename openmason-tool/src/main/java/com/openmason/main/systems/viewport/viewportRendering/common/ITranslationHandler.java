package com.openmason.main.systems.viewport.viewportRendering.common;

import org.joml.Matrix4f;

/**
 * Common interface for geometry translation handlers.
 * Handles plane-constrained dragging using Blender-style movement.
 *
 * Implementations include VertexTranslationHandler and EdgeTranslationHandler.
 * Follows SOLID principles with Interface Segregation pattern.
 */
public interface ITranslationHandler {

    /**
     * Updates the camera matrices used for raycasting.
     * Should be called each frame before processing input.
     *
     * @param view View matrix
     * @param projection Projection matrix
     * @param width Viewport width
     * @param height Viewport height
     */
    void updateCamera(Matrix4f view, Matrix4f projection, int width, int height);

    /**
     * Handles mouse press to start drag operation.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @return true if drag was started, false otherwise
     */
    boolean handleMousePress(float mouseX, float mouseY);

    /**
     * Handles mouse movement during drag.
     *
     * @param mouseX Current mouse X position
     * @param mouseY Current mouse Y position
     */
    void handleMouseMove(float mouseX, float mouseY);

    /**
     * Handles mouse release to end drag.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     */
    void handleMouseRelease(float mouseX, float mouseY);

    /**
     * Cancels the current drag operation, reverting to original position.
     */
    void cancelDrag();

    /**
     * Check if currently dragging.
     *
     * @return true if dragging, false otherwise
     */
    boolean isDragging();
}
