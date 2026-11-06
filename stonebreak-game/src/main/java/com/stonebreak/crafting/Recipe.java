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
        if (inputGrid == null || startRow < 0 || startCol < 0
                || startRow + recipeHeight > inputGrid.size()) {
            return false;
        }

        // Only the recipe-sized window at (startRow, startCol) is checked. The caller
        // (CraftingManager) is responsible for ensuring nothing sits outside it.
        for (int r = 0; r < recipeHeight; r++) {
            List<ItemStack> recipeRow = inputPattern.get(r);
            List<ItemStack> gridRow = inputGrid.get(startRow + r);
            // A null grid row reads as all-empty; a present row must be wide enough.
            if (gridRow != null && startCol + recipeWidth > gridRow.size()) {
                return false;
            }

            for (int c = 0; c < recipeWidth; c++) {
                ItemStack recipeItem = recipeRow.get(c);
                ItemStack gridItem = gridRow != null ? gridRow.get(startCol + c) : null;
                boolean gridEmpty = gridItem == null || gridItem.isEmpty();

                if (recipeItem == null) {
                    if (!gridEmpty) {
                        return false; // Shaped recipe expects this slot empty
                    }
                    continue;
                }
                // Match by Item singleton identity. ItemType and BlockType
                // each maintain a single instance per registered objectId,
                // so reference equality is the canonical "same kind" test
                // and immune to the numericId collisions that plague the
                // SBO data (e.g. stick and wooden_axe both declaring 1013).
                if (gridEmpty
                        || recipeItem.getItem() != gridItem.getItem()
                        || gridItem.getCount() < recipeItem.getCount()) {
                    return false;
                }
            }
        }
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