package com.stonebreak;

/**
 * Central class for accessing game state and resources.
 */
public class Game {
    
    // Singleton instance
    private static Game instance;    // Game components
    private World world;
    private Player player;
    private Renderer renderer;
    private PauseMenu pauseMenu;
    private InventoryScreen inventoryScreen; // Added InventoryScreen
    private WaterEffects waterEffects; // Water effects manager
    private InputHandler inputHandler; // Added InputHandler field
    
    // Game state
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
      /**     * Initializes game components.
      */
     public void init(World world, Player player, Renderer renderer, TextureAtlas textureAtlas, InputHandler inputHandler) {
        this.world = world;
        this.player = player;
        this.renderer = renderer;
        this.inputHandler = inputHandler; // Store InputHandler
        this.pauseMenu = new PauseMenu();
        this.waterEffects = new WaterEffects(); // Initialize water effects
        
        // Initialize InventoryScreen - assumes Player, Renderer, TextureAtlas, and InputHandler are already initialized
        if (player != null && player.getInventory() != null && renderer != null && renderer.getFont() != null && textureAtlas != null && this.inputHandler != null) {
            this.inventoryScreen = new InventoryScreen(player.getInventory(), renderer.getFont(), renderer, this.inputHandler);
        } else {
            System.err.println("Failed to initialize InventoryScreen due to null components (Player, Inventory, Renderer, Font, TextureAtlas, or InputHandler).");
            // Handle error appropriately, maybe throw an exception or set inventoryScreen to a safe non-functional state
        }
    }/**
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

        // If game is paused, don't update game entities
        if (paused) {
            return;
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
    }    /**
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
            // Show cursor - this should ideally be handled by a central cursor management in InputHandler or Main
            // For now, we assume Main or InputHandler will react to isPaused() or a new state like isUiVisible()
            org.lwjgl.glfw.GLFW.glfwSetInputMode(Main.getWindowHandle(), org.lwjgl.glfw.GLFW.GLFW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL);
        } else {
            // Only unpause if the pause menu is also not visible
            if (!pauseMenu.isVisible()) {
                paused = false;
                // Hide cursor
                org.lwjgl.glfw.GLFW.glfwSetInputMode(Main.getWindowHandle(), org.lwjgl.glfw.GLFW.GLFW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED);
            }
        }
    }
    
    /**
     * Cleanup game resources.
     */
    public void cleanup() {
        if (pauseMenu != null) {
            pauseMenu.cleanup();
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
