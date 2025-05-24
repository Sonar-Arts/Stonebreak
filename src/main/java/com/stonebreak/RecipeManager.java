package com.stonebreak;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecipeManager {
    private final List<CraftingRecipe> recipes;
    private static RecipeManager instance;

    private RecipeManager() { // Private constructor
        recipes = new ArrayList<>();
        initializeRecipes();
    }

    public static synchronized RecipeManager getInstance() {
        if (instance == null) {
            instance = new RecipeManager();
        }
        return instance;
    }

    private void initializeRecipes() {
        // --- Common Items ---
        ItemStack woodLogItem = new ItemStack(BlockType.WOOD, 1);
        ItemStack woodPlankItem = new ItemStack(BlockType.WOOD, 1);
        // Output ItemStacks
        ItemStack fourWoodOutput = new ItemStack(BlockType.WOOD, 4);
        ItemStack fourSticksOutput = new ItemStack(ItemType.STICK, 4);

        // --- Recipe 1: 1 Wood Log -> 4 Wood Planks (Shapeless) ---
        // This recipe means one log in any slot of a 2x2 or 3x3 grid yields 4 wood.
        List<ItemStack> logToWoodIngredients = new ArrayList<>();
        logToWoodIngredients.add(woodLogItem.copy(1)); // Shapeless ingredients define actual count needed

        Map<ItemStack.CraftableIdentifier, Integer> woodConsumption = new HashMap<>();
        woodConsumption.put(woodLogItem.getIdentifier(), 1); // Consume exactly 1 wood log

        recipes.add(new CraftingRecipe(fourWoodOutput.copy(), logToWoodIngredients, woodConsumption));

        // --- Recipe 2: 2 Wood Planks (vertically) -> 4 Sticks (Shaped 2x2) ---
        ItemStack[][] planksToSticksPattern = new ItemStack[2][2];
        planksToSticksPattern[0][0] = woodPlankItem.copy(1); // Input pattern uses count 1 for type matching
        planksToSticksPattern[1][0] = woodPlankItem.copy(1);
        // Other slots are null by default in a new array
        recipes.add(new CraftingRecipe(fourSticksOutput.copy(), planksToSticksPattern, 2));
        
        // For the "2 sticks + 1 coal -> 4 torches" example:
        // This would require a "COAL" item type (not COAL_ORE) and a "TORCH" item type.
        // Assuming these existed as ItemType.COAL and ItemType.TORCH:
        // List<ItemStack> torchIngredients = new ArrayList<>();
        // torchIngredients.add(new ItemStack(ItemType.STICK, 2));
        // torchIngredients.add(new ItemStack(ItemType.COAL, 1));
        // recipes.add(new CraftingRecipe(new ItemStack(ItemType.TORCH, 4), torchIngredients));
        // Since these types don't exist, this recipe is not added.
    }

    /**
     * Finds a matching recipe for the given crafting grid.
     * @param craftingGrid An array of ItemStacks from the crafting grid (e.g., 4 for 2x2, 9 for 3x3).
     * @param is3x3Grid True if the grid is 3x3, false if 2x2.
     * @return The matching CraftingRecipe, or null if no recipe matches.
     */
    public CraftingRecipe findMatchingRecipe(ItemStack[] craftingGrid, boolean is3x3Grid) {
        int expectedGridSize = is3x3Grid ? 9 : 4;
        int expectedDimension = is3x3Grid ? 3 : 2;

        if (craftingGrid == null || craftingGrid.length != expectedGridSize) {
            // System.err.println("RecipeManager: craftingGrid length (" + (craftingGrid == null ? "null" : craftingGrid.length) +
            //                    ") does not match expected size " + expectedGridSize + " for is3x3=" + is3x3Grid);
            return null; // Grid size mismatch
        }

        for (CraftingRecipe recipe : recipes) {
            if (recipe.isShapeless()) {
                // Shapeless recipes can match any grid size, the `matches` method handles ingredient checking.
                if (recipe.matches(craftingGrid)) {
                    return recipe;
                }
            } else {
                // For shaped recipes, the recipe's grid dimension must match the current grid's dimension.
                if (recipe.getGridDimension() == expectedDimension) {
                    if (recipe.matches(craftingGrid)) {
                        return recipe;
                    }
                }
            }
        }
        return null; // No recipe found
    }
    
    /**
     * Convenience overload for 2x2 inventory crafting.
     * @param craftingGrid2x2 A 4-element array of ItemStacks.
     * @return The matching CraftingRecipe, or null.
     */
    public CraftingRecipe findMatchingRecipe(ItemStack[] craftingGrid2x2) {
        // Ensure the input array is of size 4 for a 2x2 grid.
        if (craftingGrid2x2 == null || craftingGrid2x2.length != 4) {
            // System.err.println("RecipeManager: 2x2 craftingGrid input is not of length 4.");
            return null;
        }
        return findMatchingRecipe(craftingGrid2x2, false); // Calls the main method, assumes 2x2 grid (false)
    }

    public List<CraftingRecipe> getAllRecipes() {
        return new ArrayList<>(recipes); // Return a copy for safety
    }
}