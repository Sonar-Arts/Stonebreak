package com.stonebreak;

/**
 * Central class for accessing game state and resources.
 */
public class Game {
    
    // Singleton instance
    private static Game instance;
    
    // Game components
    private World world;
    private Player player;
    private Renderer renderer;
    private PauseMenu pauseMenu;
    private InventoryScreen inventoryScreen; // Added InventoryScreen
    private CraftingTableScreen craftingTableScreen;
    private RecipeBookScreen recipeBookScreen; // Added RecipeBookScreen
    private WaterEffects waterEffects; // Water effects manager
    private InputHandler inputHandler; // Added InputHandler field
    private UIRenderer uiRenderer; // UI renderer for menus
    private MainMenu mainMenu; // Main menu
    
    // Game state
    private GameState currentState = GameState.MAIN_MENU;
    private long lastFrameTime;
    private float deltaTime;
    private float totalTimeElapsed = 0.0f; // Added to track total time for animations
    private boolean paused = false;

    // Window dimensions
    private int windowWidth;
    private int windowHeight;

    // Time tracking for debug info
    private static long lastDebugTime = 0;
    
    /**
     * Private constructor for singleton pattern.
     */
    private Game() {
        lastFrameTime = System.nanoTime();
    }
    
    /**
     * Gets the singleton instance.
     */
    public static Game getInstance() {
        if (instance == null) {
            instance = new Game();
        }
        return instance;
    }
     /**
      * Initializes game components.
      */
     public void init(World world, Player player, Renderer renderer, TextureAtlas textureAtlas, InputHandler inputHandler) {
        this.world = world;
        this.player = player;
        this.renderer = renderer;
        this.inputHandler = inputHandler; // Store InputHandler
        // this.pauseMenu = new PauseMenu(); // Old initialization
        this.waterEffects = new WaterEffects(); // Initialize water effects
        
        // Initialize UI components
        this.uiRenderer = new UIRenderer();
        this.uiRenderer.init();
        this.mainMenu = new MainMenu(this.uiRenderer);
        this.pauseMenu = new PauseMenu(this.uiRenderer); // Initialize PauseMenu with UIRenderer
        
        // Initialize InventoryScreen - assumes Player, Renderer, TextureAtlas, and InputHandler are already initialized
        if (player != null && player.getInventory() != null && renderer != null && renderer.getFont() != null && textureAtlas != null && this.inputHandler != null) {
            this.inventoryScreen = new InventoryScreen(player.getInventory(), renderer.getFont(), renderer, this.inputHandler, RecipeManager.getInstance());
            // Now that inventoryScreen is created, give the inventory a reference to it.
            player.getInventory().setInventoryScreen(this.inventoryScreen);
            // Trigger initial tooltip for the currently selected item
            ItemStack initialSelectedItem = player.getInventory().getHotbarSlot(player.getInventory().getSelectedHotbarSlotIndex());
            if (initialSelectedItem != null && !initialSelectedItem.isEmpty()) {
                inventoryScreen.displayHotbarItemTooltip(BlockType.getById(initialSelectedItem.getBlockTypeId()));
            }

        } else {
            System.err.println("Failed to initialize InventoryScreen due to null components (Player, Inventory, Renderer, Font, TextureAtlas, or InputHandler).");
            // Handle error appropriately, maybe throw an exception or set inventoryScreen to a safe non-functional state
        }

        // Initialize CraftingTableScreen - depends on Inventory, Font, Renderer, InputHandler, RecipeManager
        // RecipeManager would need to be initialized, possibly as a singleton or passed to Game.
        // For now, assuming RecipeManager can be newed up or fetched.
        // We'd also need to pass `this` (Game instance) to CraftingTableScreen.
        if (player != null && player.getInventory() != null && uiRenderer != null && renderer != null && this.inputHandler != null) {
            RecipeManager recipeManager = RecipeManager.getInstance(); // Or get instance if it's a singleton
            this.craftingTableScreen = new CraftingTableScreen(player.getInventory(), uiRenderer, renderer, this.inputHandler, recipeManager);
        } else {
            System.err.println("Failed to initialize CraftingTableScreen due to null components (Player, Inventory, UIRenderer, Renderer, or InputHandler).");
        }

        // Initialize RecipeBookScreen
        if (uiRenderer != null) {
            this.recipeBookScreen = new RecipeBookScreen(uiRenderer);
        } else {
            System.err.println("Failed to initialize RecipeBookScreen due to null UIRenderer.");
        }
    }
    /**
     * Updates the game state.
     */
    public void update() {
        // Calculate delta time
        long currentTime = System.nanoTime();
        deltaTime = (currentTime - lastFrameTime) / 1_000_000_000.0f;
        lastFrameTime = currentTime;
        
        // Limit delta time to prevent issues with large jumps
        if (deltaTime > 0.1f) {
            deltaTime = 0.1f;
        }
        
        // Accumulate total time
        totalTimeElapsed += deltaTime;

        // Only update game world if we're in the playing state
        if (currentState != GameState.PLAYING) {
            return;
        }

        // If game is paused, handle UI updates that must occur even when paused (e.g., timers for inventory screen)
        // and then skip main game logic updates like player and world.
        if (paused) {
            if (inventoryScreen != null && inventoryScreen.isVisible()) {
                inventoryScreen.update(deltaTime);
            }
            // Note: If CraftingTableScreen also had animations/timers that needed to run while paused AND visible,
            // its update method would be called here too. For now, assuming it doesn't.
            return; // Essential: Prevents player, world, etc. updates when paused.
        }

        // If we reach here, game is NOT paused.
        // Update inventory screen (e.g., for general timers, like hotbar item name tooltip that displays even when main panel is closed)
        if (inventoryScreen != null) {
            inventoryScreen.update(deltaTime);
        }
        
        // Update world (processes chunk loading, mesh building, etc.)
        if (world != null) {
            world.update();
        }
        
        // Update player
        if (player != null) {
            player.update();
        }
        
        // Update water effects
        if (waterEffects != null && player != null) {
            waterEffects.update(player, deltaTime);
        }
    }
    
