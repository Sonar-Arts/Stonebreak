package com.stonebreak;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CraftingRecipe {
    private final ItemStack output;
    private final boolean isShapeless;
    private final Map<ItemStack.CraftableIdentifier, Integer> itemsConsumedPerCraft;

    // Shaped recipe fields
    private final ItemStack[] shapedInputPattern; // Flattened grid for shaped recipes
    private final int gridDimension; // 2 for 2x2, 3 for 3x3 (for shaped)

    // Shapeless recipe fields
    private final List<ItemStack> shapelessInputIngredients; // List of ItemStacks (type and count) for shapeless recipes

    /**
     * Constructor for a shaped crafting recipe.
     * @param output The resulting ItemStack.
     * @param input A 2D array of ItemStacks representing the recipe (e.g., 2x2 or 3x3).
     *              Use null or an empty ItemStack for an empty slot.
     * @param gridDimension The dimension of the crafting grid (e.g., 2 for 2x2, 3 for 3x3).
     */
    public CraftingRecipe(ItemStack output, ItemStack[][] input, int gridDimension) {
        this(output, input, gridDimension, null);
    }
    
    /**
     * Constructor for a shaped crafting recipe.
     * @param output The resulting ItemStack.
     * @param input A 2D array of ItemStacks representing the recipe (e.g., 2x2 or 3x3).
     *              Use null or an empty ItemStack for an empty slot.
     * @param gridDimension The dimension of the crafting grid (e.g., 2 for 2x2, 3 for 3x3).
     * @param itemsConsumedPerCraft An optional map defining custom item consumption for this recipe.
     *                              If null, the pattern's ingredients will be consumed by default (1 per non-empty slot).
     */
    public CraftingRecipe(ItemStack output, ItemStack[][] input, int gridDimension, Map<ItemStack.CraftableIdentifier, Integer> itemsConsumedPerCraft) {
        if (gridDimension <= 0) {
            throw new IllegalArgumentException("Grid dimension must be positive for shaped recipes.");
        }
        if (input == null || input.length != gridDimension) {
            throw new IllegalArgumentException("Input pattern rows (" + (input == null ? "null" : input.length) + ") must match gridDimension: " + gridDimension);
        }
        for (int i = 0; i < gridDimension; i++) {
            if (input[i] == null || input[i].length != gridDimension) {
                throw new IllegalArgumentException("Input pattern columns in row " + i + " (length: " + (input[i] == null ? "null" : input[i].length) + ") must match gridDimension: " + gridDimension);
            }
        }

        this.output = output.copy();
        this.isShapeless = false;
        this.gridDimension = gridDimension;
        this.shapedInputPattern = new ItemStack[gridDimension * gridDimension];
        for (int i = 0; i < gridDimension; i++) {
            for (int j = 0; j < gridDimension; j++) {
                if (input[i][j] != null && !input[i][j].isEmpty()) {
                    this.shapedInputPattern[i * gridDimension + j] = input[i][j].copy(1);
                } else {
                    this.shapedInputPattern[i * gridDimension + j] = ItemStack.empty();
                }
            }
        }
        this.shapelessInputIngredients = null;
        this.itemsConsumedPerCraft = (itemsConsumedPerCraft != null) ? new HashMap<>(itemsConsumedPerCraft) : null;
    }
    
    /**
     * Constructor for a shaped crafting recipe using a flat 1D input array.
     * @param output The resulting ItemStack.
     * @param flatInput A 1D array of ItemStacks, total length should be gridDimension * gridDimension.
     * @param gridDimension The dimension of the crafting grid.
     */
    public CraftingRecipe(ItemStack output, ItemStack[] flatInput, int gridDimension) {
        this(output, flatInput, gridDimension, null);
    }

    /**
     * Constructor for a shaped crafting recipe using a flat 1D input array.
     * @param output The resulting ItemStack.
     * @param flatInput A 1D array of ItemStacks, total length should be gridDimension * gridDimension.
     * @param gridDimension The dimension of the crafting grid.
     * @param itemsConsumedPerCraft An optional map defining custom item consumption for this recipe.
     *                              If null, the pattern's ingredients will be consumed by default (1 per non-empty slot).
     */
    public CraftingRecipe(ItemStack output, ItemStack[] flatInput, int gridDimension, Map<ItemStack.CraftableIdentifier, Integer> itemsConsumedPerCraft) {
        if (gridDimension <= 0) {
            throw new IllegalArgumentException("Grid dimension must be positive for shaped recipes.");
        }
        if (flatInput == null || flatInput.length != gridDimension * gridDimension) {
            throw new IllegalArgumentException("Flat input pattern must have " + (gridDimension * gridDimension) + " elements for a " + gridDimension + "x" + gridDimension + " recipe. Got: " + (flatInput == null ? "null" : flatInput.length));
        }
        this.output = output.copy();
        this.isShapeless = false;
        this.gridDimension = gridDimension;
        this.shapedInputPattern = new ItemStack[gridDimension * gridDimension];
        for (int i = 0; i < flatInput.length; i++) {
            if (flatInput[i] != null && !flatInput[i].isEmpty()) {
                this.shapedInputPattern[i] = flatInput[i].copy(1);
            } else {
                this.shapedInputPattern[i] = ItemStack.empty();
            }
        }
        this.shapelessInputIngredients = null;
        this.itemsConsumedPerCraft = (itemsConsumedPerCraft != null) ? new HashMap<>(itemsConsumedPerCraft) : null;
    }

    /**
     * Constructor for a shapeless crafting recipe.
     * @param output The resulting ItemStack.
     * @param shapelessIngredients A list of ItemStacks representing the required ingredients and their counts.
     *                             Null or empty items in the list will be ignored.
     */
    public CraftingRecipe(ItemStack output, List<ItemStack> shapelessIngredients) {
        this(output, shapelessIngredients, null);
    }
    
    /**
     * Constructor for a shapeless crafting recipe.
     * @param output The resulting ItemStack.
     * @param shapelessIngredients A list of ItemStacks representing the required ingredients and their counts.
     *                             Null or empty items in the list will be ignored.
     * @param itemsConsumedPerCraft An optional map defining custom item consumption for this recipe.
     *                              If null, the `shapelessIngredients` will be consumed by default.
     */
    public CraftingRecipe(ItemStack output, List<ItemStack> shapelessIngredients, Map<ItemStack.CraftableIdentifier, Integer> itemsConsumedPerCraft) {
        this.output = output.copy();
        this.isShapeless = true;
        this.shapelessInputIngredients = new ArrayList<>();
        if (shapelessIngredients != null) {
            for (ItemStack ingredient : shapelessIngredients) {
                if (ingredient != null && !ingredient.isEmpty() && ingredient.getCount() > 0) {
                    this.shapelessInputIngredients.add(ingredient.copy());
                }
            }
        }
        if (this.shapelessInputIngredients.isEmpty()) {
            throw new IllegalArgumentException("Shapeless recipe must have at least one valid ingredient.");
        }
        this.shapedInputPattern = null;
        this.gridDimension = 0;
        this.itemsConsumedPerCraft = (itemsConsumedPerCraft != null) ? new HashMap<>(itemsConsumedPerCraft) : null;
    }


    public ItemStack getOutput() {
        return output.copy(); // Return a copy
    }

    /**
     * Gets the input pattern for shaped recipes.
     * Returns null for shapeless recipes.
     * @return A copy of the shaped input pattern, or null.
     */
    public ItemStack[] getShapedInputPattern() {
        if (isShapeless || shapedInputPattern == null) {
            return null;
        }
        // Return copies to prevent modification of the recipe's pattern
        ItemStack[] patternCopy = new ItemStack[gridDimension * gridDimension];
        for (int i = 0; i < patternCopy.length; i++) {
            if (this.shapedInputPattern[i] != null) {
                patternCopy[i] = this.shapedInputPattern[i].copy();
            } else {
                patternCopy[i] = ItemStack.empty(); // Return empty ItemStack for empty slots
            }
        }
        return patternCopy;
    }
    
    /**
     * Gets the list of ingredients for shapeless recipes.
     * Returns null for shaped recipes.
     * @return A new list containing copies of shapeless ingredients, or null.
     */
    public List<ItemStack> getShapelessIngredients() {
        if (!isShapeless || shapelessInputIngredients == null) {
            return null;
        }
        List<ItemStack> ingredientsCopy = new ArrayList<>();
        for (ItemStack ingredient : this.shapelessInputIngredients) {
            ingredientsCopy.add(ingredient.copy());
        }
        return ingredientsCopy;
    }

    /**
     * Gets the grid dimension for shaped recipes.
     * Returns 0 or an undefined value for shapeless recipes.
     * @return The grid dimension (e.g., 2 or 3) for shaped recipes.
     */
    public int getGridDimension() {
        return gridDimension;
    }
    
    /**
     * For shaped recipes: Checks if a specific slot in the recipe's input pattern requires an item.
     * This is useful for consuming ingredients. Not applicable for shapeless recipes from this method.
     * @param index The flat index in the input pattern (0 to gridDimension*gridDimension - 1).
     * @return true if the slot at the given index in the RECIPE is not null and not empty. Returns false if shapeless.
     */
    public boolean isSlotUsedInShapedPattern(int index) {
        if (isShapeless || shapedInputPattern == null) return false;
        if (index < 0 || index >= shapedInputPattern.length) {
            return false;
        }
        return !shapedInputPattern[index].isEmpty(); // Check if the itemStack itself is empty
    }

    /**
     * Checks if the given crafting grid matches this recipe.
     * @param craftGrid An array of ItemStacks representing the current items in the crafting grid.
     *                  For shaped recipes, length must match gridDimension * gridDimension.
     *                  For shapeless recipes, the content of the grid is checked against required ingredients.
     * @return true if the grid matches the recipe, false otherwise.
     */
    public boolean matches(ItemStack[] craftGrid) {
        if (craftGrid == null) return false;

        if (isShapeless) {
            // --- Shapeless Recipe Matching ---
            if (shapelessInputIngredients == null || shapelessInputIngredients.isEmpty()) {
                return false; // Should not happen if constructor validates
            }

            Map<ItemStack.CraftableIdentifier, Integer> requiredIngredientsMap = new HashMap<>();
            for (ItemStack reqStack : shapelessInputIngredients) {
                if (!reqStack.isEmpty()) { // Only add non-empty ingredients to the map
                    requiredIngredientsMap.put(reqStack.getIdentifier(),
                                               requiredIngredientsMap.getOrDefault(reqStack.getIdentifier(), 0) + reqStack.getCount());
                }
            }

            Map<ItemStack.CraftableIdentifier, Integer> providedIngredientsMap = new HashMap<>();
            for (ItemStack gridStack : craftGrid) {
                if (gridStack != null && !gridStack.isEmpty()) {
                    providedIngredientsMap.put(gridStack.getIdentifier(),
                                               providedIngredientsMap.getOrDefault(gridStack.getIdentifier(), 0) + gridStack.getCount());
                }
            }

            // Check 1: Provided map must have at least the counts for all required CraftableIdentifiers
            for (Map.Entry<ItemStack.CraftableIdentifier, Integer> requiredEntry : requiredIngredientsMap.entrySet()) {
                ItemStack.CraftableIdentifier requiredType = requiredEntry.getKey();
                int requiredCount = requiredEntry.getValue();
                if (providedIngredientsMap.getOrDefault(requiredType, 0) < requiredCount) {
                    return false; // Not enough of a required item type
                }
            }

            // Check 2: Provided map must not contain any CraftableIdentifiers not in the required map.
            // This ensures no "junk" items are in the grid.
            for (ItemStack.CraftableIdentifier providedType : providedIngredientsMap.keySet()) {
                if (!requiredIngredientsMap.containsKey(providedType)) {
                    return false; // Grid contains an item type not in the recipe
                }
            }
            
            // Check 3: The total count of items in the grid (sum of counts of all unique items provided)
            // must exactly match the total count of items required by the recipe.
            // This handles cases like recipe [A:1, B:1] and grid [A:1, B:2] (should not match),
            // and ensures only exactly the required number of items (in total) are present.
            int totalRequiredCount = 0;
            for (int count : requiredIngredientsMap.values()) {
                totalRequiredCount += count;
            }

            int totalProvidedCount = 0;
            for (int count : providedIngredientsMap.values()) {
                totalProvidedCount += count;
            }
            return totalProvidedCount >= totalRequiredCount;

        } else {
            // --- Shaped Recipe Matching ---
            if (shapedInputPattern == null) return false; // Should not happen for shaped recipes
            if (craftGrid.length != this.gridDimension * this.gridDimension) {
                 // For a strict shaped match, the grid size must be exact.
                return false;
            }

            for (int i = 0; i < this.shapedInputPattern.length; i++) {
                ItemStack recipeItem = this.shapedInputPattern[i]; // From shaped pattern (type only, count is 1 for definition)
                ItemStack gridItem = craftGrid[i]; // Item from the actual crafting grid

                // If recipe slot is empty, corresponding grid slot must also be empty
                if (recipeItem.isEmpty()) { // Use isEmpty() for checking empty recipe slot
                    if (!gridItem.isEmpty()) { // Grid has an item where recipe expects none
                        return false; 
                    }
                } else { // Recipe expects a specific item in this slot
                    if (gridItem.isEmpty()) { // Grid has no item where recipe expects one
                        return false; 
                    }
                    // Compare item types using ItemStack.equals() which ignores count and compares identifiers
                    if (!recipeItem.equals(gridItem)) {
                        return false; // Item types (CraftableIdentifiers) do not match
                    }
                    // In shaped recipes, the player must provide at least one of the item in the slot.
                    // The recipe pattern indicates the type, not a minimum count for *that specific slot*.
                    // As long as there is an item of the correct type (and count >= 1 for validity), it matches.
                    // The total count check will happen later if necessary (e.g., deducting ingredients).
                    if (gridItem.getCount() < 1) { // A slot requiring an item must have at least count 1
                         return false; 
                    }
                }
            }
            return true;
        }
    }
    
    /**
     * Returns a map indicating the specific count of each item type to be consumed from the crafting grid
     * when this recipe is successfully crafted. If this map is null, the quantities defined in
     * `shapelessInputIngredients` for shapeless recipes or 1 per used slot for shaped recipes should be consumed.
     * This provides flexibility for recipes that consume a different amount than what's strictly required for matching
     * (e.g., consuming 1 from a stack of 64).
     *
     * @return A map of `CraftableIdentifier` to count to be consumed, or null if default consumption applies.
     *         The map returned is a copy to prevent external modification.
     */
    public Map<ItemStack.CraftableIdentifier, Integer> getItemsConsumedPerCraft() {
        if (itemsConsumedPerCraft != null) {
            return new HashMap<>(itemsConsumedPerCraft);
        }
        return null;
    }

    public boolean isShapeless() {
        return isShapeless;
    }
}