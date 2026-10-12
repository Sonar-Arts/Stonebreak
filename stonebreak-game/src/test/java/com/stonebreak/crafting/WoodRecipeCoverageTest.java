package com.stonebreak.crafting;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Recipes list one shaped variant per wood type, so a new wood has to be added to each one by
 * hand. Every generic oak-plank recipe must also craft with every other plank. Recipes whose
 * output is itself a specific wood (oak door, oak stairs) are exempt.
 */
class WoodRecipeCoverageTest {

    private static final List<String> OTHER_PLANKS = List.of(
        "stonebreak:pine_wood_planks", "stonebreak:elm_wood_planks", "stonebreak:cypress_planks");
    private static final Set<String> WOOD_SPECIFIC_OUTPUTS = Set.of("stonebreak:oak_door", "stonebreak:oak_stairs");

    private static final Set<Item> exempt = new HashSet<>();

    private static CraftingManager manager;
    private static BlockType oakPlanks;

    @BeforeAll
    static void load() {
        // Initialise both registries first, as the game does before loading recipes. If this
        // test runs alone, item lookups inside the loader would register items mid-iteration.
        oakPlanks = BlockType.WOOD_PLANKS;
        ItemType.values();
        manager = new CraftingManager();
        RecipeLoader.loadFromSBOs(manager);
        for (String id : WOOD_SPECIFIC_OUTPUTS) {
            exempt.add(BlockType.getByObjectId(id));
        }
    }

    @Test
    void cypressLogCraftsCypressPlanks() {
        ItemStack out = manager.craftItem(List.of(List.of(
            new ItemStack(BlockType.getByObjectId("stonebreak:cypress_log"), 1))));
        assertNotNull(out, "cypress log crafts nothing");
        assertEquals(BlockType.getByObjectId("stonebreak:cypress_planks"), out.getItem());
    }

    @Test
    void everyOakPlankRecipeAlsoCraftsWithEveryOtherPlank() {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (String plankId : OTHER_PLANKS) {
            BlockType planks = BlockType.getByObjectId(plankId);
            assertNotNull(planks, plankId + " not registered");
            for (Recipe recipe : manager.getAllRecipes()) {
                if (!usesOakPlanks(recipe) || exempt.contains(recipe.getOutput().getItem())) {
                    continue;
                }
                checked++;
                ItemStack out = manager.craftItem(withPlanks(recipe.getInputPattern(), planks));
                if (out == null || out.getItem() != recipe.getOutput().getItem()) {
                    missing.add(recipe.getOutput().getItem().getName() + " <- " + plankId);
                }
            }
        }
        assertFalse(checked == 0, "no oak-plank recipes found");
        assertEquals(List.of(), missing, "recipes that take oak planks but not these planks");
    }

    private static boolean usesOakPlanks(Recipe recipe) {
        return recipe.getInputPattern().stream().flatMap(List::stream)
            .anyMatch(s -> s != null && s.getItem() == oakPlanks);
    }

    private static List<List<ItemStack>> withPlanks(List<List<ItemStack>> pattern, BlockType planks) {
        List<List<ItemStack>> grid = new ArrayList<>();
        for (List<ItemStack> row : pattern) {
            List<ItemStack> copy = new ArrayList<>();
            for (ItemStack s : row) {
                copy.add(s != null && s.getItem() == oakPlanks ? new ItemStack(planks, s.getCount()) : s);
            }
            grid.add(copy);
        }
        return grid;
    }
}
