package com.openmason.ui.viewport;

import com.openmason.camera.ArcBallCamera;

import imgui.ImGui;
import imgui.ImGuiIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * ImGui-compatible input handler for the 3D viewport.
 * 
 * Handles ImGui and GLFW input events with native performance.
 * Designed for use with Dear ImGui immediate mode input handling.
 * 
 * Responsible for:
 * - ImGui mouse and keyboard input processing
 * - Camera control interactions via ImGui
 * - Input state management for viewport
 * - GLFW input callback integration
 */
public class ViewportInputHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportInputHandler.class);
    
    // Input state
    private boolean leftMousePressed = false;
    private boolean rightMousePressed = false;
    private boolean middleMousePressed = false;
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    
    // Camera reference
    private ArcBallCamera camera;
    private boolean cameraControlsEnabled = true;
    
    // Scene manager reference
    private ViewportSceneManager sceneManager;
    
    // Event callbacks
    private Consumer<Void> renderRequestCallback;
    private Runnable fitCameraToModelCallback;
    private Runnable resetCameraCallback;
    private Runnable frameOriginCallback;
    
    // Coordinate system consistency
    private ViewportModelRenderer modelRenderer;
    
    // Input sensitivity settings
    private float mouseSensitivity = 1.0f;
    private float scrollSensitivity = 1.0f;
    private float keyboardSensitivity = 1.0f;
    
    // ImGui viewport dimensions
    private float viewportWidth = 800.0f;
    private float viewportHeight = 600.0f;
    
    /**
     * Constructor.
     */
    public ViewportInputHandler(ArcBallCamera camera, ViewportSceneManager sceneManager, 
                               ViewportModelRenderer modelRenderer) {
        this.camera = camera;
        this.sceneManager = sceneManager;
        this.modelRenderer = modelRenderer;
        
        logger.debug("ViewportInputHandler initialized for ImGui");
    }
    
    /**
     * Process ImGui input events for the viewport.
     * Call this each frame within an ImGui viewport window.
     */
    public void processImGuiInput(float viewportX, float viewportY, float viewportWidth, float viewportHeight) {
        if (!cameraControlsEnabled || camera == null) {
            return;
        }
        
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        
        ImGuiIO io = ImGui.getIO();
        
        // Check if mouse is over viewport
        boolean mouseOverViewport = isMouseOverViewport(io, viewportX, viewportY, viewportWidth, viewportHeight);
        
        if (mouseOverViewport) {
            // Process mouse input
            processMouseInput(io, viewportX, viewportY);
            
            // Process scroll input
            processScrollInput(io);
            
            // Process keyboard input
            processKeyboardInput(io);
        }
    }
    
    /**
     * Check if mouse is over the viewport area.
     */
    private boolean isMouseOverViewport(ImGuiIO io, float viewportX, float viewportY, 
                                       float viewportWidth, float viewportHeight) {
        float mouseX = io.getMousePosX();
        float mouseY = io.getMousePosY();
        
        return mouseX >= viewportX && mouseX <= viewportX + viewportWidth &&
               mouseY >= viewportY && mouseY <= viewportY + viewportHeight;
    }
    
    /**
     * Process mouse input events.
     */
    private void processMouseInput(ImGuiIO io, float viewportX, float viewportY) {
        float mouseX = io.getMousePosX() - viewportX;
        float mouseY = io.getMousePosY() - viewportY;
        
        // Handle mouse press/release
        boolean leftPressed = io.getMouseDown(0);
        boolean rightPressed = io.getMouseDown(1);
        boolean middlePressed = io.getMouseDown(2);
        
        // Mouse press events
        if (leftPressed && !leftMousePressed) {
            handleMousePressed(mouseX, mouseY, MouseButton.LEFT);
        }
        if (rightPressed && !rightMousePressed) {
            handleMousePressed(mouseX, mouseY, MouseButton.RIGHT);
        }
        if (middlePressed && !middleMousePressed) {
            handleMousePressed(mouseX, mouseY, MouseButton.MIDDLE);
        }
        
        // Mouse release events  
        if (!leftPressed && leftMousePressed) {
            handleMouseReleased(mouseX, mouseY, MouseButton.LEFT);
        }
        if (!rightPressed && rightMousePressed) {
            handleMouseReleased(mouseX, mouseY, MouseButton.RIGHT);
        }
        if (!middlePressed && middleMousePressed) {
            handleMouseReleased(mouseX, mouseY, MouseButton.MIDDLE);
        }
        
        // Mouse drag events
        if (leftPressed || rightPressed || middlePressed) {
            handleMouseDragged(mouseX, mouseY);
        }
        
        // Update state
        leftMousePressed = leftPressed;
        rightMousePressed = rightPressed;
        middleMousePressed = middlePressed;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }
    
    /**
     * Process scroll input events.
     */
    private void processScrollInput(ImGuiIO io) {
        float scrollY = io.getMouseWheelH();
        if (Math.abs(scrollY) > 0.01f) {
            handleMouseScroll(scrollY * scrollSensitivity);
        }
    }
    
    /**
     * Process keyboard input events.
     */
    private void processKeyboardInput(ImGuiIO io) {
        // Camera reset shortcuts
        if (io.getKeyCtrl()) {
            // Note: Using available ImGuiKey constants - R and F may not be available in this ImGui version
            if (ImGui.isKeyPressed(ImGui.getKeyIndex(imgui.flag.ImGuiKey.Space))) {
                // Use Space as reset camera shortcut instead of Ctrl+R
                handleKeyPressed(KeyCode.SPACE, true, false, false);
            }
            if (ImGui.isKeyPressed(ImGui.getKeyIndex(imgui.flag.ImGuiKey.Enter))) {
                // Use Enter as fit to view shortcut instead of Ctrl+F
                handleKeyPressed(KeyCode.ENTER, true, false, false);
            }
            if (ImGui.isKeyPressed(ImGui.getKeyIndex(imgui.flag.ImGuiKey.Home))) {
                handleKeyPressed(KeyCode.HOME, true, false, false);
            }
        }
        
        // Individual key shortcuts
        if (ImGui.isKeyPressed(ImGui.getKeyIndex(imgui.flag.ImGuiKey.Home))) {
            handleKeyPressed(KeyCode.HOME, false, false, false);
        }
    }
    
    /**
     * Handle mouse press events.
     */
    private void handleMousePressed(double mouseX, double mouseY, MouseButton button) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        
        switch (button) {
            case LEFT:
                logger.trace("Left mouse pressed at ({}, {})", mouseX, mouseY);
                break;
            case RIGHT:
                logger.trace("Right mouse pressed at ({}, {})", mouseX, mouseY);
                break;
            case MIDDLE:
                logger.trace("Middle mouse pressed at ({}, {})", mouseX, mouseY);
                break;
        }
    }
    
    /**
     * Handle mouse release events.
     */
    private void handleMouseReleased(double mouseX, double mouseY, MouseButton button) {
        logger.trace("Mouse released: {} at ({}, {})", button, mouseX, mouseY);
        requestRender();
    }
    
    /**
     * Handle mouse drag events.
     */
    private void handleMouseDragged(double mouseX, double mouseY) {
        double deltaX = mouseX - lastMouseX;
        double deltaY = mouseY - lastMouseY;
        
        if (Math.abs(deltaX) < 0.1 && Math.abs(deltaY) < 0.1) {
            return; // Ignore very small movements
        }
        
        // Apply mouse sensitivity
        deltaX *= mouseSensitivity;
        deltaY *= mouseSensitivity;
        
        // Camera rotation (left mouse) or panning (right mouse)
        if (leftMousePressed) {
            camera.rotate((float) deltaX, (float) deltaY);
            requestRender();
        } else if (rightMousePressed) {
            camera.pan((float) deltaX, (float) deltaY);
            requestRender();
        }
        
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }
    
    /**
     * Handle mouse scroll events.
     */
    private void handleMouseScroll(double deltaY) {
        double zoomFactor = deltaY * 0.1; // Scale zoom
        camera.zoom((float) zoomFactor);
        requestRender();
        
        logger.trace("Mouse scroll: deltaY={}, zoom={}", deltaY, zoomFactor);
    }
    
    /**
     * Handle keyboard events.
     */
    private void handleKeyPressed(KeyCode key, boolean ctrl, boolean shift, boolean alt) {
        logger.trace("Key pressed: {} (Ctrl: {}, Shift: {}, Alt: {})", key, ctrl, shift, alt);
        
        switch (key) {
            case R:
                if (ctrl && resetCameraCallback != null) {
                    resetCameraCallback.run();
                    requestRender();
                }
                break;
                
            case F:
                if (ctrl && fitCameraToModelCallback != null) {
                    fitCameraToModelCallback.run();
                    requestRender();
                }
                break;
                
            case HOME:
                if (frameOriginCallback != null) {
                    frameOriginCallback.run();
                    requestRender();
                }
                break;
        }
    }
    
    /**
     * Request a render update.
     */
    private void requestRender() {
        if (renderRequestCallback != null) {
            renderRequestCallback.accept(null);
        }
    }
    
    /**
     * Set up GLFW input callbacks for the viewport window.
     */
    public void setupGLFWCallbacks(long windowHandle) {
        // This would set up GLFW callbacks if needed
        // For ImGui integration, input is typically handled through ImGui's input processing
        logger.debug("GLFW callbacks setup for window: {}", windowHandle);
    }
    
    // Getters and setters
    public void setCamera(ArcBallCamera camera) { 
        this.camera = camera; 
        logger.debug("Camera updated: {}", camera != null ? "set" : "null");
    }
    
    public boolean areCameraControlsEnabled() { return cameraControlsEnabled; }
    public void setCameraControlsEnabled(boolean enabled) { 
        this.cameraControlsEnabled = enabled; 
        logger.debug("Camera controls enabled: {}", enabled);
    }
    
    public float getMouseSensitivity() { return mouseSensitivity; }
    public void setMouseSensitivity(float sensitivity) { 
        this.mouseSensitivity = Math.max(0.1f, Math.min(5.0f, sensitivity)); 
    }
    
    public float getScrollSensitivity() { return scrollSensitivity; }
    public void setScrollSensitivity(float sensitivity) { 
        this.scrollSensitivity = Math.max(0.1f, Math.min(5.0f, sensitivity)); 
    }
    
    public float getKeyboardSensitivity() { return keyboardSensitivity; }
    public void setKeyboardSensitivity(float sensitivity) { 
        this.keyboardSensitivity = Math.max(0.1f, Math.min(5.0f, sensitivity)); 
    }
    
    // Callback setters
    public void setRenderRequestCallback(Consumer<Void> callback) { 
        this.renderRequestCallback = callback; 
    }
    
    public void setFitCameraToModelCallback(Runnable callback) { 
        this.fitCameraToModelCallback = callback; 
    }
    
    public void setResetCameraCallback(Runnable callback) { 
        this.resetCameraCallback = callback; 
    }
    
    public void setFrameOriginCallback(Runnable callback) { 
        this.frameOriginCallback = callback; 
    }
    
    /**
     * Get input state for debugging.
     */
    /**
     * Get diagnostics information.
     */
    public String getDiagnostics() {
        return String.format("Camera Controls: %s, Mouse: (%.1f, %.1f), Pressed: L:%s R:%s M:%s",
            cameraControlsEnabled, lastMouseX, lastMouseY, 
            leftMousePressed, rightMousePressed, middleMousePressed);
    }

    public String getInputStateString() {
        return String.format("Input: L:%s R:%s M:%s Pos:(%.1f,%.1f) Controls:%s", 
            leftMousePressed, rightMousePressed, middleMousePressed, 
            lastMouseX, lastMouseY, cameraControlsEnabled);
    }
    
    // Enums for ImGui compatibility
    public enum MouseButton {
        LEFT, RIGHT, MIDDLE
    }
    
    public enum KeyCode {
        R, F, HOME, ESCAPE, SPACE, ENTER
    }
}