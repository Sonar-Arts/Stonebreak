package com.stonebreak;

import static org.lwjgl.glfw.GLFW.*;
import org.joml.Vector2f;
import java.util.Arrays;

/**
 * Handles player input for movement and interaction.
 */
public class InputHandler {
    
    private long window;
    
    // Mouse state
    private boolean firstMouse = true;
    private float currentMouseX = 0; // Renamed from lastX for clarity
    private float currentMouseY = 0; // Renamed from lastY for clarity
    private float mouseSensitivity = 0.1f;

    // Mouse button states
    private boolean[] mouseButtonDown = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private boolean[] mouseButtonPressedThisFrame = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    
    // Selected hotbar slot index
    private int currentSelectedHotbarIndex = 0; // Tracks the desired index, 0-8
    // private int selectedBlock = 1; // Old field, replaced by currentSelectedHotbarIndex logic for selection
    
    // Key state tracking for toggle actions
    private boolean escapeKeyPressed = false;
    private boolean inventoryKeyPressed = false; // Added for inventory toggle
    
    public InputHandler(long window) {
        this.window = window;
        Arrays.fill(mouseButtonDown, false);
        Arrays.fill(mouseButtonPressedThisFrame, false);
        
        try {
            // Set up mouse input
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            
            // Setup mouse callback for position
            glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
                if (firstMouse) {
                    currentMouseX = (float) xpos;
                    currentMouseY = (float) ypos;
                    firstMouse = false;
                }
                
                float xOffset = (float) xpos - currentMouseX;
                float yOffset = currentMouseY - (float) ypos; // Reversed since y-coordinates go from bottom to top
                
                currentMouseX = (float) xpos;
                currentMouseY = (float) ypos;
                handleMouseLook(xOffset, yOffset);
            });
            
            // Setup mouse button callback (this will be called by Main)
            // For now, we assume Main calls a method like `processMouseButton` in this class.
            // If not, Main.java needs to be modified to call:
            // Game.getInstance().getInputHandler().processMouseButton(button, action, mods);
            
