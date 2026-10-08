package com.stonebreak.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Closing the recipe book returns to the screen it was opened over, unless that screen closed while
 * the book was up: a crafting table broken by another player abandons its screen, and returning to
 * WORKBENCH_UI then left the world paused with no table and no hotbar drawn (found in the #300 review).
 */
class RecipeBookReturnTest {

    private Game game;
    private WorkbenchScreen table;
    private boolean tableOpen;
    private GameStateController states;

    @BeforeEach
    void setUp() {
        game = mock(Game.class);
        table = mock(WorkbenchScreen.class);
        when(table.isVisible()).thenAnswer(i -> tableOpen);
        when(game.getWorkbenchScreen()).thenReturn(table);
        when(game.getRecipeBookScreen()).thenReturn(mock(RecipeScreen.class));
        states = new GameStateController(game);
        states.setState(GameState.PLAYING);
        states.openWorkbenchScreen(new BlockPos(0, 64, 0));
        tableOpen = true;
        assertEquals(GameState.WORKBENCH_UI, states.getState());
        states.openRecipeBookScreen();
        assertEquals(GameState.RECIPE_BOOK_UI, states.getState());
    }

    @Test
    void closingTheBookReturnsToTheOpenTable() {
        states.closeRecipeBookScreen();
        assertEquals(GameState.WORKBENCH_UI, states.getState());
        assertTrue(states.isPaused());
    }

    @Test
    void closingTheBookAfterTheTableWasAbandonedReturnsToPlay() {
        states.closeWorkbenchScreen(); // abandonBrokenTable, while the book is up
        tableOpen = false;
        assertEquals(GameState.RECIPE_BOOK_UI, states.getState(), "the book stays up");
        states.closeRecipeBookScreen();
        assertEquals(GameState.PLAYING, states.getState());
        assertFalse(states.isPaused(), "the world runs again");
    }
}
