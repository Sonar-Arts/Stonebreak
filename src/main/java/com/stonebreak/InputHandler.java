package com.stonebreak;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Handles player input for movement and interaction.
 */
public class InputHandler {
    
    private long window;
    
    // Mouse state
    private boolean firstMouse = true;
    private float lastX = 0;
    private float lastY = 0;
    private float mouseSensitivity = 0.1f;
    
    // Selected block
    private int selectedBlock = 1; // Default to first block type
    
    // Key state tracking for toggle actions
    private boolean escapeKeyPressed = false;
    private boolean inventoryKeyPressed = false; // Added for inventory toggle
    
    public InputHandler(long window) {
        this.window = window;
        
        try {
            // Set up mouse input
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            
            // Setup mouse callback
            glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
                if (firstMouse) {
                    lastX = (float) xpos;
                    lastY = (float) ypos;
                    firstMouse = false;
                }
                
                float xOffset = (float) xpos - lastX;
                float yOffset = lastY - (float) ypos; // Reversed since y-coordinates go from bottom to top
                
                lastX = (float) xpos;
                lastY = (float) ypos;                
                handleMouseLook(xOffset, yOffset);
            });
            
            // Mouse button callback is set up in Main class
            
            // Setup scroll callback for block selection
            glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
                handleScroll(yoffset);
            });
        } catch (Exception e) {
            System.err.println("Error setting up input handlers: " + e.getMessage());
        }
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

            // If the game is paused (either by pause menu or inventory), don't process movement/block selection
            if (Game.getInstance().isPaused()) {
                // We still want to allow looking around if only inventory is open, but not if pause menu is.
                // The handleMouseLook method already checks Game.getInstance().isPaused(),
                // but the cursor state is important. If inventory is open, cursor is normal.
                // If pause menu is open, cursor is normal.
                // If neither, cursor is disabled.
                // This logic is mostly handled in togglePauseMenu and toggleInventoryScreen.
                return;
            }
            
            // Process movement inputs
            boolean moveForward = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
            boolean moveBackward = glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
            boolean moveLeft = glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS;
            boolean moveRight = glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS;
            boolean jump = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
            
            // Handle movement
            player.processMovement(moveForward, moveBackward, moveLeft, moveRight, jump);
            
            // Handle number keys for block selection
            for (int i = 0; i <= 9; i++) {
                if (glfwGetKey(window, GLFW_KEY_0 + i) == GLFW_PRESS) {
                    selectBlockType(i == 0 ? 10 : i);
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
    }/**
     * Handles mouse click events for player interactions.
     */
    public void handleMouseClick(int button, int action) {
        // Don't process block interactions if the game is paused
        if (Game.getInstance().isPaused()) {
            // However, we need to check if the pause menu buttons were clicked
            PauseMenu pauseMenu = Game.getInstance().getPauseMenu();
            if (pauseMenu != null && button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                // Convert screen coordinates to NDC (-1 to 1)
                float x = (2.0f * lastX / Game.getWindowWidth()) - 1.0f;
                float y = 1.0f - (2.0f * lastY / Game.getWindowHeight());
                
                if (pauseMenu.isQuitButtonClicked(x, y)) {
                    glfwSetWindowShouldClose(window, true);
                }
            }
            return;
        }
        
        if (action == GLFW_PRESS) {
            Player player = Game.getPlayer();
            if (player != null) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    // Start attack animation on any left click
                    player.startAttackAnimation();
                    // Break block
                    player.breakBlock();
                } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                    // Place block (Player.placeBlock() now uses inventory's selected item)
                    player.placeBlock();
                }
            }
        }
    }
      private void handleScroll(double yOffset) {
        // If the pause menu is visible, don't process scroll
        if (Game.getInstance().isPaused()) {
            return;
        }
        
        // Change selected block type
        if (yOffset > 0) {
            selectedBlock = Math.min(selectedBlock + 1, BlockType.values().length - 1);
        } else if (yOffset < 0) {
            selectedBlock = Math.max(selectedBlock - 1, 1);
        }
        
        // Ensure the selected block is not AIR if possible
        if (BlockType.getById(selectedBlock) == BlockType.AIR && BlockType.values().length > 1) {
            if (yOffset > 0) { // Scrolled up, try next
                selectedBlock = Math.min(selectedBlock + 1, BlockType.values().length - 1);
                if(BlockType.getById(selectedBlock) == BlockType.AIR && selectedBlock < BlockType.values().length -1) selectedBlock++; // Skip AIR again if possible
            } else { // Scrolled down, try previous
                 selectedBlock = Math.max(selectedBlock - 1, 1);
                 if(BlockType.getById(selectedBlock) == BlockType.AIR && selectedBlock > 1) selectedBlock--; // Skip AIR again if possible
            }
        }
        // Ensure selectedBlock is at least 1 (first valid block)
        selectedBlock = Math.max(1, selectedBlock);


        Player player = Game.getPlayer();
        if (player != null && player.getInventory() != null) {
            player.getInventory().setSelectedBlockTypeId(selectedBlock);
        }
        System.out.println("Selected block: " + BlockType.getById(selectedBlock).getName());
    }
    
    private void selectBlockType(int blockId) {
        if (blockId >= 1 && blockId < BlockType.values().length) {
            try {
                BlockType blockType = BlockType.getById(blockId);
                if (blockType != BlockType.AIR) {
                    selectedBlock = blockId;
                    Player player = Game.getPlayer();
                    if (player != null && player.getInventory() != null) {
                        player.getInventory().setSelectedBlockTypeId(selectedBlock);
                    }
                    System.out.println("Selected block: " + blockType.getName());
                }
            } catch (Exception e) {
                System.err.println("Error selecting block type: " + e.getMessage());
            }
        }
    }
      public int getSelectedBlockType() {
        return selectedBlock;
    }
    
    /**
     * Reset the mouse position tracking to avoid camera jumps
     * when regaining cursor control
     */
    public void resetMousePosition() {
        firstMouse = true;
    }
}
