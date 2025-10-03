package com.stonebreak.ui.recipeScreen.state;

import com.stonebreak.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

public class RecipeBookState {
    private final UIState uiState;
    private final SearchState searchState;
    private final PopupState popupState;
    private List<Recipe> recipes;

    public RecipeBookState() {
        this.uiState = new UIState();
        this.searchState = new SearchState();
        this.popupState = new PopupState();
        this.recipes = new ArrayList<>();
    }

    public UIState getUiState() {
        return uiState;
    }

    public SearchState getSearchState() {
        return searchState;
    }

    public PopupState getPopupState() {
        return popupState;
    }

    public List<Recipe> getRecipes() {
        return new ArrayList<>(recipes);
    }

    public void setRecipes(List<Recipe> recipes) {
        this.recipes = recipes != null ? new ArrayList<>(recipes) : new ArrayList<>();
    }

    public void initialize() {
        searchState.clearSearch();
        uiState.resetToDefaults();
        popupState.closePopup();
    }

    public void update(double deltaTime) {
        searchState.updateTypingState();
        popupState.updateAfterFrame();
    }

    public void show() {
        uiState.setVisible(true);
        initialize();
    }

    public void hide() {
        uiState.setVisible(false);
        searchState.clearSearch();
        popupState.closePopup();
    }

    public boolean isVisible() {
        return uiState.isVisible();
    }
}