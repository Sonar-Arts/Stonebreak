package com.stonebreak.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.furnace.FurnaceScreen;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.saveChanges.SaveChangesDialog;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

/**
 * Dying must return every panel-held stack (the inventory's cursor stack and 2x2 grid, the
 * workbench's and furnace's carried stacks) to the player's inventory and close the panels
 * before DeathHandler drops the inventory, so nothing leaks through death (#351).
 */
class DeathClosesPanelTest {

    private Game game;
    private GameStateController states;
    private MockedStatic<Game> gameStatic;

    @BeforeEach
    void setUp() {
        game = mock(Game.class);
        gameStatic = mockStatic(Game.class);
        states = new GameStateController(game);
        states.setState(GameState.PLAYING);
    }

    @AfterEach
    void tearDown() {
        gameStatic.close();
    }

    @Test
    void deathWithInventoryOpenReturnsHeldItemsAndCloses() {
        InventoryScreen inventoryScreen = mock(InventoryScreen.class);
        lenient().when(game.getInventoryScreen()).thenReturn(inventoryScreen);
        states.openInventoryScreen();
        assertEquals(GameState.INVENTORY_UI, states.getState());

        states.onPlayerDied();

        verify(inventoryScreen).returnHeldItemsToPlayer();
        verify(inventoryScreen).setVisible(false);
        assertEquals(GameState.PLAYING, states.getState());
        assertFalse(states.isPaused(), "the death menu must not sit on a paused world");
    }

    @Test
    void deathWithFurnaceOpenReturnsCarriedStack() {
        FurnaceScreen furnaceScreen = mock(FurnaceScreen.class);
        when(furnaceScreen.isVisible()).thenReturn(true);
        when(game.getFurnaceScreen()).thenReturn(furnaceScreen);
        states.openFurnaceScreen(new BlockPos(0, 64, 0));
        assertEquals(GameState.FURNACE_UI, states.getState());

        states.onPlayerDied();

        verify(furnaceScreen).close();
        assertEquals(GameState.PLAYING, states.getState());
        assertFalse(states.isPaused());
    }

    @Test
    void deathWithRecipeBookOverWorkbenchClosesBoth() {
        WorkbenchScreen workbenchScreen = mock(WorkbenchScreen.class);
        when(workbenchScreen.isVisible()).thenReturn(true);
        when(game.getWorkbenchScreen()).thenReturn(workbenchScreen);
        RecipeScreen recipeScreen = mock(RecipeScreen.class);
        when(game.getRecipeBookScreen()).thenReturn(recipeScreen);
        states.openWorkbenchScreen(new BlockPos(0, 64, 0));
        states.openRecipeBookScreen();
        assertEquals(GameState.RECIPE_BOOK_UI, states.getState());

        states.onPlayerDied();

        verify(recipeScreen).onClose();
        verify(workbenchScreen).close();
        assertEquals(GameState.PLAYING, states.getState());
    }

    @Test
    void deathDismissesPendingSaveChangesPrompt() {
        Player player = mock(Player.class);
        CharacterStats stats = mock(CharacterStats.class);
        CharacterStats.RpgSnapshot snapshot = new CharacterStats.RpgSnapshot(
                null, java.util.List.of(), Map.of(), Map.of(), java.util.Set.of(), new int[0], 0, 0, 0, 0, 1, 0);
        when(player.getCharacterStats()).thenReturn(stats);
        when(stats.snapshotRpgState()).thenReturn(snapshot);
        when(stats.hasChangedSince(snapshot)).thenReturn(true);
        gameStatic.when(Game::getPlayer).thenReturn(player);

        InventoryScreen inventoryScreen = mock(InventoryScreen.class);
        when(inventoryScreen.isVisible()).thenReturn(true);
        when(game.getInventoryScreen()).thenReturn(inventoryScreen);
        SaveChangesDialog dialog = mock(SaveChangesDialog.class);
        when(game.getSaveChangesDialog()).thenReturn(dialog);

        states.openInventoryScreen();
        states.closeInventoryScreen(); // intercepted by the "Save changes?" prompt
        verify(dialog).setVisible(true);
        assertEquals(GameState.INVENTORY_UI, states.getState(), "the prompt keeps the panel up");

        states.onPlayerDied();

        verify(dialog).setVisible(false);
        verify(inventoryScreen).returnHeldItemsToPlayer();
        verify(inventoryScreen).setVisible(false);
        assertEquals(GameState.PLAYING, states.getState());
    }

    @Test
    void deathWithCharacterSheetOpenHidesIt() {
        CharacterScreen characterScreen = mock(CharacterScreen.class);
        when(game.getCharacterScreen()).thenReturn(characterScreen);
        states.openCharacterScreen();
        assertEquals(GameState.CHARACTER_SHEET_UI, states.getState());

        states.onPlayerDied();

        // Left flagged visible in PLAYING, the undrawn sheet would swallow world clicks.
        verify(characterScreen).setVisible(false);
        assertEquals(GameState.PLAYING, states.getState());
        assertFalse(states.isPaused());
    }

    @Test
    void deathDismissesSaveChangesPromptOverCharacterSheet() {
        Player player = mock(Player.class);
        CharacterStats stats = mock(CharacterStats.class);
        CharacterStats.RpgSnapshot snapshot = new CharacterStats.RpgSnapshot(
                null, java.util.List.of(), Map.of(), Map.of(), java.util.Set.of(), new int[0], 0, 0, 0, 0, 1, 0);
        when(player.getCharacterStats()).thenReturn(stats);
        when(stats.snapshotRpgState()).thenReturn(snapshot);
        when(stats.hasChangedSince(snapshot)).thenReturn(true);
        gameStatic.when(Game::getPlayer).thenReturn(player);

        CharacterScreen characterScreen = mock(CharacterScreen.class);
        when(characterScreen.isVisible()).thenReturn(true);
        when(game.getCharacterScreen()).thenReturn(characterScreen);
        SaveChangesDialog dialog = mock(SaveChangesDialog.class);
        when(game.getSaveChangesDialog()).thenReturn(dialog);

        states.openCharacterScreen();
        states.closeCharacterScreen(); // intercepted by the "Save changes?" prompt
        verify(dialog).setVisible(true);
        assertEquals(GameState.CHARACTER_SHEET_UI, states.getState(), "the prompt keeps the sheet up");

        states.onPlayerDied();

        verify(dialog).setVisible(false);
        verify(characterScreen).setVisible(false);
        assertEquals(GameState.PLAYING, states.getState());
    }

    @Test
    void deathWithNoPanelsOpenIsNoop() {
        states.onPlayerDied();

        assertEquals(GameState.PLAYING, states.getState());
        assertFalse(states.isPaused());
    }
}
