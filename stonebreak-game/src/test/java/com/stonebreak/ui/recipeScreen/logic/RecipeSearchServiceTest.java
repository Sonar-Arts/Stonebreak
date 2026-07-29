package com.stonebreak.ui.recipeScreen.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;

/**
 * Guards the search matching logic of {@link RecipeSearchService}: case-insensitive
 * text search against recipe output item name and recipe id, with null/empty query
 * matching everything.
 *
 * <p>Regression: a change to string comparison that loses case-insensitivity or
 * mishandles null/empty search text would silently break recipe filtering in the
 * recipe book UI.
 */
class RecipeSearchServiceTest {

    private final RecipeSearchService service = new RecipeSearchService();

    // ---- helpers ------------------------------------------------------------------------------

    /** Dirt -> Dirt output, recipe id "recipe:dirt" */
    private Recipe dirtRecipe() {
        return new Recipe("recipe:dirt",
            Arrays.asList(Arrays.asList(new ItemStack(BlockType.STONE, 1))),
            new ItemStack(BlockType.DIRT, 1));
    }

    /** Stone -> Stone output, recipe id "recipe:stone" */
    private Recipe stoneRecipe() {
        return new Recipe("recipe:stone",
            Arrays.asList(Arrays.asList(new ItemStack(BlockType.DIRT, 1))),
            new ItemStack(BlockType.STONE, 1));
    }

    /** Wooden Pickaxe -> Tool output, recipe id "recipe:pickaxe" */
    private Recipe pickaxeRecipe() {
        return new Recipe("recipe:pickaxe",
            Arrays.asList(Arrays.asList(new ItemStack(ItemType.STICK, 2))),
            new ItemStack(ItemType.WOODEN_PICKAXE, 1));
    }

    /** Stick -> Material output, recipe id "recipe:stick" */
    private Recipe stickRecipe() {
        return new Recipe("recipe:stick",
            Arrays.asList(Arrays.asList(new ItemStack(BlockType.WOOD, 1))),
            new ItemStack(ItemType.STICK, 1));
    }

    private List<Recipe> allRecipes() {
        List<Recipe> recipes = new ArrayList<>();
        recipes.add(dirtRecipe());
        recipes.add(stoneRecipe());
        recipes.add(pickaxeRecipe());
        recipes.add(stickRecipe());
        return recipes;
    }

    // ---- null/empty query matches everything --------------------------------------------------

    @Test
    void nullSearchTextMatchesEveryRecipe() {
        for (Recipe recipe : allRecipes()) {
            assertTrue(service.matchesSearchCriteria(recipe, null),
                recipe + ": null search text must match every recipe");
        }
    }

    @Test
    void emptySearchTextMatchesEveryRecipe() {
        for (Recipe recipe : allRecipes()) {
            assertTrue(service.matchesSearchCriteria(recipe, ""),
                recipe + ": empty search text must match every recipe");
        }
    }

    // ---- case-insensitive matching on item name -----------------------------------------------

    @Test
    void searchMatchesOutputItemNameCaseInsensitively() {
        assertTrue(service.matchesSearchCriteria(dirtRecipe(), "dirt"),
            "\"dirt\" must match Dirt recipe");
        assertTrue(service.matchesSearchCriteria(dirtRecipe(), "DIRT"),
            "\"DIRT\" must match Dirt recipe (case-insensitive)");
        assertTrue(service.matchesSearchCriteria(dirtRecipe(), "DirT"),
            "\"DirT\" must match Dirt recipe (mixed case)");
    }

    // ---- case-insensitive matching on recipe id -----------------------------------------------

    @Test
    void searchMatchesRecipeIdCaseInsensitively() {
        assertTrue(service.matchesSearchCriteria(pickaxeRecipe(), "recipe:pickaxe"),
            "\"recipe:pickaxe\" must match by id");
        assertTrue(service.matchesSearchCriteria(pickaxeRecipe(), "RECIPE:PICKAXE"),
            "\"RECIPE:PICKAXE\" must match by id (case-insensitive)");
    }

    // ---- partial name match -------------------------------------------------------------------

    @Test
    void partialNameMatchSucceeds() {
        assertTrue(service.matchesSearchCriteria(pickaxeRecipe(), "wo"),
            "\"wo\" must match \"Wooden Pickaxe\" (partial)");
        assertTrue(service.matchesSearchCriteria(pickaxeRecipe(), "pick"),
            "\"pick\" must match \"Wooden Pickaxe\" (partial)");
        assertFalse(service.matchesSearchCriteria(dirtRecipe(), "wo"),
            "\"wo\" must not match \"Dirt\"");
    }

    // ---- no-match query returns false and zero count ------------------------------------------

    @Test
    void nonMatchingQueryReturnsFalse() {
        assertFalse(service.matchesSearchCriteria(dirtRecipe(), "zzzznonexistent"),
            "\"zzzznonexistent\" must not match Dirt recipe");
    }

    @Test
    void countMatchingRecipesReturnsZeroForNoMatch() {
        long count = service.countMatchingRecipes(allRecipes(), "zzzznonexistent");
        assertEquals(0, count,
            "a query matching nothing must return count of 0");
    }

    // ---- countMatchingRecipes equals manual filter count (consistency) ------------------------

    @Test
    void countMatchingRecipesAgreesWithManualFilterCount() {
        List<Recipe> recipes = allRecipes();

        for (String query : new String[] { "dirt", "DIRT", "wo", "recipe", "stick", "zzz", "", null }) {
            long reportedCount = service.countMatchingRecipes(recipes, query);

            int manualCount = 0;
            for (Recipe r : recipes) {
                if (service.matchesSearchCriteria(r, query)) {
                    manualCount++;
                }
            }
            assertEquals(manualCount, reportedCount,
                "countMatchingRecipes for query \"" + query +
                "\" must equal the count of individually matching recipes (" + manualCount + ")");
        }
    }

    // ---- count matching specific items --------------------------------------------------------

    @Test
    void countMatchingRecipesCountsAllMatchingDirt() {
        List<Recipe> recipes = Arrays.asList(dirtRecipe(), stoneRecipe(), pickaxeRecipe());
        assertEquals(1, service.countMatchingRecipes(recipes, "dirt"),
            "only 1 of 3 recipes should match \"dirt\"");
    }

    @Test
    void countMatchingRecipesCountsAllMatchingRecipePrefix() {
        List<Recipe> recipes = allRecipes();
        // All recipe ids start with "recipe:", so all should match
        long count = service.countMatchingRecipes(recipes, "recipe:");
        assertEquals(recipes.size(), count,
            "\"recipe:\" should match all recipes because every id starts with \"recipe:\"");
    }
}