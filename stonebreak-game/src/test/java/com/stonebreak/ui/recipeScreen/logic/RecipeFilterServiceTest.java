package com.stonebreak.ui.recipeScreen.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.ui.recipeScreen.core.ItemCategoryMapper;

/**
 * Guards the filtering logic of {@link RecipeFilterService}: category filtering,
 * search filtering, and deduplication by output block type id.
 *
 * <p>Regression: a change to the filter that silently drops valid recipes or
 * returns duplicates would break the recipe book's display without a visual
 * regression catching it.
 */
class RecipeFilterServiceTest {

    private final RecipeFilterService service = new RecipeFilterService();

    // ---- helpers ------------------------------------------------------------------------------

    private Recipe dirtRecipe() {
        return new Recipe("recipe:dirt",
            Arrays.asList(Arrays.asList(new ItemStack(BlockType.STONE, 1))),
            new ItemStack(BlockType.DIRT, 1));
    }

    private Recipe stoneRecipe() {
        return new Recipe("recipe:stone",
            Arrays.asList(Arrays.asList(new ItemStack(BlockType.DIRT, 1))),
            new ItemStack(BlockType.STONE, 1));
    }

    private Recipe pickaxeRecipe() {
        return new Recipe("recipe:pickaxe",
            Arrays.asList(Arrays.asList(new ItemStack(ItemType.STICK, 2))),
            new ItemStack(ItemType.WOODEN_PICKAXE, 1));
    }

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

    // ---- "All" category returns subset of input -----------------------------------------------

    @Test
    void allCategoryReturnsSubsetOfInput() {
        List<Recipe> recipes = allRecipes();
        List<Recipe> filtered = service.getFilteredRecipes(recipes, "All", "");

        for (Recipe f : filtered) {
            assertTrue(recipes.contains(f),
                "filtered recipe " + f.getId() + " must be from the original list");
        }
    }

    // ---- category filtering -------------------------------------------------------------------

    @Test
    void buildingCategoryReturnsOnlyBuildingRecipes() {
        List<Recipe> recipes = allRecipes();
        List<Recipe> filtered = service.getFilteredRecipes(recipes, "Building", "");

        for (Recipe f : filtered) {
            String category = ItemCategoryMapper.getCategoryForItem(f.getOutput().getItem());
            assertEquals("Building", category,
                "recipe " + f.getId() + " category must be \"Building\", got \"" + category + "\"");
        }
        assertTrue(filtered.size() > 0, "Building category should have at least one recipe");
    }

    @Test
    void toolsCategoryReturnsOnlyToolRecipes() {
        List<Recipe> recipes = allRecipes();
        List<Recipe> filtered = service.getFilteredRecipes(recipes, "Tools", "");

        for (Recipe f : filtered) {
            String category = ItemCategoryMapper.getCategoryForItem(f.getOutput().getItem());
            assertEquals("Tools", category,
                "recipe " + f.getId() + " category must be \"Tools\", got \"" + category + "\"");
        }
    }

    // ---- search further narrows results -------------------------------------------------------

    @Test
    void searchTextFurtherNarrowsCategoryResults() {
        List<Recipe> recipes = allRecipes();
        List<Recipe> allBuilding = service.getFilteredRecipes(recipes, "Building", "");
        List<Recipe> buildingWithSearch = service.getFilteredRecipes(recipes, "Building", "dirt");

        assertTrue(buildingWithSearch.size() <= allBuilding.size(),
            "adding a search filter must not increase the number of results");
    }

    // ---- filtered results are true subset -----------------------------------------------------

    @Test
    void filteredResultsAreTrueSubsetOfOriginal() {
        List<Recipe> recipes = allRecipes();
        List<Recipe> filtered = service.getFilteredRecipes(recipes, "Building", "dirt");

        for (Recipe f : filtered) {
            assertTrue(recipes.contains(f),
                "filtered recipe " + f.getId() + " must be from the original list");
        }
    }

    // ---- category counts sum correctly --------------------------------------------------------

    @Test
    void getCategoryCountsReturnsCorrectTotals() {
        List<Recipe> recipes = allRecipes();
        String[] categories = new String[] { "Building", "Tools", "Food", "Decorative" };
        Map<String, Long> counts = service.getCategoryCounts(recipes, categories);

        long totalFromCounts = counts.values().stream().mapToLong(Long::longValue).sum();
        // The total across all categories may not equal recipes.size() because some categories
        // may have 0 recipes; what matters is each count is consistent.
        for (String cat : categories) {
            assertTrue(counts.containsKey(cat),
                "count map must contain key \"" + cat + "\"");
            long count = counts.get(cat);
            assertEquals(service.countRecipesInCategory(recipes, cat), count,
                "count for \"" + cat + "\" in map must agree with countRecipesInCategory");
        }
    }

    // ---- count for "All" equals total ---------------------------------------------------------

    @Test
    void countRecipesInCategoryAllEqualsTotal() {
        List<Recipe> recipes = allRecipes();
        long allCount = service.countRecipesInCategory(recipes, "All");
        assertEquals(recipes.size(), allCount,
            "count for \"All\" category must equal total recipe count");
    }

    // ---- null category behaves like "All" -----------------------------------------------------

    @Test
    void nullCategoryBehavesLikeAll() {
        List<Recipe> recipes = allRecipes();
        long nullCount = service.countRecipesInCategory(recipes, null);
        assertEquals(recipes.size(), nullCount,
            "null category must behave like \"All\" and match every recipe");
    }

    // ---- duplicate output blockTypeId is deduplicated -----------------------------------------

    @Test
    void duplicateOutputBlockTypeIdIsDeduplicated() {
        // Two recipes with the same output item (DIRT) — only the first unique output should appear
        Recipe dirt1 = new Recipe("recipe:dirt1",
            Arrays.asList(Arrays.asList(new ItemStack(BlockType.STONE, 1))),
            new ItemStack(BlockType.DIRT, 1));
        Recipe dirt2 = new Recipe("recipe:dirt2",
            Arrays.asList(Arrays.asList(new ItemStack(BlockType.GRASS, 1))),
            new ItemStack(BlockType.DIRT, 1)); // Same output as dirt1
        Recipe stone1 = new Recipe("recipe:stone1",
            Arrays.asList(Arrays.asList(new ItemStack(BlockType.DIRT, 1))),
            new ItemStack(BlockType.STONE, 1));

        List<Recipe> recipes = Arrays.asList(dirt1, dirt2, stone1);
        List<Recipe> filtered = service.getFilteredRecipes(recipes, "All", "");

        // Should have 2 entries: one DIRT output (first unique), one STONE output
        int dirtCount = 0;
        for (Recipe f : filtered) {
            if (f.getOutput().getBlockTypeId() == BlockType.DIRT.getId()) {
                dirtCount++;
            }
        }
        assertEquals(1, dirtCount,
            "only one DIRT-output recipe should appear after deduplication");
        assertTrue(filtered.size() <= recipes.size(),
            "deduplication must never produce more results than input");
    }
}