    /**
     * Sets the window dimensions.
     */
    public void setWindowDimensions(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
    }

    /**
     * Gets the window width.
     */
    public static int getWindowWidth() {
        return getInstance().windowWidth;
    }

    /**
     * Gets the window height.
     */
    public static int getWindowHeight() {
        return getInstance().windowHeight;
    }

    /**
     * Gets the time between frames.
     */
    public static float getDeltaTime() {
        return getInstance().deltaTime;
    }

    /**
     * Gets the total time elapsed since the game started.
     * @return Total time elapsed in seconds.
     */
    public float getTotalTimeElapsed() {
        return totalTimeElapsed;
    }
    
    /**
     * Gets the world.
     */
    public static World getWorld() {
        return getInstance().world;
    }
    
    /**
     * Gets the player.
     */
    public static Player getPlayer() {
        return getInstance().player;
    }
    
    /**
     * Gets the renderer.
     */
    public static Renderer getRenderer() {
        return getInstance().renderer;
    }
    
    /**
     * Gets the water effects manager.
     */
    public static WaterEffects getWaterEffects() {
        return getInstance().waterEffects;
    }
    
    /**
     * Toggles the pause menu visibility.
     */
    public void togglePauseMenu() {
        paused = !paused;
        pauseMenu.setVisible(paused);
        
        // Cursor visibility is already handled in InputHandler.handleEscapeKey
    }
    
    /**
     * Checks if the game is paused.
     */
    public boolean isPaused() {
        return paused;
    }
    
    /**
     * Gets the pause menu.
     */
    public PauseMenu getPauseMenu() {
        return pauseMenu;
    }
    /**
     * Gets the inventory screen.
     */
    public InventoryScreen getInventoryScreen() {
        return inventoryScreen;
    }
    
    /**
     * Gets the input handler.
     */
    public InputHandler getInputHandler() {
       return this.inputHandler; // Return the stored instance
   }

   /**
     * Toggles the inventory screen visibility.
     */
    public void toggleInventoryScreen() {
        if (inventoryScreen == null) return;

        inventoryScreen.toggleVisibility();
        // If inventory is open, pause the game and show cursor.
        // If inventory is closed, unpause (if pause menu isn't also up) and hide cursor.
        if (inventoryScreen.isVisible()) {
            paused = true;
            if (inputHandler != null) {
                inputHandler.setCursorVisible(true);
            }
        } else {
            // Only unpause if the pause menu is also not visible
            if (!pauseMenu.isVisible()) {
                paused = false;
                if (inputHandler != null) {
                    inputHandler.setCursorVisible(false);
                }
            }
        }
    }

    /**
     * Gets the crafting table screen.
     */
    public CraftingTableScreen getCraftingTableScreen() {
        return craftingTableScreen;
    }
    
    public RecipeBookScreen getRecipeBookScreen() {
        return recipeBookScreen;
    }

