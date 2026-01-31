package com.stonebreak.core;

import java.util.List;
import java.util.concurrent.*;

import com.stonebreak.audio.*;
import com.stonebreak.blocks.*;
import com.stonebreak.config.*;
import com.stonebreak.crafting.*;
import com.stonebreak.input.*;
import com.stonebreak.items.*;
import com.stonebreak.player.*;
import com.stonebreak.rendering.*;
import com.stonebreak.rendering.CowTextureAtlas;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.ui.*;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.worldSelect.WorldSelectScreen;
import com.stonebreak.util.*;
import com.stonebreak.world.*;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.InitialWorldGenerator;
import com.stonebreak.world.generation.SpawnLocationCalculator;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.util.StateConverter;
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
    private DeathMenu deathMenu;
    private InventoryScreen inventoryScreen; // Added InventoryScreen
    private WorkbenchScreen workbenchScreen; // Added WorkbenchScreen
    private RecipeScreen recipeScreen; // Added RecipeBookScreen
    private WaterEffects waterEffects; // Water effects manager
    private InputHandler inputHandler; // Added InputHandler field
    private MouseCaptureManager mouseCaptureManager; // Mouse capture system
    private MainMenu mainMenu; // Main menu
    private SettingsMenu settingsMenu; // Settings menu
    private SoundSystem soundSystem; // Sound system
    private ChatSystem chatSystem; // Chat system
    private CraftingManager craftingManager; // Crafting manager
    private com.stonebreak.audio.emitters.SoundEmitterManager soundEmitterManager; // Sound emitter management
    private MemoryLeakDetector memoryLeakDetector; // Memory leak detection system
    private DebugOverlay debugOverlay; // Debug overlay (F3)
    private LoadingScreen loadingScreen; // Loading screen for world generation
    private WorldSelectScreen worldSelectScreen; // World selection screen
    private com.stonebreak.ui.terrainmapper.TerrainMapperScreen terrainMapperScreen; // Terrain mapper for world creation
    private SaveService saveService; // World save/load system
    private WorldData currentWorldData; // Current world metadata
    private String currentWorldName; // Current world name for save system initialization
    private long currentWorldSeed; // Current world seed for save system initialization
    private final ExecutorService worldUpdateExecutor = Executors.newSingleThreadExecutor();
    
    // Entity system components
    private com.stonebreak.mobs.entities.EntityManager entityManager; // Entity management system
    private com.stonebreak.mobs.entities.EntitySpawner entitySpawner; // Entity spawning system


    // Time of day system
    private TimeOfDay timeOfDay; // Day/night cycle system

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

    // Window dimensions and handle
    private long window;
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
        this.window = window; // Store window handle for clipboard operations
        this.renderer = renderer;
        this.textureAtlas = textureAtlas; // Store TextureAtlas
        this.inputHandler = inputHandler; // Store InputHandler

        // Initialize mouse capture system (without camera - will be set when world is created)
        this.mouseCaptureManager = new MouseCaptureManager(window);

        this.pauseMenu = new PauseMenu();
        this.deathMenu = new DeathMenu();
        this.waterEffects = new WaterEffects(); // Initialize water effects

        // Initialize sound system
        this.soundSystem = SoundSystem.getInstance();
        this.soundSystem.initialize();
        this.soundSystem.loadSound("grasswalk", "/sounds/GrassWalk.wav");
        this.soundSystem.loadSound("sandwalk", "/sounds/SandWalk-001.wav");
        this.soundSystem.loadSound("woodwalk", "/sounds/WoodWalk.wav");
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
        this.terrainMapperScreen = new com.stonebreak.ui.terrainmapper.TerrainMapperScreen(
            this.renderer.getUIRenderer(),
            this.worldSelectScreen.getDiscoveryManager()
        );

        // Initialize crosshair with settings
        initializeCrosshairSettings();

        // Initialize CraftingManager
        this.craftingManager = new CraftingManager();

        // Add all the crafting recipes (same as before)
        initializeCraftingRecipes();

        // Initialize chat system
        this.chatSystem = new ChatSystem();
        this.chatSystem.addMessage("Welcome to Stonebreak!", new float[]{1.0f, 1.0f, 0.0f, 1.0f}); // Yellow welcome message

        // Initialize sound emitter manager
        this.soundEmitterManager = new com.stonebreak.audio.emitters.SoundEmitterManager();

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

        // Initialize MMS API early (before World creation)
        // World will be null at this point, but that's okay - it will be set when World is created
        com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.initialize(textureAtlas, null);
        System.out.println("[MMS-API] Mighty Mesh System pre-initialized (World will be set later)");

        System.out.println("[STARTUP] Core components initialized (no world/player yet)");
    }

    /**
     * Initializes components that require a world and player.
     * This should be called when a world is created/loaded.
     *
     * NOTE: MmsAPI must be initialized BEFORE World is created (World constructor depends on it).
     * NOTE: World reference is automatically set in MmsAPI during World construction via createMeshPipeline().
     */
    public void initWorldComponents(World world, Player player) {
        this.world = world;
        this.player = player;

        // MmsAPI should already be initialized and have its world reference set
        // (happens automatically in World constructor via createMeshPipeline)
        if (!com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.isInitialized()) {
            System.err.println("[MMS-API] WARNING: MmsAPI not initialized - this should not happen!");
            // Emergency fallback
            if (textureAtlas != null && world != null) {
                com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.initialize(textureAtlas, world);
                System.out.println("[MMS-API] Emergency initialization performed");
            }
        }

        // Initialize save service if it exists but hasn't been initialized yet
        if (saveService != null && currentWorldData != null) {
            System.out.println("[SAVE-SYSTEM] Updating save service references during world component initialization");
            saveService.initialize(currentWorldData, player, world);
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
        this.entitySpawner = new com.stonebreak.mobs.entities.EntitySpawner(world, entityManager);
        System.out.println("Entity system initialized - cows can now spawn!");

        // Note: TimeOfDay initialization is handled during world loading/generation
        // For new worlds: Set to NOON in performInitialWorldGeneration()
        // For existing worlds: Loaded from save data in performWorldLoadingOrGeneration()
        // This ensures default time is only applied to NEW worlds, not existing ones

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
            this.recipeScreen = new RecipeScreen(this.renderer.getUIRenderer(), this.inputHandler, renderer);
        } else {
            System.err.println("Failed to initialize RecipeBookScreen due to null UIRenderer, CraftingManager, or Font.");
        }

        // Initialize player sounds
        if (soundSystem != null) {
            soundSystem.initializePlayerSounds(world);
            System.out.println("Player sound system initialized");
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

        // Recipe 16: Wooden Bucket (Regular Wood Planks)
        // Pattern: wSw / wxw / xwx (w = wood planks, S = stick, x = empty)
        List<List<ItemStack>> woodenBucketPattern = java.util.Arrays.asList(
            java.util.Arrays.asList(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), null, new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(null, new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), null)
        );
        Recipe woodenBucketRecipe = new Recipe(
            "wooden_bucket",
            woodenBucketPattern,
            new ItemStack(ItemType.WOODEN_BUCKET, 1)
        );
        this.craftingManager.registerRecipe(woodenBucketRecipe);
        System.out.println("Registered recipe: WOOD_PLANKS + STICK -> WOODEN_BUCKET");

        // Recipe 17: Wooden Bucket (Pine Wood Planks variant)
        // Pattern: wSw / wxw / xwx (w = pine planks, S = stick, x = empty)
        List<List<ItemStack>> pineWoodenBucketPattern = java.util.Arrays.asList(
            java.util.Arrays.asList(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), null, new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(null, new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), null)
        );
        Recipe pineWoodenBucketRecipe = new Recipe(
            "pine_wooden_bucket",
            pineWoodenBucketPattern,
            new ItemStack(ItemType.WOODEN_BUCKET, 1)
        );
        this.craftingManager.registerRecipe(pineWoodenBucketRecipe);
        System.out.println("Registered recipe: PINE_WOOD_PLANKS + STICK -> WOODEN_BUCKET");

        // Recipe 18: Wooden Bucket (Elm Wood Planks variant)
        // Pattern: wSw / wxw / xwx (w = elm planks, S = stick, x = empty)
        List<List<ItemStack>> elmWoodenBucketPattern = java.util.Arrays.asList(
            java.util.Arrays.asList(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), null, new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            java.util.Arrays.asList(null, new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), null)
        );
        Recipe elmWoodenBucketRecipe = new Recipe(
            "elm_wooden_bucket",
            elmWoodenBucketPattern,
            new ItemStack(ItemType.WOODEN_BUCKET, 1)
        );
        this.craftingManager.registerRecipe(elmWoodenBucketRecipe);
        System.out.println("Registered recipe: ELM_WOOD_PLANKS + STICK -> WOODEN_BUCKET");

        System.out.println("All crafting recipes initialized");
    }

    /**
     * Legacy initialization method for backward compatibility.
     * This calls both initCoreComponents() and initWorldComponents().
     */
    public void init(World world, Player player, Renderer renderer, TextureAtlas textureAtlas, InputHandler inputHandler, long window) {
        // Initialize core components first
        initCoreComponents(renderer, textureAtlas, inputHandler, window);

        // Initialize RecipeBookScreen
        if (this.renderer.getUIRenderer() != null && this.craftingManager != null && getFont() != null) {
            this.recipeScreen = new RecipeScreen(this.renderer.getUIRenderer(), this.inputHandler, renderer);
        } else {
            System.err.println("Failed to initialize RecipeBookScreen due to null UIRenderer, CraftingManager, or Font.");
        }

        // Then initialize world-specific components
        if (world != null && player != null) {
            initWorldComponents(world, player);
        } else {
            System.out.println("[STARTUP] Skipping world component initialization (null world or player)");
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
        this.entitySpawner = new com.stonebreak.mobs.entities.EntitySpawner(world, entityManager);
        System.out.println("Entity system initialized - cows can now spawn!");

        // Note: TimeOfDay initialization is handled during world loading/generation
        // This ensures default time is only applied to NEW worlds, not existing ones
        // If using this legacy init() path, time will be set to NOON for new worlds
        if (this.timeOfDay == null) {
            this.timeOfDay = new TimeOfDay(TimeOfDay.NOON);
            System.out.println("[TIME-SYSTEM] Time of day initialized to noon (new world)");
        }

        // Initialize player sounds
        this.soundSystem.initializePlayerSounds(world);
        System.out.println("Player sound system initialized");
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

                // Continue to game world updates - PLAYING state is unpaused
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
                // Input handling for these screens is separate.
                return; // Don't update game world
            }
            case INVENTORY_UI -> {
                // Update inventory screen and continue to game world updates
                if (inventoryScreen != null && inventoryScreen.isVisible()) {
                    inventoryScreen.update(deltaTime);
                }
                // Continue to game world updates - inventory doesn't pause the game world
            }
            case RECIPE_BOOK_UI -> {
                if (recipeScreen != null) {
                    recipeScreen.update(deltaTime);
                }
                // Continue to game world updates - recipe book doesn't pause the game world
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

        // Update sound emitters
        if (soundEmitterManager != null) {
            soundEmitterManager.update(deltaTime);
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

            // Check if player died and show death menu
            if (player.isDead() && !deathMenu.isVisible()) {
                deathMenu.setVisible(true);
                // Release mouse capture when death menu appears
                if (mouseCaptureManager != null) {
                    mouseCaptureManager.updateCaptureState();
                }
            }
        }
        
        // Update water effects
        if (waterEffects != null && player != null) {
            waterEffects.update(player, deltaTime);
        }
        
        // Update entity system
        if (entityManager != null) {
            entityManager.update(deltaTime);
        }

        // Update entity spawner (handles continuous spawning and despawning)
        if (entitySpawner != null) {
            entitySpawner.update(deltaTime);
        }

        // Update time of day system (advances day/night cycle based on real time)
        if (timeOfDay != null) {
            timeOfDay.update(deltaTime);
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
     * Gets window handle for GLFW operations.
     */
    public long getWindow() {
        return window;
    }

    /**
     * Gets the time between frames.
     */
    public static float getDeltaTime() {
        return getInstance().deltaTime;
    }

    /**
     * Sets the delta time for testing purposes only.
     * This allows unit tests to simulate different frame times.
     * WARNING: Only use this in unit tests!
     *
     * @param testDeltaTime Delta time to use for testing
     */
    public static void setDeltaTimeForTesting(float testDeltaTime) {
        if (instance != null) {
            instance.deltaTime = testDeltaTime;
        }
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
     * Gets the entity spawner.
     */
    public com.stonebreak.mobs.entities.EntitySpawner getEntitySpawner() {
        return entitySpawner;
    }


    /**
     * Gets the time of day system.
     */
    public static TimeOfDay getTimeOfDay() {
        return getInstance().timeOfDay;
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
    }

    /**
     * Gets the death menu.
     */
    public DeathMenu getDeathMenu() {
        return deathMenu;
    }

    /**
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
            // Opening inventory - transition to INVENTORY_UI state
            setState(GameState.INVENTORY_UI);
        } else {
            // Closing inventory - return to PLAYING state
            // Only return to playing if no other UI is forcing a different state
            if (pauseMenu == null || !pauseMenu.isVisible()) {
                setState(GameState.PLAYING);
            }
        }

        // Mouse capture state is automatically updated by setState()
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

        // Randomize splash text when transitioning to main menu
        if (state == GameState.MAIN_MENU && mainMenu != null) {
            mainMenu.refreshSplashText();
        }

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
            case MAIN_MENU, LOADING, SETTINGS, PAUSED, WORKBENCH_UI -> {
                paused = true;
            }
            case PLAYING, INVENTORY_UI, RECIPE_BOOK_UI -> {
                // PLAYING, INVENTORY_UI, and RECIPE_BOOK_UI should be unpaused to allow normal game function
                // Movement and world interactions are prevented by InputHandler, not by pause state
                paused = false;
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
    public RecipeScreen getRecipeBookScreen() {
        return recipeScreen;
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
     * Gets the sound emitter manager.
     */
    public static com.stonebreak.audio.emitters.SoundEmitterManager getSoundEmitterManager() {
        return getInstance().soundEmitterManager;
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
        if (recipeScreen == null) {
            System.out.println("Cannot open RecipeBook: recipeBookScreen is null.");
            return;
        }

        boolean allowOpen = false;
        // Case 1: Workbench UI is active
        if (currentState == GameState.WORKBENCH_UI) {
            allowOpen = true;
        // Case 2: Inventory UI is active
        } else if (currentState == GameState.INVENTORY_UI) {
            allowOpen = true;
        // Case 3: Actively playing and not paused by the main PauseMenu (Escape key menu)
        } else if (currentState == GameState.PLAYING && (pauseMenu == null || !pauseMenu.isVisible())) {
            allowOpen = true;
        }

        if (allowOpen) {
            // setState will correctly set this.previousGameState to the current state (e.g., PLAYING, WORKBENCH_UI)
            // before changing currentState to RECIPE_BOOK_UI.
            setState(GameState.RECIPE_BOOK_UI);
            recipeScreen.onOpen(); // Initialize/refresh recipe list
            System.out.println("Opened RecipeBook Screen. Will return to: " + this.previousGameState);
        } else {
            String contextDetails = "Current state: " + currentState;
            if (inventoryScreen != null) {
                contextDetails += ", InventoryVisible: " + inventoryScreen.isVisible();
            }
            if (pauseMenu != null) {
                contextDetails += ", MainPauseActive: " + pauseMenu.isVisible();
            }
            contextDetails += ", CurrentState: " + currentState;
            System.out.println("Cannot open RecipeBook: Not in a valid context (" + contextDetails + ").");
        }
    }

    /**
     * Closes the Recipe Book screen and returns to the previous game state.
     */
    public void closeRecipeBookScreen() {
        if (recipeScreen != null && currentState == GameState.RECIPE_BOOK_UI) {
            recipeScreen.onClose();
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
        if (saveService != null) {
            try {
                System.out.println("Performing final save before shutdown...");

                // Save current game state synchronously with timeout
                java.util.concurrent.CompletableFuture<Void> saveOperation = saveService.saveAll();
                saveOperation.get(5, java.util.concurrent.TimeUnit.SECONDS); // 5 second timeout

                System.out.println("Final save completed successfully");
            } catch (java.util.concurrent.TimeoutException e) {
                System.err.println("Final save timed out after 5 seconds - proceeding with shutdown");
            } catch (Exception e) {
                System.err.println("Error during final save: " + e.getMessage());
                // Continue with shutdown even if save fails
            }

            try {
                System.out.println("Closing SaveService...");
                saveService.close();
            } catch (Exception e) {
                System.err.println("Error closing SaveService: " + e.getMessage());
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

        if (saveService != null) {
            System.out.println("[WORLD-ISOLATION] Flushing saves before world reset");
            saveService.flushSavesBlocking("world reset");
        } else {
            System.out.println("[WORLD-ISOLATION] No save system present during world reset");
        }

        // Stop auto-save to prevent further save operations during reset
        if (saveService != null) {
            try {
                saveService.stopAutoSave();
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
        currentWorldData = null;
        saveService = null; // Clear save system reference so it gets reinitialized for next world
        System.out.println("[WORLD-ISOLATION] ✓ Cleared game metadata and save system for world switching");

        System.out.println("========================================");
        System.out.println("[MAIN-MENU-TRANSITION] ✓ World reset completed - main menu is now clean!");
        System.out.println("[MAIN-MENU-TRANSITION] No background systems should be running.");
        System.out.println("========================================");
    }

    /**
     * Creates a fresh World instance with the specified seed for complete world isolation.
     * Defaults to HYBRID_SDF generator for optimal performance.
     *
     * NOTE: MmsAPI should already be initialized in initCoreComponents().
     */
    private World createFreshWorldInstance(long seed) {
        return createFreshWorldInstance(seed, "HYBRID_SDF");
    }

    /**
     * Creates a fresh World instance with the specified seed and terrain generator type.
     *
     * NOTE: MmsAPI should already be initialized in initCoreComponents().
     */
    private World createFreshWorldInstance(long seed, String generatorType) {
        WorldConfiguration config = new WorldConfiguration();
        return new World(config, seed, generatorType);
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
            // Shutdown MMS API
            System.out.println("Shutting down MMS API...");
            com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.shutdown();
        } catch (Exception e) {
            System.err.println("Error shutting down MMS API: " + e.getMessage());
        }

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
        if (memoryUsagePercent > 98) {
            System.out.println("⚠️  CRITICAL: Memory usage above 98% - ZGC will manage automatically");
            profiler.takeSnapshot("critical_memory_" + currentTime);
        } else if (memoryUsagePercent > 90) {
            System.out.println("⚠️  HIGH: Memory usage above 90% - ZGC will manage automatically");
            profiler.takeSnapshot("high_memory_usage_" + currentTime);
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
     * Gets the terrain mapper screen.
     */
    public com.stonebreak.ui.terrainmapper.TerrainMapperScreen getTerrainMapperScreen() {
        return terrainMapperScreen;
    }

    /**
     * Gets the world save system for manual save operations.
     */
    public SaveService getSaveService() {
        return saveService;
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

        // Persist and dispose of any previous save system before switching worlds
        SaveService previousSaveService = this.saveService;
        if (previousSaveService != null) {
            try {
                previousSaveService.stopAutoSave();
                System.out.println("[SAVE-SYSTEM] Stopped auto-save on previous world");
            } catch (Exception e) {
                System.err.println("[SAVE-SYSTEM] Error stopping auto-save before world switch: " + e.getMessage());
            }

            try {
                System.out.println("[SAVE-SYSTEM] Flushing previous save service before switching worlds");
                previousSaveService.flushSavesBlocking("world switch");
            } catch (Exception e) {
                System.err.println("[SAVE-SYSTEM] Flush failed during world switch: " + e.getMessage());
            }

            try {
                previousSaveService.close();
            } catch (Exception e) {
                System.err.println("[SAVE-SYSTEM] Error closing previous save service: " + e.getMessage());
            }
        }

        // Initialize save system early to enable player data loading during generation
        String worldPath = "worlds/" + worldName;
        this.saveService = new SaveService(worldPath);
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
            // Create the world generator
            InitialWorldGenerator worldGenerator = new InitialWorldGenerator(
                world,
                player,
                loadingScreen,
                saveService,
                currentWorldName,
                currentWorldSeed,
                this::completeWorldGeneration
            );

            // Perform initial generation
            timeOfDay = worldGenerator.performInitialGeneration();

            // Update current world data from generator
            currentWorldData = worldGenerator.getCurrentWorldData();

        } catch (Exception e) {
            System.err.println("Error during world generation: " + e.getMessage());
            System.err.println("World generation error details: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            // Still complete the generation to avoid being stuck
            completeWorldGeneration();
        }
    }

    /**
     * Checks if the spawn position needs to be calculated (i.e., was not yet determined).
     * @param spawnPos The spawn position from WorldData
     * @return true if spawn needs calculation
     */
    private boolean needsSpawnCalculation(Vector3f spawnPos) {
        return spawnPos == null || spawnPos.y <= -900;  // Y=-999 or similar sentinel
    }

    /**
     * Performs world loading or generation with SaveService integration.
     * This runs in a background thread while the loading screen is displayed.
     *
     * @param worldName The name of the world to load or create
     * @param seed The seed for world generation
     */
    private void performWorldLoadingOrGeneration(String worldName, long seed) {
        try {
            // Use the save service initialized in startWorldGeneration
            SaveService saveService = this.saveService;

            if (saveService == null) {
                System.err.println("[SAVE-SYSTEM] SaveService is null - creating new world");
                createNewWorldWithGeneration(worldName, seed);
                return;
            }

            // Check if world exists
            java.io.File worldDir = new java.io.File("worlds", worldName);
            boolean worldExists = worldDir.exists() && worldDir.isDirectory();

            if (worldExists) {
                // Load existing world
                if (loadingScreen != null) {
                    loadingScreen.updateProgress("Loading World: " + worldName);
                }
                System.out.println("Loading existing world: " + worldName);

                saveService.loadWorld()
                    .thenAccept(result -> {
                        try {
                            if (result.isSuccess() && result.getWorldData() != null) {
                                WorldData worldData = result.getWorldData();

                                // Create fresh World instance with correct seed and generator type for complete isolation
                                String generatorType = worldData.getGeneratorType();
                                World newWorld = createFreshWorldInstance(worldData.getSeed(), generatorType);
                                replaceWorldInstance(newWorld);
                                System.out.println("[WORLD-ISOLATION] Created fresh World instance for loading with seed: " + worldData.getSeed() + ", generator: " + generatorType);

                                // Check if spawn needs calculation (new world with sentinel value)
                                if (needsSpawnCalculation(worldData.getSpawnPosition())) {
                                    System.out.println("[SPAWN] New world detected - calculating spawn position");

                                    // Calculate spawn position using SpawnLocationCalculator
                                    SpawnLocationCalculator spawnCalculator = new SpawnLocationCalculator(newWorld);
                                    Vector3f calculatedSpawn = spawnCalculator.calculateSpawnPosition();

                                    // Update world data with calculated spawn
                                    worldData = new WorldData.Builder(worldData)
                                        .spawnPosition(calculatedSpawn)
                                        .build();

                                    // Save updated world data with proper spawn
                                    saveService.saveWorldDataOnly(worldData).join();  // Block until save completes
                                    System.out.println("[SPAWN] Saved calculated spawn position to disk: " + calculatedSpawn);
                                }

                                // CRITICAL: Apply calculated spawn to Player instance before player becomes visible
                                // This ensures player spawns at surface height, not y=100 default from constructor
                                Vector3f spawnPos = worldData.getSpawnPosition();
                                if (spawnPos != null && this.player != null) {
                                    this.player.setPosition(spawnPos);
                                    System.out.println("[SPAWN] Applied calculated spawn position to Player: " + spawnPos);
                                }

                                // Apply spawn position to the new world
                                if (worldData.getSpawnPosition() != null) {
                                    newWorld.setSpawnPosition(worldData.getSpawnPosition());
                                }

                                // Update seed for save system initialization
                                this.currentWorldSeed = worldData.getSeed();
                                this.currentWorldData = worldData;

                                // Reinitialize save system with fresh world/player instances after replaceWorldInstance
                                // IMPORTANT: Use this.player and this.world (not captured variables) because replaceWorldInstance created new instances
                                System.out.println("[SAVE-SYSTEM] Reinitializing save system after world replacement for existing world");
                                saveService.initialize(worldData, this.player, this.world);

                                // Initialize TimeOfDay with loaded world time
                                long savedTimeTicks = worldData.getWorldTimeTicks();
                                this.timeOfDay = new TimeOfDay(savedTimeTicks);
                                System.out.println("[TIME-SYSTEM] ✓ Loaded world time: " + savedTimeTicks + " ticks (" + this.timeOfDay.getTimeString() + ")");

                                // Apply player state if available
                                if (result.getPlayerData() != null) {
                                    StateConverter.applyPlayerData(this.player, result.getPlayerData());
                                    System.out.println("[PLAYER-DATA] Applied loaded player data to position: " +
                                        this.player.getPosition().x + ", " + this.player.getPosition().y + ", " + this.player.getPosition().z);

                                    // Generate initial chunks around loaded player position
                                    Vector3f playerPos = result.getPlayerData().getPosition();
                                    int playerChunkX = (int) Math.floor(playerPos.x / 16);
                                    int playerChunkZ = (int) Math.floor(playerPos.z / 16);
                                    int renderDistance = 4;

                                    if (loadingScreen != null) {
                                        loadingScreen.updateProgress("Loading chunks around player...");
                                    }

                                    // Generate chunks in expanding rings around player
                                    for (int ring = 0; ring <= renderDistance; ring++) {
                                        for (int x = playerChunkX - ring; x <= playerChunkX + ring; x++) {
                                            for (int z = playerChunkZ - ring; z <= playerChunkZ + ring; z++) {
                                                if (ring == 0 || x == playerChunkX - ring || x == playerChunkX + ring ||
                                                    z == playerChunkZ - ring || z == playerChunkZ + ring) {
                                                    this.world.getChunkAt(x, z); // Load or generate chunk
                                                }
                                            }
                                        }
                                    }

                                    try {
                                        Thread.sleep(300); // Brief wait for chunks to process
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                } else {
                                    // No player data - this is a new player in an existing world
                                    this.player.giveStartingItems();
                                    // Initialize time at noon for new worlds without player data
                                    if (this.timeOfDay == null) {
                                        this.timeOfDay = new TimeOfDay(TimeOfDay.NOON);
                                        System.out.println("[TIME-SYSTEM] ✓ Initialized new world time at noon (no player data)");
                                    }
                                    System.out.println("[PLAYER-DATA] No player data found - giving starting items");

                                    // Calculate spawn position using SpawnLocationCalculator
                                    SpawnLocationCalculator spawnCalculator = new SpawnLocationCalculator(this.world);
                                    Vector3f spawnPosition = spawnCalculator.calculateSpawnPosition();
                                    this.player.getPosition().set(spawnPosition);
                                    System.out.println("[SPAWN] New player spawning at surface: (" + spawnPosition.x + ", " + spawnPosition.y + ", " + spawnPosition.z + ")");

                                    // Generate chunks around spawn position
                                    int spawnChunkX = (int) Math.floor(spawnPosition.x / 16);
                                    int spawnChunkZ = (int) Math.floor(spawnPosition.z / 16);
                                    int renderDistance = 4;
                                    if (loadingScreen != null) {
                                        loadingScreen.updateProgress("Generating chunks around spawn...");
                                    }

                                    for (int ring = 0; ring <= renderDistance; ring++) {
                                        for (int x = spawnChunkX - ring; x <= spawnChunkX + ring; x++) {
                                            for (int z = spawnChunkZ - ring; z <= spawnChunkZ + ring; z++) {
                                                if (ring == 0 || x == spawnChunkX - ring || x == spawnChunkX + ring || z == spawnChunkZ - ring || z == spawnChunkZ + ring) {
                                                    this.world.getChunkAt(x, z);
                                                }
                                            }
                                        }
                                    }

                                    try {
                                        Thread.sleep(300); // Brief wait for chunks to process
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }

                                // Start auto-save
                                saveService.startAutoSave();

                                System.out.println("Successfully loaded complete world state for: " + worldName);
                            } else {
                                System.out.println("World load incomplete or invalid; generating new world.");
                                createNewWorldWithGeneration(worldName, seed);
                                return; // performInitialWorldGeneration handles completion
                            }
                        } catch (Exception e) {
                            System.err.println("Error applying loaded world state: " + e.getMessage());
                            e.printStackTrace();
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
        if (saveService != null && currentWorldData != null) {
            System.out.println("[SAVE-SYSTEM] ✓ Save system is working properly after world generation");
        } else {
            System.err.println("[SAVE-SYSTEM] ✗ CRITICAL: Save system is NOT working after world generation!");
            System.err.println("[SAVE-SYSTEM] saveService = " + (saveService != null ? "not null" : "NULL"));
            System.err.println("[SAVE-SYSTEM] currentWorldData = " + (currentWorldData != null ? "not null" : "NULL"));
        }
    }
}
