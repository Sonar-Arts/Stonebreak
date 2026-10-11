package com.stonebreak.input;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.furnace.FurnaceScreen;
import com.stonebreak.ui.glossaryScreen.GlossaryScreen;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.statisticsScreen.StatisticsScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;

/**
 * Keeps mouse button events from the world while a UI surface is active, in
 * priority order (chat, full-screen UIs, then the death menu, pause menu,
 * statistics and glossary; their documents take their own clicks). Events
 * nothing consumes fall through to {@link WorldMouseHandler} when PLAYING.
 *
 * Screens whose states Main routes to dedicated handlers (main menu, settings,
 * world select, ...) never reach this router.
 */
final class UiMouseRouter {

    private final WorldMouseHandler worldHandler;

    UiMouseRouter(WorldMouseHandler worldHandler) {
        this.worldHandler = worldHandler;
    }

    /**
     * Routes one mouse button event. The raw button state has already been
     * recorded in {@link MouseInputState}; the in-game screens (inventory,
     * workbench, furnace, character sheet, recipe book) are UI documents that
     * take their own clicks, so this router only stops the event from reaching
     * the world while one is open.
     */
    void route(int button, int action) {
        Game game = Game.getInstance();

        // MenuInputRouter hands battle clicks to the battle HUD; anything that still arrives here
        // must not reach chat, a screen or the world.
        if (game.getState() == GameState.FOCUS_BATTLE) {
            return;
        }

        // The chat document takes the open chat's pointer input (#301).
        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            return;
        }

        // Full-screen UIs' documents take their own clicks; just block world interaction.
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

        // The death menu, pause menu, statistics and glossary are UI documents that take their own
        // clicks; while one is up, nothing reaches the world.
        DeathMenu deathMenu = game.getDeathMenu();
        PauseMenu pauseMenu = game.getPauseMenu();
        StatisticsScreen statsScreen = game.getStatisticsScreen();
        GlossaryScreen glossaryScreen = game.getGlossaryScreen();
        if ((deathMenu != null && deathMenu.isVisible()) || (pauseMenu != null && pauseMenu.isVisible())
                || (statsScreen != null && statsScreen.isVisible())
                || (glossaryScreen != null && glossaryScreen.isVisible())) {
            return;
        }

        if (game.getState() == GameState.PLAYING) {
            worldHandler.handleMouseButton(button, action);
        }
    }
}
