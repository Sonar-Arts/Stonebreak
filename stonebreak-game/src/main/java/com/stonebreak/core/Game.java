package com.stonebreak.core;

import java.util.concurrent.*;

import com.stonebreak.rendering.textures.BlockTextureArray;
import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.audio.*;
import com.stonebreak.blocks.*;
import com.stonebreak.core.cheats.CheatState;
import com.stonebreak.core.services.GameServices;
import com.stonebreak.core.world.WorldSession;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central class for accessing game state and resources.
 */
public class Game {

    private static final Logger logger = LoggerFactory.getLogger(Game.class);

    // Singleton instance
    private static Game instance;
    
    // Game components
    /** Subsystem registry backing every {@code getX()} accessor; see {@link GameServices}. */
    private final GameServices services = new GameServices();
    /** Current world name/seed/data/save-service bookkeeping; see {@link WorldSession}. */
    private final WorldSession worldSession = new WorldSession();
    /** Every UI screen; see {@link com.stonebreak.core.screens.GameScreens}. */
    private final com.stonebreak.core.screens.GameScreens screens =
            new com.stonebreak.core.screens.GameScreens();
    private final ExecutorService worldUpdateExecutor = Executors.newSingleThreadExecutor();
    private final Thread mainThread = Thread.currentThread();
    private final java.util.Queue<Runnable> mainThreadTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();

    // NOTE: mob spawning is server-authoritative — the sole EntitySpawner lives on the
    // ServerLevel (two-world model). The local client world is render-only and never spawns.

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
    /** Builds the streamed client render world; see its docs for the generation-stamp rules. */
    private final com.stonebreak.core.world.ClientWorldBuilder clientWorldBuilder =
            new com.stonebreak.core.world.ClientWorldBuilder(this, worldLifecycle);


    // Cheat system
    private final CheatState cheats = new CheatState(services, worldSession);

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
        services.setRenderer(renderer);
        services.setTextureAtlas(textureAtlas);
        services.setInputHandler(inputHandler);

        services.setMouseCaptureManager(new MouseCaptureManager(window));

        // Audio comes up BEFORE the screens: the startup intro registers its sonar sound from
        // its constructor, and OpenAL calls made before the context exists abort with
        // "No ALCapabilities instance has been set".
        SoundSystem soundSystem = SoundSystem.getInstance();
        services.setSoundSystem(soundSystem);
        com.stonebreak.core.bootstrap.GameBootstrap.configureSoundSystem(soundSystem);
        com.stonebreak.audio.MusicManager musicManager = new com.stonebreak.audio.MusicManager(soundSystem);
        musicManager.setVolume(com.stonebreak.config.Settings.getInstance().getMusicVolume());
        musicManager.setEnabled(com.stonebreak.config.Settings.getInstance().getMusicEnabled());
        services.setMusicManager(musicManager);

        screens.createShellScreens(renderer);

        initializeCrosshairSettings();

        services.setCraftingManager(new CraftingManager());
        services.setSmeltingManager(new SmeltingManager());
        initializeSmeltingRecipes();
        initializeCraftingRecipes();
        // Furnace registries are PER-WORLD now (each World constructs its own with this
        // smelting manager); getFurnaceRegistry() delegates to the current world.

        ChatSystem chatSystem = new ChatSystem();
        chatSystem.addMessage("Welcome to Stonebreak!", new float[]{1.0f, 1.0f, 0.0f, 1.0f});
        services.setChatSystem(chatSystem);

        services.setSoundEmitterManager(new com.stonebreak.audio.emitters.SoundEmitterManager());

        services.setMemoryLeakDetector(com.stonebreak.core.bootstrap.GameBootstrap.startMemoryLeakDetection());
        services.setDebugOverlay(com.stonebreak.core.bootstrap.GameBootstrap.createDebugOverlay());
        com.stonebreak.core.bootstrap.GameBootstrap.initializeEntityAssets();
        com.stonebreak.core.bootstrap.GameBootstrap.configureEngine(renderer.getBlockTextureArray(), renderer);

        // Mount the settings-persisted cosmetic hat (Looks tab) on the local
        // player's hat socket — a static attachment that outlives world loads.
        com.stonebreak.player.PlayerLooks.applySelectedHat();

