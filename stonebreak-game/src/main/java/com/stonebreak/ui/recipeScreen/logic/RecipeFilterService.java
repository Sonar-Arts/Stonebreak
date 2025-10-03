package com.stonebreak.ui.recipeScreen.logic;

import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.Item;
import com.stonebreak.ui.recipeScreen.core.ItemCategoryMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecipeFilterService {
    private final RecipeSearchService searchService;

    public RecipeFilterService() {
        this.searchService = new RecipeSearchService();
    }

    public RecipeFilterService(RecipeSearchService searchService) {
        this.searchService = searchService;
    }

    public List<Recipe> getFilteredRecipes(List<Recipe> allRecipes, String selectedCategory, String searchText) {
        List<Recipe> filtered = new ArrayList<>();
        Map<Integer, Recipe> uniqueOutputRecipes = new HashMap<>();

        for (Recipe recipe : allRecipes) {
            if (!matchesCategoryFilter(recipe, selectedCategory)) {
                continue;
            }

            if (!searchService.matchesSearchCriteria(recipe, searchText)) {
                continue;
            }

            int outputBlockTypeId = recipe.getOutput().getBlockTypeId();
            if (!uniqueOutputRecipes.containsKey(outputBlockTypeId)) {
                uniqueOutputRecipes.put(outputBlockTypeId, recipe);
                filtered.add(recipe);
            }
        }

        return filtered;
    }

    public boolean matchesCategoryFilter(Recipe recipe, String selectedCategory) {
        if (selectedCategory == null || selectedCategory.equals("All")) {
            return true;
        }

        Item outputItem = recipe.getOutput().getItem();
        String itemCategory = ItemCategoryMapper.getCategoryForItem(outputItem);
        return itemCategory.equals(selectedCategory);
    }

    public long countRecipesInCategory(List<Recipe> allRecipes, String category) {
        return allRecipes.stream()
                .filter(recipe -> matchesCategoryFilter(recipe, category))
                .count();
    }

    public Map<String, Long> getCategoryCounts(List<Recipe> allRecipes, String[] categories) {
        Map<String, Long> counts = new HashMap<>();
        for (String category : categories) {
            counts.put(category, countRecipesInCategory(allRecipes, category));
        }
        return counts;
    }
}