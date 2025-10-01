package com.stonebreak.input;

import java.util.Arrays;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3i;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.items.Inventory;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.player.Player;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import com.stonebreak.util.MemoryProfiler;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F6;

/**
 * Handles player input for movement and interaction.
 */
public class InputHandler {
    
    private long window;
    
    // Mouse state for UI interactions only
    private float currentMouseX = 0;
    private float currentMouseY = 0;

    // Mouse button states
    private boolean[] mouseButtonDown = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private boolean[] mouseButtonPressedThisFrame = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    
    // Selected hotbar slot index
    private int currentSelectedHotbarIndex = 0; // Tracks the desired index, 0-8
    // private int selectedBlock = 1; // Old field, replaced by currentSelectedHotbarIndex logic for selection
    
    
    // Key state tracking for toggle actions
    private boolean escapeKeyPressed = false;
    private boolean inventoryKeyPressed = false; // Added for inventory toggle
    private boolean chatKeyPressed = false; // Added for chat toggle
    private boolean qKeyPressed = false; // Added for item dropping
    private boolean f3KeyPressed = false; // Added for debug info
    private boolean f4KeyPressed = false; // Added for memory leak analysis
    private boolean f5KeyPressed = false; // Added for detailed memory profiling
    private boolean f6KeyPressed = false; // Added for test cow spawning
    
    // Cached objects to avoid allocations
    private final Vector2f cachedMousePosition = new Vector2f();
    
    // Track which buttons were pressed to optimize clearing
    private boolean[] buttonWasPressed = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    // private boolean recipeBookKeyPressed = false; // Removed for Recipe Book Button
    private double scrollYOffset = 0.0;
    private boolean[] keyJustPressed = new boolean[512]; // Assuming a max key code for simplicity
    private boolean[] keyPressedState = new boolean[512]; // Tracks current GLFW state
    
