package com.stonebreak.ui.recipeScreen.logic;

import com.stonebreak.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

public class RecipeVariationService {

    public List<Recipe> getRecipeVariations(Recipe baseRecipe, List<Recipe> allRecipes) {
        if (baseRecipe == null || allRecipes == null) {
            return new ArrayList<>();
        }

        List<Recipe> variations = new ArrayList<>();
        int targetOutputBlockId = baseRecipe.getOutput().getBlockTypeId();

        for (Recipe recipe : allRecipes) {
            if (recipe.getOutput().getBlockTypeId() == targetOutputBlockId) {
                variations.add(recipe);
            }
        }

        return variations;
    }

    public boolean hasMultipleVariations(Recipe recipe, List<Recipe> allRecipes) {
        return getRecipeVariations(recipe, allRecipes).size() > 1;
    }

    public int findVariationIndex(Recipe targetRecipe, List<Recipe> variations) {
        if (targetRecipe == null || variations == null) {
            return 0;
        }

        for (int i = 0; i < variations.size(); i++) {
            if (variations.get(i).equals(targetRecipe)) {
                return i;
            }
        }
        return 0;
    }
}