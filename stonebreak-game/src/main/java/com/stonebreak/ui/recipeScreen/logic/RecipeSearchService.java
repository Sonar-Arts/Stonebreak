package com.stonebreak.ui.recipeScreen.logic;

import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.Item;

import java.util.List;

public class RecipeSearchService {

    public boolean matchesSearchCriteria(Recipe recipe, String searchText) {
        if (searchText == null || searchText.isEmpty()) {
            return true;
        }

        String lowerSearchText = searchText.toLowerCase();

        Item outputItem = recipe.getOutput().getItem();
        if (outputItem != null && outputItem.getName().toLowerCase().contains(lowerSearchText)) {
            return true;
        }

        if (recipe.getId() != null && recipe.getId().toLowerCase().contains(lowerSearchText)) {
            return true;
        }

        return false;
    }

    public long countMatchingRecipes(List<Recipe> recipes, String searchText) {
        return recipes.stream()
                .filter(recipe -> matchesSearchCriteria(recipe, searchText))
                .count();
    }
}