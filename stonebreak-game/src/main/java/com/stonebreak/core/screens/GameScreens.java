package com.stonebreak.core.screens;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stonebreak.core.Game;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.LoadingScreen;
import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.characterCreation.CharacterCreationScreen;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.furnace.FurnaceScreen;
import com.stonebreak.ui.glossaryScreen.GlossaryScreen;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.multiplayerMenu.HostWorldScreen;
import com.stonebreak.ui.multiplayerMenu.JoinWorldScreen;
import com.stonebreak.ui.multiplayerMenu.MultiplayerMenu;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.startupIntro.SonarArtsIntroScreen;
import com.stonebreak.ui.statisticsScreen.StatisticsScreen;
import com.stonebreak.ui.terrainMapper.TerrainMapperScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import com.stonebreak.ui.worldSelect.WorldSelectScreen;

/**
 * Every screen the game can show, and the two points at which they come into existence.
 *
 * <p>Screens split cleanly by what they depend on. The <em>shell</em> screens — menus, the loading
 * screen, the intro — need only the renderer, so they exist for the whole run. The <em>in-world</em>
 * screens (inventory, character sheet, workbench, furnace, recipe book) need a live player and are
 * rebuilt whenever a world is entered.</p>
 */
public final class GameScreens {

    private static final Logger logger = LoggerFactory.getLogger(GameScreens.class);

    // Shell screens — created once from the renderer.
    private PauseMenu pauseMenu;
    private DeathMenu deathMenu;
    private StatisticsScreen statisticsScreen;
    private GlossaryScreen glossaryScreen;
    private MainMenu mainMenu;
    private SettingsMenu settingsMenu;
    private MultiplayerMenu multiplayerMenu;
    private HostWorldScreen hostWorldScreen;
    private JoinWorldScreen joinWorldScreen;
    private LoadingScreen loadingScreen;
    private WorldSelectScreen worldSelectScreen;
    private CharacterCreationScreen characterCreationScreen;
    private TerrainMapperScreen terrainMapperScreen;
    private SonarArtsIntroScreen startupIntroScreen;

    // In-world screens — rebuilt per world, so they stay null outside one.
    private InventoryScreen inventoryScreen;
    private CharacterScreen characterScreen;
    private WorkbenchScreen workbenchScreen;
    private FurnaceScreen furnaceScreen;
    private RecipeScreen recipeScreen;

    /**
     * Builds the screens that exist for the whole run.
     *
     * <p>Call this only once the sound system is initialized: {@link SonarArtsIntroScreen} registers
     * its sonar sample from its constructor, and an OpenAL call made before the context exists
     * aborts the process with "No ALCapabilities instance has been set".</p>
     */
    public void createShellScreens(Renderer renderer) {
        var skija = renderer.getSkijaBackend();

        pauseMenu = new PauseMenu(skija);
        statisticsScreen = new StatisticsScreen(skija);
        glossaryScreen = new GlossaryScreen(skija);
        deathMenu = new DeathMenu(skija);
        mainMenu = new MainMenu(skija);
        settingsMenu = new SettingsMenu(skija);
        multiplayerMenu = new MultiplayerMenu(skija);
        hostWorldScreen = new HostWorldScreen(skija);
        joinWorldScreen = new JoinWorldScreen(skija);
        loadingScreen = new LoadingScreen(skija);
        worldSelectScreen = new WorldSelectScreen(skija);
        characterCreationScreen = new CharacterCreationScreen(skija);
        terrainMapperScreen = new TerrainMapperScreen(skija);
        startupIntroScreen = new SonarArtsIntroScreen(skija);
    }

    /**
     * Builds the screens that need a live player. Each is skipped with a warning if its
     * dependencies are missing rather than failing the whole world load.
     */
    public void createWorldScreens(Game game, Player player, Renderer renderer, InputHandler inputHandler,
                                   CraftingManager craftingManager, SmeltingManager smeltingManager) {
        var uiRenderer = renderer.getUIRenderer();

        if (renderer.getFont() != null && game.getBlockTextureArray() != null) {
            inventoryScreen = new InventoryScreen(player.getInventory(), renderer.getFont(), renderer,
                    uiRenderer, inputHandler, craftingManager, player.getCharacterStats());
            // The inventory needs the back-reference to drive tooltips and hover state.
            player.getInventory().setInventoryScreen(inventoryScreen);
        } else {
            logger.error("Skipping InventoryScreen: renderer font or block texture array is missing");
        }

        characterScreen = new CharacterScreen(player, renderer, inputHandler);

        if (uiRenderer != null) {
            workbenchScreen = new WorkbenchScreen(game, player.getInventory(), renderer, uiRenderer,
                    inputHandler, craftingManager);
            furnaceScreen = new FurnaceScreen(game, player.getInventory(), renderer, uiRenderer,
                    inputHandler, smeltingManager);
        } else {
            logger.error("Skipping WorkbenchScreen and FurnaceScreen: UI renderer is missing");
        }

        if (uiRenderer != null && craftingManager != null && renderer.getFont() != null) {
            recipeScreen = new RecipeScreen(uiRenderer, inputHandler, renderer);
        } else {
            logger.error("Skipping RecipeScreen: UI renderer, crafting manager or font is missing");
        }
    }

    public PauseMenu pauseMenu() {
        return pauseMenu;
    }

    public DeathMenu deathMenu() {
        return deathMenu;
    }

    public StatisticsScreen statisticsScreen() {
        return statisticsScreen;
    }

    public GlossaryScreen glossaryScreen() {
        return glossaryScreen;
    }

    public MainMenu mainMenu() {
        return mainMenu;
    }

    public SettingsMenu settingsMenu() {
        return settingsMenu;
    }

    public MultiplayerMenu multiplayerMenu() {
        return multiplayerMenu;
    }

    public HostWorldScreen hostWorldScreen() {
        return hostWorldScreen;
    }

    public JoinWorldScreen joinWorldScreen() {
        return joinWorldScreen;
    }

    public LoadingScreen loadingScreen() {
        return loadingScreen;
    }

    public WorldSelectScreen worldSelectScreen() {
        return worldSelectScreen;
    }

    public CharacterCreationScreen characterCreationScreen() {
        return characterCreationScreen;
    }

    public TerrainMapperScreen terrainMapperScreen() {
        return terrainMapperScreen;
    }

    public SonarArtsIntroScreen startupIntroScreen() {
        return startupIntroScreen;
    }

    public InventoryScreen inventoryScreen() {
        return inventoryScreen;
    }

    public CharacterScreen characterScreen() {
        return characterScreen;
    }

    public WorkbenchScreen workbenchScreen() {
        return workbenchScreen;
    }

    public FurnaceScreen furnaceScreen() {
        return furnaceScreen;
    }

    public RecipeScreen recipeScreen() {
        return recipeScreen;
    }
}
