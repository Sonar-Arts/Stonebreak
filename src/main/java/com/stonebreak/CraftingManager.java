package com.stonebreak;

import java.util.ArrayList;
import java.util.List;

public class CraftingManager {
    private final List<Recipe> recipes;
    // For faster lookups, especially if many recipes, can optimize by hashing/indexing recipes
    // e.g., by output item type or primary ingredient. For now, a list is fine.

    public CraftingManager() {
        this.recipes = new ArrayList<>();
    }

    /**
     * Registers a new crafting recipe.
     * @param recipe The recipe to add.
     */
    public void registerRecipe(Recipe recipe) {
        if (recipe != null && !recipes.contains(recipe)) {
            recipes.add(recipe);
        }
    }

    /**
     * Attempts to craft an item based on the input grid.
     * Iterates through registered recipes to find a match.
     *
     * @param inputGrid A list of lists representing the crafting input (e.g., 2x2 or 3x3).
     *                  'null' ItemStack can represent an empty slot.
     * @return The resulting ItemStack if a recipe matches, otherwise null or an empty ItemStack.
     */
    public ItemStack craftItem(List<List<ItemStack>> inputGrid) {
        if (inputGrid == null || inputGrid.isEmpty()) {
            return null; // Or ItemStack.EMPTY if such a constant exists
        }

        // Determine the effective dimensions of the player's input
        // by finding the minimal bounding box of non-null items.
        int minRow = -1, maxRow = -1, minCol = -1, maxCol = -1;

        for (int r = 0; r < inputGrid.size(); r++) {
            List<ItemStack> row = inputGrid.get(r);
            if (row != null) {
                for (int c = 0; c < row.size(); c++) {
                    if (row.get(c) != null && !row.get(c).isEmpty()) {
                        if (minRow == -1) minRow = r;
                        maxRow = r;
                        if (minCol == -1 || c < minCol) minCol = c;
                        if (c > maxCol) maxCol = c;
                    }
                }
            }
        }

        // If the grid is effectively empty
        if (minRow == -1) {
            return null;
        }

        // Create the compact input grid based on the content
        List<List<ItemStack>> compactGrid = new ArrayList<>();
        int effectiveHeight = maxRow - minRow + 1;
        int effectiveWidth = maxCol - minCol + 1;

        for (int r = 0; r < effectiveHeight; r++) {
            List<ItemStack> newRow = new ArrayList<>();
            List<ItemStack> originalRow = (minRow + r < inputGrid.size()) ? inputGrid.get(minRow + r) : null;
            for (int c = 0; c < effectiveWidth; c++) {
                ItemStack item = null;
                if (originalRow != null && (minCol + c < originalRow.size())) {
                    item = originalRow.get(minCol + c);
                }
                newRow.add(item); // Add item, which could be null if slot was empty
            }
            compactGrid.add(newRow);
        }


        // Try to match against registered recipes using the compact grid
        for (Recipe recipe : recipes) {
            // Check if dimensions match first
            if (recipe.getRecipeHeight() == effectiveHeight && recipe.getRecipeWidth() == effectiveWidth) {
                if (recipe.matches(compactGrid, 0, 0)) {
                    // Found a matching recipe
                    return recipe.getOutput().copy(); // Return a copy to prevent modification of recipe's output stack
                }
            }
        }
        
        // No recipe matched
        return null; // Or a specific "empty" ItemStack instance
    }
/**
     * Finds the recipe that matches the given input grid.
     * @param inputGrid The crafting grid input.
     * @return The matched Recipe object, or null if no recipe matches.
     */
    public Recipe getMatchedRecipe(List<List<ItemStack>> inputGrid) {
        if (inputGrid == null || inputGrid.isEmpty()) {
            return null;
        }

        int minRow = -1, maxRow = -1, minCol = -1, maxCol = -1;
        for (int r = 0; r < inputGrid.size(); r++) {
            List<ItemStack> row = inputGrid.get(r);
            if (row != null) {
                for (int c = 0; c < row.size(); c++) {
                    if (row.get(c) != null && !row.get(c).isEmpty()) {
                        if (minRow == -1) minRow = r;
                        maxRow = r;
                        if (minCol == -1 || c < minCol) minCol = c;
                        if (c > maxCol) maxCol = c;
                    }
                }
            }
        }

        if (minRow == -1) { // Grid is effectively empty
            return null;
        }

        List<List<ItemStack>> compactGrid = new ArrayList<>();
        int effectiveHeight = maxRow - minRow + 1;
        int effectiveWidth = maxCol - minCol + 1;

        for (int r = 0; r < effectiveHeight; r++) {
            List<ItemStack> newRow = new ArrayList<>();
            List<ItemStack> originalRow = (minRow + r < inputGrid.size()) ? inputGrid.get(minRow + r) : null;
            for (int c = 0; c < effectiveWidth; c++) {
                ItemStack item = null;
                if (originalRow != null && (minCol + c < originalRow.size())) {
                    item = originalRow.get(minCol + c);
                }
                newRow.add(item);
            }
            compactGrid.add(newRow);
        }

        for (Recipe recipe : recipes) {
            if (recipe.getRecipeHeight() == effectiveHeight && recipe.getRecipeWidth() == effectiveWidth) {
                if (recipe.matches(compactGrid, 0, 0)) {
                    return recipe; // Return the actual recipe object
                }
            }
        }
        return null;
    }

