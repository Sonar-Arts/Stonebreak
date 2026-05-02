package com.stonebreak.core.state;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.rpg.CharacterPanelTab;
import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;

/**
 * Owns game state transitions, the pause flag, and UI toggles. Extracted
 * from the {@code setState/togglePauseMenu/toggleInventoryScreen/...}
 * methods on {@link Game} so state machine concerns live in one place.
 */
public final class GameStateController {

    private final Game game;

    private GameState currentState = GameState.STARTUP_INTRO;
    private GameState previousGameState = GameState.STARTUP_INTRO;
    private boolean paused = false;

    public GameStateController(Game game) {
        this.game = game;
    }

    public GameState getState() {
        return currentState;
    }

    public GameState getPreviousGameState() {
        return previousGameState;
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Sets the current game state, records the previous state, updates the
     * pause flag, refreshes main-menu splash text when returning to
     * {@link GameState#MAIN_MENU}, and nudges mouse capture.
     */
    public void setState(GameState state) {
        if (this.currentState != state && state != null) {
            this.previousGameState = this.currentState;
        }
        this.currentState = state;

        MainMenu mainMenu = game.getMainMenu();
        if (state == GameState.MAIN_MENU && mainMenu != null) {
            mainMenu.refreshSplashText();
        }

        if (state == GameState.WORLD_SELECT && game.getWorldSelectScreen() != null) {
            game.getWorldSelectScreen().refreshWorlds();
        }

        if (state == GameState.HOST_WORLD_SELECT && game.getHostWorldScreen() != null) {
            game.getHostWorldScreen().onShow();
        }
        if (state == GameState.JOIN_WORLD_SCREEN && game.getJoinWorldScreen() != null) {
            game.getJoinWorldScreen().onShow();
        }

        // Tear down any active multiplayer session when returning to the main menu.
        if (state == GameState.MAIN_MENU
                && com.stonebreak.network.MultiplayerSession.isOnline()) {
            com.stonebreak.network.MultiplayerSession.shutdown();
        }

        updatePauseState(state);

        MouseCaptureManager mouseCaptureManager = game.getMouseCaptureManager();
        if (mouseCaptureManager != null) {
            mouseCaptureManager.updateCaptureState();
        }
    }

    private void updatePauseState(GameState state) {
        switch (state) {
            case STARTUP_INTRO, MAIN_MENU, LOADING, SETTINGS, PAUSED, WORKBENCH_UI,
                 MULTIPLAYER_MENU, HOST_WORLD_SELECT, JOIN_WORLD_SCREEN -> paused = true;
            case PLAYING, INVENTORY_UI, RECIPE_BOOK_UI, CHARACTER_SHEET_UI -> paused = false;
        }
    }

    public void togglePauseMenu() {
        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu == null) return;

        boolean newPauseState = !pauseMenu.isVisible();
        pauseMenu.setVisible(newPauseState);

        if (newPauseState) {
            setState(GameState.PAUSED);
        } else {
            setState(GameState.PLAYING);
        }
    }

    public void toggleInventoryScreen() {
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen == null) return;

        inventoryScreen.toggleVisibility();

        if (inventoryScreen.isVisible()) {
            setState(GameState.INVENTORY_UI);
        } else {
            PauseMenu pauseMenu = game.getPauseMenu();
            if (pauseMenu == null || !pauseMenu.isVisible()) {
                setState(GameState.PLAYING);
            }
        }
    }

    public void openWorkbenchScreen() {
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null && currentState == GameState.PLAYING && !paused) {
            setState(GameState.WORKBENCH_UI);
            workbenchScreen.open();
            System.out.println("Opened Workbench Screen.");
        } else {
            System.out.println("Cannot open workbench: Not in PLAYING state, or game paused by menu, or workbenchScreen is null.");
        }
    }

    public void closeWorkbenchScreen() {
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null && workbenchScreen.isVisible()) {
            workbenchScreen.close();
            if (currentState == GameState.WORKBENCH_UI) {
                setState(GameState.PLAYING);
            }
            System.out.println("Closed Workbench Screen.");
        }
    }

    public void openRecipeBookScreen() {
        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen == null) {
            System.out.println("Cannot open RecipeBook: recipeBookScreen is null.");
            return;
        }

        PauseMenu pauseMenu = game.getPauseMenu();
        boolean allowOpen =
            currentState == GameState.WORKBENCH_UI
            || currentState == GameState.INVENTORY_UI
            || (currentState == GameState.PLAYING && (pauseMenu == null || !pauseMenu.isVisible()));

        if (allowOpen) {
            setState(GameState.RECIPE_BOOK_UI);
            recipeScreen.onOpen();
            System.out.println("Opened RecipeBook Screen. Will return to: " + previousGameState);
        } else {
            String contextDetails = "Current state: " + currentState;
            InventoryScreen inventoryScreen = game.getInventoryScreen();
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

    public void toggleCharacterScreen() {
        CharacterScreen characterScreen = game.getCharacterScreen();
        if (characterScreen == null) return;

        characterScreen.toggleVisibility();

        if (characterScreen.isVisible()) {
            setState(GameState.CHARACTER_SHEET_UI);
        } else {
            PauseMenu pauseMenu = game.getPauseMenu();
            if (pauseMenu == null || !pauseMenu.isVisible()) {
                setState(GameState.PLAYING);
            }
        }
    }

    /**
     * Opens the character screen at the given tab, switching from any current state.
     * If the character screen is already visible, just switches the active tab.
     */
    public void openCharacterTab(CharacterPanelTab tab) {
        CharacterScreen characterScreen = game.getCharacterScreen();
        if (characterScreen == null) return;

        characterScreen.getController().setActiveTab(tab);

        if (!characterScreen.isVisible()) {
            characterScreen.toggleVisibility();
            setState(GameState.CHARACTER_SHEET_UI);
        }
    }

    public void closeRecipeBookScreen() {
        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen != null && currentState == GameState.RECIPE_BOOK_UI) {
            recipeScreen.onClose();
            setState(previousGameState);
            System.out.println("Closed RecipeBook Screen. Returning to: " + previousGameState);
        }
    }
}
