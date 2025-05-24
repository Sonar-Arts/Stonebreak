package com.stonebreak;

import java.util.Arrays;

import org.joml.Vector2f;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.glfwGetKey;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;

/**
 * Handles player input for movement and interaction.
 */
public class InputHandler {
    
    private long window;
    
    // Mouse state
    private boolean firstMouse = true;
    private float currentMouseX = 0; 
    private float currentMouseY = 0; 
    private float mouseSensitivity = 0.1f;
    private double scrollYThisFrame = 0.0; // Added for RecipeBookScreen scrolling

    // Mouse button states
    private boolean[] mouseButtonDown = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private boolean[] mouseButtonPressedThisFrame = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    
    // Selected hotbar slot index
    private int currentSelectedHotbarIndex = 0; // Tracks the desired index, 0-8
    
    // Key state tracking for toggle actions
    private boolean escapeKeyPressed = false;
    private boolean inventoryKeyPressed = false; // Added for inventory toggle
    
    public InputHandler(long window) {
        this.window = window;
        Arrays.fill(mouseButtonDown, false);
        Arrays.fill(mouseButtonPressedThisFrame, false);
        
        try {
            glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
                this.scrollYThisFrame = yoffset; // Store the raw scroll input
                handleHotbarScroll(yoffset); // Maintain existing hotbar scroll logic
            });
        } catch (Exception e) {
            System.err.println("Error setting up input handlers: " + e.getMessage());
        }
    }
    
    /**
     * Call this at the START of each frame's input processing cycle.
     */
    public void prepareForNewFrame() {
        // Clear "pressed this frame" states, as they are single-frame events
        Arrays.fill(mouseButtonPressedThisFrame, false);
        scrollYThisFrame = 0.0; // Reset scroll each frame
    }

    public void handleInput(Player player) {
        if (player == null) {
            return;
        }
        
        try {
            // Handle escape key for pause menu
            handleEscapeKey();
            
            // Handle inventory key
            handleInventoryKey();

            // Handle inventory screen mouse input if visible
            InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
            if (inventoryScreen != null && inventoryScreen.isVisible()) {
                inventoryScreen.handleMouseInput(Game.getWindowWidth(), Game.getWindowHeight());
            }

            // If the game is paused (either by pause menu or inventory), don't process movement/block selection
            // UNLESS only the inventory is open, in which case some actions might still be allowed (handled by InventoryScreen)
            if (Game.getInstance().isPaused() && (inventoryScreen == null || (inventoryScreen == null || !inventoryScreen.isVisible()))) { // If paused by menu, not just inventory
                return;
            }
            if (Game.getInstance().isPaused() && inventoryScreen != null && (inventoryScreen != null && inventoryScreen.isVisible())){
                // Movement is blocked, but other non-movement inputs might be processed by inventory screen
            } else {
                 // Process movement inputs only if not paused by menu and inventory is not open
                boolean moveForward = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
                boolean moveBackward = glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
                boolean moveLeft = glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS;
                boolean moveRight = glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS;
                boolean jump = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
                
                // Handle movement
                player.processMovement(moveForward, moveBackward, moveLeft, moveRight, jump);
                
                // Handle continuous block breaking
                if (isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
                    player.startBreakingBlock();
                } else {
                    player.stopBreakingBlock();
                }
            }
            
            // Handle number keys for hotbar slot selection
            for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) { // 0-8 for keys 1-9
                if (glfwGetKey(window, GLFW_KEY_1 + i) == GLFW_PRESS) {
                    selectHotbarSlotByKey(i); 
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing input: " + e.getMessage());
        }
    }
    /**
     * Handle the escape key for toggling the pause menu.
     */
    private void handleEscapeKey() {
        boolean isEscapePressed = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;
        
        // If key was just pressed (not held)
        if (isEscapePressed && !escapeKeyPressed) {
            escapeKeyPressed = true;
            Game.getInstance().togglePauseMenu();
            
            // Toggle cursor visibility when pausing/unpausing
            if (Game.getInstance().isPaused()) {
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            } else {
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                resetMousePosition();
            }
        } else if (!isEscapePressed) {
            escapeKeyPressed = false;
        }
    }

    private void handleInventoryKey() {
        boolean isInventoryKeyPressed = glfwGetKey(window, GLFW_KEY_E) == GLFW_PRESS;

        if (isInventoryKeyPressed && !inventoryKeyPressed) {
            inventoryKeyPressed = true;
            Game.getInstance().toggleInventoryScreen();
        } else if (!isInventoryKeyPressed) {
            inventoryKeyPressed = false;
        }
    }

    /**
     * Processes mouse movement to adjust the player's camera view.
     * This method is called when mouse movement is detected and the game is not paused.
     * @param xOffset The horizontal offset of the mouse movement.
     * @param yOffset The vertical offset of the mouse movement.
     */
    private void handleMouseLook(float xOffset, float yOffset) {
        // Only process mouse movement if the game is not paused by any UI screen
        if (Game.getInstance().isPaused()) {
            return;
        }
        
        Player player = Game.getPlayer();
        if (player != null) {
            player.processMouseLook(xOffset * mouseSensitivity, yOffset * mouseSensitivity);
        }
    }

    /**
     * Called by GLFW's mouse button callback (likely from Main.java).
     * Updates internal mouse button states.
     */
    public void processMouseButton(int button, int action, int mods) {
        if (button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST) {
            if (action == GLFW_PRESS) {
                mouseButtonDown[button] = true;
                mouseButtonPressedThisFrame[button] = true; 
            } else if (action == GLFW_RELEASE) {
                mouseButtonDown[button] = false;
            }
        }

        // Existing logic for game interactions based on clicks:
        InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            return; 
        }
        
        // If pause menu is active, it might handle clicks
        PauseMenu pauseMenu = Game.getInstance().getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible()) {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                UIRenderer uiRenderer = Game.getInstance().getUIRenderer();
                if (uiRenderer != null) {
                    // Check resume button
                    if (pauseMenu.isResumeButtonClicked(currentMouseX, currentMouseY, uiRenderer, Game.getWindowWidth(), Game.getWindowHeight())) {
                        Game.getInstance().togglePauseMenu(); // Resume the game
                    }
                    // Check quit button
                    else if (pauseMenu.isQuitButtonClicked(currentMouseX, currentMouseY, uiRenderer, Game.getWindowWidth(), Game.getWindowHeight())) {
                        // Return to main menu
                        Game.getInstance().setState(GameState.MAIN_MENU);
                        Game.getInstance().getPauseMenu().setVisible(false);
                    }
                if (pauseMenu.isQuitButtonClicked(currentMouseX, currentMouseY, Game.getWindowWidth(), Game.getWindowHeight())) {
                    glfwSetWindowShouldClose(window, true);
                }
            }
            return; 
        }

        // If game is not paused by menu and inventory is not open, handle world interaction
        if (!Game.getInstance().isPaused()) {
            if (action == GLFW_PRESS) { 
                Player player = Game.getPlayer();
                if (player != null) {
                    if (button == GLFW_MOUSE_BUTTON_LEFT) {
                        player.startAttackAnimation();
                        // Block breaking is now handled continuously in handleInput
                    } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                        player.startAttackAnimation(); // Also animate when placing blocks like Minecraft
                        player.placeBlock();
                    }
                }
            }
        }
    }
    
    private void handleHotbarScroll(double yOffset) { 
        InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
        // Allow scroll for hotbar selection even if inventory screen is open, but not if pause menu is.
        if (Game.getInstance().isPaused() && (inventoryScreen == null || !inventoryScreen.isVisible())) {
            return; 
        }
        
        int newSelectedIndex = currentSelectedHotbarIndex;
        if (yOffset > 0) { 
            newSelectedIndex = (currentSelectedHotbarIndex + 1) % Inventory.HOTBAR_SIZE;
        } else if (yOffset < 0) { 
            newSelectedIndex = (currentSelectedHotbarIndex - 1 + Inventory.HOTBAR_SIZE) % Inventory.HOTBAR_SIZE;
        }
        
        setSelectedHotbarSlot(newSelectedIndex);
    }
    
    private void selectHotbarSlotByKey(int slotIndex) { 
        if (slotIndex >= 0 && slotIndex < Inventory.HOTBAR_SIZE) {
            setSelectedHotbarSlot(slotIndex);
        }
    }

    private void setSelectedHotbarSlot(int index) {
        if (index >= 0 && index < Inventory.HOTBAR_SIZE) {
            currentSelectedHotbarIndex = index;
            Player player = Game.getPlayer();
            if (player != null && player.getInventory() != null) {
                player.getInventory().setSelectedHotbarSlotIndex(currentSelectedHotbarIndex);
            }
        }
    }

    /**
     * Gets the block type ID from the currently selected hotbar slot.
     * @return BlockType ID or AIR if empty/invalid.
     */
    public int getSelectedBlockTypeIdFromHotbar() {
        Player player = Game.getPlayer();
        if (player != null && player.getInventory() != null) {
            return player.getInventory().getSelectedBlockTypeId(); 
        }
        return BlockType.AIR.getId(); 
    }
    
    /**
     * Reset the mouse position tracking to avoid camera jumps
     * when regaining cursor control
     */
    public void resetMousePosition() {
        firstMouse = true;
    }
    
    /**
     * Update mouse position and handle mouse look (called from Main.java cursor callback)
     */
    public void updateMousePosition(float xpos, float ypos) {
        if (firstMouse) {
            currentMouseX = xpos;
            currentMouseY = ypos;
            firstMouse = false;
            return; 
        }
        
        float xOffset = xpos - currentMouseX;
        float yOffset = currentMouseY - ypos; 
        
        currentMouseX = xpos;
        currentMouseY = ypos;
        
        // Update pause menu hover state if visible
        PauseMenu pauseMenu = Game.getInstance().getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible()) {
            UIRenderer uiRenderer = Game.getInstance().getUIRenderer();
            if (uiRenderer != null) {
                pauseMenu.updateHover(currentMouseX, currentMouseY, uiRenderer, Game.getWindowWidth(), Game.getWindowHeight());
            }
        } else {
            handleMouseLook(xOffset, yOffset);
        }
    }

    /**
     * Sets the visibility of the mouse cursor.
     * @param visible true to show the cursor, false to hide and disable it.
     */
    public void setCursorVisible(boolean visible) {
        if (window != 0) { 
            if (visible) {
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            } else {
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                resetMousePosition(); 
            }
        }
    }

    // Mouse helper methods for InventoryScreen 
    public Vector2f getMousePosition() {
        return new Vector2f(currentMouseX, currentMouseY);
    }

    public boolean isMouseButtonPressed(int button) {
        if (button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST) {
            return mouseButtonPressedThisFrame[button];
        }
        return false;
    }

    public boolean isMouseButtonDown(int button) {
        if (button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST) {
            return mouseButtonDown[button];
        }
        return false;
    }

    public void consumeMouseButtonPress(int button) {
        if (button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST) {
            mouseButtonPressedThisFrame[button] = false;
        }
    }

    // New methods for RecipeBookScreen scrolling
    public double getScrollY() {
        return scrollYThisFrame;
    }

    public void consumeScroll() {
        scrollYThisFrame = 0.0;
    }
}