    public InputHandler(long window) {
        this.window = window;
        Arrays.fill(mouseButtonDown, false);
        Arrays.fill(mouseButtonPressedThisFrame, false);
        
        try {
            // Don't set cursor mode here - let Game.setState handle it based on game state
            // Note: Main.java handles cursor position callback for both UI and game mouse look
            
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
        // Clear "pressed this frame" states efficiently - only clear buttons that were actually pressed
        for (int i = 0; i < buttonWasPressed.length; i++) {
            if (buttonWasPressed[i]) {
                mouseButtonPressedThisFrame[i] = false;
                buttonWasPressed[i] = false;
            }
        }
        Arrays.fill(keyJustPressed, false); // Clear just pressed state for keys

        // Poll specific keys needed for game functionality
        // Note: RecipeBookScreen input is now handled via callbacks to prevent double input
        updateSpecificKeyState(GLFW_KEY_ESCAPE);
        updateSpecificKeyState(GLFW_KEY_BACKSPACE);

    }

    private void updateSpecificKeyState(int key) {
        boolean currentlyPressed = glfwGetKey(window, key) == GLFW_PRESS;
        if (currentlyPressed && !keyPressedState[key]) {
            keyJustPressed[key] = true;
        }
        keyPressedState[key] = currentlyPressed;
    }

    public void handleInput(Player player) {
        if (player == null) {
            return;
        }
        
        // prepareForNewFrame() should be called by Game loop before this
        
        try {
            // Check if chat is open first - if so, only handle chat-related input
            ChatSystem chatSystem = Game.getInstance().getChatSystem();
            boolean isChatOpen = chatSystem != null && chatSystem.isOpen();
            
            if (isChatOpen) {
                // Don't process any other input when chat is open
                // Chat input is handled via the key callback methods
                return;
            }

            // Handle system-level toggles first, as they might change the active UI
            handleEscapeKey();      // Toggles pauseMenu and game state transitions
            handleInventoryKey();   // Toggles inventoryScreen and INVENTORY_UI state
            handleChatKey();        // Opens chatSystem, sets cursor
            handleDropKey();        // Drops selected item when Q is pressed
            handleDebugKeys();      // Handle debug and memory profiling keys

            // Now check which UI, if any, has primary input focus
            GameState currentGameState = Game.getInstance().getState();

            // Cache expensive calls to avoid repeated method calls
            Game gameInstance = Game.getInstance();
            InventoryScreen inventoryScreen = gameInstance.getInventoryScreen();
            WorkbenchScreen workbenchScreen = Game.getInstance().getWorkbenchScreen();
            RecipeScreen recipeScreen = Game.getInstance().getRecipeBookScreen();

            // UI screens take precedence for input if active
            if (currentGameState == GameState.RECIPE_BOOK_UI && recipeScreen != null && recipeScreen.isVisible()) {
                recipeScreen.handleInput();
                return; // Recipe Book UI has full input control
            }
            if (currentGameState == GameState.WORKBENCH_UI && workbenchScreen != null && workbenchScreen.isVisible()) {
                workbenchScreen.handleInput(this);
                return; // Workbench UI has full input control
            }
            // Handle inventory screen input when in INVENTORY_UI state
            if (currentGameState == GameState.INVENTORY_UI && inventoryScreen != null && inventoryScreen.isVisible()) {
                // Cache window dimensions to avoid repeated calls
                int windowWidth = Game.getWindowWidth();
                int windowHeight = Game.getWindowHeight();
                inventoryScreen.handleMouseInput(windowWidth, windowHeight);
            }

            // Only process movement in PLAYING state
            // Block movement in UI states (PAUSED, WORKBENCH_UI, RECIPE_BOOK_UI, INVENTORY_UI, etc.)
            if (currentGameState == GameState.PLAYING) {
                // Process movement inputs
                boolean moveForward = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
                boolean moveBackward = glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
                boolean moveLeft = glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS;
                boolean moveRight = glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS;
                boolean jump = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
                boolean shift = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS || 
                               glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;
                boolean crouch = glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS || 
                               glfwGetKey(window, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS;
                
                // Handle movement
                player.processMovement(moveForward, moveBackward, moveLeft, moveRight, jump, shift);
                
                // Handle flight controls (Space for ascent, Ctrl for descent)
                if (player.isFlying()) {
                    if (jump) {
                        player.processFlightAscent(shift);
                    }
                    if (crouch) {
                        player.processFlightDescent(shift);
                    }
                }
                
                // Handle continuous block breaking
                if (isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
                    player.startBreakingBlock();
                } else {
                    player.stopBreakingBlock();
                }
            }
            
            // Handle number keys for hotbar slot selection (only allow in PLAYING state)
            if (currentGameState == GameState.PLAYING) {
                for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) { // 0-8 for keys 1-9
                    if (glfwGetKey(window, GLFW_KEY_1 + i) == GLFW_PRESS) {
                        selectHotbarSlotByKey(i); // Renamed from setSelectedHotbarSlot for clarity of source
                    }
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
        boolean isEscapePressedNow = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;

        if (isEscapePressedNow && !escapeKeyPressed) {
            escapeKeyPressed = true; // Mark as pressed to prevent repeated actions per frame

            Game game = Game.getInstance();
            ChatSystem chatSystem = game.getChatSystem();
            RecipeScreen recipeScreen = game.getRecipeBookScreen();
            WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
            InventoryScreen inventoryScreen = game.getInventoryScreen();
            // PauseMenu pauseMenu = game.getPauseMenu(); // Get the PauseMenu instance // Removed as unused

            // Priority:
            // 1. Close Chat (already handled in its own key input)
            if (chatSystem != null && chatSystem.isOpen()) {
                // Chat's handleKeyInput calls chatSystem.closeChat() and handles cursor itself
                // No further action here if chat handles escape.
                return;
            }

            // 2. Close Recipe Book
            if (recipeScreen != null && recipeScreen.isVisible() && game.getState() == GameState.RECIPE_BOOK_UI) {
                game.closeRecipeBookScreen(); // This should set state and handle cursor via Game.setState
                return; // Action taken
            }

            // 3. Close Workbench
            if (workbenchScreen != null && workbenchScreen.isVisible() && game.getState() == GameState.WORKBENCH_UI) {
                workbenchScreen.handleCloseRequest(); // Workbench handles its own close logic which calls game.closeWorkbenchScreen
                return; // Action taken
            }

            // 4. Close Inventory
            if (game.getState() == GameState.INVENTORY_UI && inventoryScreen != null && inventoryScreen.isVisible()) {
                // This covers case where Inventory is open directly, or under Recipe Book if Recipe Book was closed in a prior step this frame
                game.toggleInventoryScreen(); // This toggles visibility and handles pause/cursor
                return; // Action taken
            }
            
            // 5. Toggle Pause Menu (if no other screen was closed by Escape above)
            // No specific UI screen active, so toggle the main pause menu
            game.togglePauseMenu(); // This will manage paused state and PauseMenu visibility

            // Cursor and game state for pause menu are handled in togglePauseMenu and/or setState
            // No need for direct GLFW calls here, rely on game.setState and game.togglePauseMenu

        } else if (!isEscapePressedNow) {
            escapeKeyPressed = false; // Reset when key is released
        }
    }

    private void handleInventoryKey() {
        boolean isInventoryKeyPressed = glfwGetKey(window, GLFW_KEY_E) == GLFW_PRESS;

        if (isInventoryKeyPressed && !inventoryKeyPressed) {
            inventoryKeyPressed = true;

            // Check if inventory is already open (INVENTORY_UI state)
            if (Game.getInstance().getState() == GameState.INVENTORY_UI) {
                Game.getInstance().toggleInventoryScreen();
                return;
            }
            
            // Don't open inventory if chat is open
            ChatSystem chatSystem = Game.getInstance().getChatSystem();
            if (chatSystem != null && chatSystem.isOpen()) {
                return;
            }

            // Don't open inventory if workbench is open
            WorkbenchScreen workbenchScreen = Game.getInstance().getWorkbenchScreen();
            if (workbenchScreen != null && workbenchScreen.isVisible()) {
                return;
            }

            // Don't open inventory if recipe book is open
            RecipeScreen recipeScreen = Game.getInstance().getRecipeBookScreen();
            if (recipeScreen != null && recipeScreen.isVisible()) {
                return;
            }
            
            Game.getInstance().toggleInventoryScreen();
            // Cursor state is handled by Game.toggleInventoryScreen()
        } else if (!isInventoryKeyPressed) {
            inventoryKeyPressed = false;
        }
    }
    
    private void handleChatKey() {
        boolean isChatKeyPressed = glfwGetKey(window, GLFW_KEY_T) == GLFW_PRESS;
        
        if (isChatKeyPressed && !chatKeyPressed) {
            chatKeyPressed = true;
            
            // Don't open chat if in states other than PLAYING, INVENTORY_UI, or RECIPE_BOOK_UI
            GameState currentState = Game.getInstance().getState();
            if (currentState != GameState.PLAYING && currentState != GameState.INVENTORY_UI && currentState != GameState.RECIPE_BOOK_UI) {
                return;
            }
            
            InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
            if (inventoryScreen != null && inventoryScreen.isVisible()) {
                return;
            }
            
            ChatSystem chatSystem = Game.getInstance().getChatSystem();
            if (chatSystem != null && !chatSystem.isOpen()) {
                chatSystem.openChat();
                
            }
        } else if (!isChatKeyPressed) {
            chatKeyPressed = false;
        }
    }
    
    private void handleDropKey() {
        boolean isQKeyPressed = glfwGetKey(window, GLFW_KEY_Q) == GLFW_PRESS;
        
        if (isQKeyPressed && !qKeyPressed) {
            qKeyPressed = true;
            
            // Only allow dropping items in PLAYING, INVENTORY_UI, and RECIPE_BOOK_UI states
            GameState currentState = Game.getInstance().getState();
            if (currentState != GameState.PLAYING && currentState != GameState.INVENTORY_UI && currentState != GameState.RECIPE_BOOK_UI) {
                return;
            }
            
            ChatSystem chatSystem = Game.getInstance().getChatSystem();
            if (chatSystem != null && chatSystem.isOpen()) {
                return;
            }
            
            InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
            if (inventoryScreen != null && inventoryScreen.isVisible()) {
                return;
            }
            
            // Drop the currently selected item
            Player player = Game.getPlayer();
            if (player != null) {
                dropSelectedItem(player);
            }
        } else if (!isQKeyPressed) {
            qKeyPressed = false;
        }
    }
    
    private void dropSelectedItem(Player player) {
        // Use the new drop utility to drop a single item from selected slot
        com.stonebreak.util.DropUtil.dropSingleItemFromPlayer(player);
    }
 
    // private void handleRecipeBookKey() { ... } // Method removed
    
    private void handleDebugKeys() {
        // F3 - Toggle debug overlay
        boolean isF3Pressed = glfwGetKey(window, GLFW_KEY_F3) == GLFW_PRESS;
        if (isF3Pressed && !f3KeyPressed) {
            f3KeyPressed = true;
            Game.toggleDebugOverlay();
        } else if (!isF3Pressed) {
            f3KeyPressed = false;
        }
        
        // F4 - Trigger memory leak analysis
        boolean isF4Pressed = glfwGetKey(window, GLFW_KEY_F4) == GLFW_PRESS;
        if (isF4Pressed && !f4KeyPressed) {
            f4KeyPressed = true;
            System.out.println("[DEBUG] Manual memory leak analysis triggered by F4 key...");
            Game.triggerMemoryLeakAnalysis();
        } else if (!isF4Pressed) {
            f4KeyPressed = false;
        }
        
        // F5 - Detailed memory profiling
        boolean isF5Pressed = glfwGetKey(window, GLFW_KEY_F5) == GLFW_PRESS;
        if (isF5Pressed && !f5KeyPressed) {
            f5KeyPressed = true;
            System.out.println("[DEBUG] Detailed memory profiling triggered by F5 key...");
            MemoryProfiler profiler = MemoryProfiler.getInstance();
            profiler.takeSnapshot("manual_f5_" + System.currentTimeMillis());
            profiler.reportDetailedMemoryStats();
            Game.forceGCAndReport("F5 Manual GC");
        } else if (!isF5Pressed) {
            f5KeyPressed = false;
        }
        
        // F6 - Spawn test cow
        boolean isF6Pressed = glfwGetKey(window, GLFW_KEY_F6) == GLFW_PRESS;
        if (isF6Pressed && !f6KeyPressed) {
            f6KeyPressed = true;
            Player player = Game.getPlayer();
            EntityManager entityManager = Game.getEntityManager();
            if (player != null && entityManager != null) {
                // Spawn cow 5 blocks in front of the player
                Vector3f playerPos = player.getPosition();
                Vector3f playerDir = player.getCamera().getFront();
                Vector3f spawnPos = new Vector3f(
                    playerPos.x + playerDir.x * 5.0f,
                    playerPos.y,
                    playerPos.z + playerDir.z * 5.0f
                );
                
                // Find ground level at spawn position
                int groundY = (int)playerPos.y;
                for (int y = (int)playerPos.y + 10; y >= (int)playerPos.y - 10; y--) {
                    BlockType block = Game.getWorld().getBlockAt((int)spawnPos.x, y, (int)spawnPos.z);
                    if (block != null && block != BlockType.AIR) {
                        groundY = y + 1;
                        break;
                    }
                }
                spawnPos.y = groundY;
                
                // Force spawn Angus cow for testing updated face texture
                String textureVariant = "angus";
                Entity cow = entityManager.spawnCowWithVariant(spawnPos, textureVariant);
                if (cow != null) {
                    System.out.println("[DEBUG] Spawned test Angus cow with new cute face at " + spawnPos);
                } else {
                    System.out.println("[DEBUG] Failed to spawn test Angus cow");
                }
            }
        } else if (!isF6Pressed) {
            f6KeyPressed = false;
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
                buttonWasPressed[button] = true; // Track for efficient clearing
            } else if (action == GLFW_RELEASE) {
                mouseButtonDown[button] = false;
                // mouseButtonPressedThisFrame is already false or will be cleared next frame
            }
        }

        // Check if chat is open - if so, handle chat-specific interactions
        ChatSystem chatSystem = Game.getInstance().getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            // Handle tab switching and command button clicks
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                handleChatClick(chatSystem);
            }
            return;
        }

        // If a UI screen is active, it should handle its own mouse clicks.
        // Prevent game world interactions if a UI is up.
        // Note: handleInput methods for screens are responsible for their internal click logic using isMouseButtonPressed etc.
        // This processMouseButton method updates the state for those checks.
        // The 'return' here stops further processing for THIS mouse event in THIS method (e.g., world interaction).

        RecipeScreen recipeScreen = Game.getInstance().getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible() && Game.getInstance().getState() == GameState.RECIPE_BOOK_UI) {
            // RecipeBookScreen.handleInput should manage its clicks. This prevents world clicks.
            return;
        }
        
        WorkbenchScreen workbenchScreen = Game.getInstance().getWorkbenchScreen();
        if (workbenchScreen != null && workbenchScreen.isVisible() && Game.getInstance().getState() == GameState.WORKBENCH_UI) {
            // WorkbenchScreen.handleInput should manage its clicks. This prevents world clicks.
            return;
        }

        InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            // InventoryScreen.handleMouseInput manages its clicks. This prevents world clicks.
            return;
        }
        
