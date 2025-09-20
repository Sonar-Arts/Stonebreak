package com.stonebreak.ui.recipeScreen.input;

import com.stonebreak.crafting.Recipe;
import com.stonebreak.input.InputHandler;
import com.stonebreak.ui.recipeScreen.core.PositionCalculator;
import com.stonebreak.ui.recipeScreen.core.RecipeBookConstants;
import com.stonebreak.ui.recipeScreen.logic.RecipeFilterService;
import com.stonebreak.ui.recipeScreen.logic.RecipeVariationService;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;
import com.stonebreak.ui.recipeScreen.renderers.RecipeGridRenderer;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class RecipeBookInputHandler {
    private final RecipeBookState state;
    private final InputHandler inputHandler;
    private final SearchInputHandler searchInputHandler;
    private final PopupInputHandler popupInputHandler;
    private final RecipeFilterService filterService;
    private final RecipeVariationService variationService;

    public RecipeBookInputHandler(RecipeBookState state, InputHandler inputHandler,
                                 RecipeFilterService filterService, RecipeVariationService variationService) {
        this.state = state;
        this.inputHandler = inputHandler;
        this.filterService = filterService;
        this.variationService = variationService;
        this.searchInputHandler = new SearchInputHandler(state.getSearchState(), state.getUiState());
        this.popupInputHandler = new PopupInputHandler(state.getPopupState(), inputHandler);
    }

    public void handleInput() {
        if (!state.isVisible()) {
            return;
        }

        handleScrolling();
        handleMouseClicks();
    }

    public void handleCharacterInput(char character) {
        if (state.isVisible()) {
            searchInputHandler.handleCharacterInput(character);
        }
    }

    public void handleKeyInput(int key, int action) {
        if (!state.isVisible()) {
            return;
        }

        if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
            popupInputHandler.handleEscapeKey();
        } else {
            searchInputHandler.handleKeyInput(key, action);
        }
    }

    private void handleScrolling() {
        double scrollY = inputHandler.getAndResetScrollY();
        if (scrollY != 0) {
            List<Recipe> filteredRecipes = filterService.getFilteredRecipes(
                    state.getRecipes(),
                    state.getUiState().getSelectedCategory(),
                    state.getSearchState().getSearchText()
            );

            PositionCalculator.PanelDimensions panel = PositionCalculator.calculateMainPanelDimensions();
            PositionCalculator.GridLayout grid = PositionCalculator.calculateRecipeGridLayout(panel);

            int maxScrollRows = RecipeGridRenderer.calculateMaxScrollOffset(
                    grid.gridWidth, grid.gridHeight, filteredRecipes.size());

            int newScrollOffset = state.getUiState().getScrollOffset() - (int) scrollY;
            state.getUiState().setScrollOffset(newScrollOffset);
            state.getUiState().limitScrollOffset(maxScrollRows);
        }
    }

    private void handleMouseClicks() {
        if (inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            Vector2f mousePos = inputHandler.getMousePosition();

            if (popupInputHandler.handlePopupClick(mousePos)) {
                return;
            }

            handleMainUIClick(mousePos);
        }
    }

    private void handleMainUIClick(Vector2f mousePos) {
        PositionCalculator.PanelDimensions panel = PositionCalculator.calculateMainPanelDimensions();

        if (handleCategoryButtonClick(mousePos, panel)) {
            return;
        }

        if (handleSearchBarClick(mousePos, panel)) {
            return;
        }

        handleRecipeClick(mousePos, panel);
    }

    private boolean handleCategoryButtonClick(Vector2f mousePos, PositionCalculator.PanelDimensions panel) {
        int categoryY = PositionCalculator.calculateCategoryButtonY(panel);
        int buttonWidth = (panel.width - 2 * RecipeBookConstants.PADDING -
                          (RecipeBookConstants.CATEGORIES.length - 1) * RecipeBookConstants.CATEGORY_BUTTON_SPACING) /
                          RecipeBookConstants.CATEGORIES.length;

        for (int i = 0; i < RecipeBookConstants.CATEGORIES.length; i++) {
            int buttonX = panel.x + RecipeBookConstants.PADDING +
                         i * (buttonWidth + RecipeBookConstants.CATEGORY_BUTTON_SPACING);

            if (PositionCalculator.isPointInBounds(mousePos.x, mousePos.y,
                    buttonX, categoryY, buttonWidth, RecipeBookConstants.CATEGORY_BUTTON_HEIGHT)) {
                state.getUiState().setSelectedCategory(RecipeBookConstants.CATEGORIES[i]);
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return true;
            }
        }
        return false;
    }

    private boolean handleSearchBarClick(Vector2f mousePos, PositionCalculator.PanelDimensions panel) {
        int searchBarY = PositionCalculator.calculateSearchBarY(panel);
        int searchBarX = panel.x + RecipeBookConstants.PADDING;
        int searchBarWidth = panel.width - 2 * RecipeBookConstants.PADDING;

        boolean clickedSearchBar = PositionCalculator.isPointInBounds(mousePos.x, mousePos.y,
                searchBarX, searchBarY, searchBarWidth, RecipeBookConstants.SEARCH_BAR_HEIGHT);

        if (clickedSearchBar) {
            searchInputHandler.activateSearch();
            return true;
        } else {
            searchInputHandler.deactivateSearch();
            return false;
        }
    }

    private void handleRecipeClick(Vector2f mousePos, PositionCalculator.PanelDimensions panel) {
        Recipe clickedRecipe = getRecipeAtPosition(mousePos, panel);
        if (clickedRecipe != null) {
            List<Recipe> variations = variationService.getRecipeVariations(clickedRecipe, state.getRecipes());
            state.getPopupState().openPopup(clickedRecipe, variations);
        }
    }

    private Recipe getRecipeAtPosition(Vector2f mousePos, PositionCalculator.PanelDimensions panel) {
        List<Recipe> filteredRecipes = filterService.getFilteredRecipes(
                state.getRecipes(),
                state.getUiState().getSelectedCategory(),
                state.getSearchState().getSearchText()
        );

        if (filteredRecipes.isEmpty()) {
            return null;
        }

        PositionCalculator.GridLayout grid = PositionCalculator.calculateRecipeGridLayout(panel);

        if (!PositionCalculator.isPointInBounds(mousePos.x, mousePos.y,
                grid.gridX, grid.gridY, grid.gridWidth, grid.gridHeight)) {
            return null;
        }

        int relativeX = (int) (mousePos.x - grid.gridX);
        int relativeY = (int) (mousePos.y - grid.gridY);

        int col = relativeX / (RecipeBookConstants.RECIPE_DISPLAY_HEIGHT + 10);
        int row = relativeY / (RecipeBookConstants.RECIPE_DISPLAY_HEIGHT + 10);

        if (col >= grid.recipesPerRow || row >= grid.maxVisibleRows) {
            return null;
        }

        int cardStartX = col * (RecipeBookConstants.RECIPE_DISPLAY_HEIGHT + 10);
        int cardStartY = row * (RecipeBookConstants.RECIPE_DISPLAY_HEIGHT + 10);
        int cardEndX = cardStartX + (RecipeBookConstants.RECIPE_DISPLAY_HEIGHT - 10);
        int cardEndY = cardStartY + (RecipeBookConstants.RECIPE_DISPLAY_HEIGHT - 10);

        if (relativeX < cardStartX || relativeX > cardEndX ||
            relativeY < cardStartY || relativeY > cardEndY) {
            return null;
        }

        int recipeIndex = (row + state.getUiState().getScrollOffset()) * grid.recipesPerRow + col;

        if (recipeIndex >= 0 && recipeIndex < filteredRecipes.size()) {
            return filteredRecipes.get(recipeIndex);
        }

        return null;
    }
}