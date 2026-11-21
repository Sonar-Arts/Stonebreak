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
 * Every generic plank recipe (one that takes oak planks) must also craft with every other plank —
 * today through the {@code #stonebreak:wooden_planks} dictionary, so a new wood only has to declare
 * the dictionary on its plank SBO. Recipes whose output is a specific wood (oak stairs) are exempt.
 */
class WoodRecipeCoverageTest {

    private static final List<String> OTHER_PLANKS = List.of(
        "stonebreak:pine_wood_planks", "stonebreak:elm_wood_planks", "stonebreak:cypress_planks",
        "stonebreak:dry_oak_planks");
    private static final Set<String> WOOD_SPECIFIC_OUTPUTS = Set.of("stonebreak:oak_stairs");

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
    void dryOakLogCraftsDryOakPlanks() {
        ItemStack out = manager.craftItem(List.of(List.of(
            new ItemStack(BlockType.DRY_OAK_LOG, 1))));
        assertNotNull(out, "dry oak log crafts nothing");
        assertEquals(BlockType.DRY_OAK_PLANKS, out.getItem());
        assertEquals(4, out.getCount());
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
                ItemStack out = manager.craftItem(withPlanks(recipe, planks));
                if (out == null || out.getItem() != recipe.getOutput().getItem()) {
                    missing.add(recipe.getOutput().getItem().getName() + " <- " + plankId);
                }
            }
        }
        assertFalse(checked == 0, "no oak-plank recipes found");
        assertEquals(List.of(), missing, "recipes that take oak planks but not these planks");
    }

    private static boolean usesOakPlanks(Recipe recipe) {
        return recipe.getIngredientPattern().stream().flatMap(List::stream)
            .anyMatch(ing -> ing != null && ing.test(oakPlanks));
    }

    /** The recipe's grid with {@code planks} in every slot that takes oak planks. */
    private static List<List<ItemStack>> withPlanks(Recipe recipe, BlockType planks) {
        List<List<ItemStack>> grid = new ArrayList<>();
        for (List<Ingredient> row : recipe.getIngredientPattern()) {
            List<ItemStack> copy = new ArrayList<>();
            for (Ingredient ing : row) {
                copy.add(ing == null ? null
                    : new ItemStack(ing.test(oakPlanks) ? planks : ing.representative(), 1));
            }
            grid.add(copy);
        }
        return grid;
    }

    @Test
    void oakDoorCraftsFromMixedPlanks() {
        BlockType pine = BlockType.getByObjectId("stonebreak:pine_wood_planks");
        BlockType elm = BlockType.getByObjectId("stonebreak:elm_wood_planks");
        ItemStack stick = new ItemStack(ItemType.STICK, 1);
        List<List<ItemStack>> grid = List.of(
            List.of(new ItemStack(oakPlanks, 1), new ItemStack(pine, 1)),
            List.of(new ItemStack(elm, 1), stick),
            List.of(new ItemStack(pine, 1), new ItemStack(oakPlanks, 1)));
        ItemStack out = manager.craftItem(grid);
        assertNotNull(out, "mixed planks + stick crafts nothing");
        assertEquals(BlockType.getByObjectId("stonebreak:oak_door"), out.getItem());
    }

    @Test
    void stairsStaySpecificToTheirPlank() {
        BlockType pine = BlockType.getByObjectId("stonebreak:pine_wood_planks");
        BlockType elm = BlockType.getByObjectId("stonebreak:elm_wood_planks");
        ItemStack out = manager.craftItem(stairs(pine));
        assertNotNull(out);
        assertEquals(BlockType.getByObjectId("stonebreak:pine_stairs"), out.getItem());
        assertEquals(BlockType.getByObjectId("stonebreak:elm_stairs"), manager.craftItem(stairs(elm)).getItem());
    }

    private static List<List<ItemStack>> stairs(BlockType planks) {
        java.util.function.Supplier<ItemStack> p = () -> new ItemStack(planks, 1);
        return List.of(
            java.util.Arrays.asList(p.get(), null, null),
            java.util.Arrays.asList(p.get(), p.get(), null),
            List.of(p.get(), p.get(), p.get()));
    }
}
