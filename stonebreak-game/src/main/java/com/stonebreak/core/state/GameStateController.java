package com.stonebreak.core.state;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.CharacterPanelTab;
import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.statisticsScreen.StatisticsScreen;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import com.stonebreak.ui.furnace.FurnaceScreen;

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

    /**
     * Baseline of the spendable RPG state captured when the inventory/character
     * panel opens. Non-null only while that panel session is active; the exit
     * "Save changes?" prompt compares against it and restores it on "No".
     */
    private CharacterStats.RpgSnapshot rpgSessionBaseline;

    /** The close action deferred while the "Save changes?" prompt is showing. */
    private Runnable pendingExitAction;

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
        boolean stateChanged = this.currentState != state && state != null;
        if (stateChanged) {
            this.previousGameState = this.currentState;
        }
        this.currentState = state;

        // Bound the RPG "unsaved changes" session to the inventory/character panel:
        // snapshot when entering it from outside, drop it when leaving it. Switching
        // between the two panels (or layering the recipe book on top) keeps the same
        // session, so the baseline survives the whole stay in the panel.
        if (stateChanged) {
            boolean wasUi = isPanelSessionState(previousGameState);
            boolean isUi = isPanelSessionState(state);
            if (isUi && !wasUi) {
                beginRpgSession();
            } else if (!isUi && wasUi) {
                endRpgSession();
            }
        }

        // Entering gameplay from any menu/UI state: drop residual mouse button
        // state so the click that closed the menu (or a release swallowed by a
        // menu-routed callback) can't trigger attacks/block breaking on its own.
        if (stateChanged && state == GameState.PLAYING
                && game.getInputHandler() != null) {
            game.getInputHandler().clearMouseButtonStates();
        }

        MainMenu mainMenu = game.getMainMenu();
        if (state == GameState.MAIN_MENU && mainMenu != null) {
            mainMenu.refreshSplashText();
            mainMenu.resetTitleAnimation();
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

        // Tear down any active session when returning to the main menu. In the two-world model
        // singleplayer also runs an integrated server + local client, so tear those down too.
        if (state == GameState.MAIN_MENU
                && com.stonebreak.network.MultiplayerSession.isInWorld()) {
            com.stonebreak.network.MultiplayerSession.shutdown();
        }

        // Flush per-session world state when returning to the main menu so chat
        // history and cheat flags do not leak into the next world load.
        if (state == GameState.MAIN_MENU) {
            com.stonebreak.ui.chat.ChatSystem chatSystem = game.getChatSystem();
            if (chatSystem != null) {
                chatSystem.clear();
            }
            game.setCheatsEnabled(false);
        }

        // Hide the F3 debug overlay when entering the settings menu so it does
        // not render on top of settings.
        if (state == GameState.SETTINGS) {
            com.stonebreak.ui.DebugOverlay debugOverlay = game.getDebugOverlay();
            if (debugOverlay != null) {
                debugOverlay.hide();
            }
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
                 MULTIPLAYER_MENU, HOST_WORLD_SELECT, JOIN_WORLD_SCREEN,
                 WORLD_SELECT, CHARACTER_CREATION, TERRAIN_MAPPER, STATISTICS, GLOSSARY -> paused = true;
            case PLAYING, INVENTORY_UI, RECIPE_BOOK_UI, CHARACTER_SHEET_UI, FURNACE_UI -> paused = false;
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

        if (inventoryScreen.isVisible()) {
            closeInventoryScreen();
        } else {
            openInventoryScreen();
        }
    }

    /** Opens the inventory from gameplay, starting a fresh RPG session. */
    public void openInventoryScreen() {
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen == null) return;
        inventoryScreen.setVisible(true);
        setState(GameState.INVENTORY_UI);
    }

    /**
     * Closes the inventory back to gameplay. If the player made point allocations
     * since it opened, a "Save changes?" prompt intercepts the exit first.
     */
    public void closeInventoryScreen() {
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen == null || !inventoryScreen.isVisible()) return;
        if (interceptExit(this::doCloseInventoryScreen)) {
            return;
        }
        doCloseInventoryScreen();
    }

    private void doCloseInventoryScreen() {
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen == null) return;
        inventoryScreen.setVisible(false);
        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu == null || !pauseMenu.isVisible()) {
            setState(GameState.PLAYING);
        }
    }

    /** Moves from the character sheet to the inventory without a save prompt. */
    public void switchToInventory() {
        CharacterScreen characterScreen = game.getCharacterScreen();
        if (characterScreen != null) {
            characterScreen.setVisible(false);
        }
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null) {
            inventoryScreen.setVisible(true);
        }
        setState(GameState.INVENTORY_UI);
    }

    public void toggleCharacterScreen() {
        CharacterScreen characterScreen = game.getCharacterScreen();
        if (characterScreen == null) return;

        if (characterScreen.isVisible()) {
            closeCharacterScreen();
        } else {
            openCharacterScreen();
        }
    }

    /** Opens the character sheet from gameplay, starting a fresh RPG session. */
    public void openCharacterScreen() {
        CharacterScreen characterScreen = game.getCharacterScreen();
        if (characterScreen == null) return;
        characterScreen.setVisible(true);
        setState(GameState.CHARACTER_SHEET_UI);
    }

    /**
     * Closes the character sheet back to gameplay. Point allocations made since it
     * opened trigger the "Save changes?" prompt first.
     */
    public void closeCharacterScreen() {
        CharacterScreen characterScreen = game.getCharacterScreen();
        if (characterScreen == null || !characterScreen.isVisible()) return;
        if (interceptExit(this::doCloseCharacterScreen)) {
            return;
        }
        doCloseCharacterScreen();
    }

    private void doCloseCharacterScreen() {
        CharacterScreen characterScreen = game.getCharacterScreen();
        if (characterScreen == null) return;
        characterScreen.setVisible(false);
        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu == null || !pauseMenu.isVisible()) {
            setState(GameState.PLAYING);
        }
    }

    /** Moves from the inventory to the character sheet without a save prompt. */
    public void switchToCharacter() {
        switchToCharacter(null);
    }

    /** Moves from the inventory to the character sheet at the given tab. */
    public void switchToCharacter(CharacterPanelTab tab) {
        CharacterScreen characterScreen = game.getCharacterScreen();
        if (characterScreen == null) return;
        if (tab != null) {
            characterScreen.getController().setActiveTab(tab);
        }
        characterScreen.setVisible(true);
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null) {
            inventoryScreen.setVisible(false);
        }
        setState(GameState.CHARACTER_SHEET_UI);
    }

    public void openWorkbenchScreen() {
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null && currentState == GameState.PLAYING && !paused) {
            setState(GameState.WORKBENCH_UI);
            workbenchScreen.open();
        }
    }

    public void closeWorkbenchScreen() {
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null && workbenchScreen.isVisible()) {
            workbenchScreen.close();
            if (currentState == GameState.WORKBENCH_UI) {
                setState(GameState.PLAYING);
            }
        }
    }

    public void openFurnaceScreen(com.openmason.engine.util.BlockPos pos) {
        FurnaceScreen furnaceScreen = game.getFurnaceScreen();
        if (furnaceScreen != null && currentState == GameState.PLAYING && !paused) {
            setState(GameState.FURNACE_UI);
            furnaceScreen.open(pos);
        }
    }

    public void closeFurnaceScreen() {
        FurnaceScreen furnaceScreen = game.getFurnaceScreen();
        if (furnaceScreen != null && furnaceScreen.isVisible()) {
            furnaceScreen.close();
            if (currentState == GameState.FURNACE_UI) {
                setState(GameState.PLAYING);
            }
        }
    }

    public void openRecipeBookScreen() {
        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen == null) return;

        PauseMenu pauseMenu = game.getPauseMenu();
        boolean allowOpen =
            currentState == GameState.WORKBENCH_UI
            || currentState == GameState.INVENTORY_UI
            || (currentState == GameState.PLAYING && (pauseMenu == null || !pauseMenu.isVisible()));

        if (allowOpen) {
            setState(GameState.RECIPE_BOOK_UI);
            recipeScreen.onOpen();
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
            switchToCharacter(tab);
        }
    }

    public void openStatisticsScreen() {
        StatisticsScreen statsScreen = game.getStatisticsScreen();
        if (statsScreen == null) return;
        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu != null) pauseMenu.setVisible(false);
        statsScreen.setVisible(true);
        setState(GameState.STATISTICS);
    }

    public void closeStatisticsScreen() {
        StatisticsScreen statsScreen = game.getStatisticsScreen();
        if (statsScreen != null) statsScreen.setVisible(false);
        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu != null) pauseMenu.setVisible(true);
        setState(GameState.PAUSED);
    }

    public void openGlossaryScreen() {
        com.stonebreak.ui.glossaryScreen.GlossaryScreen glossaryScreen = game.getGlossaryScreen();
        if (glossaryScreen == null) return;
        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu != null) pauseMenu.setVisible(false);
        glossaryScreen.setVisible(true);
        setState(GameState.GLOSSARY);
    }

    public void closeGlossaryScreen() {
        com.stonebreak.ui.glossaryScreen.GlossaryScreen glossaryScreen = game.getGlossaryScreen();
        if (glossaryScreen != null) glossaryScreen.setVisible(false);
        PauseMenu pauseMenu = game.getPauseMenu();
        if (pauseMenu != null) pauseMenu.setVisible(true);
        setState(GameState.PAUSED);
    }

    public void closeRecipeBookScreen() {
        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen != null && currentState == GameState.RECIPE_BOOK_UI) {
            recipeScreen.onClose();
            setState(previousGameState);
        }
    }

    // ─────────────────────────────────────────────── RPG session / save prompt

    /** States that share the inventory/character panel's "unsaved changes" session. */
    private static boolean isPanelSessionState(GameState s) {
        return s == GameState.INVENTORY_UI
                || s == GameState.CHARACTER_SHEET_UI
                || s == GameState.RECIPE_BOOK_UI;
    }

    /** Captures the baseline the "Save changes?" prompt restores against. */
    private void beginRpgSession() {
        com.stonebreak.player.Player player = Game.getPlayer();
        if (player == null) return;
        rpgSessionBaseline = player.getCharacterStats().snapshotRpgState();
        pendingExitAction = null;
    }

    private void endRpgSession() {
        rpgSessionBaseline = null;
        pendingExitAction = null;
    }

    /** True when the player made RPG allocations since the panel session began. */
    private boolean hasUnsavedChanges() {
        if (rpgSessionBaseline == null) return false;
        com.stonebreak.player.Player player = Game.getPlayer();
        if (player == null) return false;
        return player.getCharacterStats().hasChangedSince(rpgSessionBaseline);
    }

    /**
     * Routes a panel-close through the "Save changes?" prompt when the player holds
     * unspent allocations. Returns true when the close was intercepted (the caller
     * must not close); false means proceed with the close.
     */
    private boolean interceptExit(Runnable closeAction) {
        if (!hasUnsavedChanges()) {
            endRpgSession();
            return false;
        }
        pendingExitAction = closeAction;
        game.getSaveChangesDialog().setVisible(true);
        return true;
    }

    /** Save changes? → Yes: keep the allocations and leave the panel. */
    public void confirmSaveChanges() {
        endRpgSession();
        finishPendingExit();
    }

    /** Save changes? → No: revert the session's allocations and leave the panel. */
    public void confirmDiscardChanges() {
        if (rpgSessionBaseline != null) {
            com.stonebreak.player.Player player = Game.getPlayer();
            if (player != null) {
                player.getCharacterStats().restoreRpgState(rpgSessionBaseline);
            }
        }
        endRpgSession();
        finishPendingExit();
    }

    /** Dismisses the prompt and stays in the panel. */
    public void cancelSaveChanges() {
        game.getSaveChangesDialog().setVisible(false);
        pendingExitAction = null;
    }

    private void finishPendingExit() {
        Runnable action = pendingExitAction;
        pendingExitAction = null;
        game.getSaveChangesDialog().setVisible(false);
        if (action != null) {
            action.run();
        }
    }

}
