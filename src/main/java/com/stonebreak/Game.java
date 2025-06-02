package com.stonebreak;

import java.util.List; // Added import

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
    private WorkbenchScreen workbenchScreen; // Added WorkbenchScreen
    private RecipeBookScreen recipeBookScreen; // Added RecipeBookScreen
    private WaterEffects waterEffects; // Water effects manager
    private InputHandler inputHandler; // Added InputHandler field
    private UIRenderer uiRenderer; // UI renderer for menus
    private MainMenu mainMenu; // Main menu
    private SettingsMenu settingsMenu; // Settings menu
    private SoundSystem soundSystem; // Sound system
    private ChatSystem chatSystem; // Chat system
    private CraftingManager craftingManager; // Crafting manager
    
    // Game state
    private GameState currentState = GameState.MAIN_MENU;
    private GameState previousGameState = GameState.MAIN_MENU; // Added previousGameState
    private long lastFrameTime;
    private float deltaTime;
    private float totalTimeElapsed = 0.0f; // Added to track total time for animations
    private boolean paused = false;
    
    // Cheat system
    private boolean cheatsEnabled = false;

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
        
        // Initialize water simulation with any existing water blocks
        this.waterEffects.detectExistingWater();
        
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
        
        // If sound loading failed, try alternative approaches
        if (!this.soundSystem.isSoundLoaded("grasswalk")) {
            System.err.println("First attempt failed, trying alternative loading methods...");
            
            // Try different variations
            String[] pathVariations = {
                "sounds/GrassWalk.wav",
                "/GrassWalk.wav", 
                "GrassWalk.wav"
            };
            
            for (String path : pathVariations) {
                System.out.println("Trying path: " + path);
                this.soundSystem.loadSound("grasswalk", path);
                if (this.soundSystem.isSoundLoaded("grasswalk")) {
                    System.out.println("Success with path: " + path);
                    break;
                }
            }
        }
        
        // If sand walking sound loading failed, try alternative approaches
        if (!this.soundSystem.isSoundLoaded("sandwalk")) {
            System.err.println("Sand walk sound first attempt failed, trying alternative loading methods...");
            
            // Try different variations
            String[] pathVariations = {
                "sounds/SandWalk-001.wav",
                "/SandWalk-001.wav", 
                "SandWalk-001.wav"
            };
            
            for (String path : pathVariations) {
                System.out.println("Trying sand walk path: " + path);
                this.soundSystem.loadSound("sandwalk", path);
                if (this.soundSystem.isSoundLoaded("sandwalk")) {
                    System.out.println("Success with sand walk path: " + path);
                    break;
                }
            }
        }
        
        // Initialize UI components
        this.uiRenderer = new UIRenderer();
        this.uiRenderer.init();
        this.mainMenu = new MainMenu(this.uiRenderer);
        this.settingsMenu = new SettingsMenu(this.uiRenderer);

        // Initialize CraftingManager
        this.craftingManager = new CraftingManager();

        // Remove or comment out the test "WOOD to DIRT" recipe
        // if (BlockType.WOOD != null && BlockType.DIRT != null) {
        //     List<List<ItemStack>> woodPattern = new ArrayList<>();
        //     List<ItemStack> woodRow = new ArrayList<>();
        //     woodRow.add(new ItemStack(BlockType.WOOD.getId(), 1));
        //     woodPattern.add(woodRow);
        //     this.craftingManager.registerRecipe(
        //         new Recipe("dirt_from_wood", woodPattern, new ItemStack(BlockType.DIRT.getId(), 4))
        //     );
        //     System.out.println("Registered test recipe: 1 WOOD -> 4 DIRT");
        // } else {
        //     System.err.println("Could not create placeholder recipe: WOOD or DIRT BlockType not found.");
        // }

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

        // Recipe 4: Pine Wood Planks to Workbench
        // Input: 4 BlockType.PINE_WOOD_PLANKS (2x2) -> Output: 1 BlockType.WORKBENCH
        List<List<ItemStack>> pinePlanksToWorkbenchPattern = List.of(
            List.of(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1))
        );
        Recipe pinePlanksToWorkbenchRecipe = new Recipe(
            "pine_planks_to_workbench",
            pinePlanksToWorkbenchPattern,
            new ItemStack(BlockType.WORKBENCH.getId(), 1)
        );
        this.craftingManager.registerRecipe(pinePlanksToWorkbenchRecipe);
        System.out.println("Registered recipe: PINE_WOOD_PLANKS -> WORKBENCH");

        // Recipe 5: Sticks
        // Input: 2 BlockType.WOOD_PLANKS (vertical) -> Output: 4 ItemType.STICK
        List<List<ItemStack>> planksToSticksPattern = List.of(
            List.of(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1))
        );
        Recipe planksToSticksRecipe = new Recipe(
            "planks_to_sticks",
            planksToSticksPattern,
            new ItemStack(ItemType.STICK, 4)
        );
        this.craftingManager.registerRecipe(planksToSticksRecipe);
        System.out.println("Registered recipe: WOOD_PLANKS -> STICKS");

        // Recipe 6: Pine Wood Planks to Sticks
        // Input: 2 BlockType.PINE_WOOD_PLANKS (vertical) -> Output: 4 ItemType.STICK
        List<List<ItemStack>> pinePlanksToSticksPattern = List.of(
            List.of(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1))
        );
        Recipe pinePlanksToSticksRecipe = new Recipe(
            "pine_planks_to_sticks",
            pinePlanksToSticksPattern,
            new ItemStack(ItemType.STICK, 4)
        );
        this.craftingManager.registerRecipe(pinePlanksToSticksRecipe);
        System.out.println("Registered recipe: PINE_WOOD_PLANKS -> STICKS");

        // Recipe 7: Wooden Pickaxe
        // Input: 3 Wood Planks in top row, 2 Sticks in center column (middle and bottom) -> Output: 1 Wooden Pickaxe
        List<List<ItemStack>> woodenPickaxePattern = List.of(
            List.of(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null),
            java.util.Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null)
        );
        Recipe woodenPickaxeRecipe = new Recipe(
            "wooden_pickaxe",
            woodenPickaxePattern,
            new ItemStack(ItemType.WOODEN_PICKAXE, 1)
        );
        this.craftingManager.registerRecipe(woodenPickaxeRecipe);
        System.out.println("Registered recipe: WOOD_PLANKS + STICKS -> WOODEN_PICKAXE");

        // Recipe 8: Wooden Pickaxe (Pine Wood Planks variant)
        // Input: 3 Pine Wood Planks in top row, 2 Sticks in center column (middle and bottom) -> Output: 1 Wooden Pickaxe
        List<List<ItemStack>> pineWoodenPickaxePattern = List.of(
            List.of(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null),
            java.util.Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null)
        );
        Recipe pineWoodenPickaxeRecipe = new Recipe(
            "pine_wooden_pickaxe",
            pineWoodenPickaxePattern,
            new ItemStack(ItemType.WOODEN_PICKAXE, 1)
        );
        this.craftingManager.registerRecipe(pineWoodenPickaxeRecipe);
        System.out.println("Registered recipe: PINE_WOOD_PLANKS + STICKS -> WOODEN_PICKAXE");

        // Recipe 9: Elm Wood Planks
        // Input: 1 BlockType.ELM_WOOD_LOG -> Output: 4 BlockType.ELM_WOOD_PLANKS
        List<List<ItemStack>> elmToPlanksPattern = List.of(
            List.of(new ItemStack(BlockType.ELM_WOOD_LOG.getId(), 1))
        );
        Recipe elmToPlanksRecipe = new Recipe(
            "elm_to_planks",
            elmToPlanksPattern,
            new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 4)
        );
        this.craftingManager.registerRecipe(elmToPlanksRecipe);
        System.out.println("Registered recipe: ELM_WOOD_LOG -> ELM_WOOD_PLANKS");

        // Recipe 10: Elm Wood Planks to Workbench
        // Input: 4 BlockType.ELM_WOOD_PLANKS (2x2) -> Output: 1 BlockType.WORKBENCH
        List<List<ItemStack>> elmPlanksToWorkbenchPattern = List.of(
            List.of(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1))
        );
        Recipe elmPlanksToWorkbenchRecipe = new Recipe(
            "elm_planks_to_workbench",
            elmPlanksToWorkbenchPattern,
            new ItemStack(BlockType.WORKBENCH.getId(), 1)
        );
        this.craftingManager.registerRecipe(elmPlanksToWorkbenchRecipe);
        System.out.println("Registered recipe: ELM_WOOD_PLANKS -> WORKBENCH");

        // Recipe 11: Elm Wood Planks to Sticks
        // Input: 2 BlockType.ELM_WOOD_PLANKS (vertical) -> Output: 4 ItemType.STICK
        List<List<ItemStack>> elmPlanksToSticksPattern = List.of(
            List.of(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1))
        );
        Recipe elmPlanksToSticksRecipe = new Recipe(
            "elm_planks_to_sticks",
            elmPlanksToSticksPattern,
            new ItemStack(ItemType.STICK, 4)
        );
        this.craftingManager.registerRecipe(elmPlanksToSticksRecipe);
        System.out.println("Registered recipe: ELM_WOOD_PLANKS -> STICKS");

        // Recipe 12: Wooden Pickaxe (Elm Wood Planks variant)
        // Input: 3 Elm Wood Planks in top row, 2 Sticks in center column (middle and bottom) -> Output: 1 Wooden Pickaxe
        List<List<ItemStack>> elmWoodenPickaxePattern = List.of(
            List.of(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null),
            java.util.Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null)
        );
        Recipe elmWoodenPickaxeRecipe = new Recipe(
            "elm_wooden_pickaxe",
            elmWoodenPickaxePattern,
            new ItemStack(ItemType.WOODEN_PICKAXE, 1)
        );
        this.craftingManager.registerRecipe(elmWoodenPickaxeRecipe);
        System.out.println("Registered recipe: ELM_WOOD_PLANKS + STICKS -> WOODEN_PICKAXE");

        // Recipe 13: Wooden Axe (Regular Wood Planks)
        // Input: 3 Wood Planks (2 top row, 1 middle left), 2 Sticks in center column (middle and bottom) -> Output: 1 Wooden Axe
        List<List<ItemStack>> woodenAxePattern = java.util.Arrays.asList(
            java.util.Arrays.asList(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1)),
            java.util.Arrays.asList(null, new ItemStack(ItemType.STICK, 1))
        );
        Recipe woodenAxeRecipe = new Recipe(
            "wooden_axe",
            woodenAxePattern,
            new ItemStack(ItemType.WOODEN_AXE, 1)
        );
        this.craftingManager.registerRecipe(woodenAxeRecipe);
        System.out.println("Registered recipe: WOOD_PLANKS + STICKS -> WOODEN_AXE");

        // Recipe 14: Wooden Axe (Pine Wood Planks variant)
        // Input: 3 Pine Wood Planks (2 top row, 1 middle left), 2 Sticks in center column (middle and bottom) -> Output: 1 Wooden Axe
        List<List<ItemStack>> pineWoodenAxePattern = java.util.Arrays.asList(
            java.util.Arrays.asList(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1)),
            java.util.Arrays.asList(null, new ItemStack(ItemType.STICK, 1))
        );
        Recipe pineWoodenAxeRecipe = new Recipe(
            "pine_wooden_axe",
            pineWoodenAxePattern,
            new ItemStack(ItemType.WOODEN_AXE, 1)
        );
        this.craftingManager.registerRecipe(pineWoodenAxeRecipe);
        System.out.println("Registered recipe: PINE_WOOD_PLANKS + STICKS -> WOODEN_AXE");

        // Recipe 15: Wooden Axe (Elm Wood Planks variant)
        // Input: 3 Elm Wood Planks (2 top row, 1 middle left), 2 Sticks in center column (middle and bottom) -> Output: 1 Wooden Axe
        List<List<ItemStack>> elmWoodenAxePattern = java.util.Arrays.asList(
            java.util.Arrays.asList(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1)),
            java.util.Arrays.asList(null, new ItemStack(ItemType.STICK, 1))
        );
        Recipe elmWoodenAxeRecipe = new Recipe(
            "elm_wooden_axe",
            elmWoodenAxePattern,
            new ItemStack(ItemType.WOODEN_AXE, 1)
        );
        this.craftingManager.registerRecipe(elmWoodenAxeRecipe);
        System.out.println("Registered recipe: ELM_WOOD_PLANKS + STICKS -> WOODEN_AXE");

        // Initialize chat system
        this.chatSystem = new ChatSystem();
        this.chatSystem.addMessage("Welcome to Stonebreak!", new float[]{1.0f, 1.0f, 0.0f, 1.0f}); // Yellow welcome message
        
        // Initialize InventoryScreen - assumes Player, Renderer, TextureAtlas, and InputHandler are already initialized
        if (player != null && player.getInventory() != null && renderer != null && renderer.getFont() != null && textureAtlas != null && this.inputHandler != null && this.craftingManager != null) {
            this.inventoryScreen = new InventoryScreen(player.getInventory(), renderer.getFont(), renderer, this.uiRenderer, this.inputHandler, this.craftingManager);
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
            // Handle error appropriately, maybe throw an exception or set inventoryScreen to a safe non-functional state
        }

        // Initialize WorkbenchScreen
        if (player != null && player.getInventory() != null && renderer != null && this.uiRenderer != null && this.inputHandler != null && this.craftingManager != null) {
            this.workbenchScreen = new WorkbenchScreen(this, player.getInventory(), renderer, this.uiRenderer, this.inputHandler, this.craftingManager);
        } else {
            System.err.println("Failed to initialize WorkbenchScreen due to null components.");
        }

        // Initialize RecipeBookScreen
        if (this.uiRenderer != null && this.craftingManager != null && getFont() != null) {
            this.recipeBookScreen = new RecipeBookScreen(this.uiRenderer, this.inputHandler, renderer);
        } else {
            System.err.println("Failed to initialize RecipeBookScreen due to null UIRenderer, CraftingManager, or Font.");
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

        // Handle updates based on game state
        switch (currentState) {
            case MAIN_MENU -> {
                // Main menu updates (if any)
                if (mainMenu != null) {
                    // mainMenu.update(deltaTime); // If main menu needs updates
                }
                return; // Don't update game world
            }
            case PLAYING -> {
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
     * Toggles the inventory screen visibility.
     */
    public void toggleInventoryScreen() {
        if (inventoryScreen == null) return;

        inventoryScreen.toggleVisibility();

        if (inventoryScreen.isVisible()) {
            // Opening inventory. Game world should pause, cursor visible.
            // We don't set PLAYING_WITH_INVENTORY_UI as a separate state.
            // Instead, isPaused() and inventoryScreen.isVisible() conditions are used.
            // setState might not be strictly necessary if 'paused' and cursor are set here,
            // but to centralize cursor/pause logic, we can inform setState or just manage directly.
             this.paused = true; // Game.paused flag
             org.lwjgl.glfw.GLFW.glfwSetInputMode(Main.getWindowHandle(), org.lwjgl.glfw.GLFW.GLFW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL);
             // No specific GameState change, as Inventory is an overlay on PLAYING (or WORKBENCH etc.)
             // but ensure game logic respects Game.isPaused()

        } else {
            // Closing inventory.
            // The InputHandler.handleEscapeKey now handles closing inventory and determining the next state.
            // If inventory is closed via 'E' key:
            // We need to decide what state to return to. If previousGameState was something like RECIPE_BOOK_UI
            // which might have been open over inventory, we should return there, or to PLAYING if nothing else is layered.

            // If the main PauseMenu is NOT active:
            if (pauseMenu == null || !pauseMenu.isVisible()) {
                 // If the current state IS NOT PLAYING, it implies another UI (Workbench, RecipeBook) is active.
                 // Closing inventory should keep that UI's state (and its paused=true, cursor=visible settings).
                 // If current state IS PLAYING, then inventory was the primary UI, so unpause.
                if (this.currentState == GameState.PLAYING) {
                    this.paused = false;
                    org.lwjgl.glfw.GLFW.glfwSetInputMode(Main.getWindowHandle(), org.lwjgl.glfw.GLFW.GLFW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED);
                    if (this.inputHandler != null) {
                        this.inputHandler.resetMousePosition();
                    }
                }
                // If currentState is WORKBENCH_UI or RECIPE_BOOK_UI, those states already enforce paused=true and cursor visible.
                // So, closing inventory under them should not change that.
            }
            // If pauseMenu *is* active, closing inventory should not affect pauseMenu's paused state or cursor.
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
        // Don't update previousGameState if we are just re-setting the same state
        // or if the new state is null (should not happen)
        if (this.currentState != state && state != null) {
            this.previousGameState = this.currentState;
        }
        this.currentState = state;
        
        // Handle cursor visibility based on state transitions
        long windowHandle = Main.getWindowHandle();
        if (windowHandle != 0) {
            // Unified logic for states that require cursor and game pause
            if (state == GameState.MAIN_MENU ||
                state == GameState.SETTINGS ||
                state == GameState.PAUSED ||
                state == GameState.WORKBENCH_UI ||
                state == GameState.RECIPE_BOOK_UI) {
                
                org.lwjgl.glfw.GLFW.glfwSetInputMode(windowHandle, org.lwjgl.glfw.GLFW.GLFW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL);
                paused = true;
                
                // Reset mouse position if cursor was previously hidden (e.g., coming from unencumbered PLAYING state)
                if (this.inputHandler != null && this.previousGameState == GameState.PLAYING) {
                     // Check if the previous PLAYING state was truly unencumbered (no inventory/chat) to decide on reset.
                     // This prevents resetting if the cursor was already visible due to an overlay like inventory.
                    boolean prevPlayingWasUnencumbered = (inventoryScreen == null || !inventoryScreen.isVisible()) &&
                                                         (chatSystem == null || !chatSystem.isOpen());
                    if (prevPlayingWasUnencumbered) {
                        this.inputHandler.resetMousePosition();
                    }
                }

            } else if (state == GameState.PLAYING) {
                // Transitioning to PLAYING state (e.g., from a menu, or closing workbench/recipe book/pause menu)
                // Hide cursor and unpause game logic *only if* no overlay UI (inventory, chat) is active.
                if ((inventoryScreen == null || !inventoryScreen.isVisible()) &&
                    (chatSystem == null || !chatSystem.isOpen())) {
                    
                    org.lwjgl.glfw.GLFW.glfwSetInputMode(windowHandle, org.lwjgl.glfw.GLFW.GLFW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED);
                    // Ensure game logic is unpaused only if inventory isn't forcing a pause.
                    // `Game.paused` is used by InputHandler to control player movement updates.
                    // `toggleInventoryScreen` sets `Game.paused = true` when inventory opens.
                    if (inventoryScreen == null || !inventoryScreen.isVisible()) { // Double check inventory before unpausing
                         paused = false;
                    }
                    if (this.inputHandler != null) {
                        this.inputHandler.resetMousePosition();
                    }
                } else {
                    // If inventory IS visible, it manages 'paused = true' and 'CURSOR_NORMAL'.
                    // If chat IS open, it manages 'CURSOR_NORMAL'; 'paused' is false (world input blocked by InputHandler).
                    // If we are trying to set state to PLAYING but an overlay exists:
                    if (inventoryScreen != null && inventoryScreen.isVisible()) {
                        // Ensure game world logic remains paused for inventory. Cursor is already NORMAL from toggleInventoryScreen.
                        paused = true;
                    }
                    // If only chat is open, 'paused' should remain false (unless inventory was also open and now closed, then this path isn't hit).
                    // Cursor is NORMAL (from chat). InputHandler blocks game world actions. This is consistent.
                }
            }
            // Other game states (like LOADING, etc.) might not affect cursor/pause, or have their own specific handlers.
            // Note: Game.toggleInventoryScreen() handles its cursor/pause state changes somewhat independently
            //       when opening/closing inventory, particularly by not always invoking setState for these specific aspects.
            //       This setState logic aims to correctly handle transitions *between* major game states
            //       and ensure consistency for states like PAUSED, WORKBENCH_UI, etc.
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
    public UIRenderer getUIRenderer() {
        return uiRenderer;
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
        if (this.renderer != null) {
            return this.renderer.getTextureAtlas();
        }
        return null;
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
        if (pauseMenu != null) {
            pauseMenu.cleanup();
        }
        if (uiRenderer != null) {
            uiRenderer.cleanup();
        }
        if (soundSystem != null) {
            soundSystem.cleanup();
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
}
