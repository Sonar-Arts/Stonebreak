package com.openmason.ui.viewport;

import com.openmason.camera.ArcBallCamera;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GLFW-based input handler for the Dear ImGui viewport.
 * 
 * This class provides GLFW-based input handling with modern callbacks
 * for mouse and keyboard input processing. It provides professional camera control
 * interactions with native performance.
 * 
 * Key features:
 * - Mouse drag for camera rotation and panning
 * - Mouse scroll for camera zoom
 * - Keyboard shortcuts for camera operations
 * - Proper input state management
 * - Integration with ArcBall camera system
 */
public class GLFWInputHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(GLFWInputHandler.class);
    
    // Camera reference
    private ArcBallCamera camera;
    
    // Input state
    private boolean leftMousePressed = false;
    private boolean rightMousePressed = false;
    private boolean middleMousePressed = false;
    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    
    // Viewport bounds for coordinate conversion
    private int viewportX = 0;
    private int viewportY = 0;
    private int viewportWidth = 800;
    private int viewportHeight = 600;
    
    // Input settings
    private boolean cameraControlsEnabled = true;
    private float mouseSensitivity = 1.0f;
    private float scrollSensitivity = 1.0f;
    private float keyboardMoveSpeed = 0.5f;
    
    // GLFW window handle
    private long windowHandle = 0L;
    
    /**
     * Create input handler for the specified camera.
     */
    public GLFWInputHandler(ArcBallCamera camera) {
        this.camera = camera;
        logger.debug("GLFW input handler created");
    }
    
    /**
     * Initialize input handling for the specified GLFW window.
     */
    public void initialize(long windowHandle) {
        this.windowHandle = windowHandle;
        setupCallbacks();
        logger.info("GLFW input handler initialized for window: {}", windowHandle);
    }
    
    /**
     * Set up GLFW input callbacks.
     */
    private void setupCallbacks() {
        if (windowHandle == 0L) {
            logger.warn("Cannot setup callbacks: invalid window handle");
            return;
        }
        
        // Mouse button callback
        glfwSetMouseButtonCallback(windowHandle, this::handleMouseButton);
        
        // Cursor position callback
        glfwSetCursorPosCallback(windowHandle, this::handleCursorPos);
        
        // Scroll callback
        glfwSetScrollCallback(windowHandle, this::handleScroll);
        
        // Key callback
        glfwSetKeyCallback(windowHandle, this::handleKey);
        
        logger.debug("GLFW callbacks registered");
    }
    
    /**
     * Handle mouse button events.
     */
    private void handleMouseButton(long window, int button, int action, int mods) {
        if (!cameraControlsEnabled || camera == null) {
            return;
        }
        
        // Check if mouse is within viewport bounds
        if (!isMouseInViewport()) {
            return;
        }
        
        boolean pressed = action == GLFW_PRESS;
        
        switch (button) {
            case GLFW_MOUSE_BUTTON_LEFT -> {
                leftMousePressed = pressed;
                if (pressed) {
                    logger.trace("Left mouse button pressed");
                }
            }
            case GLFW_MOUSE_BUTTON_RIGHT -> {
                rightMousePressed = pressed;
                if (pressed) {
                    logger.trace("Right mouse button pressed");
                }
            }
            case GLFW_MOUSE_BUTTON_MIDDLE -> {
                middleMousePressed = pressed;
                if (pressed) {
                    logger.trace("Middle mouse button pressed");
                }
            }
        }
    }
    
    /**
     * Handle cursor position events.
     */
    private void handleCursorPos(long window, double xpos, double ypos) {
        if (!cameraControlsEnabled || camera == null) {
            return;
        }
        
        // Convert to viewport-relative coordinates
        double viewportX = xpos - this.viewportX;
        double viewportY = ypos - this.viewportY;
        
        // Check if mouse is within viewport bounds
        if (viewportX < 0 || viewportX >= viewportWidth || viewportY < 0 || viewportY >= viewportHeight) {
            return;
        }
        
        // Calculate deltas
        double deltaX = viewportX - lastMouseX;
        double deltaY = viewportY - lastMouseY;
        
        // Get camera mode for different mouse behaviors
        ArcBallCamera.CameraMode cameraMode = camera.getCameraMode();
        
        // Handle camera interactions based on mouse button state and camera mode
        if (leftMousePressed) {
            // Mouse look - works for both camera modes but behaves differently
            float rotationX = (float) (deltaX * mouseSensitivity);
            float rotationY = (float) (deltaY * mouseSensitivity);
            
            camera.rotate(rotationX, rotationY);
            camera.update(0.016f); // Assume 60 FPS
            
            logger.trace("{} rotation: deltaX={}, deltaY={}", 
                        cameraMode, rotationX, rotationY);
            
        } else if (rightMousePressed) {
            if (cameraMode == ArcBallCamera.CameraMode.ARCBALL) {
                // Pan camera with right mouse drag in arc-ball mode
                float panX = (float) (deltaX * mouseSensitivity * 0.01);
                float panY = (float) (-deltaY * mouseSensitivity * 0.01);
                
                camera.pan(panX, panY);
                logger.trace("Arc-ball pan: panX={}, panY={}", panX, panY);
                
            } else if (cameraMode == ArcBallCamera.CameraMode.FIRST_PERSON) {
                // Alternative mouse look in first-person mode
                float rotationX = (float) (deltaX * mouseSensitivity);
                float rotationY = (float) (deltaY * mouseSensitivity);
                
                camera.rotate(rotationX, rotationY);
                logger.trace("First-person look (right drag): deltaX={}, deltaY={}", 
                            rotationX, rotationY);
            }
            
        } else if (middleMousePressed) {
            if (cameraMode == ArcBallCamera.CameraMode.ARCBALL) {
                // Zoom with middle mouse drag in arc-ball mode
                float zoomDelta = (float) (-deltaY * mouseSensitivity * 0.1);
                camera.zoom(zoomDelta);
                logger.trace("Arc-ball zoom (middle drag): {}", zoomDelta);
                
            } else if (cameraMode == ArcBallCamera.CameraMode.FIRST_PERSON) {
                // Move forward/backward in first-person mode
                float moveDelta = (float) (-deltaY * mouseSensitivity * 0.01);
                camera.moveForward(moveDelta);
                logger.trace("First-person move (middle drag): {}", moveDelta);
            }
        }
        
        // Update last mouse position
        lastMouseX = viewportX;
        lastMouseY = viewportY;
    }
    
    /**
     * Handle scroll events.
     */
    private void handleScroll(long window, double xoffset, double yoffset) {
        if (!cameraControlsEnabled || camera == null) {
            return;
        }
        
        // Check if mouse is within viewport bounds
        if (!isMouseInViewport()) {
            return;
        }
        
        float zoomDelta = (float) (yoffset * scrollSensitivity * 0.5);
        camera.zoom(zoomDelta);
        camera.update(0.016f); // Assume 60 FPS
        
        logger.trace("Camera zoom (scroll): {}", zoomDelta);
    }
    
    /**
     * Handle keyboard events.
     */
    private void handleKey(long window, int key, int scancode, int action, int mods) {
        if (!cameraControlsEnabled || camera == null) {
            return;
        }
        
        if (action != GLFW_PRESS && action != GLFW_REPEAT) {
            return;
        }
        
        // Check camera mode for different movement behaviors
        ArcBallCamera.CameraMode cameraMode = camera.getCameraMode();
        
        switch (key) {
            case GLFW_KEY_W -> {
                if (cameraMode == ArcBallCamera.CameraMode.FIRST_PERSON) {
                    camera.moveForward(keyboardMoveSpeed);
                    logger.trace("First-person: moved forward");
                } else {
                    camera.zoom(-keyboardMoveSpeed);
                    logger.trace("Arc-ball: zoomed in");
                }
            }
            case GLFW_KEY_S -> {
                if (cameraMode == ArcBallCamera.CameraMode.FIRST_PERSON) {
                    camera.moveForward(-keyboardMoveSpeed);
                    logger.trace("First-person: moved backward");
                } else {
                    camera.zoom(keyboardMoveSpeed);
                    logger.trace("Arc-ball: zoomed out");
                }
            }
            case GLFW_KEY_A -> {
                if (cameraMode == ArcBallCamera.CameraMode.FIRST_PERSON) {
                    camera.moveRight(-keyboardMoveSpeed);
                    logger.trace("First-person: moved left");
                } else {
                    camera.pan(-keyboardMoveSpeed * 0.5f, 0f);
                    logger.trace("Arc-ball: panned left");
                }
            }
            case GLFW_KEY_D -> {
                if (cameraMode == ArcBallCamera.CameraMode.FIRST_PERSON) {
                    camera.moveRight(keyboardMoveSpeed);
                    logger.trace("First-person: moved right");
                } else {
                    camera.pan(keyboardMoveSpeed * 0.5f, 0f);
                    logger.trace("Arc-ball: panned right");
                }
            }
            case GLFW_KEY_Q -> {
                if (cameraMode == ArcBallCamera.CameraMode.FIRST_PERSON) {
                    camera.moveUp(-keyboardMoveSpeed); // Q = move down
                    logger.trace("First-person: moved down");
                } else {
                    camera.pan(0f, keyboardMoveSpeed * 0.5f);
                    logger.trace("Arc-ball: panned up");
                }
            }
            case GLFW_KEY_E -> {
                if (cameraMode == ArcBallCamera.CameraMode.FIRST_PERSON) {
                    camera.moveUp(keyboardMoveSpeed); // E = move up
                    logger.trace("First-person: moved up");
                } else {
                    camera.pan(0f, -keyboardMoveSpeed * 0.5f);
                    logger.trace("Arc-ball: panned down");
                }
            }
            case GLFW_KEY_UP -> {
                camera.rotate(0f, -5.0f);
                logger.trace("Camera rotated up");
            }
            case GLFW_KEY_DOWN -> {
                camera.rotate(0f, 5.0f);
                logger.trace("Camera rotated down");
            }
            case GLFW_KEY_LEFT -> {
                camera.rotate(-5.0f, 0f);
                logger.trace("Camera rotated left");
            }
            case GLFW_KEY_RIGHT -> {
                camera.rotate(5.0f, 0f);
                logger.trace("Camera rotated right");
            }
            case GLFW_KEY_R -> {
                resetCamera();
                logger.debug("Camera reset via 'R' key");
            }
            case GLFW_KEY_F -> {
                fitCameraToModel();
                logger.debug("Fit camera to model via 'F' key");
            }
            case GLFW_KEY_O -> {
                frameOrigin();
                logger.debug("Frame origin via 'O' key");
            }
            case GLFW_KEY_C -> {
                // Toggle camera mode
                camera.toggleCameraMode();
                logger.info("Camera mode switched to: {}", camera.getCameraMode());
            }
            case GLFW_KEY_SPACE -> {
                if (cameraMode == ArcBallCamera.CameraMode.FIRST_PERSON) {
                    camera.moveUp(keyboardMoveSpeed);
                    logger.trace("First-person: jumped/moved up");
                }
            }
            case GLFW_KEY_LEFT_SHIFT -> {
                if (cameraMode == ArcBallCamera.CameraMode.FIRST_PERSON) {
                    camera.moveUp(-keyboardMoveSpeed);
                    logger.trace("First-person: crouched/moved down");
                }
            }
        }
    }
    
    /**
     * Check if mouse cursor is within viewport bounds.
     */
    private boolean isMouseInViewport() {
        double[] xpos = new double[1];
        double[] ypos = new double[1];
        glfwGetCursorPos(windowHandle, xpos, ypos);
        
        double viewportX = xpos[0] - this.viewportX;
        double viewportY = ypos[0] - this.viewportY;
        
        return viewportX >= 0 && viewportX < viewportWidth && 
               viewportY >= 0 && viewportY < viewportHeight;
    }
    
    /**
     * Reset camera to default position.
     */
    private void resetCamera() {
        if (camera != null) {
            camera.setDistance(10.0f);
            camera.setTarget(new org.joml.Vector3f(0, 0, 0));
            camera.setOrientation(0.0f, 0.0f);
        }
    }
    
    /**
     * Fit camera to current model (placeholder).
     */
    private void fitCameraToModel() {
        if (camera != null) {
            // TODO: Calculate model bounds and fit camera appropriately
            camera.setDistance(15.0f);
            camera.setTarget(new org.joml.Vector3f(0, 0, 0));
        }
    }
    
    /**
     * Frame the origin.
     */
    private void frameOrigin() {
        if (camera != null) {
            camera.setTarget(new org.joml.Vector3f(0, 0, 0));
            camera.setDistance(5.0f);
        }
    }
    
    // ========== Public API ==========
    
    /**
     * Set viewport bounds for input coordinate conversion.
     */
    public void setViewportBounds(int x, int y, int width, int height) {
        this.viewportX = x;
        this.viewportY = y;
        this.viewportWidth = width;
        this.viewportHeight = height;
        
        logger.trace("Viewport bounds updated: {}x{} at ({}, {})", width, height, x, y);
    }
    
    /**
     * Enable or disable camera controls.
     */
    public void setCameraControlsEnabled(boolean enabled) {
        this.cameraControlsEnabled = enabled;
        logger.debug("Camera controls enabled: {}", enabled);
    }
    
    /**
     * Check if camera controls are enabled.
     */
    public boolean areCameraControlsEnabled() {
        return cameraControlsEnabled;
    }
    
    /**
     * Set mouse sensitivity.
     */
    public void setMouseSensitivity(float sensitivity) {
        this.mouseSensitivity = Math.max(0.1f, Math.min(5.0f, sensitivity));
        logger.debug("Mouse sensitivity set to: {}", this.mouseSensitivity);
    }
    
    /**
     * Get mouse sensitivity.
     */
    public float getMouseSensitivity() {
        return mouseSensitivity;
    }
    
    /**
     * Set scroll sensitivity.
     */
    public void setScrollSensitivity(float sensitivity) {
        this.scrollSensitivity = Math.max(0.1f, Math.min(5.0f, sensitivity));
        logger.debug("Scroll sensitivity set to: {}", this.scrollSensitivity);
    }
    
    /**
     * Get scroll sensitivity.
     */
    public float getScrollSensitivity() {
        return scrollSensitivity;
    }
    
    /**
     * Set keyboard movement speed.
     */
    public void setKeyboardMoveSpeed(float speed) {
        this.keyboardMoveSpeed = Math.max(0.1f, Math.min(2.0f, speed));
        logger.debug("Keyboard move speed set to: {}", this.keyboardMoveSpeed);
    }
    
    /**
     * Get keyboard movement speed.
     */
    public float getKeyboardMoveSpeed() {
        return keyboardMoveSpeed;
    }
    
    /**
     * Get current mouse state for debugging.
     */
    public String getMouseState() {
        return String.format("Mouse: L=%s R=%s M=%s Pos=(%.1f,%.1f)", 
            leftMousePressed, rightMousePressed, middleMousePressed, lastMouseX, lastMouseY);
    }
    
    /**
     * Get input diagnostics.
     */
    public String getDiagnostics() {
        return String.format(
            "GLFWInputHandler Diagnostics:\\n" +
            "  Window: %d\\n" +
            "  Viewport: %dx%d at (%d,%d)\\n" +
            "  Camera Controls: %s\\n" +
            "  Mouse Sensitivity: %.2f\\n" +
            "  Scroll Sensitivity: %.2f\\n" +
            "  Keyboard Speed: %.2f\\n" +
            "  %s",
            windowHandle, viewportWidth, viewportHeight, viewportX, viewportY,
            cameraControlsEnabled ? "Enabled" : "Disabled",
            mouseSensitivity, scrollSensitivity, keyboardMoveSpeed,
            getMouseState()
        );
    }
    
    /**
     * Clean up resources and remove callbacks.
     */
    public void dispose() {
        if (windowHandle != 0L) {
            // Remove callbacks
            glfwSetMouseButtonCallback(windowHandle, null);
            glfwSetCursorPosCallback(windowHandle, null);
            glfwSetScrollCallback(windowHandle, null);
            glfwSetKeyCallback(windowHandle, null);
            
            windowHandle = 0L;
        }
        
        camera = null;
        
        logger.debug("GLFW input handler disposed");
    }
}