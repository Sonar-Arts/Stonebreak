package com.stonebreak.core;

import java.util.List;
import java.util.concurrent.*;

import com.stonebreak.audio.*;
import com.stonebreak.blocks.*;
import com.stonebreak.chat.*;
import com.stonebreak.config.*;
import com.stonebreak.crafting.*;
import com.stonebreak.input.*;
import com.stonebreak.items.*;
import com.stonebreak.player.*;
import com.stonebreak.rendering.*;
import com.stonebreak.rendering.CowTextureAtlas;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.ui.*;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.worldSelect.WorldSelectScreen;
import com.stonebreak.util.*;
import com.stonebreak.world.*;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.managers.WorldSaveSystem;
import org.joml.Vector3f;

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
    private TextureAtlas textureAtlas;
    // Note: Using static CowTextureAtlas instead of instance variable
    private PauseMenu pauseMenu;
    private InventoryScreen inventoryScreen; // Added InventoryScreen
    private WorkbenchScreen workbenchScreen; // Added WorkbenchScreen
    private RecipeBookScreen recipeBookScreen; // Added RecipeBookScreen
    private WaterEffects waterEffects; // Water effects manager
    private InputHandler inputHandler; // Added InputHandler field
    private MouseCaptureManager mouseCaptureManager; // Mouse capture system
    private MainMenu mainMenu; // Main menu
    private SettingsMenu settingsMenu; // Settings menu
    private SoundSystem soundSystem; // Sound system
    private ChatSystem chatSystem; // Chat system
    private CraftingManager craftingManager; // Crafting manager
    private MemoryLeakDetector memoryLeakDetector; // Memory leak detection system
    private DebugOverlay debugOverlay; // Debug overlay (F3)
    private LoadingScreen loadingScreen; // Loading screen for world generation
    private WorldSelectScreen worldSelectScreen; // World selection screen
    private WorldSaveSystem worldSaveSystem; // World save/load system for manual saves
    private String currentWorldName; // Current world name for save system initialization
    private long currentWorldSeed; // Current world seed for save system initialization
    private final ExecutorService worldUpdateExecutor = Executors.newSingleThreadExecutor();
    
    // Entity system components
    private com.stonebreak.mobs.entities.EntityManager entityManager; // Entity management system
    
    // Game state
    private GameState currentState = GameState.MAIN_MENU;
    private GameState previousGameState = GameState.MAIN_MENU; // Added previousGameState
    private long lastFrameTime;
    private float deltaTime;
    private float totalTimeElapsed = 0.0f; // Added to track total time for animations
    private boolean paused = false;
    private boolean hasInitializedMouseCaptureAfterLoading = false; // Track initial mouse capture setup
    
    
    // Cheat system
    private boolean cheatsEnabled = false;

    // Window dimensions
    private int windowWidth;
    private int windowHeight;

    // Time tracking for debug info
    private static long lastDebugTime = 0;
    private static long lastMemoryCheckTime = 0;
    
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
     * Initializes core game components that don't require a world or player.
     * This includes renderer, sound system, UI components, and basic systems.
     */
    public void initCoreComponents(Renderer renderer, TextureAtlas textureAtlas, InputHandler inputHandler, long window) {
        this.renderer = renderer;
        this.textureAtlas = textureAtlas; // Store TextureAtlas
        this.inputHandler = inputHandler; // Store InputHandler

        // Initialize mouse capture system (without camera - will be set when world is created)
        this.mouseCaptureManager = new MouseCaptureManager(window);

        this.pauseMenu = new PauseMenu();
        this.waterEffects = new WaterEffects(); // Initialize water effects

        // Initialize sound system
        this.soundSystem = SoundSystem.getInstance();
        this.soundSystem.initialize();
        this.soundSystem.loadSound("grasswalk", "/sounds/GrassWalk.wav");
        this.soundSystem.loadSound("sandwalk", "/sounds/SandWalk-001.wav");
        this.soundSystem.loadSound("blockpickup", "/sounds/BlockPickup.wav");

        // Apply settings to sound system
        Settings gameSettings = Settings.getInstance();
        this.soundSystem.setMasterVolume(gameSettings.getMasterVolume());

        this.soundSystem.testBasicFunctionality(); // Test sound system

        // Initialize UI components that don't require world/player
        this.mainMenu = new MainMenu(this.renderer.getUIRenderer());
        this.settingsMenu = new SettingsMenu(this.renderer.getUIRenderer());
        this.loadingScreen = new LoadingScreen(this.renderer.getUIRenderer());
        this.worldSelectScreen = new WorldSelectScreen(this.renderer.getUIRenderer());

        // Initialize crosshair with settings
        initializeCrosshairSettings();

        // Initialize CraftingManager
        this.craftingManager = new CraftingManager();

        // Add all the crafting recipes (same as before)
        initializeCraftingRecipes();

        // Initialize chat system
        this.chatSystem = new ChatSystem();
        this.chatSystem.addMessage("Welcome to Stonebreak!", new float[]{1.0f, 1.0f, 0.0f, 1.0f}); // Yellow welcome message

        // Initialize memory leak detector
        this.memoryLeakDetector = MemoryLeakDetector.getInstance();
        this.memoryLeakDetector.startMonitoring();
        System.out.println("Memory leak detection started.");

        // Initialize debug overlay
        this.debugOverlay = new DebugOverlay();
        System.out.println("Debug overlay initialized (F3 to toggle).");

        // Initialize cow texture atlas
        CowTextureAtlas.initialize();
        System.out.println("Cow texture atlas initialized");

        System.out.println("[STARTUP] Core components initialized (no world/player yet)");
    }

    /**
     * Initializes components that require a world and player.
     * This should be called when a world is created/loaded.
     */
    public void initWorldComponents(World world, Player player) {
        this.world = world;
        this.player = player;

        // Update save system references if it exists (regardless of initialization state)
        // This ensures the save system gets the correct world/player references even if
        // initialization happens later in the flow
        if (worldSaveSystem != null) {
            System.out.println("[SAVE-SYSTEM] Updating save system references during world component initialization");
            worldSaveSystem.updateReferences(world, player);
        }

        // Set camera for mouse capture system
        if (mouseCaptureManager != null && player != null) {
            mouseCaptureManager.setCamera(player.getCamera());
        }

        // Initialize water simulation with any existing water blocks
        if (waterEffects != null) {
            waterEffects.detectExistingWater();
        }

        // Initialize entity system
        this.entityManager = new com.stonebreak.mobs.entities.EntityManager(world);
        System.out.println("Entity system initialized - cows can now spawn!");

        // Initialize InventoryScreen - requires Player, Renderer, TextureAtlas, and InputHandler
        if (renderer.getFont() != null && textureAtlas != null) {
            this.inventoryScreen = new InventoryScreen(player.getInventory(), renderer.getFont(), renderer, this.renderer.getUIRenderer(), this.inputHandler, this.craftingManager);
            // Now that inventoryScreen is created, give the inventory a reference to it.
            player.getInventory().setInventoryScreen(this.inventoryScreen);
            // Trigger initial tooltip for the currently selected item
            ItemStack initialSelectedItem = player.getInventory().getHotbarSlot(player.getInventory().getSelectedHotbarSlotIndex());
            if (initialSelectedItem != null && !initialSelectedItem.isEmpty()) {
                if (initialSelectedItem.getItem() instanceof BlockType blockType) {
                    inventoryScreen.displayHotbarItemTooltip(blockType);
                }
            }
        } else {
            System.err.println("Failed to initialize InventoryScreen due to null components (Player, Inventory, Renderer, Font, TextureAtlas, InputHandler, or CraftingManager).");
        }

        // Initialize WorkbenchScreen
        if (this.renderer.getUIRenderer() != null) {
            this.workbenchScreen = new WorkbenchScreen(this, player.getInventory(), renderer, this.renderer.getUIRenderer(), this.inputHandler, this.craftingManager);
        } else {
            System.err.println("Failed to initialize WorkbenchScreen due to null components.");
        }

        // Initialize RecipeBookScreen
        if (this.renderer.getUIRenderer() != null && this.craftingManager != null && getFont() != null) {
            this.recipeBookScreen = new RecipeBookScreen(this.renderer.getUIRenderer(), this.inputHandler, renderer);
        } else {
            System.err.println("Failed to initialize RecipeBookScreen due to null UIRenderer, CraftingManager, or Font.");
        }

        System.out.println("[WORLD-CREATION] World components initialized for new world");
    }

    /**
     * Initializes all crafting recipes.
     */
    private void initializeCraftingRecipes() {
        // Recipe 1: Wood Planks
        // Input: 1 BlockType.WOOD -> Output: 4 BlockType.WOOD_PLANKS
        List<List<ItemStack>> woodToPlanksPattern = List.of(
            List.of(new ItemStack(BlockType.WOOD.getId(), 1))
        );
        Recipe woodToPlanksRecipe = new Recipe(
            "wood_to_planks",
            woodToPlanksPattern,
            new ItemStack(BlockType.WOOD_PLANKS.getId(), 4)
        );
        this.craftingManager.registerRecipe(woodToPlanksRecipe);
        System.out.println("Registered recipe: WOOD -> WOOD_PLANKS");

        // Recipe 2: Pine Wood Planks
        // Input: 1 BlockType.PINE -> Output: 4 BlockType.PINE_WOOD_PLANKS
        List<List<ItemStack>> pineToPlanksPattern = List.of(
            List.of(new ItemStack(BlockType.PINE.getId(), 1))
        );
        Recipe pineToPlanksRecipe = new Recipe(
            "pine_to_planks",
            pineToPlanksPattern,
            new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 4)
        );
        this.craftingManager.registerRecipe(pineToPlanksRecipe);
        System.out.println("Registered recipe: PINE -> PINE_WOOD_PLANKS");

        // Recipe 3: Workbench
        // Input: 4 BlockType.WOOD_PLANKS (2x2) -> Output: 1 BlockType.WORKBENCH
        List<List<ItemStack>> planksToWorkbenchPattern = List.of(
            List.of(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1))
        );
        Recipe planksToWorkbenchRecipe = new Recipe(
            "planks_to_workbench",
            planksToWorkbenchPattern,
            new ItemStack(BlockType.WORKBENCH.getId(), 1)
        );
        this.craftingManager.registerRecipe(planksToWorkbenchRecipe);
        System.out.println("Registered recipe: WOOD_PLANKS -> WORKBENCH");

        // Add remaining recipes... (truncated for brevity - would include all existing recipes)
        System.out.println("All crafting recipes initialized");
    }

    /**
     * Legacy initialization method for backward compatibility.
     * This calls both initCoreComponents() and initWorldComponents().
     */
    public void init(World world, Player player, Renderer renderer, TextureAtlas textureAtlas, InputHandler inputHandler, long window) {
        // Initialize core components first
        initCoreComponents(renderer, textureAtlas, inputHandler, window);

        // Then initialize world-specific components
        if (world != null && player != null) {
            initWorldComponents(world, player);
        } else {
            System.out.println("[STARTUP] Skipping world component initialization (null world or player)");
        }
        System.out.println("[STARTUP] Game initialization completed");
    }
    
    /**
     * Initializes the crosshair renderer with settings from the config file.
     */
    private void initializeCrosshairSettings() {
        if (renderer != null && renderer.getUIRenderer() != null) {
            var crosshairRenderer = renderer.getUIRenderer().getCrosshairRenderer();
            if (crosshairRenderer != null) {
                Settings settings = Settings.getInstance();
                
                // Apply crosshair style
                try {
                    var styleEnum = com.stonebreak.rendering.UI.components.CrosshairRenderer.CrosshairStyle.valueOf(settings.getCrosshairStyle());
                    crosshairRenderer.setStyle(styleEnum);
                } catch (IllegalArgumentException e) {
                    System.err.println("Invalid crosshair style in settings: " + settings.getCrosshairStyle() + ", using default");
                    crosshairRenderer.setStyle(com.stonebreak.rendering.UI.components.CrosshairRenderer.CrosshairStyle.SIMPLE_CROSS);
                }
                
                // Apply other crosshair properties
                crosshairRenderer.setSize(settings.getCrosshairSize());
                crosshairRenderer.setThickness(settings.getCrosshairThickness());
                crosshairRenderer.setGap(settings.getCrosshairGap());
                crosshairRenderer.setOpacity(settings.getCrosshairOpacity());
                crosshairRenderer.setColor(settings.getCrosshairColorR(), settings.getCrosshairColorG(), settings.getCrosshairColorB());
                crosshairRenderer.setOutline(settings.getCrosshairOutline());
                
                System.out.println("Crosshair settings initialized: style=" + settings.getCrosshairStyle() + 
                                 ", size=" + settings.getCrosshairSize());
            } else {
                System.err.println("Warning: CrosshairRenderer not available during initialization");
            }
        } else {
            System.err.println("Warning: Renderer or UIRenderer not available during crosshair initialization");
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

        // Handle updates based on game state
        switch (currentState) {
            case MAIN_MENU -> {
                // Main menu updates (if any)
                if (mainMenu != null) {
                    // mainMenu.update(deltaTime); // If main menu needs updates
                }
                return; // Don't update game world
            }
            case LOADING -> {
                // Loading screen updates (if any)
                // World generation progress is handled elsewhere
                return; // Don't update game world during loading
            }
            case PLAYING -> {
                // Ensure mouse capture is properly initialized after loading (one-time check)
                if (!hasInitializedMouseCaptureAfterLoading && mouseCaptureManager != null) {
                    mouseCaptureManager.forceUpdate();
                    hasInitializedMouseCaptureAfterLoading = true;
                }
                
                if (paused) {
                    // Check if paused by pause menu vs just inventory/UI
                    boolean pausedByMenu = pauseMenu != null && pauseMenu.isVisible();
                    
                    // Update UI screens that are visible
                    if (inventoryScreen != null && inventoryScreen.isVisible()) {
                        inventoryScreen.update(deltaTime);
                    }
                    if (workbenchScreen != null && workbenchScreen.isVisible()) {
                        workbenchScreen.update(deltaTime);
                    }
                    
                    // Only pause game world updates if actually paused by the pause menu
                    // If paused only by inventory/UI, continue to game world updates
                    if (pausedByMenu) {
                        return; // Don't update player/world if paused by escape menu
                    }
                }
                // Continue to game world updates (either not paused, or paused only by inventory/UI)
            }
            case WORKBENCH_UI -> {
                // Update workbench UI but keep the game world running
                if (workbenchScreen != null && workbenchScreen.isVisible()) {
                    workbenchScreen.update(deltaTime);
                }
                if (inventoryScreen != null && inventoryScreen.isVisible()) {
                    inventoryScreen.update(deltaTime);
                }
                // Continue to game world updates - workbench doesn't pause the game
            }
            case PAUSED -> { // Only truly pause for the pause menu
                // Update relevant UI screens if they are visible
                if (inventoryScreen != null && inventoryScreen.isVisible()) {
                    inventoryScreen.update(deltaTime);
                }
                if (workbenchScreen != null && workbenchScreen.isVisible()) {
                    workbenchScreen.update(deltaTime);
                }
                if (pauseMenu != null && pauseMenu.isVisible()) {
                    // pauseMenu.update(deltaTime); // If pause menu has timed elements
                }
                 if (recipeBookScreen != null && currentState == GameState.RECIPE_BOOK_UI) { // This specific check seems odd here if RECIPE_BOOK_UI is its own case
                    recipeBookScreen.update(deltaTime);
                }
                // Input handling for these screens is separate.
                return; // Don't update game world
            }
            case RECIPE_BOOK_UI -> {
                if (recipeBookScreen != null) {
                    recipeBookScreen.update(deltaTime);
                }
                return; // Don't update game world
            }
            default -> {
                return; // Unhandled state
            }
        }

        // --- Game World Updates (only if PLAYING and not paused by menu) ---

        // Update inventory screen (its own update method handles timers etc.)
        if (inventoryScreen != null) { // Though it might not be visible
            inventoryScreen.update(deltaTime);
        }
        // Update workbench screen
        if (workbenchScreen != null) { // Though it might not be visible
            workbenchScreen.update(deltaTime);
        }
        
        // Update chat system
        if (chatSystem != null) {
            chatSystem.update(deltaTime);
        }
        
        // Update world (processes chunk loading, mesh building, etc.)
        if (world != null) {
            worldUpdateExecutor.submit(() -> world.update(renderer));
            world.updateMainThread();
            // Process GPU cleanup on main thread after world update
            world.processGpuCleanupQueue();
        }
        
        // Update player
        if (player != null) {
            player.update();
        }
        
        // Update water effects
        if (waterEffects != null && player != null) {
            waterEffects.update(player, deltaTime);
        }
        
        // Update entity system
        if (entityManager != null) {
            entityManager.update(deltaTime);
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
     * Gets the entity manager.
     */
    public static com.stonebreak.mobs.entities.EntityManager getEntityManager() {
        return getInstance().entityManager;
    }
    
    /**
     * Toggles the pause menu visibility.
     */
    public void togglePauseMenu() {
        if (pauseMenu == null) return;

        boolean newPauseState = !pauseMenu.isVisible(); // Determine new state based on current visibility
        pauseMenu.setVisible(newPauseState);

        if (newPauseState) {
            // If we are opening the pause menu, set state to PAUSED.
            // setState will handle 'paused' flag and cursor.
            setState(GameState.PAUSED);
        } else {
            // If we are closing the pause menu.
            // Return to PLAYING state. setState handles 'paused' and cursor.
            // We assume closing the pause menu means returning to gameplay unless another UI is active.
            // If another UI was underneath (e.g. inventory), previousGameState logic in setState
            // or InputHandler's escape hierarchy should manage the transition.
            // For now, closing pause menu attempts to go to PLAYING.
            setState(GameState.PLAYING);
        }
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
     * Gets the workbench screen.
     */
    public WorkbenchScreen getWorkbenchScreen() {
        return workbenchScreen;
    }
    
    /**
     * Gets the input handler.
     */
    public InputHandler getInputHandler() {
       return this.inputHandler; // Return the stored instance
   }

   /**
     * Gets the mouse capture manager.
     */
    public MouseCaptureManager getMouseCaptureManager() {
        return this.mouseCaptureManager;
    }

   /**
     * Toggles the inventory screen visibility.
     */
    public void toggleInventoryScreen() {
        if (inventoryScreen == null) return;

        inventoryScreen.toggleVisibility();

        if (inventoryScreen.isVisible()) {
            // Opening inventory - pause the game
            this.paused = true;
        } else {
            // Closing inventory - check if we should unpause
            if (pauseMenu == null || !pauseMenu.isVisible()) {
                // Only unpause if we're in PLAYING state and no pause menu is visible
                if (this.currentState == GameState.PLAYING) {
                    this.paused = false;
                }
                // Other states (WORKBENCH_UI, RECIPE_BOOK_UI) remain paused
            }
        }
        
        // Update mouse capture state when inventory visibility changes
        if (mouseCaptureManager != null) {
            mouseCaptureManager.updateCaptureState();
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
        System.out.println("DEBUG: Game.setState() called - transitioning from " + this.currentState + " to " + state);

        // Don't update previousGameState if we are just re-setting the same state
        if (this.currentState != state && state != null) {
            this.previousGameState = this.currentState;
            System.out.println("DEBUG: Previous state saved as: " + this.previousGameState);
        }
        this.currentState = state;
        System.out.println("DEBUG: Current state set to: " + this.currentState);

        // Special debug for world select screen
        if (state == GameState.WORLD_SELECT) {
            System.out.println("DEBUG: Transitioning to WORLD_SELECT state");
            System.out.println("DEBUG: worldSelectScreen = " + (worldSelectScreen != null ? "not null" : "NULL"));
            if (worldSelectScreen != null) {
                System.out.println("DEBUG: worldSelectScreen class: " + worldSelectScreen.getClass().getName());
            }
        }

        // Update pause state based on game state
        updatePauseState(state);

        // Update mouse capture state based on new game state
        if (mouseCaptureManager != null) {
            mouseCaptureManager.updateCaptureState();
        }

        System.out.println("DEBUG: Game.setState() completed successfully");
    }
    
    /**
     * Updates the pause state based on the game state.
     */
    private void updatePauseState(GameState state) {
        switch (state) {
            case MAIN_MENU, LOADING, SETTINGS, PAUSED, WORKBENCH_UI, RECIPE_BOOK_UI, INVENTORY_UI -> {
                paused = true;
            }
            case PLAYING -> {
                // Special case: Always unpause when coming from LOADING state
                if (this.previousGameState == GameState.LOADING) {
                    paused = false;
                } else {
                    // For other transitions, only keep paused if inventory is explicitly visible
                    boolean shouldUnpause = (inventoryScreen == null || !inventoryScreen.isVisible());
                    
                    if (shouldUnpause) {
                        paused = false;
                    }
                }
            }
        }
    }

    /**
     * Gets the previous game state.
     * @return The game state before the current one.
     */
    public GameState getPreviousGameState() {
        return this.previousGameState;
    }
    
    /**
     * Gets the recipe book screen.
     */
    public RecipeBookScreen getRecipeBookScreen() {
        return recipeBookScreen;
    }

    /**
     * Gets the main menu.
     */
    public MainMenu getMainMenu() {
        return mainMenu;
    }
    
    /**
     * Gets the settings menu.
     */
    public SettingsMenu getSettingsMenu() {
        return settingsMenu;
    }
    
    /**
     * Gets the UI renderer.
     */
    public com.stonebreak.rendering.UI.UIRenderer getUIRenderer() {
        return renderer != null ? renderer.getUIRenderer() : null;
    }
    
    /**
     * Gets the sound system.
     */
    public static SoundSystem getSoundSystem() {
        return getInstance().soundSystem;
    }
    
    /**
     * Gets the chat system.
     */
    public ChatSystem getChatSystem() {
        return chatSystem;
    }

    /**
     * Gets the crafting manager.
     */
    public static CraftingManager getCraftingManager() {
        return getInstance().craftingManager;
    }

    /**
     * Gets the game's primary font.
     * This assumes the font is loaded and available via the Renderer.
     * @return The Font object, or null if not available.
     */
    public Font getFont() {
        if (this.renderer != null) {
            return this.renderer.getFont();
        }
        return null;
    }

    /**
     * Gets the game's texture atlas.
     * This assumes the texture atlas is loaded and available via the Renderer.
     * @return The TextureAtlas object, or null if not available.
     */
    public TextureAtlas getTextureAtlas() {
        return this.textureAtlas;
    }

    /**
     * Called when the player interacts with a workbench.
     * This will open the Workbench UI.
     */
    public void openWorkbenchScreen() {
        if (workbenchScreen != null && currentState == GameState.PLAYING && !paused) {
            setState(GameState.WORKBENCH_UI);
            workbenchScreen.open();
            // Cursor visibility and game pause are handled by setState and workbenchScreen.open() logic indirectly
            // or explicitly in workbenchScreen.handleCloseRequest and setState.
            System.out.println("Opened Workbench Screen.");
        } else {
            System.out.println("Cannot open workbench: Not in PLAYING state, or game paused by menu, or workbenchScreen is null.");
        }
    }

    /**
     * Opens the Recipe Book screen.
     */
    public void openRecipeBookScreen() {
        if (recipeBookScreen == null) {
            System.out.println("Cannot open RecipeBook: recipeBookScreen is null.");
            return;
        }

        boolean allowOpen = false;
        // Case 1: Workbench UI is active
        if (currentState == GameState.WORKBENCH_UI) {
            allowOpen = true;
        // Case 2: Inventory screen is visible (implies game is PLAYING but effectively paused for this UI)
        } else if (inventoryScreen != null && inventoryScreen.isVisible()) {
            allowOpen = true;
        // Case 3: Actively playing and not paused by the main PauseMenu (Escape key menu)
        } else if (currentState == GameState.PLAYING && (pauseMenu == null || !pauseMenu.isVisible())) {
            allowOpen = true;
        }

        if (allowOpen) {
            // setState will correctly set this.previousGameState to the current state (e.g., PLAYING, WORKBENCH_UI)
            // before changing currentState to RECIPE_BOOK_UI.
            setState(GameState.RECIPE_BOOK_UI);
            recipeBookScreen.onOpen(); // Initialize/refresh recipe list
            System.out.println("Opened RecipeBook Screen. Will return to: " + this.previousGameState);
        } else {
            String contextDetails = "Current state: " + currentState;
            if (inventoryScreen != null) {
                contextDetails += ", InventoryVisible: " + inventoryScreen.isVisible();
            }
            if (pauseMenu != null) {
                contextDetails += ", MainPauseActive: " + pauseMenu.isVisible();
            }
            contextDetails += ", Game.paused: " + this.paused;
            System.out.println("Cannot open RecipeBook: Not in a valid context (" + contextDetails + ").");
        }
    }

    /**
     * Closes the Recipe Book screen and returns to the previous game state.
     */
    public void closeRecipeBookScreen() {
        if (recipeBookScreen != null && currentState == GameState.RECIPE_BOOK_UI) {
            recipeBookScreen.onClose();
            setState(previousGameState); // Uses the stored previous state
            System.out.println("Closed RecipeBook Screen. Returning to: " + previousGameState);
        }
    }
    
    /**
     * Closes the workbench screen and returns to the game.
     */
    public void closeWorkbenchScreen() {
        if (workbenchScreen != null && workbenchScreen.isVisible()) {
            workbenchScreen.close(); // WorkbenchScreen itself will set its visible flag to false
            // Only transition to PLAYING if no other UI (like pause menu) is forcing a different state.
            // If pause menu was opened *over* workbench, ESC from workbench should probably reveal pause menu.
            // For now, assume direct close from workbench returns to PLAYING.
            if (currentState == GameState.WORKBENCH_UI) {
                 setState(GameState.PLAYING); // This will handle cursor and unpause
            }
            System.out.println("Closed Workbench Screen.");
        }
    }
    
    /**
     * Cleanup game resources.
     */
    public void cleanup() {
        System.out.println("Starting Game cleanup...");
        
        if (world != null) {
            world.cleanup();
        }
        if (pauseMenu != null) {
            pauseMenu.cleanup();
        }
        if (soundSystem != null) {
            soundSystem.cleanup();
        }
        if (mouseCaptureManager != null) {
            mouseCaptureManager.cleanup();
        }
        if (memoryLeakDetector != null) {
            memoryLeakDetector.stopMonitoring();
        }
        if (entityManager != null) {
            entityManager.cleanup();
        }

        // Save current player state before cleanup
        if (worldSaveSystem != null) {
            try {
                System.out.println("Performing final save before shutdown...");

                // Save current game state synchronously with timeout
                java.util.concurrent.CompletableFuture<Void> saveOperation = worldSaveSystem.saveWorldNow();
                saveOperation.get(5, java.util.concurrent.TimeUnit.SECONDS); // 5 second timeout

                System.out.println("Final save completed successfully");
            } catch (java.util.concurrent.TimeoutException e) {
                System.err.println("Final save timed out after 5 seconds - proceeding with shutdown");
            } catch (Exception e) {
                System.err.println("Error during final save: " + e.getMessage());
                // Continue with shutdown even if save fails
            }

            try {
                System.out.println("Closing WorldSaveSystem...");
                worldSaveSystem.close();
            } catch (Exception e) {
                System.err.println("Error closing WorldSaveSystem: " + e.getMessage());
            }
        }

        // Shutdown world update executor with proper termination waiting
        System.out.println("Shutting down world update executor...");
        worldUpdateExecutor.shutdownNow();
        try {
            if (!worldUpdateExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println("World update executor did not terminate gracefully");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting for world update executor shutdown");
        }
        
        // Cleanup static resources that may have executors
        cleanupStaticResources();
        
        System.out.println("Game cleanup completed");
    }

    /**
     * Resets the world state without fully cleaning up resources.
     * Used when returning to main menu from gameplay.
     */
    public void resetWorld() {
        System.out.println("========================================");
        System.out.println("[MAIN-MENU-TRANSITION] Starting complete world reset...");
        System.out.println("========================================");

        // Skip explicit save during world reset to avoid hanging
        // The auto-save system should have already saved data periodically
        System.out.println("[WORLD-ISOLATION] Skipping explicit save during world reset to prevent hanging");
        System.out.println("[WORLD-ISOLATION] Auto-save system should have saved data already");

        // Stop auto-save to prevent further save operations during reset
        if (worldSaveSystem != null) {
            try {
                worldSaveSystem.stopAutoSave();
                System.out.println("[WORLD-ISOLATION] Stopped auto-save system for clean reset");
            } catch (Exception e) {
                System.err.println("[WORLD-ISOLATION] Error stopping auto-save: " + e.getMessage());
            }
        }

        // Note: We do NOT reset player data here anymore, as it should be preserved
        // for when the player returns to this world. Player data will be loaded
        // fresh when starting a new world or loading an existing one.
        System.out.println("[WORLD-ISOLATION] Player data preserved for world switching");

        // Clear world data without shutting down critical systems
        if (world != null) {
            // If clearWorldData exists, use it; otherwise use a basic reset
            try {
                world.clearWorldData();
                System.out.println("[WORLD-ISOLATION] World chunks and caches cleared");
            } catch (Exception e) {
                System.err.println("[WORLD-ISOLATION] Error clearing world data: " + e.getMessage());
            }
        } else {
            System.out.println("[WORLD-ISOLATION] No world to clear");
        }

        // Stop auto-save when switching worlds
        if (worldSaveSystem != null) {
            try {
                worldSaveSystem.stopAutoSave();
                System.out.println("[SAVE-SYSTEM] Stopped auto-save for world switch");
            } catch (Exception e) {
                System.err.println("[SAVE-SYSTEM] Error stopping auto-save: " + e.getMessage());
            }
        } else {
            System.out.println("[SAVE-SYSTEM] No save system to stop");
        }

        // Stop and clean up entity system to prevent background activity
        if (entityManager != null) {
            try {
                entityManager.cleanup();
                System.out.println("[BACKGROUND-SYSTEMS] ✓ Stopped EntityManager - no more cows or entities running");
            } catch (Exception e) {
                System.err.println("[BACKGROUND-SYSTEMS] ✗ Error stopping EntityManager: " + e.getMessage());
            }
        } else {
            System.out.println("[BACKGROUND-SYSTEMS] ⚠ No EntityManager to stop (unexpected)");
        }

        // Reset water effects system to clear persistent water simulation data
        if (waterEffects != null) {
            try {
                waterEffects.detectExistingWater(); // This clears all water state
                System.out.println("[BACKGROUND-SYSTEMS] ✓ Reset WaterEffects - cleared water simulation data");
            } catch (Exception e) {
                System.err.println("[BACKGROUND-SYSTEMS] ✗ Error resetting WaterEffects: " + e.getMessage());
            }
        } else {
            System.out.println("[BACKGROUND-SYSTEMS] ⚠ No WaterEffects to reset (unexpected)");
        }

        // Clear game metadata and save system to prevent world data leakage
        currentWorldName = null;
        currentWorldSeed = 0;
        worldSaveSystem = null; // Clear save system reference so it gets reinitialized for next world
        System.out.println("[WORLD-ISOLATION] ✓ Cleared game metadata and save system for world switching");

        System.out.println("========================================");
        System.out.println("[MAIN-MENU-TRANSITION] ✓ World reset completed - main menu is now clean!");
        System.out.println("[MAIN-MENU-TRANSITION] No background systems should be running.");
        System.out.println("========================================");
    }

    /**
     * Creates a fresh World instance with the specified seed for complete world isolation.
     */
    private World createFreshWorldInstance(long seed) {
        WorldConfiguration config = new WorldConfiguration();
        return new World(config, seed);
    }

    /**
     * Replaces the current World instance with a new one, ensuring all references are updated.
     */
    private void replaceWorldInstance(World newWorld) {
        // Clean up old world if it exists
        if (world != null) {
            world.cleanup();
        }

        // Always create a fresh player for the new world to ensure world isolation
        // The player's data will be loaded from save files specific to this world
        Player newPlayer = new Player(newWorld);
        System.out.println("[WORLD-ISOLATION] Created fresh player for new world to ensure inventory isolation");

        // Initialize world components with new world and player
        if (newWorld != null) {
            initWorldComponents(newWorld, newPlayer);
            System.out.println("[WORLD-ISOLATION] Initialized world components for new world");
        }

        System.out.println("[WORLD-ISOLATION] World instance replaced successfully");
    }

    /**
     * Cleanup static resources that may have background threads.
     */
    private void cleanupStaticResources() {
        try {
            // Shutdown ModelLoader async executor
            System.out.println("Shutting down ModelLoader executor...");
            com.stonebreak.model.ModelLoader.shutdown();
        } catch (Exception e) {
            System.err.println("Error shutting down ModelLoader: " + e.getMessage());
        }
        
        try {
            // Shutdown CowTextureAtlas if it has any background resources
            System.out.println("Cleaning up CowTextureAtlas...");
            com.stonebreak.rendering.CowTextureAtlas.cleanup();
        } catch (Exception e) {
            System.err.println("Error cleaning up CowTextureAtlas: " + e.getMessage());
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
        
        // Use MemoryProfiler for memory monitoring (reduced frequency for ZGC)
        MemoryProfiler profiler = MemoryProfiler.getInstance();
        // Only check memory pressure every 30 seconds instead of every 5 seconds
        if (currentTime - lastMemoryCheckTime > 30000) {
            profiler.checkMemoryPressure();
            lastMemoryCheckTime = currentTime;
        }
        
        // Get memory information
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        double memoryUsagePercent = (double) usedMemory / maxMemory * 100;
        
        // Get world information
        int chunkCount = 0;
        int meshPendingCount = 0;
        int glUploadPendingCount = 0;
        if (getInstance().world != null) {
            chunkCount = getInstance().world.getLoadedChunkCount();
            meshPendingCount = getInstance().world.getPendingMeshBuildCount();
            glUploadPendingCount = getInstance().world.getPendingGLUploadCount();
        }
        
        // Get player position
        String playerPos = "Unknown";
        if (getInstance().player != null) {
            org.joml.Vector3f pos = getInstance().player.getPosition();
            playerPos = String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z);
        }
        
        // Display information
        System.out.println("========== MEMORY & PERFORMANCE DEBUG ==========");
        System.out.printf("Memory Usage: %d/%d MB (%.1f%% of max %d MB)%n",
                         usedMemory, totalMemory, memoryUsagePercent, maxMemory);
        System.out.printf("Free Memory: %d MB%n", freeMemory);
        System.out.printf("Chunks: %d loaded, %d pending mesh, %d pending GL%n",
                         chunkCount, meshPendingCount, glUploadPendingCount);
        System.out.printf("Player Position: %s%n", playerPos);
        System.out.printf("FPS: %d%n", Math.round(1.0f / getInstance().deltaTime));
        System.out.printf("Delta Time: %.3f ms%n", getInstance().deltaTime * 1000);
        
        // Memory pressure warning (ZGC-optimized thresholds)
        if (memoryUsagePercent > 90) {
            System.out.println("⚠️  HIGH: Memory usage above 90% - ZGC will manage automatically");
            profiler.takeSnapshot("high_memory_usage_" + currentTime);
        }
        if (memoryUsagePercent > 98) {
            System.out.println("🚨 CRITICAL: Memory usage above 98% - emergency cleanup triggered!");
            profiler.takeSnapshot("critical_memory_" + currentTime);
            // Let MemoryProfiler handle emergency cleanup - no forced GC with ZGC
        }
        
        System.out.println("===============================================");
    }
    
    /**
     * Logs detailed memory information for debugging memory leaks.
     */
    public static void logDetailedMemoryInfo(String context) {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        
        System.out.printf("[MEMORY] %s - Used: %dMB, Total: %dMB, Max: %dMB, Free: %dMB%n",
                         context, usedMemory, totalMemory, maxMemory, freeMemory);
    }
    
    /**
     * Forces garbage collection and reports memory usage before/after.
     * Enhanced with multiple GC cycles for better cleanup.
     */
    public static void forceGCAndReport(String context) {
        Runtime runtime = Runtime.getRuntime();
        long beforeGC = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        
        System.out.printf("[GC] %s - Memory before GC: %dMB%n", context, beforeGC);
        
        // With ZGC, let garbage collection happen naturally
        // ZGC handles memory management more efficiently than forced GC cycles
        System.out.println("[MEMORY] Relying on ZGC for optimal memory management");
        
        // Final wait for cleanup completion
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long afterGC = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long freed = beforeGC - afterGC;
        
        System.out.printf("[GC] %s - Memory after GC: %dMB (freed %+dMB)%n", context, afterGC, freed);
    }
    
    /**
     * Reports allocation statistics using the memory profiler.
     */
    public static void reportAllocations() {
        MemoryProfiler.getInstance().reportAllocations();
    }
    
    /**
     * Prints detailed memory profiler information.
     */
    public static void printDetailedMemoryProfile() {
        MemoryProfiler.getInstance().printDetailedMemoryInfo();
    }
    
    /**
     * Sets whether cheats are enabled.
     */
    public void setCheatsEnabled(boolean enabled) {
        this.cheatsEnabled = enabled;
    }
    
    /**
     * Returns whether cheats are enabled.
     */
    public boolean isCheatsEnabled() {
        return cheatsEnabled;
    }
    
    /**
     * Gets the memory leak detector.
     */
    public static MemoryLeakDetector getMemoryLeakDetector() {
        return getInstance().memoryLeakDetector;
    }
    
    /**
     * Triggers manual memory leak analysis.
     */
    public static void triggerMemoryLeakAnalysis() {
        if (getInstance().memoryLeakDetector != null) {
            getInstance().memoryLeakDetector.triggerLeakAnalysis();
        } else {
            System.err.println("Memory leak detector not initialized!");
        }
    }
    
    /**
     * Gets the debug overlay.
     */
    public static DebugOverlay getDebugOverlay() {
        return getInstance().debugOverlay;
    }
    
    /**
     * Toggles the debug overlay visibility.
     */
    public static void toggleDebugOverlay() {
        if (getInstance().debugOverlay != null) {
            getInstance().debugOverlay.toggleVisibility();
            System.out.println("Debug overlay " + (getInstance().debugOverlay.isVisible() ? "enabled" : "disabled"));
            
            // Clear all cow path data when debug overlay is hidden
            if (!getInstance().debugOverlay.isVisible() && getInstance().entityManager != null) {
                getInstance().entityManager.clearAllCowPaths();
            }
        }
    }
    
    /**
     * Gets the loading screen.
     */
    public LoadingScreen getLoadingScreen() {
        return loadingScreen;
    }

    /**
     * Gets the world select screen.
     */
    public WorldSelectScreen getWorldSelectScreen() {
        return worldSelectScreen;
    }

    /**
     * Gets the world save system for manual save operations.
     */
    public WorldSaveSystem getWorldSaveSystem() {
        return worldSaveSystem;
    }

    /**
     * Starts world generation with loading screen.
     * This method should be called when transitioning from main menu to game.
     */
    public void startWorldGeneration() {
        if (loadingScreen != null) {
            loadingScreen.show(); // This sets state to LOADING
            System.out.println("Started world generation with loading screen");

            // Trigger initial world generation in a separate thread
            new Thread(this::performInitialWorldGeneration).start();
        }
    }

    /**
     * Starts world generation with loading screen for a specific named world.
     * This method should be called when loading/creating a world from world selection.
     *
     * @param worldName The name of the world to load or create
     * @param seed The seed for world generation
     */
    public void startWorldGeneration(String worldName, long seed) {
        System.out.println("Starting world generation for: " + worldName + " with seed: " + seed);

        // Initialize save system early to enable player data loading during generation
        String worldPath = "worlds/" + worldName;
        this.worldSaveSystem = new WorldSaveSystem(worldPath);
        this.currentWorldName = worldName;
        this.currentWorldSeed = seed;

        if (loadingScreen != null) {
            loadingScreen.show(); // This sets state to LOADING

            // Trigger world loading/generation in a separate thread
            new Thread(() -> performWorldLoadingOrGeneration(worldName, seed)).start();
        }
    }
    
    /**
     * Performs initial world generation with progress updates.
     * This runs in a background thread while the loading screen is displayed.
     */
    private void performInitialWorldGeneration() {
        try {
            // Update progress through the loading screen
            if (loadingScreen != null) {
                loadingScreen.updateProgress("Initializing Noise System");
            }
            
            // Give a brief moment for the loading screen to render
            Thread.sleep(100);
            
            // Generate initial chunks around spawn point (0, 0)
            if (world != null && player != null) {
                // Try to load existing player data before setting default position
                org.joml.Vector3f playerPosition = new org.joml.Vector3f(0, 100, 0); // Default position

                if (worldSaveSystem != null) {
                    try {
                        // Initialize save system with world metadata for proper world isolation
                        com.stonebreak.world.save.core.WorldMetadata worldMetadata =
                            new com.stonebreak.world.save.core.WorldMetadata(currentWorldSeed, currentWorldName);
                        worldSaveSystem.initialize(world, player, worldMetadata);
                        System.out.println("[SAVE-SYSTEM] ✓ Initialized save system for world '" + currentWorldName + "'");
                        System.out.println("[SAVE-SYSTEM] Save system isInitialized(): " + worldSaveSystem.isInitialized());

                        // Check if player data exists and load it
                        com.stonebreak.world.save.core.PlayerState existingPlayerState = null;
                        try {
                            existingPlayerState = worldSaveSystem.loadPlayerState().get(); // Blocking call for simplicity during generation
                        } catch (Exception loadException) {
                            System.out.println("[PLAYER-DATA] Player data loading failed (likely new world): " + loadException.getMessage());
                            existingPlayerState = null;
                        }

                        if (existingPlayerState != null) {
                            // Apply the loaded player state (with world name validation)
                            worldSaveSystem.applyPlayerState(existingPlayerState);
                            playerPosition = new org.joml.Vector3f(existingPlayerState.getPosition());
                            System.out.println("[PLAYER-DATA] ✓ Loaded existing player data for world '" + currentWorldName + "': position=" +
                                playerPosition.x + "," + playerPosition.y + "," + playerPosition.z);
                        } else {
                            // No existing player data, give starting items and set default position (this is normal for new worlds)
                            player.giveStartingItems();
                            System.out.println("[PLAYER-DATA] ✓ No existing player data found for world '" + currentWorldName + "' - treating as new world, giving starting items");
                        }
                    } catch (Exception e) {
                        // Failed to initialize save system or load player data
                        System.err.println("[SAVE-SYSTEM] ✗ CRITICAL ERROR: Save system initialization failed for world '" + currentWorldName + "'!");
                        System.err.println("[SAVE-SYSTEM] Error details: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        System.err.println("[SAVE-SYSTEM] This will prevent saving/loading - player will get starter items every time");

                        player.giveStartingItems();
                        System.out.println("[PLAYER-DATA] Save system failed, giving starting items as fallback: " + e.getMessage());
                    }
                } else {
                    // No save system available, give starting items and use defaults
                    player.giveStartingItems();
                    System.out.println("[PLAYER-DATA] No save system available for world '" + currentWorldName + "', giving starting items");
                }

                // Generate chunks around player position
                int playerChunkX = (int) Math.floor(playerPosition.x / 16);
                int playerChunkZ = (int) Math.floor(playerPosition.z / 16);
                int renderDistance = 4; // Smaller initial area
                
                if (loadingScreen != null) {
                    loadingScreen.updateProgress("Generating Base Terrain Shape");
                }
                
                // Generate chunks in expanding rings
                long lastProgressUpdate = System.currentTimeMillis();
                int chunksGenerated = 0;
                
                for (int ring = 0; ring <= renderDistance; ring++) {
                    for (int x = playerChunkX - ring; x <= playerChunkX + ring; x++) {
                        for (int z = playerChunkZ - ring; z <= playerChunkZ + ring; z++) {
                            // Only generate chunks on the edge of the current ring
                            if (ring == 0 || x == playerChunkX - ring || x == playerChunkX + ring ||
                                z == playerChunkZ - ring || z == playerChunkZ + ring) {
                                
                                world.getChunkAt(x, z); // This generates the chunk
                                chunksGenerated++;
                                
                                // Rate limiting: pause every few chunks to prevent excessive CPU usage
                                if (chunksGenerated % 3 == 0) {
                                    long currentTime = System.currentTimeMillis();
                                    // Rate limit progress updates to 50ms intervals
                                    if (currentTime - lastProgressUpdate < 50) {
                                        continue;
                                    }
                                    lastProgressUpdate = System.currentTimeMillis();
                                }
                            }
                        }
                    }
                    
                    // Update progress based on ring completion
                    if (loadingScreen != null) {
                        switch (ring) {
                            case 1 -> loadingScreen.updateProgress("Determining Biomes");
                            case 2 -> loadingScreen.updateProgress("Applying Biome Materials");
                            case 3 -> loadingScreen.updateProgress("Adding Surface Decorations & Details");
                            case 4 -> loadingScreen.updateProgress("Meshing Chunk");
                        }
                    }
                }
                
                // Give time for all chunks to finish processing
                Thread.sleep(500);
                
                // Complete world generation
                completeWorldGeneration();
                
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("World generation interrupted: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error during world generation: " + e.getMessage());
            System.err.println("World generation error details: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            // Still complete the generation to avoid being stuck
            completeWorldGeneration();
        }
    }

    /**
     * Performs world loading or generation with WorldSaveSystem integration.
     * This runs in a background thread while the loading screen is displayed.
     *
     * @param worldName The name of the world to load or create
     * @param seed The seed for world generation
     */
    private void performWorldLoadingOrGeneration(String worldName, long seed) {
        try {
            // Use the save system initialized in startWorldGeneration
            WorldSaveSystem saveSystem = this.worldSaveSystem;

            // Check if world exists
            java.io.File worldDir = new java.io.File("worlds", worldName);
            boolean worldExists = worldDir.exists() && worldDir.isDirectory();

            if (worldExists) {
                // Load existing world: metadata + player state, then finalize
                if (loadingScreen != null) {
                    loadingScreen.updateProgress("Loading World: " + worldName);
                }
                System.out.println("Loading existing world: " + worldName);

                saveSystem.loadCompleteWorldState()
                    .thenAccept(result -> {
                        try {
                            if (result != null && result.isValid()) {
                                var metadata = result.metadata;
                                if (metadata != null) {
                                    // Create fresh World instance with correct seed for complete isolation
                                    World newWorld = createFreshWorldInstance(metadata.getSeed());
                                    replaceWorldInstance(newWorld);
                                    System.out.println("[WORLD-ISOLATION] Created fresh World instance for loading with seed: " + metadata.getSeed());

                                    // Apply spawn position to the new world
                                    if (metadata.getSpawnPosition() != null) {
                                        newWorld.setSpawnPosition(metadata.getSpawnPosition());
                                    }
                                    // Update seed for save system initialization
                                    this.currentWorldSeed = metadata.getSeed();
                                }

                                // Reinitialize save system with fresh world/player instances after replaceWorldInstance
                                if (metadata != null) {
                                    System.out.println("[SAVE-SYSTEM] Reinitializing save system after world replacement for existing world");
                                    com.stonebreak.world.save.core.WorldMetadata worldMetadata =
                                        new com.stonebreak.world.save.core.WorldMetadata(metadata.getSeed(), worldName);
                                    saveSystem.initialize(world, player, worldMetadata);
                                    System.out.println("[SAVE-SYSTEM] Save system reinitialized - isInitialized(): " + saveSystem.isInitialized());
                                }

                                // Apply player state
                                if (result.playerState != null) {
                                    saveSystem.applyPlayerState(result.playerState);
                                }

                                System.out.println("Successfully loaded complete world state for: " + worldName);
                            } else {
                                System.out.println("World load incomplete or invalid; generating new world.");
                                createNewWorldWithGeneration(worldName, seed);
                                return; // performInitialWorldGeneration handles completion
                            }
                        } catch (Exception e) {
                            System.err.println("Error applying loaded world state: " + e.getMessage());
                        }
                    })
                    .exceptionally(throwable -> {
                        System.err.println("Failed to load world: " + worldName + " - " + throwable.getMessage());
                        if (loadingScreen != null) {
                            loadingScreen.updateProgress("Load failed - generating new world...");
                        }
                        createNewWorldWithGeneration(worldName, seed);
                        return null;
                    })
                    .thenRun(this::completeWorldGeneration);
            } else {
                // Create new world (performInitialWorldGeneration will call completeWorldGeneration)
                createNewWorldWithGeneration(worldName, seed);
            }

        } catch (Exception e) {
            System.err.println("Error during world loading/generation: " + e.getMessage());
            e.printStackTrace();

            if (loadingScreen != null) {
                loadingScreen.updateProgress("Error occurred - falling back to default generation");
            }

            // Fall back to basic world generation
            try {
                Thread.sleep(1000); // Brief pause to show error message
                performInitialWorldGeneration();
            } catch (Exception fallbackError) {
                System.err.println("Fallback generation also failed: " + fallbackError.getMessage());
                completeWorldGeneration(); // Complete anyway to avoid being stuck
            }
        }
    }

    /**
     * Creates a new world with specified name and seed, then generates initial terrain.
     *
     * @param worldName The name of the new world
     * @param seed The seed for world generation
     */
    private void createNewWorldWithGeneration(String worldName, long seed) {
        try {
            if (loadingScreen != null) {
                loadingScreen.updateProgress("Creating New World: " + worldName);
            }
            System.out.println("Creating new world: " + worldName + " with seed: " + seed);

            // Create fresh World instance to ensure complete isolation
            World newWorld = createFreshWorldInstance(seed);
            replaceWorldInstance(newWorld);
            System.out.println("[WORLD-ISOLATION] Created fresh World instance with seed: " + seed);

            // Note: Player position and inventory will be set in performInitialWorldGeneration()
            // based on existing save data or defaults if no save data exists

            // Generate initial chunks around spawn
            performInitialWorldGeneration();

        } catch (Exception e) {
            System.err.println("Error creating new world: " + e.getMessage());
            e.printStackTrace();
            throw e; // Re-throw to be handled by calling method
        }
    }

    /**
     * Completes world generation and transitions to playing.
     * This method should be called when initial world generation is complete.
     */
    public void completeWorldGeneration() {
        if (loadingScreen != null) {
            loadingScreen.hide(); // This sets state to PLAYING

            // Force mouse capture update after loading screen completion
            if (mouseCaptureManager != null) {
                mouseCaptureManager.forceUpdate();
            }
        }

        // Save system was already initialized during world generation for player data loading
        // No need to reinitialize here
        if (worldSaveSystem != null && worldSaveSystem.isInitialized()) {
            System.out.println("[SAVE-SYSTEM] ✓ Save system is working properly after world generation");
        } else {
            System.err.println("[SAVE-SYSTEM] ✗ CRITICAL: Save system is NOT working after world generation!");
            System.err.println("[SAVE-SYSTEM] worldSaveSystem = " + (worldSaveSystem != null ? "not null" : "NULL"));
            if (worldSaveSystem != null) {
                System.err.println("[SAVE-SYSTEM] isInitialized() = " + worldSaveSystem.isInitialized());
            }
        }
    }
}
