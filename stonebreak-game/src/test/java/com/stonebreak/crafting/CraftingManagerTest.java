package com.stonebreak.crafting;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemCategory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CraftingManager}: recipe registration, craft matching with
 * compact-grid logic, copy semantics, and lookup helpers.
 */
class CraftingManagerTest {

    private static List<ItemStack> row(ItemStack... slots) {
        return Arrays.asList(slots);
    }

    private static List<List<ItemStack>> grid(List<ItemStack>... rows) {
        return Arrays.asList(rows);
    }

    private static ItemStack s(Item item, int count) {
        return new ItemStack(item, count);
    }

    // ── Registration ─────────────────────────────────────────────────────

    @Test
    void registerRecipeIgnoresNullAndDuplicates() {
        CraftingManager manager = new CraftingManager();
        Recipe recipe = new Recipe("dup", grid(row(s(BlockType.STONE, 1))), s(BlockType.STONE, 1));

        manager.registerRecipe(null);
        manager.registerRecipe(recipe);
        manager.registerRecipe(recipe);

        assertEquals(1, manager.getRecipeCount());
    }

    // ── craftItem edge cases ─────────────────────────────────────────────

    @Test
    void craftItemReturnsNullForNullOrEmptyOrAllEmptyGrids() {
        CraftingManager manager = new CraftingManager();

        assertNull(manager.craftItem(null));
        assertNull(manager.craftItem(grid()));
        assertNull(manager.craftItem(grid(row((ItemStack) null, (ItemStack) null),
                                          row((ItemStack) null, (ItemStack) null))));
    }

    // ── craftItem position-independence ──────────────────────────────────

    @Test
    void craftItemMatches1x1RecipeRegardlessOfPositionIn3x3Grid() {
        CraftingManager manager = new CraftingManager();
        manager.registerRecipe(new Recipe("dirtStick", grid(row(s(BlockType.DIRT, 1))),
                                          s(ItemType.STICK, 4)));

        // DIRT at [0][0]
        List<List<ItemStack>> gridCorner = grid(row(s(BlockType.DIRT, 1), null, null),
                                                row(null, null, null),
                                                row(null, null, null));
        ItemStack resultCorner = manager.craftItem(gridCorner);
        assertNotNull(resultCorner);
        assertEquals(4, resultCorner.getCount());

        // DIRT at [2][2]
        List<List<ItemStack>> gridOpposite = grid(row(null, null, null),
                                                   row(null, null, null),
                                                   row(null, null, s(BlockType.DIRT, 1)));
        ItemStack resultOpposite = manager.craftItem(gridOpposite);
        assertNotNull(resultOpposite);
        assertEquals(4, resultOpposite.getCount());
    }

    // ── craftItem offset matching ────────────────────────────────────────

    @Test
    void craftItemMatches2x2RecipePlacedOffsetIn3x3Grid() {
        CraftingManager manager = new CraftingManager();
        manager.registerRecipe(new Recipe("lBlock",
                grid(row(s(BlockType.DIRT, 1), s(BlockType.DIRT, 1)),
                     row(s(BlockType.DIRT, 1), (ItemStack) null)),
                s(ItemType.STICK, 2)));

        // Dirt occupies rows 1-2, cols 1-2 — an L-shape offset in 3x3.
        List<List<ItemStack>> inputGrid = grid(row(null, null, null),
                                               row(null, s(BlockType.DIRT, 1), s(BlockType.DIRT, 1)),
                                               row(null, s(BlockType.DIRT, 1), null));
        ItemStack result = manager.craftItem(inputGrid);
        assertNotNull(result);
        assertEquals(2, result.getCount());
    }

    // ── craftItem exact compact dimensions ───────────────────────────────

    @Test
    void craftItemEnforcesExactCompactDimensions() {
        CraftingManager manager = new CraftingManager();
        // 1x2 recipe: two DIRT side by side.
        manager.registerRecipe(new Recipe("hPair", grid(row(s(BlockType.DIRT, 1), s(BlockType.DIRT, 1))),
                                          s(ItemType.STICK, 1)));

        // Two DIRT stacked vertically (2x1) — must NOT match the 1x2 recipe.
        List<List<ItemStack>> verticalGrid = grid(row(s(BlockType.DIRT, 1)),
                                                  row(s(BlockType.DIRT, 1)));
        assertNull(manager.craftItem(verticalGrid));
    }

