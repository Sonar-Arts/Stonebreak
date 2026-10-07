package com.stonebreak.input;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.PauseMenuActions;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.furnace.FurnaceScreen;
import com.stonebreak.ui.glossaryScreen.GlossaryScreen;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.statisticsScreen.StatisticsScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

/**
 * Routes mouse button events and cursor movement to whichever UI surface is
 * active, in priority order (chat, full-screen UIs, death menu, pause menu,
 * statistics, glossary). Events nothing consumes fall through to
 * {@link WorldMouseHandler} when PLAYING.
 *
 * Screens whose states Main routes to dedicated handlers (main menu, settings,
 * world select, ...) never reach this router.
 */
final class UiMouseRouter {

    private final MouseInputState mouse;
    private final ChatInputRouter chatRouter;
    private final WorldMouseHandler worldHandler;

    UiMouseRouter(MouseInputState mouse, ChatInputRouter chatRouter, WorldMouseHandler worldHandler) {
        this.mouse = mouse;
        this.chatRouter = chatRouter;
        this.worldHandler = worldHandler;
    }

    /**
     * Routes one mouse button event. The raw button state has already been
     * recorded in {@link MouseInputState}; screens that poll it (inventory,
     * workbench, furnace, character) only need this router to stop the event
     * from reaching the world.
     */
    void route(int button, int action) {
        Game game = Game.getInstance();

        // MenuInputRouter hands battle clicks to the battle HUD; anything that still arrives here
        // must not reach chat, a polled screen or the world.
        if (game.getState() == GameState.FOCUS_BATTLE) {
            return;
        }

        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            chatRouter.handleMouseButton(chatSystem, button, action);
            return;
        }

        // Full-screen UIs manage their own clicks by polling; just block world interaction.
        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible() && game.getState() == GameState.RECIPE_BOOK_UI) {
            return;
        }
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null && workbenchScreen.isVisible() && game.getState() == GameState.WORKBENCH_UI) {
            return;
        }
        FurnaceScreen furnaceScreen = game.getFurnaceScreen();
        if (furnaceScreen != null && furnaceScreen.isVisible() && game.getState() == GameState.FURNACE_UI) {
            return;
        }
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            return;
        }
        CharacterScreen characterScreen = game.getCharacterScreen();
        if (characterScreen != null && characterScreen.isVisible()) {
            return;
        }

        DeathMenu deathMenu = game.getDeathMenu();
        if (deathMenu != null && deathMenu.isVisible()) {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                handleDeathMenuClick(deathMenu);
            }
            return;
        }

        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible()) {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                handlePauseMenuClick(pauseMenu);
            }
            return;
        }

        StatisticsScreen statsScreen = game.getStatisticsScreen();
        if (statsScreen != null && statsScreen.isVisible()) {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS
                    && statsScreen.isBackButtonClicked(mouse.x(), mouse.y(), Game.getWindowWidth(), Game.getWindowHeight())) {
                game.closeStatisticsScreen();
            }
            return;
        }

        GlossaryScreen glossaryScreen = game.getGlossaryScreen();
        if (glossaryScreen != null && glossaryScreen.isVisible()) {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                int w = Game.getWindowWidth();
                int h = Game.getWindowHeight();
                // Variant cycler arrows take precedence over the Back button.
                if (glossaryScreen.handleClick(mouse.x(), mouse.y(), w, h)) {
                    return;
                }
                if (glossaryScreen.isBackButtonClicked(mouse.x(), mouse.y(), w, h)) {
                    game.closeGlossaryScreen();
                }
            }
            return;
        }

        if (game.getState() == GameState.PLAYING) {
            worldHandler.handleMouseButton(button, action);
        }
    }

    /** Updates hover highlights (and chat drag) as the cursor moves over UI surfaces. */
    void onMouseMove() {
        Game game = Game.getInstance();
        int windowWidth = Game.getWindowWidth();
        int windowHeight = Game.getWindowHeight();

        DeathMenu deathMenu = game.getDeathMenu();
        if (deathMenu != null && deathMenu.isVisible()) {
            deathMenu.updateHover(mouse.x(), mouse.y(), windowWidth, windowHeight);
        }

        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible()) {
            pauseMenu.updateHover(mouse.x(), mouse.y(), windowWidth, windowHeight);
        }

        StatisticsScreen statsScreen = game.getStatisticsScreen();
        if (statsScreen != null && statsScreen.isVisible()) {
            statsScreen.updateHover(mouse.x(), mouse.y(), windowWidth, windowHeight);
        }

        // #299: the glossary's hover existed but was never fed (rows, arrows and Back never lit).
        GlossaryScreen glossaryScreen = game.getGlossaryScreen();
        if (glossaryScreen != null && glossaryScreen.isVisible()) {
            glossaryScreen.updateHover(mouse.x(), mouse.y(), windowWidth, windowHeight);
        }

        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            chatRouter.onMouseMove(chatSystem);
        }
    }

    private void handleDeathMenuClick(DeathMenu deathMenu) {
        if (!deathMenu.isRespawnButtonClicked(mouse.x(), mouse.y(), Game.getWindowWidth(), Game.getWindowHeight())) {
            return;
        }
        com.stonebreak.ui.DeathMenuActions.respawn(Game.getInstance());
    }

    private void handlePauseMenuClick(PauseMenu pauseMenu) {
        Game game = Game.getInstance();
        int w = Game.getWindowWidth();
        int h = Game.getWindowHeight();

        if (pauseMenu.isResumeButtonClicked(mouse.x(), mouse.y(), w, h)) {
            PauseMenuActions.resume(game);
        } else if (pauseMenu.isStatisticsButtonClicked(mouse.x(), mouse.y(), w, h)) {
            PauseMenuActions.openStatistics(game);
        } else if (pauseMenu.isGlossaryButtonClicked(mouse.x(), mouse.y(), w, h)) {
            PauseMenuActions.openGlossary(game);
        } else if (pauseMenu.isSettingsButtonClicked(mouse.x(), mouse.y(), w, h)) {
            PauseMenuActions.openSettings(game);
        } else if (pauseMenu.isResyncButtonClicked(mouse.x(), mouse.y(), w, h)) {
            PauseMenuActions.resync(game);
        } else if (pauseMenu.isQuitButtonClicked(mouse.x(), mouse.y(), w, h)) {
            PauseMenuActions.quitToMenu(game);
        }
    }
}
