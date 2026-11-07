package com.stonebreak.crafting;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.ItemCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CraftingManager {
    private static final Logger logger = LoggerFactory.getLogger(CraftingManager.class);

    /** Key for bucketing recipes by their (already trimmed) pattern dimensions. */
    private record Shape(int width, int height) {}

    // Insertion-ordered so the first registered recipe wins when several match.
    private final Map<String, Recipe> recipesById = new LinkedHashMap<>();
    private final Map<Shape, List<Recipe>> recipesByShape = new HashMap<>();

    /**
     * Registers a new crafting recipe. Null recipes and recipes whose ID is already
     * registered are ignored, which keeps repeated loads idempotent.
     * @param recipe The recipe to add.
     */
    public void registerRecipe(Recipe recipe) {
        if (recipe == null || recipesById.putIfAbsent(recipe.getId(), recipe) != null) {
            return;
        }
        recipesByShape.computeIfAbsent(new Shape(recipe.getRecipeWidth(), recipe.getRecipeHeight()),
                k -> new ArrayList<>()).add(recipe);
    }

    /**
     * Attempts to craft an item based on the input grid.
     *
     * @param inputGrid A list of rows representing the crafting input (e.g., 2x2 or 3x3).
     *                  A null or empty ItemStack represents an empty slot.
     * @return A copy of the matching recipe's output, or null if no recipe matches.
     */
    public ItemStack craftItem(List<List<ItemStack>> inputGrid) {
        Recipe recipe = getMatchedRecipe(inputGrid);
        // Copy so callers can't mutate the recipe's output stack
        return recipe != null ? recipe.getOutput().copy() : null;
    }

    /**
     * Finds the recipe that matches the given input grid. The input is reduced to the
     * bounding box of its non-empty slots, so a recipe matches anywhere in the grid.
     * @param inputGrid The crafting grid input.
     * @return The matched Recipe object, or null if no recipe matches.
     */
    public Recipe getMatchedRecipe(List<List<ItemStack>> inputGrid) {
        if (inputGrid == null || inputGrid.isEmpty()) {
            return null;
        }

        int minRow = -1, maxRow = -1, minCol = Integer.MAX_VALUE, maxCol = -1;
        for (int r = 0; r < inputGrid.size(); r++) {
            List<ItemStack> row = inputGrid.get(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c < row.size(); c++) {
                ItemStack stack = row.get(c);
                if (stack != null && !stack.isEmpty()) {
                    if (minRow == -1) minRow = r;
                    maxRow = r;
                    if (c < minCol) minCol = c;
                    if (c > maxCol) maxCol = c;
                }
            }
        }

        if (minRow == -1) { // Grid is effectively empty
            return null;
        }

        List<Recipe> candidates = recipesByShape.get(new Shape(maxCol - minCol + 1, maxRow - minRow + 1));
        if (candidates == null) {
            return null;
        }
        // Everything outside the bounding box is empty, so matching the recipe-sized
        // window at its corner is equivalent to matching a compacted copy of the grid.
        for (Recipe recipe : candidates) {
            if (recipe.matches(inputGrid, minRow, minCol)) {
                return recipe;
            }
        }
        return null;
    }

    /**
     * Returns a list of all registered recipes, in registration order.
     * @return A new list containing all recipes.
     */
    public List<Recipe> getAllRecipes() {
        return new ArrayList<>(recipesById.values()); // Return a copy to prevent external modification
    }

    /**
     * Gets recipes by category.
     * @param category The item category to filter by
     * @return List of recipes that produce items in the given category
     */
    public List<Recipe> getRecipesByCategory(ItemCategory category) {
        return recipesById.values().stream()
            .filter(recipe -> recipe.getOutput().getCategory() == category)
            .toList();
    }

    /**
     * Gets recipes that produce a specific item.
     * @param item The item to search for
     * @return List of recipes that produce the item
     */
    public List<Recipe> getRecipesForItem(Item item) {
        return recipesById.values().stream()
            .filter(recipe -> recipe.getOutput().getItem() == item)
            .toList();
    }

    /**
     * Clears all recipes (used before reloading and in tests).
     */
    public void clearRecipes() {
        recipesById.clear();
        recipesByShape.clear();
    }

    /**
     * Gets the number of registered recipes.
     * @return The recipe count
     */
    public int getRecipeCount() {
        return recipesById.size();
    }

    /**
     * Creates an ItemStack from an item name (for convenience).
     * @param itemName The name of the item (BlockType or ItemType), case-insensitive for blocks
     * @param count The quantity
     * @return ItemStack or null if item not found
     */
    public static ItemStack createItemStack(String itemName, int count) {
        BlockType blockType = BlockType.getByName(itemName);
        if (blockType != null) {
            return new ItemStack(blockType, count);
        }

        ItemType itemType = ItemType.getByName(itemName);
        if (itemType != null) {
            return new ItemStack(itemType, count);
        }

        logger.warn("Item not found: {}", itemName);
        return null;
    }

    /**
     * Creates an ItemStack from an Item.
     * @param item The item (BlockType or ItemType)
     * @param count The quantity
     * @return ItemStack
     */
    public static ItemStack createItemStack(Item item, int count) {
        return new ItemStack(item, count);
    }
}