        // If pause menu is active, it handles clicks for its buttons
        PauseMenu pauseMenu = Game.getInstance().getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible()) { // Main pause menu (Escape)
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                UIRenderer uiRenderer = Game.getInstance().getUIRenderer();
                if (uiRenderer != null) {
                    if (pauseMenu.isResumeButtonClicked(currentMouseX, currentMouseY, uiRenderer, Game.getWindowWidth(), Game.getWindowHeight())) {
                        Game.getInstance().togglePauseMenu(); // Resume the game
                    }
                    // Check settings button
                    else if (pauseMenu.isSettingsButtonClicked(currentMouseX, currentMouseY, uiRenderer, Game.getWindowWidth(), Game.getWindowHeight())) {
                        // Go to settings menu, remember we came from the game
                        SettingsMenu settingsMenu = Game.getInstance().getSettingsMenu();
                        if (settingsMenu != null) {
                            settingsMenu.setPreviousState(GameState.PLAYING);
                        }
                        // Mouse button states will be managed by the new state
                        Game.getInstance().setState(GameState.SETTINGS);
                        Game.getInstance().getPauseMenu().setVisible(false);
                    }
                    // Check quit button
                    else if (pauseMenu.isQuitButtonClicked(currentMouseX, currentMouseY, uiRenderer, Game.getWindowWidth(), Game.getWindowHeight())) {
                        // Return to main menu
                        Game.getInstance().setState(GameState.MAIN_MENU);
                        Game.getInstance().getPauseMenu().setVisible(false);
                    }
                }
            }
            return; // Pause menu handled or ignored the click
        }

        // Only allow world interaction in PLAYING state
        GameState currentState = Game.getInstance().getState();
        if (currentState == GameState.PLAYING) {
            if (action == GLFW_PRESS) { // Only react on initial press for world actions
                Player player = Game.getPlayer();
                if (player != null) {
                    if (button == GLFW_MOUSE_BUTTON_LEFT) {
                        player.startAttackAnimation();
                        // Block breaking is now handled continuously in handleInput
                    } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                        player.startAttackAnimation(); // Animate for interaction attempts as well

                        // Raycast to see what block is being targeted
                        Vector3i targetedBlockPos = player.raycast();
                        if (targetedBlockPos != null) {
                            BlockType targetedBlockType = Game.getWorld().getBlockAt(targetedBlockPos.x, targetedBlockPos.y, targetedBlockPos.z);
                            if (targetedBlockType == BlockType.WORKBENCH) {
                                // Interacted with a Workbench
                                System.out.println("Player right-clicked on a Workbench block."); // Keep for clarity
                                Game.getInstance().openWorkbenchScreen();
                            } else {
                                // Not a workbench, proceed with normal block placement
                                player.placeBlock();
                            }
                        } else {
                            // Targeting air or out of range, try to place block (normal behavior)
                            player.placeBlock();
                        }
                    }
                }
            }
        }
    }
    
    // Renamed from handleMouseClick to avoid confusion, as this is the GLFW callback receiver
    // public void handleMouseClick(int button, int action) { ... } // Old method removed/refactored into processMouseButton

    private void handleScroll(double yOffset) {
        // Store scroll offset for UI screens that need it (like RecipeBookScreen)
        this.scrollYOffset = yOffset;

        // Handle chat scrolling if chat is open
        ChatSystem chatSystem = Game.getInstance().getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            chatSystem.handleScroll(yOffset);
            return;
        }
        
        // If recipe book is open, let it handle scrolling and don't process hotbar scroll
        RecipeScreen recipeScreen = Game.getInstance().getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible()) {
            return; // RecipeBookScreen will use getAndResetScrollY()
        }
        
        InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
        WorkbenchScreen workbenchScreen = Game.getInstance().getWorkbenchScreen();

        // Block hotbar scroll if inventory or workbench screen is open
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            return; // Inventory screen is open, block hotbar selection
        }

        if (workbenchScreen != null && workbenchScreen.isVisible()) {
            return; // Workbench screen is open, block hotbar selection
        }

        // Only allow hotbar scrolling in PLAYING state
        GameState currentState = Game.getInstance().getState();
        if (currentState != GameState.PLAYING) {
            return; // Block hotbar scroll in UI states
        }
        
        int newSelectedIndex = currentSelectedHotbarIndex;
        if (yOffset > 0) { // Scrolled up (conventionally next item)
            newSelectedIndex = (currentSelectedHotbarIndex + 1) % Inventory.HOTBAR_SIZE;
        } else if (yOffset < 0) { // Scrolled down (conventionally previous item)
            newSelectedIndex = (currentSelectedHotbarIndex - 1 + Inventory.HOTBAR_SIZE) % Inventory.HOTBAR_SIZE;
        }
        
        setSelectedHotbarSlot(newSelectedIndex);
        this.scrollYOffset = 0; // Reset scroll after handling hotbar selection
    }

    public double getAndResetScrollY() {
        double offset = this.scrollYOffset;
        this.scrollYOffset = 0.0;
        return offset;
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
     * Update mouse position for UI interactions (called from Main.java cursor callback)
     */
    public void updateMousePosition(float xpos, float ypos) {
        currentMouseX = xpos;
        currentMouseY = ypos;

        // Update UI hover states
        PauseMenu pauseMenu = Game.getInstance().getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible()) {
            UIRenderer uiRenderer = Game.getInstance().getUIRenderer();
            if (uiRenderer != null) {
                pauseMenu.updateHover(currentMouseX, currentMouseY, uiRenderer, Game.getWindowWidth(), Game.getWindowHeight());
            }
        }

        // Update chat renderer hover states
        ChatSystem chatSystem = Game.getInstance().getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            UIRenderer uiRenderer = Game.getInstance().getUIRenderer();
            if (uiRenderer != null && uiRenderer.getChatRenderer() != null) {
                uiRenderer.getChatRenderer().updateMousePosition(currentMouseX, currentMouseY);
            }
        }
    }

    /**
     * Handle chat click interactions (tab switching and command buttons)
     */
    private void handleChatClick(ChatSystem chatSystem) {
        int windowWidth = Game.getWindowWidth();
        int windowHeight = Game.getWindowHeight();

        // Calculate tab button areas (matching ChatRenderer folder-style tabs)
        float backgroundPadding = 10;
        float maxChatWidth = windowWidth * 0.4f;
        float inputBoxHeight = 25;
        float inputBoxMargin = 10;
        float lineHeight = 20;
        float chatAreaHeight = (10 * lineHeight) + inputBoxHeight + inputBoxMargin + (backgroundPadding * 2);

        float backgroundY = windowHeight - chatAreaHeight;
        float backgroundX = 20 - backgroundPadding;

        // Tabs are positioned ABOVE the panel
        float tabHeight = 22;
        float tabSpacing = 2;
        float tabY = backgroundY - tabHeight - tabSpacing;
        float tabWidth = 70; // Compact tab width
        float tabGap = 3; // Gap between tabs
        float startX = backgroundX + 5; // Upper left corner offset

        // Chat tab hitbox
        float chatTabX = startX;
        float chatTabY = tabY;
        float chatTabEndX = chatTabX + tabWidth;
        float chatTabEndY = chatTabY + tabHeight;

        // Commands tab hitbox
        float commandsTabX = startX + tabWidth + tabGap;
        float commandsTabY = tabY;
        float commandsTabEndX = commandsTabX + tabWidth;
        float commandsTabEndY = commandsTabY + tabHeight;

        // Check if clicked on Chat tab
        if (currentMouseX >= chatTabX && currentMouseX <= chatTabEndX &&
            currentMouseY >= chatTabY && currentMouseY <= chatTabEndY) {
            chatSystem.setCurrentTab(ChatSystem.ChatTab.CHAT);
            return;
        }

        // Check if clicked on Commands tab
        if (currentMouseX >= commandsTabX && currentMouseX <= commandsTabEndX &&
            currentMouseY >= commandsTabY && currentMouseY <= commandsTabEndY) {
            chatSystem.setCurrentTab(ChatSystem.ChatTab.COMMANDS);
            return;
        }

        // Check if clicked on a command button (only if Commands tab is active)
        if (chatSystem.getCurrentTab() == ChatSystem.ChatTab.COMMANDS) {
            UIRenderer uiRenderer = Game.getInstance().getUIRenderer();
            if (uiRenderer != null && uiRenderer.getChatRenderer() != null) {
                String clickedCommand = uiRenderer.getChatRenderer().getClickedCommand(
                    chatSystem, currentMouseX, currentMouseY, windowWidth, windowHeight);

                if (clickedCommand != null) {
                    // Populate the chat input with the command instead of executing it
                    chatSystem.setInput("/" + clickedCommand + " ");
                    // Switch back to Chat tab to show the input
                    chatSystem.setCurrentTab(ChatSystem.ChatTab.CHAT);
                }
            }
        }
    }

    // Mouse helper methods for InventoryScreen - now correctly inside the InputHandler class
    public Vector2f getMousePosition() {
        // Reuse cached vector to avoid allocation
        return cachedMousePosition.set(currentMouseX, currentMouseY);
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
    
    /**
     * Handle character input for chat and recipe book search
     */
    public void handleCharacterInput(char character) {
        ChatSystem chatSystem = Game.getInstance().getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            chatSystem.handleCharInput(character);
            return;
        }
        
        // Handle recipe book search input
        RecipeScreen recipeScreen = Game.getInstance().getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible() &&
            Game.getInstance().getState() == GameState.RECIPE_BOOK_UI) {
            recipeScreen.handleCharacterInput(character);
        }
    }
    
    /**
     * Handle keyboard input for chat and recipe book (backspace, enter, etc.)
     */
    public void handleKeyInput(int key, int action) {
        ChatSystem chatSystem = Game.getInstance().getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            // When chat is open, only process chat-related keys
            if (action == GLFW_PRESS || action == GLFW_REPEAT) {
                switch (key) {
                    case GLFW_KEY_BACKSPACE -> chatSystem.handleBackspace();
                    case GLFW_KEY_ENTER -> {
                        chatSystem.handleEnter();
                    }
                    case GLFW_KEY_ESCAPE -> {
                        chatSystem.closeChat();
                    }
                    case GLFW_KEY_V -> {
                        // Handle Ctrl+V for paste
                        if (glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS ||
                            glfwGetKey(window, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS) {
                            chatSystem.handlePaste();
                        }
                    }
                    case GLFW_KEY_C -> {
                        // Handle Ctrl+C for copy
                        if (glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS ||
                            glfwGetKey(window, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS) {
                            chatSystem.handleCopy();
                        }
                    }
                    case GLFW_KEY_T -> {
                        // T key does nothing when chat is already open
                    }
                    case GLFW_KEY_TAB -> {
                        // Handle Tab for command autocomplete
                        chatSystem.handleTab();
                    }
                }
            }
            return; // Block all other key processing when chat is open
        }
        
        // Handle recipe book search input
        RecipeScreen recipeScreen = Game.getInstance().getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible() &&
            Game.getInstance().getState() == GameState.RECIPE_BOOK_UI) {
            if (action == GLFW_PRESS || action == GLFW_REPEAT) {
                recipeScreen.handleKeyInput(key, action);
            }
        }
        
        // If chat and recipe book are not handling input, allow normal input handling to continue
    }

    /**
     * Checks if a key was just pressed in this frame (edge detection).
     * Assumes prepareForNewFrame and updateSpecificKeyState (or a general key polling loop) has been called.
     * @param key The GLFW key code.
     * @return True if the key was pressed down in this frame, false otherwise.
     */
    public boolean isKeyPressedOnce(int key) {
        if (key >= 0 && key < keyJustPressed.length) {
            return keyJustPressed[key];
        }
        return false;
    }
     
    /**
     * Checks if a key is currently held down.
     * @param key The GLFW key code.
     * @return True if the key is currently pressed, false otherwise.
     */
    public boolean isKeyDown(int key) {
        // For general "is down" state, direct GLFW query is fine,
        // or use keyPressedState if already polling.
        return glfwGetKey(window, key) == GLFW_PRESS;
    }

} // This is the final closing brace for the InputHandler class
