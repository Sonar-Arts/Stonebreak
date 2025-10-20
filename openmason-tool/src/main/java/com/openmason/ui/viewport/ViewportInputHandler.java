package com.openmason.ui.viewport;

import com.openmason.ui.viewport.gizmo.GizmoRenderer;
import imgui.ImGui;
import imgui.ImVec2;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.DoubleBuffer;

/**
 * Handles all input processing for the OpenMason 3D viewport.
 * Separates input concerns from viewport rendering for better architecture.
 *
 * Features:
 * - Mouse capture for endless dragging
 * - Camera rotation and zooming
 * - Gizmo interaction (transform manipulation)
 * - Professional mouse sensitivity settings
 * - Raw mouse motion support
 * - Clean separation of input and rendering concerns
 */
public class ViewportInputHandler {

    private static final Logger logger = LoggerFactory.getLogger(ViewportInputHandler.class);

    // Camera reference for input handling
    private final Camera camera;

    // Gizmo renderer for transform interaction
    private GizmoRenderer gizmoRenderer = null;

    // Mouse interaction state
    private boolean isDragging = false;
    
    // Mouse capture state for endless dragging
    private boolean isMouseCaptured = false;
    private double savedCursorX = 0.0;
    private double savedCursorY = 0.0;
    private long windowHandle = 0L; // GLFW window handle
    private boolean rawMouseMotionSupported = false;
    
    public ViewportInputHandler(Camera camera) {
        this.camera = camera;
        // logger.info("ViewportInputHandler initialized");
    }

