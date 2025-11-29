package com.openmason.main.systems.viewport.input;

import com.openmason.main.systems.viewport.ViewportCamera;
import imgui.ImGui;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles all camera input for the viewport.
 *
 * Responsibilities:
 * - Camera rotation via mouse dragging (with mouse capture)
 * - Camera zooming via mouse wheel
 * - Keyboard movement (WASD, Space, Ctrl) for first-person mode
 * - Delegates camera operations to ViewportCamera
 * - Uses MouseCaptureManager for endless dragging
 *
 * Design:
 * - Single Responsibility: Camera input only
 * - Delegation: ViewportCamera for camera operations, MouseCaptureManager for cursor
 * - Returns false always (camera never blocks input, lowest priority)
 */
public class CameraInputController {

    private static final Logger logger = LoggerFactory.getLogger(CameraInputController.class);

    private final ViewportCamera viewportCamera;
    private final MouseCaptureManager mouseCaptureManager;

    private boolean isDragging = false;

    public CameraInputController(ViewportCamera viewportCamera, MouseCaptureManager mouseCaptureManager) {
        this.viewportCamera = viewportCamera;
        this.mouseCaptureManager = mouseCaptureManager;
    }

    /**
     * Handle camera input for rotation and zooming.
     *
     * @param context Input context with mouse state
     * @return Always false (camera never blocks input)
     */
    public boolean handleInput(InputContext context) {
        if (viewportCamera == null) {
            logger.warn("Camera is null - cannot handle input");
            return false;
        }

        // Start dragging when left mouse button is pressed within viewport bounds
        // AND the viewport window itself is being hovered (not overlaying windows)
        if (context.mouseInBounds && context.mouseClicked && context.viewportHovered) {
            startDragging();
        }

        // Continue dragging with unlimited mouse movement (mouse is now captured)
        if (isDragging && context.mouseDown) {
            processDragging(context);
        }

        // Stop dragging when mouse button is released
        if (context.mouseReleased && isDragging) {
            stopDragging();
        }

        // Handle mouse wheel for zooming (only when hovering over viewport or actively dragging)
        // Check viewportHovered to prevent scroll bleed-through from overlaying windows
        if ((context.mouseInBounds && context.viewportHovered) || isDragging) {
            processZooming(context);
        }

        return false; // Camera never blocks input (lowest priority)
    }

    /**
     * Handle keyboard input for first-person camera movement.
     *
     * @param deltaTime Time delta for frame-rate independent movement
     */
    public void handleKeyboardInput(float deltaTime) {
        if (viewportCamera == null || viewportCamera.getCameraMode() != ViewportCamera.CameraMode.FIRST_PERSON) {
            return;
        }

        // First-person movement controls
        boolean moved = false;

        if (ImGui.isKeyDown(GLFW.GLFW_KEY_W)) {
            viewportCamera.moveForward(deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_S)) {
            viewportCamera.moveForward(-deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_A)) {
            viewportCamera.moveRight(-deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_D)) {
            viewportCamera.moveRight(deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_SPACE)) {
            viewportCamera.moveUp(deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL) || ImGui.isKeyDown(GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            viewportCamera.moveUp(-deltaTime);
            moved = true;
        }

        if (moved) {
            logger.trace("First-person camera movement processed");
        }
    }

    /**
     * Start a drag operation.
     */
    private void startDragging() {
        isDragging = true;
        mouseCaptureManager.captureMouse(); // Enable endless dragging with mouse capture
    }

    /**
     * Process mouse dragging for camera rotation.
     */
    private void processDragging(InputContext context) {
        // Process any mouse movement - even tiny amounts for smooth rotation
        if (Math.abs(context.mouseDelta.x) > 0.0001f || Math.abs(context.mouseDelta.y) > 0.0001f) {
            logger.trace("Processing mouse delta: ({}, {})", context.mouseDelta.x, context.mouseDelta.y);

            // Apply rotation with appropriate sensitivity for captured mouse
            float rotationSpeed = mouseCaptureManager.isMouseCaptured() ? 0.003f : 0.1f;
            viewportCamera.rotate(-context.mouseDelta.x * rotationSpeed, -context.mouseDelta.y * rotationSpeed);
            logger.trace("Camera rotated by delta: ({}, {}) with speed: {}",
                    -context.mouseDelta.x * rotationSpeed, -context.mouseDelta.y * rotationSpeed, rotationSpeed);
        }
    }

    /**
     * Stop the current drag operation.
     */
    public void stopDragging() {
        isDragging = false;
        mouseCaptureManager.releaseMouse(); // Restore cursor visibility and position
    }

    /**
     * Process mouse wheel for zooming.
     */
    private void processZooming(InputContext context) {
        if (context.mouseWheel != 0) {
            viewportCamera.zoom(context.mouseWheel * 0.5f);
        }
    }

    /**
     * Reset camera input state.
     */
    public void reset() {
        if (isDragging) {
            stopDragging();
        }
    }

    // Getters

    public boolean isDragging() {
        return isDragging;
    }
}