    /**
     * Toggles the crafting table screen visibility.
     */
    public void toggleCraftingTableScreen() {
        if (craftingTableScreen == null) return;

        // If recipe book is open, close it first
        if (recipeBookScreen != null && recipeBookScreen.isVisible()) {
            recipeBookScreen.hide();
        }

        craftingTableScreen.toggleVisibility();
        
        if (craftingTableScreen.isVisible()) {
            paused = true;
            inputHandler.setCursorVisible(true);
        } else {
            boolean otherUiVisible = (pauseMenu != null && pauseMenu.isVisible()) ||
                                     (inventoryScreen != null && inventoryScreen.isVisible());
            if (!otherUiVisible) {
                paused = false;
                inputHandler.setCursorVisible(false);
            }
        }
    }

    /**
     * Toggles the recipe book screen visibility.
     */
    public void toggleRecipeBookScreen() {
        if (recipeBookScreen == null) return;

        // If crafting table is open, close it first
        if (craftingTableScreen != null && craftingTableScreen.isVisible()) {
            // We need a way for CraftingTableScreen to tell Game to close it
            // For now, assume it will be handled by player closing crafting table first
            // or simply close it here.
             craftingTableScreen.setVisible(false); // Force close if open
        }
        if (inventoryScreen != null && inventoryScreen.isVisible()){
            inventoryScreen.setVisible(false); // Force close if open
        }


        recipeBookScreen.toggleVisibility();
        
        if (recipeBookScreen.isVisible()) {
            paused = true;
            inputHandler.setCursorVisible(true);
        } else {
             boolean otherUiVisible = (pauseMenu != null && pauseMenu.isVisible()) ||
                                     (inventoryScreen != null && inventoryScreen.isVisible()) ||
                                     (craftingTableScreen != null && craftingTableScreen.isVisible()); // check crafting table too
            if (!otherUiVisible) {
                paused = false;
                inputHandler.setCursorVisible(false);
            }
        }
    }
    
    /**
     * Gets the current game state.
     */
    public GameState getState() {
        return currentState;
    }
    
    /**
     * Sets the current game state.
     */
    public void setState(GameState state) {
        GameState previousState = this.currentState;
        this.currentState = state;
        
        // Handle cursor visibility based on state transitions
        long windowHandle = Main.getWindowHandle();
        if (windowHandle != 0) {
            if (state == GameState.PLAYING && previousState == GameState.MAIN_MENU) {
                // Hide cursor when entering game
                org.lwjgl.glfw.GLFW.glfwSetInputMode(windowHandle, org.lwjgl.glfw.GLFW.GLFW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED);
            } else if (state == GameState.MAIN_MENU && previousState == GameState.PLAYING) {
                // Show cursor when returning to menu
                org.lwjgl.glfw.GLFW.glfwSetInputMode(windowHandle, org.lwjgl.glfw.GLFW.GLFW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL);
            }
        }
    }
    
    /**
     * Gets the main menu.
     */
    public MainMenu getMainMenu() {
        return mainMenu;
    }
    
    /**
     * Gets the UI renderer.
     */
    public UIRenderer getUIRenderer() {
        return uiRenderer;
    }
    
    /**
     * Cleanup game resources.
     */
    public void cleanup() {
        if (pauseMenu != null) {
            pauseMenu.cleanup();
        }
        if (uiRenderer != null) {
            uiRenderer.cleanup();
        }
    }
    
    /**
     * Displays debug information about the game state.
     * This includes memory usage and chunk counts.
     */
    public static void displayDebugInfo() {
        // Only update every few seconds to avoid console spam
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDebugTime < 5000) {
            return;
        }
        lastDebugTime = currentTime;
        
        // Get memory information
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        
        // Get world information
        int chunkCount = 0;
        if (getInstance().world != null) {
            chunkCount = getInstance().world.getLoadedChunkCount();
        }
        
        // Get player position
        String playerPos = "Unknown";
        if (getInstance().player != null) {
            org.joml.Vector3f pos = getInstance().player.getPosition();
            playerPos = String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z);
        }
        
        // Display information
        System.out.println("----- DEBUG INFO -----");
        System.out.println("Memory: " + usedMemory + "MB / " + totalMemory + "MB");
        System.out.println("Chunks loaded: " + chunkCount);
        System.out.println("Player position: " + playerPos);
        System.out.println("FPS: " + Math.round(1.0f / getInstance().deltaTime));
        System.out.println("---------------------");
    }
}
