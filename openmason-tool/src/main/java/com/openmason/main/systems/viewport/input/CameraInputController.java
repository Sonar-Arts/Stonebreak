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
 * - Camera rotation via left mouse button dragging (with mouse capture)
 * - Camera panning via middle mouse button dragging (with mouse capture)
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
    private boolean isPanning = false;

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

        // Start panning when middle mouse button is pressed within viewport bounds
        if (context.mouseInBounds && context.middleMouseClicked && context.viewportHovered) {
            startPanning();
        }

        // Continue dragging with unlimited mouse movement (mouse is now captured)
        if (isDragging && context.mouseDown) {
            processDragging(context);
        }

        // Continue panning with unlimited mouse movement (mouse is now captured)
        if (isPanning && context.middleMouseDown) {
            processPanning(context);
        }

        // Stop dragging when mouse button is released
        if (context.mouseReleased && isDragging) {
            stopDragging();
        }

        // Stop panning when middle mouse button is released
        if (context.middleMouseReleased && isPanning) {
            stopPanning();
        }

        // Handle mouse wheel for zooming (only when hovering over viewport or actively dragging/panning)
        // Check viewportHovered to prevent scroll bleed-through from overlaying windows
        if ((context.mouseInBounds && context.viewportHovered) || isDragging || isPanning) {
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
     * Start a drag operation (orbit rotation).
     */
    private void startDragging() {
        isDragging = true;
        mouseCaptureManager.captureMouse();
    }

    /**
     * Start a pan operation (middle mouse button).
     */
    private void startPanning() {
        isPanning = true;
        mouseCaptureManager.captureMouse();
    }

    /**
     * Process mouse dragging for camera rotation.
     */
    private void processDragging(InputContext context) {
        if (Math.abs(context.mouseDelta.x) > 0.0001f || Math.abs(context.mouseDelta.y) > 0.0001f) {
            logger.trace("Processing mouse delta: ({}, {})", context.mouseDelta.x, context.mouseDelta.y);

            float rotationSpeed = mouseCaptureManager.isMouseCaptured() ? 0.003f : 0.1f;
            viewportCamera.rotate(-context.mouseDelta.x * rotationSpeed, -context.mouseDelta.y * rotationSpeed);
            logger.trace("Camera rotated by delta: ({}, {}) with speed: {}",
                    -context.mouseDelta.x * rotationSpeed, -context.mouseDelta.y * rotationSpeed, rotationSpeed);
        }
    }

    /**
     * Process mouse dragging for camera panning.
     */
    private void processPanning(InputContext context) {
        if (Math.abs(context.mouseDelta.x) > 0.0001f || Math.abs(context.mouseDelta.y) > 0.0001f) {
            logger.trace("Processing pan delta: ({}, {})", context.mouseDelta.x, context.mouseDelta.y);
            viewportCamera.pan(context.mouseDelta.x, context.mouseDelta.y);
        }
    }

    /**
     * Stop the current drag operation (orbit rotation).
     */
    public void stopDragging() {
        isDragging = false;
        mouseCaptureManager.releaseMouse();
    }

    /**
     * Stop the current pan operation.
     */
    private void stopPanning() {
        isPanning = false;
        mouseCaptureManager.releaseMouse();
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
        if (isPanning) {
            stopPanning();
        }
    }

    // Getters

    /**
     * @return true if the camera is actively being manipulated (orbit drag or pan drag)
     */
    public boolean isDragging() {
        return isDragging || isPanning;
    }
}
