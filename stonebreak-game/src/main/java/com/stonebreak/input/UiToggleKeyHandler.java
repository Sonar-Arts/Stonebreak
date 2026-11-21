package com.stonebreak.input;

import com.openmason.engine.input.ActionKeys;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.player.Player;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.furnace.FurnaceScreen;
import com.stonebreak.ui.glossaryScreen.GlossaryScreen;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.saveChanges.SaveChangesDialog;
import com.stonebreak.ui.statisticsScreen.StatisticsScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;


/**
 * Edge-triggered actions that open/close UI surfaces or act on the current
 * selection ({@link GameplayAction}; defaults: Escape close-topmost / pause,
 * E inventory, C character sheet, T chat, Q drop item).
 */
final class UiToggleKeyHandler {

    private static final GameplayAction[] TOGGLE_ACTIONS = {
        GameplayAction.PAUSE, GameplayAction.INVENTORY, GameplayAction.CHARACTER, GameplayAction.CHAT,
        GameplayAction.DROP_ITEM};

    private final ActionKeys<GameplayAction> keys;
    /** Actions handed over while held; each stays inert until it is seen released. */
    private final java.util.EnumSet<GameplayAction> suppressedUntilReleased = java.util.EnumSet.noneOf(GameplayAction.class);

    UiToggleKeyHandler(ActionKeys<GameplayAction> keys) {
        this.keys = keys;
    }

    /**
     * These keys are not polled during a Focus battle (its HUD takes key events instead), so the
     * edge tracker is stale when polling resumes and a key still held from the battle would read as
     * a new press: Escape would close the pause menu it just opened, E would open the inventory on
     * leaving the result panel. Marked actions do nothing until released.
     */
    void suppressUntilReleased() {
        suppressedUntilReleased.addAll(java.util.List.of(TOGGLE_ACTIONS));
    }

    /** {@link ActionKeys#pressedOnce} that honours {@link #suppressUntilReleased()}. */
    private boolean pressed(GameplayAction action) {
        boolean fired = keys.pressedOnce(action); // always poll so the edges stay in sync
        if (suppressedUntilReleased.contains(action)) {
            if (!keys.held(action)) {
                suppressedUntilReleased.remove(action);
            }
            return false;
        }
        return fired;
    }

    /**
     * True while a Focus battle is running, including while it sits under the pause menu. Inventory,
     * character sheet, chat and item drop would switch to states that simulate the parked player, so
     * they stay off until the battle ends. Escape is exempt: it must still drive the pause menu.
     */
    private static boolean battleOwnsToggles() {
        return com.stonebreak.battle.stage.FocusBattle.isActive();
    }