    /**
     * Returns a list of all registered recipes.
     * @return A new list containing all recipes.
     */
    public List<Recipe> getAllRecipes() {
        return new ArrayList<>(this.recipes); // Return a copy to prevent external modification
    }

    // Placeholder for shapeless recipe matching - not required for this task
    // private ItemStack checkForShapelessRecipe(List<ItemStack> flatInputItems) { ... }


    // Example of how one might define a utility for creating placeholder ItemStacks
    // This is not strictly part of CraftingManager but useful for testing/setup
    public static ItemStack createPlaceholderItemStack(String itemTypeName, int count) {
        // This is a placeholder. In a real system, you'd look up an ItemType/BlockType ID.
        // For example, if BlockType had a static method `get(String name)`:
        // return new ItemStack(BlockType.get(itemTypeName).getId(), count);

        // For now, using a simple hash of the name as a pseudo-ID if BlockType isn't integrated
        // THIS IS NOT ROBUST FOR A REAL GAME. Just for initial system setup.
        int pseudoId = itemTypeName.hashCode();
        // Ensure positive ID if hash is negative, and avoid 0 if BlockType.AIR.getId() is 0.
        if (pseudoId == BlockType.AIR.getId()) pseudoId = -1; // just an example shift

        // To make this work with existing ItemStack constructor expecting blockTypeId
        // we'd need a mapping from these string names to actual BlockType.java IDs.
        // The instructions mention "use string identifiers for item types if full ItemType
        // integration is complex". ItemStack currently takes int blockTypeId.
        // A Map<String, Integer> for placeholder IDs would be one way if direct BlockType access is hard.
        
        // For the spirit of "string identifiers" let's assume BlockType can give us an ID from a string.
        // If not, this example would need to be adapted. For instance, manually assign pseudo IDs.
        // Example: if (itemTypeName.equals("WOOD_LOG")) pseudoId = 1000; etc.

        // This placeholder ID will cause issues if it collides with real BlockType IDs or if
        // BlockType.java does not actually use these string names.
        // The actual ItemStacks for recipes will be created using existing BlockType IDs from BlockType.java

        // For testing Recipe.java & CraftingManager.java, actual recipes would be:
        // List<List<ItemStack>> pattern = ...
        // pattern.get(0).add(new ItemStack(BlockType.LOG.getId(), 1)); // example
        // new Recipe("planks", pattern, new ItemStack(BlockType.PLANKS.getId(), 4));
        System.err.println("Warning: createPlaceholderItemStack is using pseudo-IDs. Integrate with actual BlockType IDs for proper functionality.");
        return new ItemStack(pseudoId, count);
    }
}