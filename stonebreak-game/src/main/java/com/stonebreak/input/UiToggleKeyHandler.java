package com.stonebreak.input;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.player.Player;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.furnace.FurnaceScreen;
import com.stonebreak.ui.glossaryScreen.GlossaryScreen;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.statisticsScreen.StatisticsScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Q;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;

/**
 * Edge-triggered keys that open/close UI surfaces or act on the current
 * selection: Escape (close-topmost / pause), E (inventory), C (character
 * sheet), T (chat), Q (drop item).
 */
final class UiToggleKeyHandler {

    private static final int[] TOGGLE_KEYS = {GLFW_KEY_ESCAPE, GLFW_KEY_E, GLFW_KEY_C, GLFW_KEY_T, GLFW_KEY_Q};

    private final KeyEdgeTracker keys;
    /** Keys handed over while held; each stays inert until it is seen released. */
    private final java.util.BitSet suppressedUntilReleased = new java.util.BitSet();

    UiToggleKeyHandler(KeyEdgeTracker keys) {
        this.keys = keys;
    }

    /**
     * These keys are not polled during a Focus battle (its HUD takes key events instead), so the
     * edge tracker is stale when polling resumes and a key still held from the battle would read as
     * a new press: Escape would close the pause menu it just opened, E would open the inventory on
     * leaving the result panel. Marked keys do nothing until released.
     */
    void suppressUntilReleased() {
        for (int key : TOGGLE_KEYS) {
            suppressedUntilReleased.set(key);
        }
    }

    /** {@link KeyEdgeTracker#pressedOnce} that honours {@link #suppressUntilReleased()}. */
    private boolean pressed(int key) {
        boolean fired = keys.pressedOnce(key); // always poll so the edge tracker stays in sync
        if (suppressedUntilReleased.get(key)) {
            if (!keys.isDown(key)) {
                suppressedUntilReleased.clear(key);
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
        if (!pressed(GLFW_KEY_ESCAPE)) {
            return;
        }

        Game game = Game.getInstance();

        // The battle HUD handles its own Escape through key events (back / open pause menu).
        if (game.getState() == GameState.FOCUS_BATTLE) {
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
        if (!pressed(GLFW_KEY_E) || battleOwnsToggles()) {
            return;
        }

        Game game = Game.getInstance();
        if (game.getState() == GameState.INVENTORY_UI) {
            game.toggleInventoryScreen();
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
        if (game.getState() == GameState.FURNACE_UI) {
            return;
        }

        game.toggleInventoryScreen();
    }

    /** C toggles the character sheet; closes an open inventory first so the two never stack. */
    void pollCharacterToggle() {
        if (!pressed(GLFW_KEY_C) || battleOwnsToggles()) {
            return;
        }

        Game game = Game.getInstance();
        if (game.getState() == GameState.CHARACTER_SHEET_UI) {
            game.toggleCharacterScreen();
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
        if (game.getState() == GameState.FURNACE_UI) {
            return;
        }

        GameState state = game.getState();
        if (state != GameState.PLAYING && state != GameState.INVENTORY_UI) {
            return;
        }

        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            game.toggleInventoryScreen();
        }
        game.toggleCharacterScreen();
    }

    /** T opens chat from gameplay-adjacent states. */
    void pollChatOpen() {
        if (!pressed(GLFW_KEY_T) || battleOwnsToggles()) {
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
        if (!pressed(GLFW_KEY_Q) || battleOwnsToggles()) {
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