    /**
     * Set the GLFW window handle for mouse capture functionality.
     * This should be called from the main application.
     */
    public void setWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
        if (windowHandle != 0L) {
            // Check if raw mouse motion is supported
            this.rawMouseMotionSupported = GLFW.glfwRawMouseMotionSupported();
            // logger.info("Window handle set. Raw mouse motion supported: {}", rawMouseMotionSupported);
        }
    }

    /**
     * Set the gizmo renderer for transform interaction.
     * This should be called after viewport initialization.
     */
    public void setGizmoRenderer(GizmoRenderer gizmoRenderer) {
        this.gizmoRenderer = gizmoRenderer;
        logger.debug("Gizmo renderer set in ViewportInputHandler");
    }
    
    /**
     * Capture the mouse cursor for endless dragging.
     * Hides cursor and enables unlimited mouse movement.
     */
    private void captureMouse() {
        if (windowHandle == 0L || isMouseCaptured) {
            return;
        }
        
        try {
            // Save current cursor position
            DoubleBuffer xPos = BufferUtils.createDoubleBuffer(1);
            DoubleBuffer yPos = BufferUtils.createDoubleBuffer(1);
            GLFW.glfwGetCursorPos(windowHandle, xPos, yPos);
            savedCursorX = xPos.get(0);
            savedCursorY = yPos.get(0);
            
            // Disable cursor (hides it and enables infinite movement)
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            
            // Enable raw mouse motion if supported for better camera control
            if (rawMouseMotionSupported) {
                GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
            }
            
            isMouseCaptured = true;
            // logger.info("Mouse captured for endless dragging at position: ({}, {})", savedCursorX, savedCursorY);
            
        } catch (Exception e) {
            logger.error("Failed to capture mouse", e);
        }
    }
    
    /**
     * Release mouse capture and restore cursor visibility and position.
     */
    private void releaseMouse() {
        if (windowHandle == 0L || !isMouseCaptured) {
            return;
        }
        
        try {
            // Restore normal cursor mode
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            
            // Disable raw mouse motion
            if (rawMouseMotionSupported) {
                GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_FALSE);
            }
            
            // Restore cursor position
            GLFW.glfwSetCursorPos(windowHandle, savedCursorX, savedCursorY);
            
            isMouseCaptured = false;
            // logger.info("Mouse released and cursor restored to position: ({}, {})", savedCursorX, savedCursorY);
            
        } catch (Exception e) {
            logger.error("Failed to release mouse", e);
        }
    }
    
    /**
     * Handle input for camera controls with endless dragging and mouse capture.
     * This is the main entry point for input processing.
     */
    public void handleInput(ImVec2 imagePos, float imageWidth, float imageHeight) {
        if (camera == null) {
            logger.warn("Camera is null - cannot handle input");
            return;
        }

        // Get mouse state
        ImVec2 mousePos = ImGui.getIO().getMousePos();
        boolean wantCapture = ImGui.getIO().getWantCaptureMouse();

        // Calculate viewport-relative mouse coordinates
        float viewportMouseX = mousePos.x - imagePos.x;
        float viewportMouseY = mousePos.y - imagePos.y;

        // Check if mouse is within viewport bounds
        boolean mouseInBounds = !isMouseCaptured && (mousePos.x >= imagePos.x &&
                               mousePos.x < imagePos.x + imageWidth &&
                               mousePos.y >= imagePos.y &&
                               mousePos.y < imagePos.y + imageHeight);

        // Only respect WantCaptureMouse if mouse is NOT in viewport bounds or we're not actively dragging
        if (wantCapture && !mouseInBounds && !isDragging) {
            if (isDragging) {
                stopDragging();
            }
            return;
        }

        // Handle gizmo input first (if enabled and initialized)
        boolean gizmoHandledInput = false;
        if (gizmoRenderer != null && gizmoRenderer.isInitialized() &&
            gizmoRenderer.getGizmoState().isEnabled()) {

            // Update gizmo with camera matrices for raycasting
            gizmoRenderer.handleMouseMove(
                viewportMouseX,
                viewportMouseY,
                camera.getViewMatrix(),
                camera.getProjectionMatrix(),
                (int) imageWidth,
                (int) imageHeight
            );

            // Check for mouse press on gizmo
            if (mouseInBounds && ImGui.isMouseClicked(0)) {
                gizmoHandledInput = gizmoRenderer.handleMousePress(viewportMouseX, viewportMouseY);
                if (gizmoHandledInput) {
                    logger.debug("Gizmo captured mouse press");
                }
            }

            // Check for mouse release
            if (ImGui.isMouseReleased(0)) {
                gizmoRenderer.handleMouseRelease(viewportMouseX, viewportMouseY);
            }

            // If gizmo is dragging, it handles input
            if (gizmoRenderer.isDragging()) {
                gizmoHandledInput = true;
            }
        }

        // Only handle camera input if gizmo didn't capture the input
        if (!gizmoHandledInput) {
            boolean mouseClicked = ImGui.isMouseClicked(0);

            // Start camera dragging when left mouse button is pressed within viewport bounds
            if (mouseInBounds && mouseClicked) {
                startDragging();
            }

            // Continue dragging with unlimited mouse movement (mouse is now captured)
            if (isDragging && ImGui.isMouseDown(0)) {
                processDragging();
            }

            // Stop dragging when mouse button is released
            if (ImGui.isMouseReleased(0) && isDragging) {
                stopDragging();
            }

            // Handle mouse wheel for zooming (only when hovering over viewport or actively dragging)
            if (mouseInBounds || isDragging) {
                processZooming();
            }
        }
    }
    
    /**
     * Start a drag operation.
     */
    private void startDragging() {
        isDragging = true;
        captureMouse(); // Enable endless dragging with mouse capture
        // logger.info("Started endless dragging in 3D viewport - mouse captured");
    }
    
    /**
     * Process mouse dragging for camera rotation.
     */
    private void processDragging() {
        ImVec2 mouseDelta = ImGui.getIO().getMouseDelta();
        
        // Process any mouse movement - even tiny amounts for smooth rotation
        if (Math.abs(mouseDelta.x) > 0.0001f || Math.abs(mouseDelta.y) > 0.0001f) {
            logger.trace("Processing mouse delta: ({}, {})", mouseDelta.x, mouseDelta.y);
            
            // Apply rotation with appropriate sensitivity for captured mouse
            float rotationSpeed = isMouseCaptured ? 0.003f : 0.1f; // Lower sensitivity for captured mouse
            camera.rotate(-mouseDelta.x * rotationSpeed, -mouseDelta.y * rotationSpeed);
            logger.trace("Camera rotated by delta: ({}, {}) with speed: {}", 
                -mouseDelta.x * rotationSpeed, -mouseDelta.y * rotationSpeed, rotationSpeed);
        }
    }
    
    /**
     * Stop the current drag operation.
     */
    private void stopDragging() {
        // logger.info("Stopped endless dragging in 3D viewport - mouse released");
        isDragging = false;
        releaseMouse(); // Restore cursor visibility and position
    }
    
    /**
     * Process mouse wheel for zooming.
     */
    private void processZooming() {
        float wheel = ImGui.getIO().getMouseWheel();
        if (wheel != 0) {
            camera.zoom(wheel * 0.5f);
            // logger.debug("Camera zoomed by: {}", wheel * 0.5f);
        }
    }
    
    /**
     * Force release mouse capture. Useful for escape key handling.
     */
    public void forceReleaseMouse() {
        if (isDragging) {
            isDragging = false;
            // logger.info("Forced release of mouse capture");
        }
        releaseMouse();
    }
    
    /**
     * Handle keyboard input for camera controls.
     * This can be extended for first-person camera movement.
     */
    public void handleKeyboardInput(float deltaTime) {
        if (camera == null || camera.getCameraMode() != Camera.CameraMode.FIRST_PERSON) {
            return;
        }
        
        // First-person movement controls
        boolean moved = false;
        
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_W)) {
            camera.moveForward(deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_S)) {
            camera.moveForward(-deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_A)) {
            camera.moveRight(-deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_D)) {
            camera.moveRight(deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_SPACE)) {
            camera.moveUp(deltaTime);
            moved = true;
        }
        if (ImGui.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)) {
            camera.moveUp(-deltaTime);
            moved = true;
        }
        
        if (moved) {
            logger.trace("First-person camera movement processed");
        }
    }
    
    /**
     * Reset the input handler state.
     */
    public void reset() {
        if (isDragging) {
            stopDragging();
        }
        // logger.debug("ViewportInputHandler reset");
    }
    
    /**
     * Cleanup resources when the input handler is no longer needed.
     */
    public void cleanup() {
        reset();
        // logger.info("ViewportInputHandler cleaned up");
    }
    
    // Getters for state information
    public boolean isDragging() { 
        return isDragging; 
    }
    
    public boolean isMouseCaptured() { 
        return isMouseCaptured; 
    }
    
    public boolean isRawMouseMotionSupported() { 
        return rawMouseMotionSupported; 
    }
    
    public double getSavedCursorX() { 
        return savedCursorX; 
    }
    
    public double getSavedCursorY() { 
        return savedCursorY; 
    }
    
    public long getWindowHandle() {
        return windowHandle;
    }
}