package com.stonebreak.crafting;

import com.stonebreak.items.ItemStack;

import java.util.List;
import java.util.Objects;

/**
 * Represents a crafting recipe.
 * For shaped recipes, the inputPattern defines the exact arrangement of items.
 * Null values in the inputPattern represent empty slots.
 */
public class Recipe {
    private final String id;
    private final List<List<ItemStack>> inputPattern; // Inner list is a row, outer list is the grid
    private final ItemStack output;
    private final int recipeWidth;
    private final int recipeHeight;

    /**
     * Constructs a new Recipe.
     *
     * @param id A unique identifier for this recipe.
     * @param inputPattern A list of lists representing the crafting grid pattern.
     *                     Outer list contains rows, inner list contains ItemStacks for columns.
     *                     'null' can be used to represent an empty slot in the pattern.
     * @param output The ItemStack produced by this recipe.
     */
    public Recipe(String id, List<List<ItemStack>> inputPattern, ItemStack output) {
        Objects.requireNonNull(id, "Recipe ID cannot be null");
        Objects.requireNonNull(inputPattern, "Input pattern cannot be null");
        Objects.requireNonNull(output, "Output ItemStack cannot be null");

        this.id = id;
        this.inputPattern = inputPattern;
        this.output = output;

        if (inputPattern.isEmpty()) {
            this.recipeHeight = 0;
            this.recipeWidth = 0;
        } else {
            this.recipeHeight = inputPattern.size();
            this.recipeWidth = inputPattern.get(0) != null ? inputPattern.get(0).size() : 0;
            // Validate that all rows in the input pattern have the same width
            for (List<ItemStack> row : inputPattern) {
                if (row != null && row.size() != this.recipeWidth) {
                    throw new IllegalArgumentException("All rows in the input pattern must have the same number of columns.");
                }
                if (row == null && this.recipeWidth != 0) {
                     throw new IllegalArgumentException("A null row is only allowed if the recipe width is 0.");
                }
            }
        }
    }

    public String getId() {
        return id;
    }

    public List<List<ItemStack>> getInputPattern() {
        return inputPattern;
    }
/**
     * Gets the input pattern of the recipe as a 2D array of ItemStack.
     * This is a convenience method for accessing the pattern in a more traditional array format.
     * Note: The underlying storage is List<List<ItemStack>>. Modifying the returned array
     * will not modify the original recipe.
     * @return A 2D array of ItemStack representing the recipe pattern.
     */
    public ItemStack[][] getPattern() {
        if (inputPattern == null || inputPattern.isEmpty()) {
            return new ItemStack[0][0];
        }
        int rows = recipeHeight;
        int cols = recipeWidth;
        ItemStack[][] patternArray = new ItemStack[rows][cols];
        for (int r = 0; r < rows; r++) {
            List<ItemStack> rowList = inputPattern.get(r);
            for (int c = 0; c < cols; c++) {
                if (rowList != null && c < rowList.size()) {
                    patternArray[r][c] = rowList.get(c); // Can be null if slot is empty
                } else {
                    patternArray[r][c] = null; // Should not happen with current validation
                }
            }
        }
        return patternArray;
    }

    public ItemStack getOutput() {
        return output;
    }

    public int getRecipeWidth() {
        return recipeWidth;
    }

    public int getRecipeHeight() {
        return recipeHeight;
    }

    /**
     * Checks if this recipe matches the provided input grid at a specific offset.
     *
     * @param inputGrid The player's crafting input.
     * @param startRow The starting row in the inputGrid to check against.
     * @param startCol The starting column in the inputGrid to check against.
     * @return True if the recipe matches, false otherwise.
     */
    public boolean matches(List<List<ItemStack>> inputGrid, int startRow, int startCol) {
        if (inputGrid == null) {
            return false;
        }
        int inputGridHeight = inputGrid.size();
        int inputGridWidth = inputGrid.isEmpty() || inputGrid.get(0) == null ? 0 : inputGrid.get(0).size();

        // Check if recipe can fit at the given offset
        if (startRow + recipeHeight > inputGridHeight || startCol + recipeWidth > inputGridWidth) {
            return false;
        }


        for (int r = 0; r < recipeHeight; r++) {
            List<ItemStack> recipeRow = inputPattern.get(r);
            List<ItemStack> gridRow = inputGrid.get(startRow + r);

            if (recipeRow == null) { // Should not happen with current constructor validation
                continue;
            }
            if (gridRow == null && recipeWidth > 0) { // An empty row in input grid where recipe expects items
                return false;
            }

            for (int c = 0; c < recipeWidth; c++) {
                ItemStack recipeItem = recipeRow.get(c);
                ItemStack gridItem = (gridRow != null && c < gridRow.size()) ? gridRow.get(c) : null;


                // Using a simplified comparison for now.
                // In a full system, this would compare item types and potentially amounts/metadata.
                if (recipeItem == null && gridItem == null) {
                    continue; // Both empty, slot matches
                }
                if (recipeItem == null && (gridItem != null && !gridItem.isEmpty())) {
                     // Recipe expects empty, but grid has an item in this part of the recipe's bounds
                    // However, for shapeless, this might be fine. For shaped, it must be empty.
                    // For now, for shaped recipes, if recipe expects empty, grid must be empty.
                    return false;
                }
                if (recipeItem != null && (gridItem == null || gridItem.isEmpty())) {
                    return false; // Recipe expects an item, but grid slot is empty
                }
                if (recipeItem != null && gridItem != null && !gridItem.isEmpty()) {
                    // Basic check: item types must match.
                    // A more robust check would involve ItemType.equals() or similar.
                    // For now, if we're using string identifiers in ItemStack (as placeholder), this will work.
                    // If using proper ItemType objects, a conversion from blockTypeId to ItemType and then .equals() would be needed.
                    if (recipeItem.getBlockTypeId() != gridItem.getBlockTypeId()) {
                        return false;
                    }
                    // Potentially check stack sizes if recipes require specific counts per slot,
                    // for now, assume 1 item from recipe means >=1 item in grid.
                    if (gridItem.getCount() < recipeItem.getCount()) { // Recipe needs more than available
                        return false;
                    }
                }
            }
        }
        
        // After checking the recipe pattern, ensure no other items are in the crafting grid
        // within the bounds defined by the recipe dimensions, effectively creating a "tight fit".
        // However, the instruction "checking if a recipe fits within the provided grid and
        // if the recipe's dimensions match the non-empty part of the input grid" implies
        // we should consider a compact version of the input.

        // The current `matches` logic correctly checks if the recipe's non-null items match items
        // in the grid and recipe's null items match nulls in the grid for the specific subgrid.
        // The CraftingManager will be responsible for finding the "effective" input grid.
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Recipe recipe = (Recipe) o;
        return id.equals(recipe.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Recipe{" +
               "id='" + id + '\'' +
               ", output=" + output +
               ", recipeWidth=" + recipeWidth +
               ", recipeHeight=" + recipeHeight +
               '}';
    }
}