    // ── craftItem returns a copy ─────────────────────────────────────────

    @Test
    void craftItemReturnsACopyNotTheOriginalOutput() {
        CraftingManager manager = new CraftingManager();
        manager.registerRecipe(new Recipe("copyTest", grid(row(s(BlockType.STONE, 1))),
                                          s(ItemType.STICK, 4)));

        List<List<ItemStack>> g = grid(row(s(BlockType.STONE, 1)));
        ItemStack first = manager.craftItem(g);
        first.setCount(99);

        ItemStack second = manager.craftItem(g);
        assertEquals(4, second.getCount());
    }

    // ── getMatchedRecipe ─────────────────────────────────────────────────

    @Test
    void getMatchedRecipeReturnsRegisteredInstanceOrNull() {
        CraftingManager manager = new CraftingManager();
        Recipe recipe = new Recipe("matched", grid(row(s(BlockType.STONE, 1))), s(BlockType.STONE, 1));
        manager.registerRecipe(recipe);

        assertSame(recipe, manager.getMatchedRecipe(grid(row(s(BlockType.STONE, 1)))));
        assertNull(manager.getMatchedRecipe(grid(row(s(BlockType.DIRT, 1)))));
    }

    // ── getRecipesByCategory ─────────────────────────────────────────────

    @Test
    void getRecipesByCategoryFiltersCorrectly() {
        CraftingManager manager = new CraftingManager();
        Recipe blocksRecipe = new Recipe("forStone", grid(row(s(BlockType.STONE, 1))),
                                          s(BlockType.STONE, 1));
        Recipe toolsRecipe = new Recipe("forPick", grid(row(s(BlockType.DIRT, 1))),
                                        s(ItemType.WOODEN_PICKAXE, 1));
        manager.registerRecipe(blocksRecipe);
        manager.registerRecipe(toolsRecipe);

        assertEquals(1, manager.getRecipesByCategory(ItemCategory.BLOCKS).size());
        assertEquals(blocksRecipe, manager.getRecipesByCategory(ItemCategory.BLOCKS).get(0));

        assertEquals(1, manager.getRecipesByCategory(ItemCategory.TOOLS).size());
        assertEquals(toolsRecipe, manager.getRecipesByCategory(ItemCategory.TOOLS).get(0));
    }

    // ── getRecipesForItem ────────────────────────────────────────────────

    @Test
    void getRecipesForItemReturnsRecipesWhoseOutputItemMatches() {
        CraftingManager manager = new CraftingManager();
        manager.registerRecipe(new Recipe("makesStone", grid(row(s(BlockType.DIRT, 1))),
                                          s(BlockType.STONE, 1)));
        manager.registerRecipe(new Recipe("makesPick", grid(row(s(BlockType.STONE, 1))),
                                          s(ItemType.WOODEN_PICKAXE, 1)));

        List<Recipe> stoneRecipes = manager.getRecipesForItem(BlockType.STONE);
        assertEquals(1, stoneRecipes.size());
        assertEquals("makesStone", stoneRecipes.get(0).getId());

        List<Recipe> pickRecipes = manager.getRecipesForItem(ItemType.WOODEN_PICKAXE);
        assertEquals(1, pickRecipes.size());
        assertEquals("makesPick", pickRecipes.get(0).getId());
    }

    // ── createItemStack ──────────────────────────────────────────────────

    @Test
    void createItemStackResolvesBlockNameCaseInsensitivelyAndItemTypeByNameAndReturnsNullForUnknown() {
        ItemStack dirt = CraftingManager.createItemStack("dirt", 5);
        assertNotNull(dirt);
        assertEquals(BlockType.DIRT, dirt.getItem());
        assertEquals(5, dirt.getCount());

        ItemStack stick = CraftingManager.createItemStack("Stick", 10);
        assertNotNull(stick);
        assertEquals(ItemType.STICK, stick.getItem());
        assertEquals(10, stick.getCount());

        assertNull(CraftingManager.createItemStack("definitely_not_an_item", 1));
    }
}