package com.stonebreak.core.render;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;

import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.core.window.GameWindow;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.components.DamageNumberRenderer;
import com.stonebreak.rendering.UI.components.DoubtMarkerRenderer;
import com.stonebreak.rendering.UI.components.EnemyAwarenessRenderer;
import com.stonebreak.rendering.UI.components.PlayerNameTagRenderer;
import com.stonebreak.rendering.UI.components.QuarryMarkerRenderer;
import com.stonebreak.rendering.UI.components.StealthHudRenderer;
import com.stonebreak.ui.DebugOverlay;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.LoadingScreen;
import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.characterCreation.CharacterCreationScreen;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.furnace.FurnaceScreen;
import com.stonebreak.ui.glossaryScreen.GlossaryScreen;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.startupIntro.SonarArtsIntroScreen;
import com.stonebreak.ui.statisticsScreen.StatisticsScreen;
import com.stonebreak.ui.terrainMapper.TerrainMapperScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import com.stonebreak.ui.worldSelect.WorldSelectScreen;
import com.stonebreak.world.World;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.glBindVertexArray;

/**
 * Draws one frame: picks the screen for the current {@link GameState}, or the 3D world plus its HUD
 * and menu layers.
 *
 * <p>Every full-screen menu here is Skija-backed and brackets its own GL state, so this class only
 * decides <em>what</em> is drawn and in what order — it never opens a UI frame of its own.</p>
 */
public final class FrameRenderer {

    private static final Logger logger = LoggerFactory.getLogger(FrameRenderer.class);

    private static final String CRASH_LOG = "crash_log.txt";

    /** In-game states that keep the crosshair/hotbar/chat HUD on screen. */
    private static final java.util.Set<GameState> HUD_STATES = java.util.EnumSet.of(
            GameState.PLAYING, GameState.PAUSED, GameState.INVENTORY_UI, GameState.RECIPE_BOOK_UI,
            GameState.CHARACTER_SHEET_UI, GameState.FURNACE_UI, GameState.WORKBENCH_UI);

    private final GameWindow window;
    private boolean firstRender = true;

    public FrameRenderer(GameWindow window) {
        this.window = window;
    }

    private int width() {
        return window.width();
    }

    private int height() {
        return window.height();
    }

    public void renderFrame() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        Game game = Game.getInstance();
        Renderer renderer = Game.getRenderer();
        int width = width();
        int height = height();

        switch (game.getState()) {
            case STARTUP_INTRO -> {
                SonarArtsIntroScreen intro = game.getStartupIntroScreen();
                if (intro != null) intro.render(width, height);
            }
            case MAIN_MENU -> {
                MainMenu mainMenu = game.getMainMenu();
                if (mainMenu != null) mainMenu.render(width, height);
            }
            case WORLD_SELECT -> {
                WorldSelectScreen worldSelect = game.getWorldSelectScreen();
                if (worldSelect != null) worldSelect.render(width, height);
            }
            case CHARACTER_CREATION -> {
                CharacterCreationScreen creation = game.getCharacterCreationScreen();
                if (creation != null) {
                    creation.updateLabelsForMode();
                    creation.render(width, height);
                }
            }
            case TERRAIN_MAPPER -> {
                TerrainMapperScreen terrainMapper = game.getTerrainMapperScreen();
                if (terrainMapper != null) terrainMapper.render(width, height);
            }
            case LOADING -> {
                LoadingScreen loading = game.getLoadingScreen();
                if (loading != null) loading.render(width, height);
            }
            case SETTINGS -> {
                SettingsMenu settings = game.getSettingsMenu();
                if (settings != null) settings.render(width, height);
            }
            case MULTIPLAYER_MENU -> {
                if (game.getMultiplayerMenu() != null) game.getMultiplayerMenu().render(width, height);
            }
            case HOST_WORLD_SELECT -> {
                if (game.getHostWorldScreen() != null) game.getHostWorldScreen().render(width, height);
            }
            case JOIN_WORLD_SCREEN -> {
                if (game.getJoinWorldScreen() != null) game.getJoinWorldScreen().render(width, height);
            }
            default -> renderInGame(game, renderer);
        }

