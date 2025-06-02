package com.stonebreak;

import java.util.ArrayList;
import java.util.List;

public class CraftingManager {
    private final List<Recipe> recipes;
    // For faster lookups, especially if many recipes, can optimize by hashing/indexing recipes
    // e.g., by output item type or primary ingredient. For now, a list is fine.

    public CraftingManager() {
        this.recipes = new ArrayList<>();
        // Recipes are initialized in Game.java to avoid duplication
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

        // Calculate effective dimensions
        int effectiveHeight = maxRow - minRow + 1;
        int effectiveWidth = maxCol - minCol + 1;
        

        // Create the compact input grid based on the content
        List<List<ItemStack>> compactGrid = new ArrayList<>();

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
    
    /**
     * Gets recipes by category.
     * @param category The item category to filter by
     * @return List of recipes that produce items in the given category
     */
    public List<Recipe> getRecipesByCategory(ItemCategory category) {
        return recipes.stream()
            .filter(recipe -> recipe.getOutput().getCategory() == category)
            .toList();
    }
    
    /**
     * Gets recipes that produce a specific item.
     * @param item The item to search for
     * @return List of recipes that produce the item
     */
    public List<Recipe> getRecipesForItem(Item item) {
        return recipes.stream()
            .filter(recipe -> recipe.getOutput().getItem().isSameType(item))
            .toList();
    }

    /**
     * Clears all recipes (useful for testing).
     */
    public void clearRecipes() {
        recipes.clear();
    }
    
    /**
     * Gets the number of registered recipes.
     * @return The recipe count
     */
    public int getRecipeCount() {
        return recipes.size();
    }


    /**
     * Creates an ItemStack from an item name (for convenience).
     * @param itemName The name of the item (BlockType or ItemType)
     * @param count The quantity
     * @return ItemStack or null if item not found
     */
    public static ItemStack createItemStack(String itemName, int count) {
        // Try BlockType first
        for (BlockType blockType : BlockType.values()) {
            if (blockType.getName().equalsIgnoreCase(itemName)) {
                return new ItemStack(blockType, count);
            }
        }
        
        // Try ItemType
        ItemType itemType = ItemType.getByName(itemName);
        if (itemType != null) {
            return new ItemStack(itemType, count);
        }
        
        System.err.println("Warning: Item not found: " + itemName);
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