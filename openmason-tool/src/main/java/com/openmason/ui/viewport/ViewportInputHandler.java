package com.openmason.ui.viewport;

import com.openmason.camera.ArcBallCamera;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.Node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Handles mouse and keyboard input for the 3D viewport.
 * 
 * Responsible for:
 * - Mouse event processing (press, drag, release, scroll)
 * - Keyboard event handling
 * - Camera control interactions
 * - Input state management
 */
public class ViewportInputHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportInputHandler.class);
    
    // Input state
    private boolean leftMousePressed = false;
    private boolean rightMousePressed = false;
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    
    // Camera reference
    private ArcBallCamera camera;
    private boolean cameraControlsEnabled = true;
    
    // Event callbacks
    private Consumer<Void> renderRequestCallback;
    private Runnable fitCameraToModelCallback;
    private Runnable resetCameraCallback;
    private Runnable frameOriginCallback;
    
    /**
     * Initialize input handler with camera reference.
     */
    public void initialize(ArcBallCamera camera) {
        this.camera = camera;
        logger.debug("ViewportInputHandler initialized with camera");
    }
    
    /**
     * Setup input event handlers for a given node.
     */
    public void setupEventHandlers(Node node) {
        if (node == null) {
            logger.warn("Cannot setup event handlers: node is null");
            return;
        }
        
        // Mouse events
        node.setOnMousePressed(event -> {
            // Request focus to ensure the node can receive events
            node.requestFocus();
            handleMousePressed(event);
        });
        node.setOnMouseDragged(this::handleMouseDragged);
        node.setOnMouseReleased(this::handleMouseReleased);
        node.setOnScroll(this::handleMouseScroll);
        
        // Key events
        node.setOnKeyPressed(this::handleKeyPressed);
        
        // Make sure node can receive focus for key events
        node.setFocusTraversable(true);
        
        logger.debug("Input event handlers setup for node: {}", node.getClass().getSimpleName());
    }
    
    /**
     * Handle mouse press events.
     */
    private void handleMousePressed(MouseEvent event) {
        logger.debug("Mouse pressed event received - enabled: {}, camera: {}", cameraControlsEnabled, camera != null);
        
        if (!cameraControlsEnabled || camera == null) {
            return;
        }
        
        // Store initial mouse position
        lastMouseX = event.getSceneX();
        lastMouseY = event.getSceneY();
        
        // Track mouse button state
        if (event.getButton() == MouseButton.PRIMARY) {
            leftMousePressed = true;
            logger.debug("Left mouse button pressed at ({}, {}) - camera controls active", lastMouseX, lastMouseY);
        } else if (event.getButton() == MouseButton.SECONDARY) {
            rightMousePressed = true;
            logger.debug("Right mouse button pressed at ({}, {}) - camera controls active", lastMouseX, lastMouseY);
        }
        
        event.consume();
    }
    
    /**
     * Handle mouse drag events.
     */
    private void handleMouseDragged(MouseEvent event) {
        if (!cameraControlsEnabled || camera == null) {
            return;
        }
        
        double currentX = event.getSceneX();
        double currentY = event.getSceneY();
        
        double deltaX = currentX - lastMouseX;
        double deltaY = currentY - lastMouseY;
        
        if (leftMousePressed) {
            // Rotate camera with left mouse drag
            // Note: Camera.rotate expects (deltaX, deltaY) where deltaX affects azimuth (horizontal) and deltaY affects elevation (vertical)
            float sensitivity = 1.0f; // Use higher sensitivity since camera has built-in ROTATION_SENSITIVITY of 0.3f
            camera.rotate((float) (deltaX * sensitivity), (float) (deltaY * sensitivity));
            
            // Update camera to apply interpolation
            camera.update(0.016f); // Assume 60 FPS for smooth interpolation
            
            logger.debug("Camera rotation applied: deltaX={}, deltaY={}, sensitivity={}, azimuth={}°, elevation={}°", 
                deltaX, deltaY, sensitivity, camera.getAzimuth(), camera.getElevation());
            
            // Request render update
            requestRender();
            
        } else if (rightMousePressed) {
            // Pan camera with right mouse drag
            float panSensitivity = 0.02f;
            camera.pan((float) (deltaX * panSensitivity), (float) (-deltaY * panSensitivity));
            
            logger.trace("Camera pan: deltaX={}, deltaY={}", deltaX, deltaY);
            
            // Request render update
            requestRender();
        }
        
        // Update last mouse position
        lastMouseX = currentX;
        lastMouseY = currentY;
        
        event.consume();
    }
    
    /**
     * Handle mouse release events.
     */
    private void handleMouseReleased(MouseEvent event) {
        // Reset mouse button state
        if (event.getButton() == MouseButton.PRIMARY) {
            leftMousePressed = false;
            logger.trace("Left mouse button released");
        } else if (event.getButton() == MouseButton.SECONDARY) {
            rightMousePressed = false;
            logger.trace("Right mouse button released");
        }
        
        event.consume();
    }
    
    /**
     * Handle mouse scroll events for camera zoom.
     */
    private void handleMouseScroll(ScrollEvent event) {
        if (!cameraControlsEnabled || camera == null) {
            return;
        }
        
        double scrollDelta = event.getDeltaY();
        float zoomAmount = (float) (scrollDelta / 40.0); // Convert scroll delta to reasonable zoom amount
        
        camera.zoom(zoomAmount); // Positive = zoom in, negative = zoom out
        
        // Update camera to apply interpolation
        camera.update(0.016f); // Assume 60 FPS for smooth interpolation
        
        logger.debug("Camera zoom: scrollDelta={}, distance={}", scrollDelta, camera.getDistance());
        
        // Request render update
        requestRender();
        
        event.consume();
    }
    
    /**
     * Handle key press events.
     */
    private void handleKeyPressed(KeyEvent event) {
        if (!cameraControlsEnabled) {
            return;
        }
        
        KeyCode code = event.getCode();
        
        switch (code) {
            case F:
                // Fit camera to model
                if (fitCameraToModelCallback != null) {
                    fitCameraToModelCallback.run();
                    logger.debug("Fit camera to model triggered via 'F' key");
                }
                break;
                
            case R:
                // Reset camera
                if (resetCameraCallback != null) {
                    resetCameraCallback.run();
                    logger.debug("Reset camera triggered via 'R' key");
                }
                break;
                
            case O:
                // Frame origin
                if (frameOriginCallback != null) {
                    frameOriginCallback.run();
                    logger.debug("Frame origin triggered via 'O' key");
                }
                break;
                
            case W:
                // Move camera forward
                if (camera != null) {
                    camera.zoom(-0.5f);
                    requestRender();
                    logger.trace("Camera moved forward via 'W' key");
                }
                break;
                
            case S:
                // Move camera backward
                if (camera != null) {
                    camera.zoom(0.5f);
                    requestRender();
                    logger.trace("Camera moved backward via 'S' key");
                }
                break;
                
            case A:
                // Pan camera left
                if (camera != null) {
                    camera.pan(-0.2f, 0f);
                    requestRender();
                    logger.trace("Camera panned left via 'A' key");
                }
                break;
                
            case D:
                // Pan camera right
                if (camera != null) {
                    camera.pan(0.2f, 0f);
                    requestRender();
                    logger.trace("Camera panned right via 'D' key");
                }
                break;
                
            case Q:
                // Pan camera up
                if (camera != null) {
                    camera.pan(0f, 0.2f);
                    requestRender();
                    logger.trace("Camera panned up via 'Q' key");
                }
                break;
                
            case E:
                // Pan camera down
                if (camera != null) {
                    camera.pan(0f, -0.2f);
                    requestRender();
                    logger.trace("Camera panned down via 'E' key");
                }
                break;
                
            case UP:
                // Rotate camera up
                if (camera != null) {
                    camera.rotate(-0.1f, 0f);
                    requestRender();
                    logger.trace("Camera rotated up via UP arrow key");
                }
                break;
                
            case DOWN:
                // Rotate camera down
                if (camera != null) {
                    camera.rotate(0.1f, 0f);
                    requestRender();
                    logger.trace("Camera rotated down via DOWN arrow key");
                }
                break;
                
            case LEFT:
                // Rotate camera left
                if (camera != null) {
                    camera.rotate(0f, -0.1f);
                    requestRender();
                    logger.trace("Camera rotated left via LEFT arrow key");
                }
                break;
                
            case RIGHT:
                // Rotate camera right
                if (camera != null) {
                    camera.rotate(0f, 0.1f);
                    requestRender();
                    logger.trace("Camera rotated right via RIGHT arrow key");
                }
                break;
                
            default:
                // No action for other keys
                return;
        }
        
        event.consume();
    }
    
    /**
     * Request a render update via callback.
     */
    private void requestRender() {
        if (renderRequestCallback != null) {
            renderRequestCallback.accept(null);
        }
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
     * Set callback for render requests.
     */
    public void setRenderRequestCallback(Consumer<Void> callback) {
        this.renderRequestCallback = callback;
    }
    
    /**
     * Set callback for fit camera to model action.
     */
    public void setFitCameraToModelCallback(Runnable callback) {
        this.fitCameraToModelCallback = callback;
    }
    
    /**
     * Set callback for reset camera action.
     */
    public void setResetCameraCallback(Runnable callback) {
        this.resetCameraCallback = callback;
    }
    
    /**
     * Set callback for frame origin action.
     */
    public void setFrameOriginCallback(Runnable callback) {
        this.frameOriginCallback = callback;
    }
    
    /**
     * Get current mouse state for debugging.
     */
    public String getMouseState() {
        return String.format("Mouse: L=%s R=%s Pos=(%.1f,%.1f)", 
            leftMousePressed, rightMousePressed, lastMouseX, lastMouseY);
    }
}