        renderDebugOverlay(renderer);
    }

    // ─── In-game ──────────────────────────────────────────────────────────────

    private void renderInGame(Game game, Renderer renderer) {
        logFirstRender(game);

        if (!resetOpenGLState()) {
            return;
        }

        renderWorld(game, renderer);

        // Underwater tint goes down before the UI so it doesn't discolour the hotbar/menus.
        renderer.getOverlayRenderer().renderUnderwaterOverlay(game, width(), height());

        renderGameUI(game, renderer);
        renderFullscreenMenus(game);
        renderer.renderOverlay(game, width(), height());
        renderModalMenus(game, renderer);
    }

    private void logFirstRender(Game game) {
        if (firstRender) {
            logger.info("First 3D render after loading - State: {}", game.getState());
        }
    }

    /**
     * Returns the GL state to the baseline the world renderer expects. Something else may have left
     * a program, texture or buffer bound, and on rare occasions a different context current.
     *
     * @return false if the reset failed, in which case the frame must be skipped
     */
    private boolean resetOpenGLState() {
        try {
            if (!window.isContextCurrent()) {
                logger.error("CRITICAL: Wrong OpenGL context - resetting");
                window.makeContextCurrent();
                GL.createCapabilities();
            }

            if (firstRender) {
                logger.info("OpenGL Version: {}", glGetString(GL_VERSION));
            }

            glUseProgram(0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_CULL_FACE);

            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glDepthMask(true);

            int error = glGetError();
            if (error != GL_NO_ERROR && firstRender) {
                logger.error("Error after complete state reset: 0x{}", Integer.toHexString(error));
            }

            firstRender = false;
            return true;
        } catch (Exception e) {
            logger.error("Exception during OpenGL state reset", e);
            return false;
        }
    }

    private void renderWorld(Game game, Renderer renderer) {
        try {
            World world = game.getWorld();
            Player player = game.getPlayer();
            if (world != null && player != null) {
                renderer.renderWorld(world, player, game.getTotalTimeElapsed());
            }
        } catch (Exception e) {
            logRenderCrash(game, e);
            throw new RuntimeException("Render crash - see " + CRASH_LOG, e);
        }
    }

    private static void logRenderCrash(Game game, Exception e) {
        World world = game.getWorld();
        Player player = game.getPlayer();
        String position = player != null
                ? player.getPosition().x + ", " + player.getPosition().y + ", " + player.getPosition().z
                : "null";
        String chunks = world != null ? String.valueOf(world.getLoadedChunkCount()) : "null";
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;

        logger.error("CRITICAL CRASH in renderWorld() - state={} player={} chunks={} memory={}MB",
                game.getState(), position, chunks, usedMb, e);

        try (FileWriter log = new FileWriter(CRASH_LOG, true)) {
            log.write("=== CRASH LOG " + LocalDateTime.now() + " ===\n");
            log.write("State: " + game.getState() + "\n");
            log.write("Player: " + position + "\n");
            log.write("Chunks: " + chunks + "\n");
            log.write("Memory: " + usedMb + "MB\n");
            log.write("Exception: " + e.getMessage() + "\n");
            log.write("Stack trace: " + Arrays.toString(e.getStackTrace()) + "\n\n");
        } catch (IOException logFailure) {
            logger.error("Failed to write crash log", logFailure);
        }
    }

    // ─── HUD and menu layers ──────────────────────────────────────────────────

    private void renderGameUI(Game game, Renderer renderer) {
        if (renderer == null) {
            return;
        }

        if (HUD_STATES.contains(game.getState())) {
            renderCrosshair(game, renderer);
            renderInventoryAndHotbar(game);
            renderChat(game, renderer);
        }

        if (game.getState() == GameState.PLAYING) {
            renderWorldSpaceHud(renderer);
        }

        // The recipe book is an overlay, not a fullscreen menu.
        if (game.getState() == GameState.RECIPE_BOOK_UI) {
            RecipeScreen recipeScreen = game.getRecipeBookScreen();
            if (recipeScreen != null && recipeScreen.isVisible()) {
                recipeScreen.render();
            }
        }

        renderActivePauseMenu(game);
    }

    /** Markers and tags anchored to world positions, projected through the player's view. */
    private void renderWorldSpaceHud(Renderer renderer) {
        Player player = Game.getPlayer();
        if (player == null) {
            return;
        }
        int width = width();
        int height = height();
        var projection = renderer.getProjectionMatrix();
        var view = player.getViewMatrix();

        DamageNumberRenderer damageNumbers = DamageNumberRenderer.getInstance();
        damageNumbers.update(Game.getDeltaTime());
        damageNumbers.render(projection, view, width, height);
        QuarryMarkerRenderer.getInstance().render(projection, view, width, height);
        DoubtMarkerRenderer.getInstance().render(projection, view, width, height);
        EnemyAwarenessRenderer.getInstance().render(projection, view, width, height);
        PlayerNameTagRenderer.getInstance().render(projection, view, width, height);
        StealthHudRenderer.getInstance().render(width, height);
    }

    private void renderCrosshair(Game game, Renderer renderer) {
        if (game.getState() != GameState.PLAYING) {
            return;
        }
        InventoryScreen inventory = game.getInventoryScreen();
        WorkbenchScreen workbench = game.getWorkbenchScreen();
        FurnaceScreen furnace = game.getFurnaceScreen();
        boolean anyScreenOpen = (inventory != null && inventory.isVisible())
                || (workbench != null && workbench.isVisible())
                || (furnace != null && furnace.isVisible());
        if (anyScreenOpen) {
            return;
        }
        renderer.getUIRenderer().renderCrosshair(width(), height());
    }

    private void renderInventoryAndHotbar(Game game) {
        InventoryScreen inventory = game.getInventoryScreen();
        CharacterScreen characterScreen = game.getCharacterScreen();
        GameState state = game.getState();
        int width = width();
        int height = height();

        // The recipe book takes the foreground; draw only the hotbar underneath so hover
        // detection over the inventory grid doesn't run.
        if (state == GameState.RECIPE_BOOK_UI) {
            if (inventory != null) {
                inventory.renderHotbar(width, height);
            }
            return;
        }

        // Furnace and workbench are drawn exclusively by renderFullscreenMenus (outside the UI frame
        // bracket). Drawing them here as well double-renders, and the second Skija pass covers the GL
        // block-texture icons from the first.
        if (state == GameState.FURNACE_UI || state == GameState.WORKBENCH_UI) {
            return;
        }

        if (state == GameState.CHARACTER_SHEET_UI && characterScreen != null && characterScreen.isVisible()) {
            // Character screen is open — draw it, but keep the hotbar visible below.
            characterScreen.render(width, height);
            if (inventory != null) {
                inventory.renderHotbar(width, height);
            }
        } else if (inventory != null) {
            if (inventory.isVisible()) {
                inventory.render(width, height);
            } else {
                inventory.renderHotbar(width, height);
            }
        }
    }

    private void renderChat(Game game, Renderer renderer) {
        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null) {
            renderer.renderChat(chatSystem, width(), height());
        }
    }

    private void renderActivePauseMenu(Game game) {
        if (game.getState() != GameState.PLAYING && game.getState() != GameState.PAUSED) {
            return;
        }
        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible()) {
            pauseMenu.render(width(), height());
        }
    }

    private void renderFullscreenMenus(Game game) {
        if (game.getState() == GameState.WORKBENCH_UI) {
            WorkbenchScreen workbench = game.getWorkbenchScreen();
            if (workbench != null && workbench.isVisible()) {
                workbench.render();
            }
        }
        if (game.getState() == GameState.FURNACE_UI) {
            FurnaceScreen furnace = game.getFurnaceScreen();
            if (furnace != null && furnace.isVisible()) {
                furnace.render();
            }
        }
    }

    /** Menus that float above the world in any in-game state, plus the pause menu's depth curtain. */
    private void renderModalMenus(Game game, Renderer renderer) {
        if (renderer == null) {
            return;
        }
        int width = width();
        int height = height();

        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible()) {
            pauseMenu.render(width, height);
            renderer.getUIRenderer().renderPauseMenuDepthCurtain();
        }

        StatisticsScreen statistics = game.getStatisticsScreen();
        if (statistics != null && statistics.isVisible()) {
            statistics.render(width, height);
        }

        GlossaryScreen glossary = game.getGlossaryScreen();
        if (glossary != null && glossary.isVisible()) {
            glossary.render(width, height);
        }

        DeathMenu deathMenu = game.getDeathMenu();
        if (deathMenu != null && deathMenu.isVisible()) {
            deathMenu.render(width, height);
        }
    }

    private void renderDebugOverlay(Renderer renderer) {
        DebugOverlay debugOverlay = Game.getDebugOverlay();
        if (debugOverlay == null || !debugOverlay.isVisible()) {
            return;
        }
        debugOverlay.renderWireframes(renderer);
        if (renderer != null) {
            // All debug panels (left RAM/VRAM + right debug info) are MasonryUI/Skija —
            // a single GL bracket covers them.
            debugOverlay.renderResourcePanels(renderer, width(), height());
        }
    }

}
