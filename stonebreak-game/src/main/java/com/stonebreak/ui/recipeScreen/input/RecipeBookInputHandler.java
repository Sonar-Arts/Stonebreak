package com.stonebreak.ui.recipeScreen.input;

import com.stonebreak.ui.recipeScreen.state.PopupState;

import com.stonebreak.crafting.Recipe;
import com.stonebreak.ui.recipeScreen.core.RecipeGridLayout;
import com.stonebreak.ui.recipeScreen.logic.RecipeFilterService;
import com.stonebreak.ui.recipeScreen.logic.RecipeVariationService;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Translates input into state changes on the recipe book: the document's presses addressed by
 * target, the wheel (clamped against {@link RecipeGridLayout}), and search/key forwarding.
 */
public class RecipeBookInputHandler {
    private final RecipeBookState state;
    private final SearchInputHandler searchInputHandler;
    private final PopupInputHandler popupInputHandler;
    private final RecipeFilterService filterService;
    private final RecipeVariationService variationService;
    private final RecipeGridLayout grid;

    public RecipeBookInputHandler(RecipeBookState state, RecipeFilterService filterService,
                                  RecipeVariationService variationService, RecipeGridLayout grid) {
        this.state = state;
        this.filterService = filterService;
        this.variationService = variationService;
        this.grid = grid;
        this.searchInputHandler = new SearchInputHandler(state.getSearchState(), state.getUiState());
        this.popupInputHandler  = new PopupInputHandler(state.getPopupState());
    }

    public void handleCharacterInput(char character) {
        if (state.isVisible()) {
            searchInputHandler.handleCharacterInput(character);
        }
    }

    public void handleKeyInput(int key, int action) {
        if (!state.isVisible()) return;

        if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
            // Deselect detail pane on ESC; book closure is handled upstream by InputHandler.
            popupInputHandler.handleEscapeKey();
        } else {
            searchInputHandler.handleKeyInput(key, action);
        }
    }

    // ── the recipe book document's presses (#301), addressed by target ──

    /** A press on a category button. */
    public void pressCategory(String category) {
        state.getUiState().setSelectedCategory(category);
    }

    /** A press anywhere else: focuses the search on the field, unfocuses it elsewhere, then selects a recipe. */
    public void pressContent(boolean onSearchField, Recipe recipe) {
        if (onSearchField) {
            searchInputHandler.activateSearch();
        } else {
            searchInputHandler.deactivateSearch();
        }
        if (recipe != null) {
            state.getPopupState().openPopup(recipe, variationService.getRecipeVariations(recipe, state.getRecipes()));
        }
    }

    /** A press on Prev ({@code -1}) or Next ({@code 1}); false when the book does not take it. */
    public boolean pressVariant(int direction) {
        PopupState popup = state.getPopupState();
        if (!popup.isShowingRecipePopup() || popup.isPopupJustOpened() || !popup.hasMultipleVariations()) {
            return false;
        }
        if (direction < 0 && popup.canNavigatePrevious()) {
            popup.navigatePrevious();
            return true;
        }
        if (direction > 0 && popup.canNavigateNext()) {
            popup.navigateNext();
            return true;
        }
        return false;
    }

    /** Wheel by {@code scrollY} (GLFW's sign: up is positive), clamped to the grid's last window. */
    public void wheel(double scrollY) {
        if (scrollY == 0) return;
        List<Recipe> filtered = filterService.getFilteredRecipes(
                state.getRecipes(),
                state.getUiState().getSelectedCategory(),
                state.getSearchState().getSearchText());
        int maxScroll = grid.maxScrollOffset(filtered.size());
        state.getUiState().setScrollOffset(state.getUiState().getScrollOffset() - (int) scrollY);
        state.getUiState().limitScrollOffset(maxScroll);
    }
}
