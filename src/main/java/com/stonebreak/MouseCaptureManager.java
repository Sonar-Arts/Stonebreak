package com.stonebreak;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Mouse capture management system for handling cursor modes and camera movement.
 * This system provides a clean, non-hacky implementation that properly accounts
 * for all game states, phases, and menus.
 */
public class MouseCaptureManager {
    
    private final long window;
    private boolean isMouseCaptured = false;
    private boolean firstMouseMovement = true;
    private float lastMouseX = 0.0f;
    private float lastMouseY = 0.0f;
    private float mouseSensitivity = 0.1f;
    
    // Camera reference for mouse look
    private Camera camera;
    
    public MouseCaptureManager(long window) {
        this.window = window;
    }
    
    /**
     * Sets the camera reference for mouse look functionality.
     */
    public void setCamera(Camera camera) {
        this.camera = camera;
    }
    
    /**
     * Updates mouse capture state based on current game state.
     * This should be called whenever the game state changes.
     */
    public void updateCaptureState() {
        GameState currentState = Game.getInstance().getState();
        boolean shouldCapture = shouldCaptureMouseForState(currentState);
        
        if (shouldCapture && !isMouseCaptured) {
            captureMouse();
        } else if (!shouldCapture && isMouseCaptured) {
            releaseMouse();
        }
    }
    
    /**
     * Determines if mouse should be captured for the given game state.
     */
    private boolean shouldCaptureMouseForState(GameState state) {
        Game game = Game.getInstance();
        
        switch (state) {
            case PLAYING -> {
                // Only capture if not paused or if paused by inventory only
                if (!game.isPaused()) {
                    return true; // Normal gameplay
                }
                
                // Check if paused by pause menu vs just inventory/UI
                PauseMenu pauseMenu = game.getPauseMenu();
                boolean pausedByMenu = pauseMenu != null && pauseMenu.isVisible();
                
                // Don't capture if paused by the main pause menu
                if (pausedByMenu) {
                    return false;
                }
                
                // Check if chat is open
                ChatSystem chatSystem = game.getChatSystem();
                if (chatSystem != null && chatSystem.isOpen()) {
                    return false;
                }
                
                // Check if any UI screens that should block camera movement are open
                InventoryScreen inventoryScreen = game.getInventoryScreen();
                if (inventoryScreen != null && inventoryScreen.isVisible()) {
                    return false;
                }
                
                return true; // Paused by something that still allows camera movement
            }
            case WORKBENCH_UI, RECIPE_BOOK_UI, INVENTORY_UI, PAUSED -> {
                return false;
            }
            case MAIN_MENU, LOADING, SETTINGS -> {
                return false;
            }
            default -> {
                return false;
            }
        }
    }
    
    /**
     * Captures the mouse cursor and enables camera look.
     */
    private void captureMouse() {
        // Check if window is focused
        boolean windowFocused = glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_TRUE;
        
        if (!windowFocused) {
            glfwRequestWindowAttention(window);
            glfwFocusWindow(window);
            
            // Give a small delay for the focus to take effect
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        isMouseCaptured = true;
        firstMouseMovement = true; // Reset to prevent jump on capture
        
        // Get current cursor position to avoid initial jump
        double[] xpos = new double[1];
        double[] ypos = new double[1];
        glfwGetCursorPos(window, xpos, ypos);
        lastMouseX = (float) xpos[0];
        lastMouseY = (float) ypos[0];
    }
    
    /**
     * Releases the mouse cursor and disables camera look.
     */
    private void releaseMouse() {
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        isMouseCaptured = false;
        firstMouseMovement = true; // Reset for next capture
    }
    
    /**
     * Processes mouse movement for camera look.
     * This should be called from the GLFW cursor position callback.
     */
    public void processMouseMovement(double xpos, double ypos) {
        float mouseX = (float) xpos;
        float mouseY = (float) ypos;
        
        // Only process camera movement if mouse is captured
        if (!isMouseCaptured || camera == null) {
            // Update last position even when not captured to prevent jumps
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return;
        }
        
        // Additional safety check - don't process camera movement if chat is open
        Game game = Game.getInstance();
        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            // Update last position to prevent jumps when chat closes
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return;
        }
        
        // Handle first mouse movement to prevent camera jump
        if (firstMouseMovement) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            firstMouseMovement = false;
            return;
        }
        
        // Calculate mouse movement offset
        float xOffset = mouseX - lastMouseX;
        float yOffset = lastMouseY - mouseY; // Reversed since y-coordinates go from bottom to top
        
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        
        // Apply sensitivity
        xOffset *= mouseSensitivity;
        yOffset *= mouseSensitivity;
        
        // Apply to camera
        camera.processMouseMovement(xOffset, yOffset);
    }
    
    /**
     * Forces mouse capture state update.
     * Useful when game state changes require immediate mouse capture update.
     */
    public void forceUpdate() {
        updateCaptureState();
    }
    
    /**
     * Checks if mouse is currently captured.
     */
    public boolean isMouseCaptured() {
        return isMouseCaptured;
    }
    
    /**
     * Sets mouse sensitivity for camera movement.
     */
    public void setMouseSensitivity(float sensitivity) {
        this.mouseSensitivity = Math.max(0.01f, Math.min(1.0f, sensitivity));
    }
    
    /**
     * Gets current mouse sensitivity.
     */
    public float getMouseSensitivity() {
        return mouseSensitivity;
    }
    
    /**
     * Temporarily releases mouse (useful for ALT+TAB scenarios).
     */
    public void temporaryRelease() {
        if (isMouseCaptured) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }
    }
    
    /**
     * Restores mouse capture after temporary release.
     */
    public void restoreCapture() {
        if (isMouseCaptured) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            firstMouseMovement = true; // Prevent jump on restore
        }
    }
    
    /**
     * Cleanup resources.
     */
    public void cleanup() {
        if (isMouseCaptured) {
            releaseMouse();
        }
    }
}