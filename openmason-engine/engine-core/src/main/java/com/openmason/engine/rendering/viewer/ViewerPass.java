package com.openmason.engine.rendering.viewer;

/**
 * One step of a {@link ModelViewer}'s frame.
 *
 * <p>Passes let a host layer its own rendering on top of the shared viewer core without
 * the engine knowing anything about it: the model editor contributes its vertex/edge/face,
 * socket-preview and rigging overlays as passes, while a scene viewport contributes only
 * the grid and its model instances.
 *
 * <p>The viewer runs passes in ascending {@link #order()}. Use the constants in
 * {@link ViewerPassOrder} rather than bare numbers so the relative sequence stays legible.
 */
public interface ViewerPass {

    /** Sort key; lower runs first. See {@link ViewerPassOrder}. */
    int order();

    /** Short name, used in log messages when a pass fails. */
    String name();

    /** Skip this pass for the current frame. Checked every frame. */
    default boolean isEnabled() {
        return true;
    }

    /** Draw. The framebuffer is already bound and cleared, and GL state is configured. */
    void render(ViewerFrame frame);

    /** Release any GL resources this pass owns. Called when the viewer closes. */
    default void cleanup() {
        // no-op by default
    }
}
