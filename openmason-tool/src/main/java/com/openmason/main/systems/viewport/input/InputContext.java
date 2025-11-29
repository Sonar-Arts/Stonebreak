package com.openmason.main.systems.viewport.input;

import imgui.ImVec2;
import org.joml.Matrix4f;

/**
 * Immutable data transfer object containing all input state for a single frame.
 * Passed to sub-controllers to reduce parameter count and group related data.
 *
 * Design Benefits:
 * - Reduces method signatures from 4+ parameters to 1
 * - Groups related data logically
 * - Easier to extend without changing all method signatures
 * - Immutable = thread-safe by default
 * - Common pattern in event-driven systems
 */
public class InputContext {

    // Mouse position (viewport-relative coordinates)
    public final float mouseX;
    public final float mouseY;

    // Mouse state
    public final boolean mouseInBounds;
    public final boolean viewportHovered;
    public final boolean mouseClicked;
    public final boolean mouseDown;
    public final boolean mouseReleased;
    public final float mouseWheel;
    public final ImVec2 mouseDelta;

    // Viewport dimensions
    public final int viewportWidth;
    public final int viewportHeight;

    // Camera matrices (for raycasting and transformations)
    public final Matrix4f viewMatrix;
    public final Matrix4f projectionMatrix;

    /**
     * Construct an immutable InputContext with all required state.
     *
     * @param mouseX Viewport-relative mouse X coordinate
     * @param mouseY Viewport-relative mouse Y coordinate
     * @param mouseInBounds Whether mouse is within viewport bounds
     * @param viewportHovered Whether viewport window itself is hovered (not overlaying windows)
     * @param mouseClicked Whether left mouse button was clicked this frame
     * @param mouseDown Whether left mouse button is currently held down
     * @param mouseReleased Whether left mouse button was released this frame
     * @param mouseWheel Mouse wheel delta this frame
     * @param mouseDelta Mouse movement delta this frame
     * @param viewportWidth Width of viewport in pixels
     * @param viewportHeight Height of viewport in pixels
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     */
    public InputContext(
            float mouseX,
            float mouseY,
            boolean mouseInBounds,
            boolean viewportHovered,
            boolean mouseClicked,
            boolean mouseDown,
            boolean mouseReleased,
            float mouseWheel,
            ImVec2 mouseDelta,
            int viewportWidth,
            int viewportHeight,
            Matrix4f viewMatrix,
            Matrix4f projectionMatrix
    ) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.mouseInBounds = mouseInBounds;
        this.viewportHovered = viewportHovered;
        this.mouseClicked = mouseClicked;
        this.mouseDown = mouseDown;
        this.mouseReleased = mouseReleased;
        this.mouseWheel = mouseWheel;
        this.mouseDelta = mouseDelta;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.viewMatrix = viewMatrix;
        this.projectionMatrix = projectionMatrix;
    }
}
