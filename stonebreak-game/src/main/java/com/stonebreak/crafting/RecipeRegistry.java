package com.stonebreak.crafting;

import java.util.Arrays;
import java.util.List;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;

/**
 * Registers all built-in crafting recipes on a {@link CraftingManager}.
 * Extracted from {@code Game.initializeCraftingRecipes()} to keep
 * {@code Game} focused on master-controller concerns.
 */
public final class RecipeRegistry {

    private RecipeRegistry() {
    }

    /**
     * Registers every built-in recipe on the given crafting manager.
     */
    public static void registerAll(CraftingManager craftingManager) {
        // Recipe 1: Wood Planks
        List<List<ItemStack>> woodToPlanksPattern = List.of(
            List.of(new ItemStack(BlockType.WOOD.getId(), 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "wood_to_planks",
            woodToPlanksPattern,
            new ItemStack(BlockType.WOOD_PLANKS.getId(), 4)
        ));
        System.out.println("Registered recipe: WOOD -> WOOD_PLANKS");

        // Recipe 2: Pine Wood Planks
        List<List<ItemStack>> pineToPlanksPattern = List.of(
            List.of(new ItemStack(BlockType.PINE.getId(), 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "pine_to_planks",
            pineToPlanksPattern,
            new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 4)
        ));
        System.out.println("Registered recipe: PINE -> PINE_WOOD_PLANKS");

        // Recipe 3: Workbench
        List<List<ItemStack>> planksToWorkbenchPattern = List.of(
            List.of(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "planks_to_workbench",
            planksToWorkbenchPattern,
            new ItemStack(BlockType.WORKBENCH.getId(), 1)
        ));
        System.out.println("Registered recipe: WOOD_PLANKS -> WORKBENCH");

        // Recipe 4: Pine Wood Planks to Workbench
        List<List<ItemStack>> pinePlanksToWorkbenchPattern = List.of(
            List.of(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "pine_planks_to_workbench",
            pinePlanksToWorkbenchPattern,
            new ItemStack(BlockType.WORKBENCH.getId(), 1)
        ));
        System.out.println("Registered recipe: PINE_WOOD_PLANKS -> WORKBENCH");

        // Recipe 5: Sticks
        List<List<ItemStack>> planksToSticksPattern = List.of(
            List.of(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "planks_to_sticks",
            planksToSticksPattern,
            new ItemStack(ItemType.STICK, 4)
        ));
        System.out.println("Registered recipe: WOOD_PLANKS -> STICKS");

        // Recipe 6: Pine Wood Planks to Sticks
        List<List<ItemStack>> pinePlanksToSticksPattern = List.of(
            List.of(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "pine_planks_to_sticks",
            pinePlanksToSticksPattern,
            new ItemStack(ItemType.STICK, 4)
        ));
        System.out.println("Registered recipe: PINE_WOOD_PLANKS -> STICKS");

        // Recipe 7: Wooden Pickaxe
        List<List<ItemStack>> woodenPickaxePattern = List.of(
            List.of(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null),
            Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null)
        );
        craftingManager.registerRecipe(new Recipe(
            "wooden_pickaxe",
            woodenPickaxePattern,
            new ItemStack(ItemType.WOODEN_PICKAXE, 1)
        ));
        System.out.println("Registered recipe: WOOD_PLANKS + STICKS -> WOODEN_PICKAXE");

        // Recipe 8: Wooden Pickaxe (Pine Wood Planks variant)
        List<List<ItemStack>> pineWoodenPickaxePattern = List.of(
            List.of(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null),
            Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null)
        );
        craftingManager.registerRecipe(new Recipe(
            "pine_wooden_pickaxe",
            pineWoodenPickaxePattern,
            new ItemStack(ItemType.WOODEN_PICKAXE, 1)
        ));
        System.out.println("Registered recipe: PINE_WOOD_PLANKS + STICKS -> WOODEN_PICKAXE");

        // Recipe 9: Elm Wood Planks
        List<List<ItemStack>> elmToPlanksPattern = List.of(
            List.of(new ItemStack(BlockType.ELM_WOOD_LOG.getId(), 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "elm_to_planks",
            elmToPlanksPattern,
            new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 4)
        ));
        System.out.println("Registered recipe: ELM_WOOD_LOG -> ELM_WOOD_PLANKS");

        // Recipe 10: Elm Wood Planks to Workbench
        List<List<ItemStack>> elmPlanksToWorkbenchPattern = List.of(
            List.of(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "elm_planks_to_workbench",
            elmPlanksToWorkbenchPattern,
            new ItemStack(BlockType.WORKBENCH.getId(), 1)
        ));
        System.out.println("Registered recipe: ELM_WOOD_PLANKS -> WORKBENCH");