            // Setup scroll callback for block selection
            glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
                handleScroll(yoffset);
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
    }

    public void handleInput(Player player) {
        if (player == null) {
            return;
        }
        
        // prepareForNewFrame() should be called by Game loop before this
        
        try {
            // Handle escape key for pause menu
            handleEscapeKey();
            
            // Handle inventory key
            handleInventoryKey();

            // Handle inventory screen mouse input if visible
            InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
            if (inventoryScreen != null && inventoryScreen.isVisible()) {
                // Assuming screenWidth and screenHeight are accessible, e.g., via Game.getWindowWidth/Height()
                inventoryScreen.handleMouseInput(Game.getWindowWidth(), Game.getWindowHeight());
            }


            // If the game is paused (either by pause menu or inventory), don't process movement/block selection
            // UNLESS only the inventory is open, in which case some actions might still be allowed (handled by InventoryScreen)
            if (Game.getInstance().isPaused() && !inventoryScreen.isVisible()) { // If paused by menu, not just inventory
                return;
            }
            if (Game.getInstance().isPaused() && inventoryScreen.isVisible()){
                // Movement is blocked, but other non-movement inputs might be processed by inventory screen
                // The return above handles if pause menu is open.
                // If only inventory is open, we skip player movement below but allow inventory interaction.
            } else {
                 // Process movement inputs only if not paused by menu and inventory is not open
                boolean moveForward = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
                boolean moveBackward = glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
                boolean moveLeft = glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS;
                boolean moveRight = glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS;
                boolean jump = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
                
                // Handle movement
                player.processMovement(moveForward, moveBackward, moveLeft, moveRight, jump);
            }
            
            // Handle number keys for hotbar slot selection
            for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) { // 0-8 for keys 1-9
                if (glfwGetKey(window, GLFW_KEY_1 + i) == GLFW_PRESS) {
                    selectHotbarSlotByKey(i); // Renamed from setSelectedHotbarSlot for clarity of source
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
                // Reset first mouse to avoid camera jump when returning to game
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
            // Cursor state is handled by Game.toggleInventoryScreen()
        } else if (!isInventoryKeyPressed) {
            inventoryKeyPressed = false;
        }
    }
      private void handleMouseLook(float xOffset, float yOffset) {
        // Only process mouse movement if the game is not paused
        if (Game.getInstance().isPaused()) {
            return;
        }
        
        // This will be processed by the player's camera
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
                mouseButtonPressedThisFrame[button] = true; // Set pressed for this frame
            } else if (action == GLFW_RELEASE) {
                mouseButtonDown[button] = false;
                // mouseButtonPressedThisFrame is already false or will be cleared next frame
            }
        }

        // Existing logic for game interactions based on clicks:
        InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            // Inventory screen handles its own clicks if visible via handleMouseInput
            // which is called from this class's handleInput method.
            // We might still want to pass the raw click for its internal logic if not consumed.
            // For now, handleMouseInput calls inventoryScreen.handleMouseInput which uses isMouseButtonPressed.
            // This processMouseButton is more for updating the state that isMouseButtonPressed reads.
            return; // Let inventory screen handle it if it's visible
        }
        
        // If pause menu is active, it might handle clicks
        PauseMenu pauseMenu = Game.getInstance().getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible()) {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                // Convert screen coordinates to NDC (-1 to 1)
                // Use currentMouseX, currentMouseY which are continuously updated
                float x = (2.0f * currentMouseX / Game.getWindowWidth()) - 1.0f;
                float y = 1.0f - (2.0f * currentMouseY / Game.getWindowHeight());
                
                if (pauseMenu.isQuitButtonClicked(x, y)) {
                    glfwSetWindowShouldClose(window, true);
                }
            }
            return; // Pause menu handled the click (or ignored it)
        }

        // If game is not paused by menu and inventory is not open, handle world interaction
        if (!Game.getInstance().isPaused()) {
            if (action == GLFW_PRESS) { // Only react on initial press for world actions
                Player player = Game.getPlayer();
                if (player != null) {
                    if (button == GLFW_MOUSE_BUTTON_LEFT) {
                        player.startAttackAnimation();
                        player.breakBlock();
                    } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                        player.placeBlock();
                    }
                }
            }
        }
    }
    
    // Renamed from handleMouseClick to avoid confusion, as this is the GLFW callback receiver
    // public void handleMouseClick(int button, int action) { ... } // Old method removed/refactored into processMouseButton

    private void handleScroll(double yOffset) {
        InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
        // Allow scroll for hotbar selection even if inventory screen is open, but not if pause menu is.
        if (Game.getInstance().isPaused() && (inventoryScreen == null || !inventoryScreen.isVisible())) {
            return; // Paused by menu, not just inventory
        }
        
        int newSelectedIndex = currentSelectedHotbarIndex;
        if (yOffset > 0) { // Scrolled up (conventionally next item)
            newSelectedIndex = (currentSelectedHotbarIndex + 1) % Inventory.HOTBAR_SIZE;
        } else if (yOffset < 0) { // Scrolled down (conventionally previous item)
            newSelectedIndex = (currentSelectedHotbarIndex - 1 + Inventory.HOTBAR_SIZE) % Inventory.HOTBAR_SIZE;
        }
        
        setSelectedHotbarSlot(newSelectedIndex);
    }
    
    // Renamed from selectBlockType and updated logic
    private void selectHotbarSlotByKey(int slotIndex) { // slotIndex is 0-8 (for keys 1-9)
        if (slotIndex >= 0 && slotIndex < Inventory.HOTBAR_SIZE) {
            setSelectedHotbarSlot(slotIndex);
        }
    }

    // Helper method to actually set the hotbar slot and update player
    private void setSelectedHotbarSlot(int index) {
        if (index >= 0 && index < Inventory.HOTBAR_SIZE) {
            currentSelectedHotbarIndex = index;
            Player player = Game.getPlayer();
            if (player != null && player.getInventory() != null) {
                player.getInventory().setSelectedHotbarSlotIndex(currentSelectedHotbarIndex);
                
                // ItemStack selectedStack = player.getInventory().getHotbarSlot(currentSelectedHotbarIndex); // Get the actual stack - Unused
                // Hotbar slot selection handled silently
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
            return player.getInventory().getSelectedBlockTypeId(); // This method in Inventory gets from selected hotbar slot
        }
        return BlockType.AIR.getId(); // Default or error case
    }
    
    // New methods for InventoryScreen - MOVED TO CLASS LEVEL
    // public Vector2f getMousePosition() { ... } // Defined below
    // public boolean isMouseButtonPressed(int button) { ... } // Defined below
    // public boolean isMouseButtonDown(int button) { ... } // Defined below
    // public void consumeMouseButtonPress(int button) { ... } // Defined below
    
    /**
     * Reset the mouse position tracking to avoid camera jumps
     * when regaining cursor control
     */
    public void resetMousePosition() {
        firstMouse = true;
    }

    // Mouse helper methods for InventoryScreen - now correctly inside the InputHandler class
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
} // This is the final closing brace for the InputHandler class