        logger.debug("[STARTUP] Core components initialized (no world/player yet)");
    }

    /**
     * Initializes components that require a world and player.
     * This should be called when a world is created/loaded.
     *
     * NOTE: MmsAPI must be initialized BEFORE World is created (World constructor depends on it).
     * NOTE: World reference is automatically set in MmsAPI during World construction via createMeshPipeline().
     */
    public void initWorldComponents(World world, Player player) {
        services.setWorld(world);
        services.setPlayer(player);

        Renderer renderer = services.renderer();
        com.stonebreak.core.bootstrap.GameBootstrap.ensureMmsApiInitialized(renderer.getBlockTextureArray(), world);
        com.stonebreak.core.bootstrap.GameBootstrap.reinitializeSaveService(
                worldSession.saveService(), worldSession.currentWorldData(), player, world);

        // Apply character creation stats to the new player if a creation session was active.
        CharacterCreationScreen creationScreen = screens.characterCreationScreen();
        if (creationScreen != null) {
            com.stonebreak.player.CharacterStats pending = creationScreen.getCharacterStats();
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
        MouseCaptureManager mouseCaptureManager = services.mouseCaptureManager();
        if (mouseCaptureManager != null && player != null) {
            mouseCaptureManager.setCamera(player.getCamera());
        }

        // Initialize the client-side entity system. This EntityManager holds network-shadow
        // entities streamed from the server; it owns no spawner (spawning is server-authoritative,
        // driven by ServerLevel's EntitySpawner — the single source of truth).
        com.stonebreak.mobs.entities.EntityManager entityManager =
                new com.stonebreak.mobs.entities.EntityManager(world);
        services.setEntityManager(entityManager);
        world.setEntityManager(entityManager);
        System.out.println("Client entity system initialized (network shadows).");

        // Note: TimeOfDay initialization is handled during world loading/generation
        // For new worlds: Set to NOON in performInitialWorldGeneration()
        // For existing worlds: Loaded from save data in performWorldLoadingOrGeneration()
        // This ensures default time is only applied to NEW worlds, not existing ones

        screens.createWorldScreens(this, player, renderer, services.inputHandler(),
                services.craftingManager(), services.smeltingManager());
        // Surface a tooltip for whatever is already selected in the hotbar.
        InventoryScreen inventory = screens.inventoryScreen();
        if (inventory != null) {
            ItemStack selected = player.getInventory()
                    .getHotbarSlot(player.getInventory().getSelectedHotbarSlotIndex());
            if (selected != null && !selected.isEmpty() && selected.getItem() instanceof BlockType blockType) {
                inventory.displayHotbarItemTooltip(blockType);
            }
        }

        // Initialize player sounds (game-side binding over the engine SoundSystem)
        services.setPlayerSounds(new PlayerSounds(world));
        System.out.println("Player sound system initialized");

        System.out.println("[WORLD-CREATION] World components initialized for new world");
    }

    /**
     * Initializes all crafting recipes by harvesting them from registered SBO files.
     */
    private void initializeCraftingRecipes() {
        com.stonebreak.crafting.RecipeLoader.loadFromSBOs(services.craftingManager());
    }

    /**
     * Registers smelting recipes and fuel sources by harvesting them from
     * registered SBO files. Mirrors {@link #initializeCraftingRecipes()}.
     */
    private void initializeSmeltingRecipes() {
        com.stonebreak.crafting.SmeltingRecipeLoader.loadFromSBOs(services.smeltingManager());
    }


    
    /**
     * Applies saved crosshair settings. Delegates to
     * {@link com.stonebreak.core.bootstrap.CrosshairConfigurator}.
     */
    private void initializeCrosshairSettings() {
        com.stonebreak.core.bootstrap.CrosshairConfigurator.apply(services.renderer());
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
        return getInstance().services.world();
    }
    
    /**
     * Gets the player.
     */
    public static Player getPlayer() {
        return getInstance().services.player();
    }
    
    /**
     * Gets the renderer.
     */
    public static Renderer getRenderer() {
        return getInstance().services.renderer();
    }
    
    /**
     * Gets the entity manager.
     */
    public static com.stonebreak.mobs.entities.EntityManager getEntityManager() {
        return getInstance().services.entityManager();
    }


    /**
     * Gets the time of day system.
     */
    public static TimeOfDay getTimeOfDay() {
        return getInstance().services.timeOfDay();
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
        return screens.pauseMenu();
    }

    public com.stonebreak.ui.statisticsScreen.StatisticsScreen getStatisticsScreen() {
        return screens.statisticsScreen();
    }

    public com.stonebreak.ui.glossaryScreen.GlossaryScreen getGlossaryScreen() {
        return screens.glossaryScreen();
    }

    /**
     * Gets the death menu.
     */
    public DeathMenu getDeathMenu() {
        return screens.deathMenu();
    }

    /**
     * Gets the inventory screen.
     */
    public InventoryScreen getInventoryScreen() {
        return screens.inventoryScreen();
    }

    /**
     * Gets the workbench screen.
     */
    public WorkbenchScreen getWorkbenchScreen() {
        return screens.workbenchScreen();
    }

    /**
     * Gets the furnace screen.
     */
    public FurnaceScreen getFurnaceScreen() {
        return screens.furnaceScreen();
    }

    /**
     * Gets the smelting manager.
     */
    public SmeltingManager getSmeltingManager() {
        return services.smeltingManager();
    }

    /**
     * The CURRENT world's furnace registry (per-world since the two-world furnace split),
     * or null before a world exists. On a client render world this is the display registry
     * fed by server echoes; the smelting registry lives on the headless server world.
     */
    public com.stonebreak.blocks.furnace.FurnaceStateRegistry getFurnaceRegistry() {
        World w = getWorld();
        return w != null ? w.getFurnaceRegistry() : null;
    }
    
    /**
     * Gets the input handler.
     */
    public InputHandler getInputHandler() {
       return services.inputHandler();
   }

   /**
     * Gets the mouse capture manager.
     */
    public MouseCaptureManager getMouseCaptureManager() {
        return services.mouseCaptureManager();
    }

    /** Delegates to {@link com.stonebreak.core.state.GameStateController#toggleInventoryScreen()}. */
    public void toggleInventoryScreen() {
        stateController.toggleInventoryScreen();
    }

    /**
     * Gets the character screen.
     */
    public CharacterScreen getCharacterScreen() {
        return screens.characterScreen();
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
        return screens.recipeScreen();
    }

    /**
     * Gets the main menu.
     */
    public MainMenu getMainMenu() {
        return screens.mainMenu();
    }
    
    /**
     * Gets the settings menu.
     */
    public SettingsMenu getSettingsMenu() {
        return screens.settingsMenu();
    }

    public com.stonebreak.ui.multiplayerMenu.MultiplayerMenu getMultiplayerMenu() {
        return screens.multiplayerMenu();
    }

    public com.stonebreak.ui.multiplayerMenu.HostWorldScreen getHostWorldScreen() {
        return screens.hostWorldScreen();
    }

    public com.stonebreak.ui.multiplayerMenu.JoinWorldScreen getJoinWorldScreen() {
        return screens.joinWorldScreen();
    }
    
    /**
     * Gets the UI renderer.
     */
    public com.stonebreak.rendering.UI.UIRenderer getUIRenderer() {
        Renderer renderer = services.renderer();
        return renderer != null ? renderer.getUIRenderer() : null;
    }
    
    /**
     * Gets the sound system.
     */
    public static SoundSystem getSoundSystem() {
        return getInstance().services.soundSystem();
    }

    /**
     * Gets the player sound binding (footstep selection). May be null before a world is loaded.
     */
    public static PlayerSounds getPlayerSounds() {
        return getInstance().services.playerSounds();
    }

    /**
     * Gets the chat system.
     */
    public ChatSystem getChatSystem() {
        return services.chatSystem();
    }

    /**
     * Gets the crafting manager.
     */
    public static CraftingManager getCraftingManager() {
        return getInstance().services.craftingManager();
    }

    /**
     * Gets the sound emitter manager.
     */
    public static com.stonebreak.audio.emitters.SoundEmitterManager getSoundEmitterManager() {
        return getInstance().services.soundEmitterManager();
    }

    /**
     * Gets the background music manager.
     */
    public static com.stonebreak.audio.MusicManager getMusicManager() {
        return getInstance().services.musicManager();
    }

    /**
     * Gets the game's primary font.
     * This assumes the font is loaded and available via the Renderer.
     * @return The Font object, or null if not available.
     */
    public Font getFont() {
        Renderer renderer = services.renderer();
        if (renderer != null) {
            return renderer.getFont();
        }
        return null;
    }

    /**
     * Gets the game's texture atlas.
     * This assumes the texture atlas is loaded and available via the Renderer.
     * @return The BlockTextureArray object, or null if not available.
     */
    public BlockTextureArray getBlockTextureArray() {
        return services.textureAtlas();
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
    
    /** Delegates to {@link CheatState#setEnabled(boolean)}. */
    public void setCheatsEnabled(boolean enabled) {
        cheats.setEnabled(enabled);
    }

    /** Delegates to {@link CheatState#applyToCurrentWorld(boolean)}. */
    public void applyCheatsToCurrentWorld(boolean enabled) {
        cheats.applyToCurrentWorld(enabled);
    }

    /** Delegates to {@link CheatState#isEnabled()}. */
    public boolean isCheatsEnabled() {
        return cheats.isEnabled();
    }
    
    /**
     * Gets the memory leak detector.
     */
    public static MemoryLeakDetector getMemoryLeakDetector() {
        return getInstance().services.memoryLeakDetector();
    }
    
    /** Delegates to {@link com.stonebreak.core.diagnostics.GameDiagnostics#triggerMemoryLeakAnalysis()}. */
    public static void triggerMemoryLeakAnalysis() {
        com.stonebreak.core.diagnostics.GameDiagnostics.triggerMemoryLeakAnalysis();
    }
    
    /**
     * Gets the debug overlay.
     */
    public static DebugOverlay getDebugOverlay() {
        return getInstance().services.debugOverlay();
    }
    
    /** Delegates to {@link com.stonebreak.core.diagnostics.GameDiagnostics#toggleDebugOverlay()}. */
    public static void toggleDebugOverlay() {
        com.stonebreak.core.diagnostics.GameDiagnostics.toggleDebugOverlay();
    }
    
    /**
     * Gets the loading screen.
     */
    public LoadingScreen getLoadingScreen() {
        return screens.loadingScreen();
    }

    /**
     * Gets the world select screen.
     */
    public WorldSelectScreen getWorldSelectScreen() {
        return screens.worldSelectScreen();
    }

    /**
     * Gets the character creation screen.
     */
    public CharacterCreationScreen getCharacterCreationScreen() {
        return screens.characterCreationScreen();
    }

    /**
     * Gets the terrain mapper screen (preview + world creation).
     */
    public TerrainMapperScreen getTerrainMapperScreen() {
        return screens.terrainMapperScreen();
    }

    /**
     * Gets the boot-time Sonar Arts intro screen.
     */
    public SonarArtsIntroScreen getStartupIntroScreen() {
        return screens.startupIntroScreen();
    }

    /**
     * Gets the world save system for manual save operations.
     */
    public SaveService getSaveService() {
        return worldSession.saveService();
    }

    // ---- World-metadata setters / getters ----
    // Used by WorldLifecycle and the client-world bootstrap to mutate fields
    // whose public API (getters above) lives on Game.

    public void setSaveService(SaveService saveService) {
        worldSession.setSaveService(saveService);
    }

    public WorldData getCurrentWorldData() {
        return worldSession.currentWorldData();
    }

    public void setCurrentWorldData(WorldData currentWorldData) {
        worldSession.setCurrentWorldData(currentWorldData);
    }

    public String getCurrentWorldName() {
        return worldSession.currentWorldName();
    }

    public void setCurrentWorldName(String currentWorldName) {
        worldSession.setCurrentWorldName(currentWorldName);
    }

    public long getCurrentWorldSeed() {
        return worldSession.currentWorldSeed();
    }

    public void setCurrentWorldSeed(long currentWorldSeed) {
        worldSession.setCurrentWorldSeed(currentWorldSeed);
    }

    public void setTimeOfDay(TimeOfDay timeOfDay) {
        services.setTimeOfDay(timeOfDay);
    }

    // ---- World lifecycle (client render world) ----

    /** Invalidate any in-flight client-world build (called from session shutdown). */
    public void cancelClientWorldBuild() {
        clientWorldBuilder.cancel();
    }

    /**
     * True when inbound network state can be applied to {@code Game.getWorld()} /
     * {@code Game.getEntityManager()}: both exist AND no client-world build is mid-swap.
     */
    public static boolean isClientWorldReady() {
        Game game = instance;
        return game != null && game.clientWorldBuilder.isReady();
    }

    /**
     * Builds the client render world for a joined session. See
     * {@link com.stonebreak.core.world.ClientWorldBuilder}.
     */
    public void startClientWorld(String worldName, long seed, org.joml.Vector3f spawn) {
        clientWorldBuilder.start(worldName, seed, spawn);
    }
}