        // Recipe 11: Elm Wood Planks to Sticks
        List<List<ItemStack>> elmPlanksToSticksPattern = List.of(
            List.of(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            List.of(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "elm_planks_to_sticks",
            elmPlanksToSticksPattern,
            new ItemStack(ItemType.STICK, 4)
        ));
        System.out.println("Registered recipe: ELM_WOOD_PLANKS -> STICKS");

        // Recipe 12: Wooden Pickaxe (Elm Wood Planks variant)
        List<List<ItemStack>> elmWoodenPickaxePattern = List.of(
            List.of(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null),
            Arrays.asList(null, new ItemStack(ItemType.STICK, 1), null)
        );
        craftingManager.registerRecipe(new Recipe(
            "elm_wooden_pickaxe",
            elmWoodenPickaxePattern,
            new ItemStack(ItemType.WOODEN_PICKAXE, 1)
        ));
        System.out.println("Registered recipe: ELM_WOOD_PLANKS + STICKS -> WOODEN_PICKAXE");

        // Recipe 13: Wooden Axe (Regular Wood Planks)
        List<List<ItemStack>> woodenAxePattern = Arrays.asList(
            Arrays.asList(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            Arrays.asList(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1)),
            Arrays.asList(null, new ItemStack(ItemType.STICK, 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "wooden_axe",
            woodenAxePattern,
            new ItemStack(ItemType.WOODEN_AXE, 1)
        ));
        System.out.println("Registered recipe: WOOD_PLANKS + STICKS -> WOODEN_AXE");

        // Recipe 14: Wooden Axe (Pine Wood Planks variant)
        List<List<ItemStack>> pineWoodenAxePattern = Arrays.asList(
            Arrays.asList(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            Arrays.asList(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1)),
            Arrays.asList(null, new ItemStack(ItemType.STICK, 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "pine_wooden_axe",
            pineWoodenAxePattern,
            new ItemStack(ItemType.WOODEN_AXE, 1)
        ));
        System.out.println("Registered recipe: PINE_WOOD_PLANKS + STICKS -> WOODEN_AXE");

        // Recipe 15: Wooden Axe (Elm Wood Planks variant)
        List<List<ItemStack>> elmWoodenAxePattern = Arrays.asList(
            Arrays.asList(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            Arrays.asList(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1)),
            Arrays.asList(null, new ItemStack(ItemType.STICK, 1))
        );
        craftingManager.registerRecipe(new Recipe(
            "elm_wooden_axe",
            elmWoodenAxePattern,
            new ItemStack(ItemType.WOODEN_AXE, 1)
        ));
        System.out.println("Registered recipe: ELM_WOOD_PLANKS + STICKS -> WOODEN_AXE");

        // Recipe 16: Wooden Bucket (Regular Wood Planks)
        List<List<ItemStack>> woodenBucketPattern = Arrays.asList(
            Arrays.asList(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1), new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            Arrays.asList(new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), null, new ItemStack(BlockType.WOOD_PLANKS.getId(), 1)),
            Arrays.asList(null, new ItemStack(BlockType.WOOD_PLANKS.getId(), 1), null)
        );
        craftingManager.registerRecipe(new Recipe(
            "wooden_bucket",
            woodenBucketPattern,
            new ItemStack(ItemType.WOODEN_BUCKET, 1)
        ));
        System.out.println("Registered recipe: WOOD_PLANKS + STICK -> WOODEN_BUCKET");

        // Recipe 17: Wooden Bucket (Pine Wood Planks variant)
        List<List<ItemStack>> pineWoodenBucketPattern = Arrays.asList(
            Arrays.asList(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1), new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            Arrays.asList(new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), null, new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1)),
            Arrays.asList(null, new ItemStack(BlockType.PINE_WOOD_PLANKS.getId(), 1), null)
        );
        craftingManager.registerRecipe(new Recipe(
            "pine_wooden_bucket",
            pineWoodenBucketPattern,
            new ItemStack(ItemType.WOODEN_BUCKET, 1)
        ));
        System.out.println("Registered recipe: PINE_WOOD_PLANKS + STICK -> WOODEN_BUCKET");

        // Recipe 18: Wooden Bucket (Elm Wood Planks variant)
        List<List<ItemStack>> elmWoodenBucketPattern = Arrays.asList(
            Arrays.asList(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), new ItemStack(ItemType.STICK, 1), new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            Arrays.asList(new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), null, new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1)),
            Arrays.asList(null, new ItemStack(BlockType.ELM_WOOD_PLANKS.getId(), 1), null)
        );
        craftingManager.registerRecipe(new Recipe(
            "elm_wooden_bucket",
            elmWoodenBucketPattern,
            new ItemStack(ItemType.WOODEN_BUCKET, 1)
        ));
        System.out.println("Registered recipe: ELM_WOOD_PLANKS + STICK -> WOODEN_BUCKET");

        System.out.println("All crafting recipes initialized");
    }
}
