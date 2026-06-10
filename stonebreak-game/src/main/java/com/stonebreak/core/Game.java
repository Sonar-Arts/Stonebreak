package com.stonebreak.core;

import java.util.concurrent.*;

import com.stonebreak.rendering.textures.BlockTextureArray;
import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.audio.*;
import com.stonebreak.blocks.*;
import com.stonebreak.crafting.*;
import com.stonebreak.input.*;
import com.stonebreak.items.*;
import com.stonebreak.player.*;
import com.stonebreak.rendering.*;
import com.stonebreak.ui.*;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.rpg.CharacterPanelTab;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import com.stonebreak.ui.furnace.FurnaceScreen;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.characterCreation.CharacterCreationScreen;
import com.stonebreak.ui.startupIntro.SonarArtsIntroScreen;
import com.stonebreak.ui.terrainMapper.TerrainMapperScreen;
import com.stonebreak.ui.worldSelect.WorldSelectScreen;
import com.stonebreak.util.*;
import com.stonebreak.world.*;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.model.WorldData;

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
    private BlockTextureArray textureAtlas;
    private PauseMenu pauseMenu;
    private DeathMenu deathMenu;
    private InventoryScreen inventoryScreen; // Added InventoryScreen
    private CharacterScreen characterScreen; // Character stats screen
    private com.stonebreak.ui.statisticsScreen.StatisticsScreen statisticsScreen; // Statistics screen
    private com.stonebreak.ui.glossaryScreen.GlossaryScreen glossaryScreen; // Entity Glossary screen
    private WorkbenchScreen workbenchScreen; // Added WorkbenchScreen
    private FurnaceScreen furnaceScreen; // Furnace smelting GUI
    private RecipeScreen recipeScreen; // Added RecipeBookScreen
    private WaterEffects waterEffects; // Water effects manager
    private InputHandler inputHandler; // Added InputHandler field
    private MouseCaptureManager mouseCaptureManager; // Mouse capture system
    private MainMenu mainMenu; // Main menu
    private SettingsMenu settingsMenu; // Settings menu
    private com.stonebreak.ui.multiplayerMenu.MultiplayerMenu multiplayerMenu;
    private com.stonebreak.ui.multiplayerMenu.HostWorldScreen hostWorldScreen;
    private com.stonebreak.ui.multiplayerMenu.JoinWorldScreen joinWorldScreen;
    private SoundSystem soundSystem; // Sound system (engine)
    private PlayerSounds playerSounds; // Game-side player footstep sound binding
    private ChatSystem chatSystem; // Chat system
    private CraftingManager craftingManager; // Crafting manager
    private SmeltingManager smeltingManager; // Smelting manager for furnace
    private com.stonebreak.blocks.furnace.FurnaceStateRegistry furnaceRegistry; // Per-position furnace state
    private com.stonebreak.audio.emitters.SoundEmitterManager soundEmitterManager; // Sound emitter management
    private MemoryLeakDetector memoryLeakDetector; // Memory leak detection system
    private DebugOverlay debugOverlay; // Debug overlay (F3)
    private LoadingScreen loadingScreen; // Loading screen for world generation
    private WorldSelectScreen worldSelectScreen; // World selection screen
    private CharacterCreationScreen characterCreationScreen; // Character creation before terrain mapper
    private TerrainMapperScreen terrainMapperScreen; // Terrain preview + world creation screen
    private SonarArtsIntroScreen startupIntroScreen; // Boot-time Sonar Arts animation
    private SaveService saveService; // World save/load system
    private WorldData currentWorldData; // Current world metadata
    private String currentWorldName; // Current world name for save system initialization
    private long currentWorldSeed; // Current world seed for save system initialization
    private final ExecutorService worldUpdateExecutor = Executors.newSingleThreadExecutor();
    private final Thread mainThread = Thread.currentThread();
    private final java.util.Queue<Runnable> mainThreadTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    
    // Entity system components
    private com.stonebreak.mobs.entities.EntityManager entityManager; // Entity management system
    private com.stonebreak.mobs.entities.EntitySpawner entitySpawner; // Entity spawning system


    // Time of day system
    private TimeOfDay timeOfDay; // Day/night cycle system

    // Game state
    private final com.stonebreak.core.state.GameStateController stateController =
            new com.stonebreak.core.state.GameStateController(this);
    private long lastFrameTime;
    private float deltaTime;
    private float totalTimeElapsed = 0.0f; // Added to track total time for animations
    private final com.stonebreak.core.loop.GameLoop gameLoop =
            new com.stonebreak.core.loop.GameLoop(this, worldUpdateExecutor);
    private final com.stonebreak.core.world.WorldLifecycle worldLifecycle =
            new com.stonebreak.core.world.WorldLifecycle(this);
    private final com.stonebreak.core.world.WorldGenerationCoordinator worldGenerationCoordinator =
            new com.stonebreak.core.world.WorldGenerationCoordinator(this, worldLifecycle);
    
    
    // Cheat system
    private boolean cheatsEnabled = false;

    // Window dimensions and handle
    private long window;
    private int windowWidth;
    private int windowHeight;

    /**
     * Private constructor for singleton pattern.
     */
    private Game() {
        lastFrameTime = System.nanoTime();
    }
    
    /**
     * Gets the singleton instance.
     * No synchronization needed: always created on the main thread during startup,
     * before any background threads are spawned. Post-init access is read-only.
     */
    public static Game getInstance() {
        if (instance == null) {
            instance = new Game();
        }
        return instance;
    }

    /**
     * Runs {@code task} immediately if called from the main thread, or queues
     * it to execute at the start of the next {@link #update()} call otherwise.
     * Use this to defer OpenGL operations that originate on background threads.
     */
    public void runOnMainThread(Runnable task) {
        if (Thread.currentThread() == mainThread) {
            task.run();
        } else {
            mainThreadTasks.offer(task);
        }
    }

    /**
     * Initializes core game components that don't require a world or player.
     * This includes renderer, sound system, UI components, and basic systems.
     */
    public void initCoreComponents(Renderer renderer, BlockTextureArray textureAtlas, InputHandler inputHandler, long window) {
        this.window = window;
        this.renderer = renderer;
        this.textureAtlas = textureAtlas;
        this.inputHandler = inputHandler;

        this.mouseCaptureManager = new MouseCaptureManager(window);
        this.pauseMenu = new PauseMenu(this.renderer.getSkijaBackend());
        this.statisticsScreen = new com.stonebreak.ui.statisticsScreen.StatisticsScreen(this.renderer.getSkijaBackend());
        this.glossaryScreen = new com.stonebreak.ui.glossaryScreen.GlossaryScreen(this.renderer.getSkijaBackend());
        this.deathMenu = new DeathMenu(this.renderer.getSkijaBackend());
        this.waterEffects = new WaterEffects();

        this.soundSystem = SoundSystem.getInstance();
        com.stonebreak.core.bootstrap.GameBootstrap.configureSoundSystem(this.soundSystem);

        this.mainMenu = new MainMenu(this.renderer.getSkijaBackend());
        this.settingsMenu = new SettingsMenu(this.renderer.getSkijaBackend());
        this.multiplayerMenu = new com.stonebreak.ui.multiplayerMenu.MultiplayerMenu(this.renderer.getSkijaBackend());
        this.hostWorldScreen = new com.stonebreak.ui.multiplayerMenu.HostWorldScreen(this.renderer.getSkijaBackend());
        this.joinWorldScreen = new com.stonebreak.ui.multiplayerMenu.JoinWorldScreen(this.renderer.getSkijaBackend());
        this.loadingScreen = new LoadingScreen(this.renderer.getSkijaBackend());
        this.worldSelectScreen = new WorldSelectScreen(this.renderer.getSkijaBackend());
        this.characterCreationScreen = new CharacterCreationScreen(this.renderer.getSkijaBackend());
        this.terrainMapperScreen = new TerrainMapperScreen(this.renderer.getSkijaBackend());
        this.startupIntroScreen = new SonarArtsIntroScreen(this.renderer.getSkijaBackend());

        initializeCrosshairSettings();

        this.craftingManager = new CraftingManager();
        this.smeltingManager = new SmeltingManager();
        initializeSmeltingRecipes();
        initializeCraftingRecipes();
        this.furnaceRegistry = new com.stonebreak.blocks.furnace.FurnaceStateRegistry(this.smeltingManager);

        this.chatSystem = new ChatSystem();
        this.chatSystem.addMessage("Welcome to Stonebreak!", new float[]{1.0f, 1.0f, 0.0f, 1.0f});

        this.soundEmitterManager = new com.stonebreak.audio.emitters.SoundEmitterManager();

        this.memoryLeakDetector = com.stonebreak.core.bootstrap.GameBootstrap.startMemoryLeakDetection();
        this.debugOverlay = com.stonebreak.core.bootstrap.GameBootstrap.createDebugOverlay();
        com.stonebreak.core.bootstrap.GameBootstrap.initializeEntityAssets();
        com.stonebreak.core.bootstrap.GameBootstrap.configureEngine(renderer.getBlockTextureArray(), renderer);

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

        com.stonebreak.core.bootstrap.GameBootstrap.ensureMmsApiInitialized(renderer.getBlockTextureArray(), world);
        com.stonebreak.core.bootstrap.GameBootstrap.reinitializeSaveService(saveService, currentWorldData, player, world);

        // Apply character creation stats to the new player if a creation session was active.
        if (characterCreationScreen != null) {
            com.stonebreak.player.CharacterStats pending = characterCreationScreen.getCharacterStats();
            com.stonebreak.player.CharacterStats live = player.getCharacterStats();
            live.restore(
                pending.getSelectedClassId(),
                new java.util.HashMap<>(pending.getSpentAbilityCp()),
                new java.util.HashMap<>(pending.getSkillLevels()),
                new java.util.HashSet<>(pending.getAcquiredFeatIds()),
                pending.getRemainingCp(),
                pending.getRemainingSkillPoints(),
                pending.getRemainingFeatPoints(),
                pending.getAbilityScores(),
                pending.getRemainingAp(),
                pending.getLevel(),
                pending.getXp()
            );
            live.setSelectedBackground(pending.getSelectedBackground());
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
        world.setEntityManager(this.entityManager);
        System.out.println("Entity system initialized - cows can now spawn!");

        // Note: TimeOfDay initialization is handled during world loading/generation
        // For new worlds: Set to NOON in performInitialWorldGeneration()
        // For existing worlds: Loaded from save data in performWorldLoadingOrGeneration()
        // This ensures default time is only applied to NEW worlds, not existing ones

        // Initialize InventoryScreen - requires Player, Renderer, BlockTextureArray, and InputHandler
        if (renderer.getFont() != null && textureAtlas != null) {
            this.inventoryScreen = new InventoryScreen(player.getInventory(), renderer.getFont(),
                renderer, this.renderer.getUIRenderer(), this.inputHandler, this.craftingManager,
                player.getCharacterStats());
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
            System.err.println("Failed to initialize InventoryScreen due to null components (Player, Inventory, Renderer, Font, BlockTextureArray, InputHandler, or CraftingManager).");
        }

        // Initialize CharacterScreen
        this.characterScreen = new CharacterScreen(player, renderer, this.inputHandler);

        // Initialize WorkbenchScreen
        if (this.renderer.getUIRenderer() != null) {
            this.workbenchScreen = new WorkbenchScreen(this, player.getInventory(), renderer, this.renderer.getUIRenderer(), this.inputHandler, this.craftingManager);
        } else {
            System.err.println("Failed to initialize WorkbenchScreen due to null components.");
        }

        // Initialize FurnaceScreen
        if (this.renderer.getUIRenderer() != null) {
            this.furnaceScreen = new FurnaceScreen(this, player.getInventory(), renderer, this.renderer.getUIRenderer(), this.inputHandler, this.smeltingManager);
        } else {
            System.err.println("Failed to initialize FurnaceScreen due to null components.");
        }

        // Initialize RecipeBookScreen
        if (this.renderer.getUIRenderer() != null && this.craftingManager != null && getFont() != null) {
            this.recipeScreen = new RecipeScreen(this.renderer.getUIRenderer(), this.inputHandler, renderer);
        } else {
            System.err.println("Failed to initialize RecipeBookScreen due to null UIRenderer, CraftingManager, or Font.");
        }

        // Initialize player sounds (game-side binding over the engine SoundSystem)
        this.playerSounds = new PlayerSounds(world);
        System.out.println("Player sound system initialized");

        System.out.println("[WORLD-CREATION] World components initialized for new world");
    }

    /**
     * Initializes all crafting recipes by harvesting them from registered SBO files.
     */
    private void initializeCraftingRecipes() {
        com.stonebreak.crafting.RecipeLoader.loadFromSBOs(this.craftingManager);
    }

    /**
     * Registers smelting recipes and fuel sources by harvesting them from
     * registered SBO files. Mirrors {@link #initializeCraftingRecipes()}.
     */
    private void initializeSmeltingRecipes() {
        com.stonebreak.crafting.SmeltingRecipeLoader.loadFromSBOs(this.smeltingManager);
    }


    
    /**
     * Applies saved crosshair settings. Delegates to
     * {@link com.stonebreak.core.bootstrap.CrosshairConfigurator}.
     */
    private void initializeCrosshairSettings() {
        com.stonebreak.core.bootstrap.CrosshairConfigurator.apply(this.renderer);
    }

    /**
     * Per-frame tick: computes delta time, accumulates total time, then
     * delegates to {@link com.stonebreak.core.loop.GameLoop#tick(float)}.
     */
    public void update() {
        long currentTime = System.nanoTime();
        deltaTime = (currentTime - lastFrameTime) / 1_000_000_000.0f;
        lastFrameTime = currentTime;

        if (deltaTime > 0.1f) {
            deltaTime = 0.1f;
        }

        totalTimeElapsed += deltaTime;

        Runnable task;
        while ((task = mainThreadTasks.poll()) != null) {
            task.run();
        }

        gameLoop.tick(deltaTime);
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

    /** Delegates to {@link com.stonebreak.core.state.GameStateController#togglePauseMenu()}. */
    public void togglePauseMenu() {
        stateController.togglePauseMenu();
    }

    public void openStatisticsScreen()  { stateController.openStatisticsScreen(); }
    public void closeStatisticsScreen() { stateController.closeStatisticsScreen(); }
    public void openGlossaryScreen()  { stateController.openGlossaryScreen(); }
    public void closeGlossaryScreen() { stateController.closeGlossaryScreen(); }

    public com.stonebreak.core.state.GameStateController getStateController() { return stateController; }

    /** Checks if the game is paused. */
    public boolean isPaused() {
        return stateController.isPaused();
    }
    
    /**
     * Gets the pause menu.
     */
    public PauseMenu getPauseMenu() {
        return pauseMenu;
    }

    public com.stonebreak.ui.statisticsScreen.StatisticsScreen getStatisticsScreen() {
        return statisticsScreen;
    }

    public com.stonebreak.ui.glossaryScreen.GlossaryScreen getGlossaryScreen() {
        return glossaryScreen;
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
     * Gets the furnace screen.
     */
    public FurnaceScreen getFurnaceScreen() {
        return furnaceScreen;
    }

    /**
     * Gets the smelting manager.
     */
    public SmeltingManager getSmeltingManager() {
        return smeltingManager;
    }

    public com.stonebreak.blocks.furnace.FurnaceStateRegistry getFurnaceRegistry() {
        return furnaceRegistry;
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

    /** Delegates to {@link com.stonebreak.core.state.GameStateController#toggleInventoryScreen()}. */
    public void toggleInventoryScreen() {
        stateController.toggleInventoryScreen();
    }

    /**
     * Gets the character screen.
     */
    public CharacterScreen getCharacterScreen() {
        return characterScreen;
    }

    /** Delegates to {@link com.stonebreak.core.state.GameStateController#toggleCharacterScreen()}. */
    public void toggleCharacterScreen() {
        stateController.toggleCharacterScreen();
    }

    /** Opens the character screen at the given tab. */
    public void openCharacterTab(CharacterPanelTab tab) {
        stateController.openCharacterTab(tab);
    }

    /** Gets the current game state. */
    public GameState getState() {
        return stateController.getState();
    }

    /** Sets the current game state. */
    public void setState(GameState state) {
        stateController.setState(state);
    }

    /** Gets the previous game state. */
    public GameState getPreviousGameState() {
        return stateController.getPreviousGameState();
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

    public com.stonebreak.ui.multiplayerMenu.MultiplayerMenu getMultiplayerMenu() {
        return multiplayerMenu;
    }

    public com.stonebreak.ui.multiplayerMenu.HostWorldScreen getHostWorldScreen() {
        return hostWorldScreen;
    }

    public com.stonebreak.ui.multiplayerMenu.JoinWorldScreen getJoinWorldScreen() {
        return joinWorldScreen;
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
     * Gets the player sound binding (footstep selection). May be null before a world is loaded.
     */
    public static PlayerSounds getPlayerSounds() {
        return getInstance().playerSounds;
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
     * @return The BlockTextureArray object, or null if not available.
     */
    public BlockTextureArray getBlockTextureArray() {
        return this.textureAtlas;
    }

    /** Delegates to {@link com.stonebreak.core.state.GameStateController#openWorkbenchScreen()}. */
    public void openWorkbenchScreen() {
        stateController.openWorkbenchScreen();
    }

    /** Delegates to {@link com.stonebreak.core.state.GameStateController#openRecipeBookScreen()}. */
    public void openRecipeBookScreen() {
        stateController.openRecipeBookScreen();
    }

    /** Delegates to {@link com.stonebreak.core.state.GameStateController#closeRecipeBookScreen()}. */
    public void closeRecipeBookScreen() {
        stateController.closeRecipeBookScreen();
    }

    /** Delegates to {@link com.stonebreak.core.state.GameStateController#closeWorkbenchScreen()}. */
    public void closeWorkbenchScreen() {
        stateController.closeWorkbenchScreen();
    }

    /** Delegates to {@link com.stonebreak.core.state.GameStateController#openFurnaceScreen(com.openmason.engine.util.BlockPos)}. */
    public void openFurnaceScreen(com.openmason.engine.util.BlockPos pos) {
        stateController.openFurnaceScreen(pos);
    }

    /** Delegates to {@link com.stonebreak.core.state.GameStateController#closeFurnaceScreen()}. */
    public void closeFurnaceScreen() {
        stateController.closeFurnaceScreen();
    }
    
    /**
     * Cleanup game resources. Delegates to
     * {@link com.stonebreak.core.lifecycle.GameShutdown#shutdown(Game, java.util.concurrent.ExecutorService)}.
     */
    public void cleanup() {
        com.stonebreak.core.lifecycle.GameShutdown.shutdown(this, worldUpdateExecutor);
    }

    /** Delegates to {@link com.stonebreak.core.world.WorldLifecycle#resetWorld()}. */
    public void resetWorld() {
        worldLifecycle.resetWorld();
    }

    /** Delegates to {@link com.stonebreak.core.diagnostics.GameDiagnostics#displayDebugInfo()}. */
    public static void displayDebugInfo() {
        com.stonebreak.core.diagnostics.GameDiagnostics.displayDebugInfo();
    }

    /** Delegates to {@link com.stonebreak.core.diagnostics.GameDiagnostics#logDetailedMemoryInfo(String)}. */
    public static void logDetailedMemoryInfo(String context) {
        com.stonebreak.core.diagnostics.GameDiagnostics.logDetailedMemoryInfo(context);
    }

    /** Delegates to {@link com.stonebreak.core.diagnostics.GameDiagnostics#forceGCAndReport(String)}. */
    public static void forceGCAndReport(String context) {
        com.stonebreak.core.diagnostics.GameDiagnostics.forceGCAndReport(context);
    }

    /** Delegates to {@link com.stonebreak.core.diagnostics.GameDiagnostics#reportAllocations()}. */
    public static void reportAllocations() {
        com.stonebreak.core.diagnostics.GameDiagnostics.reportAllocations();
    }

    /** Delegates to {@link com.stonebreak.core.diagnostics.GameDiagnostics#printDetailedMemoryProfile()}. */
    public static void printDetailedMemoryProfile() {
        com.stonebreak.core.diagnostics.GameDiagnostics.printDetailedMemoryProfile();
    }
    
    /**
     * Sets the runtime cheats flag. Does not modify any world's persisted
     * cheats state — use {@link #applyCheatsToCurrentWorld(boolean)} when
     * the user toggles cheats from inside a world session.
     */
    public void setCheatsEnabled(boolean enabled) {
        this.cheatsEnabled = enabled;
    }

    /**
     * Toggles cheats for the active world: updates the runtime flag, the
     * in-memory {@link WorldData}, and the {@link com.stonebreak.world.save.SaveService}
     * so the change persists on the next save. No-op when no world is loaded.
     */
    public void applyCheatsToCurrentWorld(boolean enabled) {
        this.cheatsEnabled = enabled;
        if (currentWorldData != null) {
            WorldData updated = currentWorldData.withCheatsEnabled(enabled);
            this.currentWorldData = updated;
            if (saveService != null && player != null && world != null) {
                saveService.initialize(updated, player, world);
            }
        }
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
    
    /** Delegates to {@link com.stonebreak.core.diagnostics.GameDiagnostics#triggerMemoryLeakAnalysis()}. */
    public static void triggerMemoryLeakAnalysis() {
        com.stonebreak.core.diagnostics.GameDiagnostics.triggerMemoryLeakAnalysis();
    }
    
    /**
     * Gets the debug overlay.
     */
    public static DebugOverlay getDebugOverlay() {
        return getInstance().debugOverlay;
    }
    
    /** Delegates to {@link com.stonebreak.core.diagnostics.GameDiagnostics#toggleDebugOverlay()}. */
    public static void toggleDebugOverlay() {
        com.stonebreak.core.diagnostics.GameDiagnostics.toggleDebugOverlay();
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
     * Gets the character creation screen.
     */
    public CharacterCreationScreen getCharacterCreationScreen() {
        return characterCreationScreen;
    }

    /**
     * Gets the terrain mapper screen (preview + world creation).
     */
    public TerrainMapperScreen getTerrainMapperScreen() {
        return terrainMapperScreen;
    }

    /**
     * Gets the boot-time Sonar Arts intro screen.
     */
    public SonarArtsIntroScreen getStartupIntroScreen() {
        return startupIntroScreen;
    }

    /**
     * Gets the world save system for manual save operations.
     */
    public SaveService getSaveService() {
        return saveService;
    }

    // ---- Coordinator-facing setters / getters (package-private) ----
    // These exist solely so WorldLifecycle / WorldGenerationCoordinator can
    // mutate fields whose public API (getters above) lives on Game.

    public void setSaveService(SaveService saveService) {
        this.saveService = saveService;
    }

    public WorldData getCurrentWorldData() {
        return currentWorldData;
    }

    public void setCurrentWorldData(WorldData currentWorldData) {
        this.currentWorldData = currentWorldData;
    }

    public String getCurrentWorldName() {
        return currentWorldName;
    }

    public void setCurrentWorldName(String currentWorldName) {
        this.currentWorldName = currentWorldName;
    }

    public long getCurrentWorldSeed() {
        return currentWorldSeed;
    }

    public void setCurrentWorldSeed(long currentWorldSeed) {
        this.currentWorldSeed = currentWorldSeed;
    }

    public void setTimeOfDay(TimeOfDay timeOfDay) {
        this.timeOfDay = timeOfDay;
    }

    // ---- World generation + lifecycle delegates ----

    /** Delegates to {@link com.stonebreak.core.world.WorldGenerationCoordinator#startWorldGeneration()}. */
    public void startWorldGeneration() {
        worldGenerationCoordinator.startWorldGeneration();
    }

    /** Delegates to {@link com.stonebreak.core.world.WorldGenerationCoordinator#startWorldGeneration(String, long)}. */
    public void startWorldGeneration(String worldName, long seed) {
        worldGenerationCoordinator.startWorldGeneration(worldName, seed);
    }

    /**
     * Build the client RENDER world (two-world model): a {@code World.createClientView} world
     * that generates no terrain and carries no {@link SaveService} — its chunks, entities, and
     * blocks stream in from the authoritative server. Called from the client world view on
     * {@code WelcomeS2C}. Shows the loading screen until the spawn chunk has streamed in, then
     * enters play (the loading screen's hide() transitions to PLAYING).
     */
    public void startClientWorld(String worldName, long seed, org.joml.Vector3f spawn) {
        // The client never persists — drop any save service so the chunk store stays read-only.
        SaveService prev = this.saveService;
        if (prev != null) {
            try { prev.stopAutoSave(); prev.close(); } catch (Exception ignored) { }
            this.saveService = null;
        }
        setCurrentWorldName(worldName);
        setCurrentWorldSeed(seed);
        setCurrentWorldData(null);

        LoadingScreen ls = getLoadingScreen();
        if (ls != null) {
            ls.show();
        }
        new Thread(() -> buildClientWorld(seed, spawn), "ClientWorld-Build").start();
    }

    private void buildClientWorld(long seed, org.joml.Vector3f spawn) {
        try {
            World renderWorld = worldLifecycle.createClientWorldInstance(seed);
            worldLifecycle.replaceWorldInstance(renderWorld); // fresh player + world components
            renderWorld.setSpawnPosition(spawn);
            Player p = Game.getPlayer();
            if (p != null) {
                p.setPosition(spawn);
            }

            // Restore the local player's saved data NOW, on this thread, applied to the player we
            // just created (p). Doing it here — rather than from the main-thread tick reading
            // Game.getPlayer() — avoids the re-open bug where the restore hit the previous
            // session's stale player. For SP/host this is synchronous; for JOIN it's a no-op
            // (the data arrives via PlayerDataS2C and is applied by the client view).
            com.stonebreak.network.MultiplayerSession.restoreLocalPlayer(p);

            if (this.timeOfDay == null) {
                // Client clock is server-authoritative; seed a sane default until TimeSync lands.
                setTimeOfDay(new TimeOfDay(TimeOfDay.NOON));
            }

            // Wait (bounded) for the spawn chunk to stream in so the player doesn't fall into
            // void before terrain arrives. The client tick (which installs chunks) runs during
            // LOADING because GameLoop pumps the network before routing state updates.
            // Wait for two things before entering PLAY:
            //  1. the spawn chunk is STREAMED IN (present) — not meshed; meshing happens in
            //     PLAYING and the player physics guard holds the player until it renders.
            //  2. the local player's saved data has been RESTORED — otherwise the player would
            //     briefly appear with an empty inventory before the restore lands (visible
            //     especially on a fast same-world re-open).
            int scx = (int) Math.floor(spawn.x / 16.0);
            int scz = (int) Math.floor(spawn.z / 16.0);
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline
                    && (renderWorld.getChunkIfLoaded(scx, scz) == null
                        || !com.stonebreak.network.MultiplayerSession.isLocalPlayerDataReady())) {
                Thread.sleep(50);
            }

            LoadingScreen ls = getLoadingScreen();
            if (ls != null) {
                ls.hide(); // → GameState.PLAYING
            }
            if (mouseCaptureManager != null) {
                mouseCaptureManager.forceUpdate();
            }
        } catch (Exception e) {
            System.err.println("[CLIENT-WORLD] Failed to build render world: " + e.getMessage());
            e.printStackTrace();
            LoadingScreen ls = getLoadingScreen();
            if (ls != null) {
                ls.hide();
            }
        }
    }

    /** Delegates to {@link com.stonebreak.core.world.WorldGenerationCoordinator#completeWorldGeneration()}. */
    public void completeWorldGeneration() {
        worldGenerationCoordinator.completeWorldGeneration();
    }
}
