package com.stonebreak.crafting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Content-independent smoke test — asserts loader invariants that hold
 * whatever recipes the game's SBO content declares.
 */
class SboRecipeLoadingTest {

    @Test
    void loadFromSBOsThrowsForNullManager() {
        assertThrows(IllegalArgumentException.class, () -> RecipeLoader.loadFromSBOs(null));
        assertThrows(IllegalArgumentException.class, () -> SmeltingRecipeLoader.loadFromSBOs(null));
    }

    @Test
    void craftingLoadSmoke() {
        CraftingManager manager = new CraftingManager();
        int n = RecipeLoader.loadFromSBOs(manager);

        assertTrue(n >= 0, "should return non-negative count");
        assertEquals(n, manager.getRecipeCount());

        for (Recipe recipe : manager.getAllRecipes()) {
            assertNotNull(recipe.getOutput(), "recipe output should not be null");
            assertFalse(recipe.getOutput().isEmpty(), "recipe output should not be empty");
            assertTrue(recipe.getRecipeWidth() > 0, "recipe width should be positive");
            assertTrue(recipe.getRecipeHeight() > 0, "recipe height should be positive");
        }
    }

    @Test
    void craftingDoubleLoadIsIdempotent() {
        CraftingManager manager = new CraftingManager();
        RecipeLoader.loadFromSBOs(manager);
        int afterFirst = manager.getRecipeCount();

        RecipeLoader.loadFromSBOs(manager);

        assertEquals(afterFirst, manager.getRecipeCount(), "second load should not change recipe count");
    }

    @Test
    void smeltingLoadSmoke() {
        SmeltingManager manager = new SmeltingManager();
        SmeltingRecipeLoader.LoadStats stats = SmeltingRecipeLoader.loadFromSBOs(manager);

        assertTrue(stats.recipes() >= 0, "recipes should be non-negative");
        assertTrue(stats.fuels() >= 0, "fuels should be non-negative");
        assertEquals(stats.recipes(), manager.getRecipeCount());

        for (SmeltingRecipe recipe : manager.getAllRecipes()) {
            assertNotNull(recipe.getInput(), "smelting recipe input should not be null");
            assertNotNull(recipe.getOutput(), "smelting recipe output should not be null");
            assertTrue(recipe.getInput().getCount() >= 1, "smelting recipe input count should be >= 1");
        }
    }

    @Test
    void smeltingDoubleLoadIsIdempotent() {
        SmeltingManager manager = new SmeltingManager();
        SmeltingRecipeLoader.loadFromSBOs(manager);
        int afterFirst = manager.getRecipeCount();

        SmeltingRecipeLoader.loadFromSBOs(manager);

        assertEquals(afterFirst, manager.getRecipeCount(), "second load should not change recipe count");
    }
}