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
import com.stonebreak.ui.*;
import com.stonebreak.util.*;
import com.stonebreak.world.*;

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
    private UIRenderer uiRenderer; // UI renderer for menus
    private MainMenu mainMenu; // Main menu
    private SettingsMenu settingsMenu; // Settings menu
    private SoundSystem soundSystem; // Sound system
    private ChatSystem chatSystem; // Chat system
    private CraftingManager craftingManager; // Crafting manager
    private MemoryLeakDetector memoryLeakDetector; // Memory leak detection system
    private DebugOverlay debugOverlay; // Debug overlay (F3)
    private LoadingScreen loadingScreen; // Loading screen for world generation
    private final ExecutorService worldUpdateExecutor = Executors.newSingleThreadExecutor();
    
    // Entity system components
    private com.stonebreak.mobs.entities.EntityManager entityManager; // Entity management system
    private com.stonebreak.mobs.entities.EntityRenderer entityRenderer; // Entity rendering system
    
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
      /**     * Initializes game components.
      */
     public void init(World world, Player player, Renderer renderer, TextureAtlas textureAtlas, InputHandler inputHandler, long window) {
        this.world = world;
        this.player = player;
        this.renderer = renderer;
        this.textureAtlas = textureAtlas; // Store TextureAtlas
        this.inputHandler = inputHandler; // Store InputHandler
        
        // Initialize mouse capture system
        this.mouseCaptureManager = new MouseCaptureManager(window);
        this.mouseCaptureManager.setCamera(player.getCamera());
        
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
        this.loadingScreen = new LoadingScreen(this.uiRenderer);

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
        if (renderer.getFont() != null && textureAtlas != null) {
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
        if (this.uiRenderer != null) {
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
        
        // Initialize entity system
        this.entityManager = new com.stonebreak.mobs.entities.EntityManager(world);
        this.entityRenderer = new com.stonebreak.mobs.entities.EntityRenderer();
        this.entityRenderer.initialize();
        System.out.println("Entity system initialized - cows can now spawn!");
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
            worldUpdateExecutor.submit(() -> world.update());
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
     * Gets the entity renderer.
     */
    public static com.stonebreak.mobs.entities.EntityRenderer getEntityRenderer() {
        return getInstance().entityRenderer;
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
        // Don't update previousGameState if we are just re-setting the same state
        if (this.currentState != state && state != null) {
            this.previousGameState = this.currentState;
        }
        this.currentState = state;
        
        // Update pause state based on game state
        updatePauseState(state);
        
        // Update mouse capture state based on new game state
        if (mouseCaptureManager != null) {
            mouseCaptureManager.updateCaptureState();
        }
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
        if (world != null) {
            world.cleanup();
        }
        if (pauseMenu != null) {
            pauseMenu.cleanup();
        }
        if (uiRenderer != null) {
            uiRenderer.cleanup();
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
        if (entityRenderer != null) {
            entityRenderer.cleanup();
        }
        if (entityManager != null) {
            entityManager.cleanup();
        }
        worldUpdateExecutor.shutdownNow();
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
                // Set player position first
                player.setPosition(0, 100, 0);
                
                // Generate chunks around spawn
                int playerChunkX = 0;
                int playerChunkZ = 0;
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
    }
}
