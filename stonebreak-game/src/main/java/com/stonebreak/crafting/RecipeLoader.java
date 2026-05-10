package com.stonebreak.crafting;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.registry.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads crafting recipes from SBO files at startup.
 *
 * <p>Recipes are declared on the SBO whose item is the recipe's <em>output</em>.
 * Every block and item registered through {@link BlockRegistry} / {@link ItemRegistry}
 * is inspected; any {@link SBOFormat.RecipeData} block found is converted into
 * one or more {@link Recipe} entries and registered with the supplied
 * {@link CraftingManager}.
 *
 * <p>Replaces the old hardcoded {@code RecipeRegistry}. Keeping this class small
 * and single-purpose mirrors the rest of the SBO-loading pipeline.
 */
public final class RecipeLoader {

    private static final Logger logger = LoggerFactory.getLogger(RecipeLoader.class);

    private RecipeLoader() {}

    /**
     * Iterate the block and item registries, harvest recipes, and register them.
     *
     * <p>Both registries are expected to be populated before this is called
     * (they are: {@code BlockType} and {@code ItemType} static initializers
     * trigger their own {@code scanAndLoad}).
     *
     * @return number of recipes registered
     */
    public static int loadFromSBOs(CraftingManager craftingManager) {
        if (craftingManager == null) {
            throw new IllegalArgumentException("craftingManager cannot be null");
        }

        int registered = 0;

        for (BlockRegistry.BlockEntry entry : BlockRegistry.getInstance().all()) {
            registered += harvest(entry.objectId(), entry.sboData(), craftingManager);
        }
        for (ItemRegistry.ItemEntry entry : ItemRegistry.getInstance().all()) {
            registered += harvest(entry.objectId(), entry.sboData(), craftingManager);
        }

        logger.info("RecipeLoader: registered {} recipe(s) from SBO files", registered);
        return registered;
    }

    private static int harvest(String outputObjectId,
                                SBOParseResult sbo,
                                CraftingManager craftingManager) {
        if (sbo == null || !sbo.manifest().hasRecipes()) {
            return 0;
        }

        Item outputItem = resolveItem(outputObjectId);
        if (outputItem == null) {
            logger.warn("Cannot register recipes for unknown output objectId: {}", outputObjectId);
            return 0;
        }

        int count = 0;
        List<SBOFormat.ShapedRecipe> shapedList = sbo.manifest().recipes().shaped();
        for (int i = 0; i < shapedList.size(); i++) {
            SBOFormat.ShapedRecipe shaped = shapedList.get(i);
            Recipe recipe = buildRecipe(outputObjectId, outputItem, shaped, i);
            if (recipe == null) {
                continue;
            }
            craftingManager.registerRecipe(recipe);
            count++;
        }
        return count;
    }

    private static Recipe buildRecipe(String outputObjectId,
                                       Item outputItem,
                                       SBOFormat.ShapedRecipe shaped,
                                       int recipeIndex) {
        int width = shaped.width();
        int height = shaped.height();
        List<String> flat = shaped.pattern();

        List<List<ItemStack>> rows = new ArrayList<>(height);
        for (int r = 0; r < height; r++) {
            List<ItemStack> row = new ArrayList<>(width);
            for (int c = 0; c < width; c++) {
                String slotId = flat.get(r * width + c);
                if (slotId == null || slotId.isBlank()) {
                    row.add(null);
                    continue;
                }
                Item ingredient = resolveItem(slotId);
                if (ingredient == null) {
                    logger.warn("Recipe for {} #{} references unknown ingredient '{}' — skipping recipe",
                            outputObjectId, recipeIndex, slotId);
                    return null;
                }
                row.add(new ItemStack(ingredient, 1));
            }
            rows.add(row);
        }

        String recipeId = outputObjectId.replace(':', '_') + "_recipe_" + recipeIndex;
        ItemStack output = new ItemStack(outputItem, shaped.outputCount());
        try {
            return new Recipe(recipeId, rows, output);
        } catch (RuntimeException ex) {
            logger.warn("Failed to build recipe {}: {}", recipeId, ex.getMessage());
            return null;
        }
    }

    /**
     * Resolve an SBO {@code objectId} to a concrete {@link Item}. Blocks are
     * resolved through {@link BlockRegistry} -> {@link BlockType#getById(int)};
     * items go through {@link ItemRegistry} -> {@link ItemType#getById(int)}.
     *
     * @return the resolved item, or {@code null} if unknown
     */
    /**
     * Resolve an SBO {@code objectId} to a concrete {@link Item} purely by
     * name. Both {@link BlockType} and {@link ItemType} maintain a
     * {@code BY_OBJECT_ID} index populated whenever a type is registered from
     * an SBO source (or, for PNG-backed sentinels, mapped explicitly).
     */
    private static Item resolveItem(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return null;
        }
        BlockType bt = BlockType.getByObjectId(objectId);
        if (bt != null) return bt;
        return ItemType.getByObjectId(objectId);
    }
}
