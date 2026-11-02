package com.stonebreak.crafting;

import com.stonebreak.gameplay.SboGameplay;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.registry.ItemRegistry;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads smelting recipes and fuel definitions from SBO files at startup.
 *
 * <p>Smelting recipes are declared on the SBO whose item is the recipe's
 * <em>output</em> (mirroring the convention used by {@link RecipeLoader} for
 * crafting). Fuel data is declared on the SBO whose item burns as the fuel.
 * A smelting input may be a dictionary ({@code "#stonebreak:sand"}: any member).
 *
 * <p>An SBO may also carry a {@code fuelTable} (the furnace does) giving burn times to other
 * objects or whole dictionaries; it is applied after every per-object fuel, so an object's own
 * declaration always wins.
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
        ItemDictionary dictionary = ItemDictionary.fromRegistries();

        List<String> ids = new ArrayList<>();
        List<SBOParseResult> sbos = new ArrayList<>();
        for (BlockRegistry.BlockEntry entry : List.copyOf(BlockRegistry.getInstance().all())) {
            ids.add(entry.objectId());
            sbos.add(entry.sboData());
        }
        for (ItemRegistry.ItemEntry entry : List.copyOf(ItemRegistry.getInstance().all())) {
            ids.add(entry.objectId());
            sbos.add(entry.sboData());
        }
        for (int i = 0; i < ids.size(); i++) {
            recipes += harvestRecipes(ids.get(i), sbos.get(i), dictionary, smeltingManager);
            fuels += harvestFuel(ids.get(i), sbos.get(i), smeltingManager);
        }
        // Fuel tables last: an object's own fuel declaration always wins over a table entry.
        for (int i = 0; i < ids.size(); i++) {
            fuels += harvestFuelTable(ids.get(i), sbos.get(i), dictionary, smeltingManager);
        }

        logger.info("SmeltingRecipeLoader: registered {} recipe(s) and {} fuel(s) from SBO files",
                recipes, fuels);
        return new LoadStats(recipes, fuels);
    }

    private static int harvestRecipes(String outputObjectId,
                                       SBOParseResult sbo,
                                       ItemDictionary dictionary,
                                       SmeltingManager smeltingManager) {
        if (sbo == null || !SboGameplay.of(sbo.manifest()).hasSmeltingRecipes()) {
            return 0;
        }

        Item outputItem = ItemResolver.resolve(outputObjectId);
        if (outputItem == null) {
            logger.warn("Cannot register smelting recipes for unknown output objectId: {}", outputObjectId);
            return 0;
        }

        int count = 0;
        var entries = SboGameplay.of(sbo.manifest()).smeltingRecipes().recipes();
        for (int i = 0; i < entries.size(); i++) {
            SboGameplay.SmeltingRecipeEntry entry = entries.get(i);
            Ingredient input = Ingredient.parse(entry.inputObjectId(), dictionary);
            if (input == null) {
                logger.warn("Smelting recipe for {} #{} references unknown input '{}' — skipping",
                        outputObjectId, i, entry.inputObjectId());
                continue;
            }
            String recipeId = outputObjectId.replace(':', '_')
                    + "_smelt_" + entry.inputObjectId().replace(':', '_').replace("#", "tag_")
                    + "_" + i;
            try {
                smeltingManager.registerRecipe(SmeltingRecipe.ofIngredient(
                        recipeId,
                        input,
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
        if (sbo == null || !SboGameplay.of(sbo.manifest()).hasFuel()) {
            return 0;
        }
        Item item = ItemResolver.resolve(objectId);
        if (item == null) {
            logger.warn("Cannot register fuel for unknown objectId: {}", objectId);
            return 0;
        }
        smeltingManager.registerFuel(item, SboGameplay.of(sbo.manifest()).fuel().burnTicks());
        return 1;
    }

    private static int harvestFuelTable(String ownerObjectId,
                                        SBOParseResult sbo,
                                        ItemDictionary dictionary,
                                        SmeltingManager smeltingManager) {
        if (sbo == null || !SboGameplay.of(sbo.manifest()).hasFuelTable()) {
            return 0;
        }
        int count = 0;
        for (SboGameplay.FuelTableEntry entry : SboGameplay.of(sbo.manifest()).fuelTable().entries()) {
            Ingredient ingredient = Ingredient.parse(entry.ingredient(), dictionary);
            if (ingredient == null) {
                logger.warn("Fuel table on {} references unknown '{}' — skipping entry",
                        ownerObjectId, entry.ingredient());
                continue;
            }
            for (Item item : ingredient.displayItems()) {
                if (smeltingManager.registerFuelIfAbsent(item, entry.burnTicks())) {
                    count++;
                }
            }
        }
        return count;
    }
}