    /**
     * Escape closes the topmost UI surface, falling through in priority order;
     * with nothing open it toggles the pause menu. Chat handles its own Escape
     * via the key callback, so an open chat swallows the press here.
     */
    void pollEscape() {
        if (!pressed(GameplayAction.PAUSE)) {
            return;
        }

        Game game = Game.getInstance();

        // The battle HUD handles its own Escape through key events (back / open pause menu).
        if (game.getState() == GameState.FOCUS_BATTLE) {
            return;
        }

        // The "Save changes?" prompt is modal: Escape answers No (discards the allocations and leaves the panel).
        SaveChangesDialog saveDialog = game.getSaveChangesDialog();
        if (saveDialog != null && saveDialog.isVisible()) {
            game.confirmDiscardChanges();
            return;
        }

        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            return;
        }

        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible() && game.getState() == GameState.RECIPE_BOOK_UI) {
            game.closeRecipeBookScreen();
            return;
        }

        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null && workbenchScreen.isVisible() && game.getState() == GameState.WORKBENCH_UI) {
            workbenchScreen.handleCloseRequest();
            return;
        }

        FurnaceScreen furnaceScreen = game.getFurnaceScreen();
        if (furnaceScreen != null && furnaceScreen.isVisible() && game.getState() == GameState.FURNACE_UI) {
            furnaceScreen.handleCloseRequest();
            return;
        }

        com.stonebreak.ui.chest.ChestScreen chestScreen = game.getChestScreen();
        if (chestScreen != null && chestScreen.isVisible() && game.getState() == GameState.CHEST_UI) {
            chestScreen.handleCloseRequest();
            return;
        }

        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (game.getState() == GameState.INVENTORY_UI && inventoryScreen != null && inventoryScreen.isVisible()) {
            game.toggleInventoryScreen();
            return;
        }

        CharacterScreen characterScreen = game.getCharacterScreen();
        if (characterScreen != null && characterScreen.isVisible()) {
            game.toggleCharacterScreen();
            return;
        }

        StatisticsScreen statsScreen = game.getStatisticsScreen();
        if (statsScreen != null && statsScreen.isVisible()) {
            game.closeStatisticsScreen();
            return;
        }

        GlossaryScreen glossaryScreen = game.getGlossaryScreen();
        if (glossaryScreen != null && glossaryScreen.isVisible()) {
            game.closeGlossaryScreen();
            return;
        }

        game.togglePauseMenu();
    }

    /** E toggles the inventory, unless another UI surface already owns the screen. */
    void pollInventoryToggle() {
        if (!pressed(GameplayAction.INVENTORY) || battleOwnsToggles()) {
            return;
        }

        Game game = Game.getInstance();
        if (game.getSaveChangesDialog() != null && game.getSaveChangesDialog().isVisible()) {
            return;
        }
        if (game.getState() == GameState.INVENTORY_UI) {
            game.toggleInventoryScreen();
            return;
        }

        if (overlayOwnsScreen(game)) {
            return;
        }
        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            return;
        }
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null && workbenchScreen.isVisible()) {
            return;
        }
        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible()) {
            return;
        }
        if (game.getState() == GameState.FURNACE_UI || game.getState() == GameState.CHEST_UI) {
            return;
        }

        game.toggleInventoryScreen();
    }

    /**
     * The pause menu or the death menu is up: E and C must not open a screen underneath it (it would
     * unpause the world and, as a UI document, take the clicks meant for the menu above it, #300).
     */
    private static boolean overlayOwnsScreen(Game game) {
        if (game.getState() == GameState.PAUSED
                || (game.getPauseMenu() != null && game.getPauseMenu().isVisible())
                || (game.getDeathMenu() != null && game.getDeathMenu().isVisible())) {
            return true;
        }
        Player player = Game.getPlayer();
        return player != null && player.isDead();
    }

    /** C toggles the character sheet; closes an open inventory first so the two never stack. */
    void pollCharacterToggle() {
        if (!pressed(GameplayAction.CHARACTER) || battleOwnsToggles()) {
            return;
        }

        Game game = Game.getInstance();
        if (game.getSaveChangesDialog() != null && game.getSaveChangesDialog().isVisible()) {
            return;
        }
        if (game.getState() == GameState.CHARACTER_SHEET_UI) {
            game.toggleCharacterScreen();
            return;
        }
        if (overlayOwnsScreen(game)) {
            return;
        }

        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            return;
        }
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null && workbenchScreen.isVisible()) {
            return;
        }
        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible()) {
            return;
        }
        if (game.getState() == GameState.FURNACE_UI || game.getState() == GameState.CHEST_UI) {
            return;
        }

        GameState state = game.getState();
        if (state != GameState.PLAYING && state != GameState.INVENTORY_UI) {
            return;
        }

        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            game.switchToCharacter();
            return;
        }
        game.toggleCharacterScreen();
    }

    /** T opens chat from gameplay-adjacent states. */
    void pollChatOpen() {
        if (!pressed(GameplayAction.CHAT) || battleOwnsToggles()) {
            return;
        }

        Game game = Game.getInstance();
        GameState currentState = game.getState();
        if (currentState != GameState.PLAYING && currentState != GameState.INVENTORY_UI
                && currentState != GameState.RECIPE_BOOK_UI) {
            return;
        }

        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            return;
        }

        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && !chatSystem.isOpen()) {
            chatSystem.openChat();
        }
    }

    /** Q drops a single item from the selected hotbar slot. */
    void pollItemDrop() {
        if (!pressed(GameplayAction.DROP_ITEM) || battleOwnsToggles()) {
            return;
        }

        Game game = Game.getInstance();
        GameState currentState = game.getState();
        if (currentState != GameState.PLAYING && currentState != GameState.INVENTORY_UI
                && currentState != GameState.RECIPE_BOOK_UI) {
            return;
        }

        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            return;
        }
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            return;
        }

        Player player = Game.getPlayer();
        if (player != null) {
            com.stonebreak.util.DropUtil.dropSingleItemFromPlayer(player);
        }
    }
}
