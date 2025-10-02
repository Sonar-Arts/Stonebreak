package com.stonebreak.ui.recipeScreen.state;

import com.stonebreak.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

public class PopupState {
    private boolean showingRecipePopup = false;
    private Recipe selectedRecipe = null;
    private boolean popupJustOpened = false;
    private List<Recipe> currentRecipeVariations = new ArrayList<>();
    private int currentVariationIndex = 0;

    public boolean isShowingRecipePopup() {
        return showingRecipePopup;
    }

    public void setShowingRecipePopup(boolean showingRecipePopup) {
        this.showingRecipePopup = showingRecipePopup;
    }

    public Recipe getSelectedRecipe() {
        return selectedRecipe;
    }

    public void setSelectedRecipe(Recipe selectedRecipe) {
        this.selectedRecipe = selectedRecipe;
    }

    public boolean isPopupJustOpened() {
        return popupJustOpened;
    }

    public void setPopupJustOpened(boolean popupJustOpened) {
        this.popupJustOpened = popupJustOpened;
    }

    public List<Recipe> getCurrentRecipeVariations() {
        return new ArrayList<>(currentRecipeVariations);
    }

    public void setCurrentRecipeVariations(List<Recipe> variations) {
        this.currentRecipeVariations = new ArrayList<>(variations);
    }

    public int getCurrentVariationIndex() {
        return currentVariationIndex;
    }

    public void setCurrentVariationIndex(int index) {
        if (index >= 0 && index < currentRecipeVariations.size()) {
            this.currentVariationIndex = index;
        }
    }

    public void openPopup(Recipe recipe, List<Recipe> variations) {
        this.selectedRecipe = recipe;
        this.currentRecipeVariations = new ArrayList<>(variations);
        this.currentVariationIndex = findRecipeIndex(recipe, variations);
        this.showingRecipePopup = true;
        this.popupJustOpened = true;
    }

    public void closePopup() {
        this.showingRecipePopup = false;
        this.selectedRecipe = null;
        this.popupJustOpened = false;
        this.currentRecipeVariations.clear();
        this.currentVariationIndex = 0;
    }

    public boolean canNavigatePrevious() {
        return currentVariationIndex > 0;
    }

    public boolean canNavigateNext() {
        return currentVariationIndex < currentRecipeVariations.size() - 1;
    }

    public void navigatePrevious() {
        if (canNavigatePrevious()) {
            currentVariationIndex--;
            selectedRecipe = currentRecipeVariations.get(currentVariationIndex);
        }
    }

    public void navigateNext() {
        if (canNavigateNext()) {
            currentVariationIndex++;
            selectedRecipe = currentRecipeVariations.get(currentVariationIndex);
        }
    }

    public boolean hasMultipleVariations() {
        return currentRecipeVariations.size() > 1;
    }

    public void updateAfterFrame() {
        if (popupJustOpened) {
            popupJustOpened = false;
        }
    }

    private int findRecipeIndex(Recipe recipe, List<Recipe> variations) {
        for (int i = 0; i < variations.size(); i++) {
            if (variations.get(i).equals(recipe)) {
                return i;
            }
        }
        return 0;
    }
}