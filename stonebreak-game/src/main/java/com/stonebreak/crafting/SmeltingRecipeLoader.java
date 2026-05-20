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

/**
 * Loads smelting recipes and fuel definitions from SBO files at startup.
 *
 * <p>Smelting recipes are declared on the SBO whose item is the recipe's
 * <em>output</em> (mirroring the convention used by {@link RecipeLoader} for
 * crafting). Fuel data is declared on the SBO whose item burns as the fuel.
 *
 * <p>Replaces the old hardcoded smelting initialisation in {@code Game}.
 */
public final class SmeltingRecipeLoader {

    private static final Logger logger = LoggerFactory.getLogger(SmeltingRecipeLoader.class);

    private SmeltingRecipeLoader() {}

    /**
     * Result counts from a single load pass.
     */
    public record LoadStats(int recipes, int fuels) {}

    /**
     * Iterate the block and item registries, harvest smelting recipes and fuel
     * definitions, and register them with the supplied {@link SmeltingManager}.
     */
    public static LoadStats loadFromSBOs(SmeltingManager smeltingManager) {
        if (smeltingManager == null) {
            throw new IllegalArgumentException("smeltingManager cannot be null");
        }

        int recipes = 0;
        int fuels = 0;

        for (BlockRegistry.BlockEntry entry : BlockRegistry.getInstance().all()) {
            recipes += harvestRecipes(entry.objectId(), entry.sboData(), smeltingManager);
            fuels += harvestFuel(entry.objectId(), entry.sboData(), smeltingManager);
        }
        for (ItemRegistry.ItemEntry entry : ItemRegistry.getInstance().all()) {
            recipes += harvestRecipes(entry.objectId(), entry.sboData(), smeltingManager);
            fuels += harvestFuel(entry.objectId(), entry.sboData(), smeltingManager);
        }

        logger.info("SmeltingRecipeLoader: registered {} recipe(s) and {} fuel(s) from SBO files",
                recipes, fuels);
        return new LoadStats(recipes, fuels);
    }

    private static int harvestRecipes(String outputObjectId,
                                       SBOParseResult sbo,
                                       SmeltingManager smeltingManager) {
        if (sbo == null || !sbo.manifest().hasSmeltingRecipes()) {
            return 0;
        }

        Item outputItem = resolveItem(outputObjectId);
        if (outputItem == null) {
            logger.warn("Cannot register smelting recipes for unknown output objectId: {}", outputObjectId);
            return 0;
        }

        int count = 0;
        var entries = sbo.manifest().smeltingRecipes().recipes();
        for (int i = 0; i < entries.size(); i++) {
            SBOFormat.SmeltingRecipeEntry entry = entries.get(i);
            Item input = resolveItem(entry.inputObjectId());
            if (input == null) {
                logger.warn("Smelting recipe for {} #{} references unknown input '{}' — skipping",
                        outputObjectId, i, entry.inputObjectId());
                continue;
            }
            String recipeId = outputObjectId.replace(':', '_')
                    + "_smelt_" + entry.inputObjectId().replace(':', '_')
                    + "_" + i;
            try {
                smeltingManager.registerRecipe(new SmeltingRecipe(
                        recipeId,
                        new ItemStack(input, 1),
                        new ItemStack(outputItem, entry.outputCount())
                ));
                count++;
            } catch (RuntimeException ex) {
                logger.warn("Failed to register smelting recipe {}: {}", recipeId, ex.getMessage());
            }
        }
        return count;
    }

    private static int harvestFuel(String objectId,
                                    SBOParseResult sbo,
                                    SmeltingManager smeltingManager) {
        if (sbo == null || !sbo.manifest().hasFuel()) {
            return 0;
        }
        Item item = resolveItem(objectId);
        if (item == null) {
            logger.warn("Cannot register fuel for unknown objectId: {}", objectId);
            return 0;
        }
        smeltingManager.registerFuel(item, sbo.manifest().fuel().burnTicks());
        return 1;
    }

    private static Item resolveItem(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return null;
        }
        BlockType bt = BlockType.getByObjectId(objectId);
        if (bt != null) return bt;
        return ItemType.getByObjectId(objectId);
    }
}
