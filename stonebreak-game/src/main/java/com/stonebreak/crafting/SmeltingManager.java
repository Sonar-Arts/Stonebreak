package com.stonebreak.crafting;

import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for smelting recipes plus fuel-burn-time lookup.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register {@link SmeltingRecipe} instances</li>
 *   <li>Look up the smelting result for a given ingredient</li>
 *   <li>Provide burn-time (in ticks) for each fuel item</li>
 * </ul>
 *
 * <p>A single constant {@link #TICKS_PER_SMELT} defines how many game-ticks
 * one unit of ingredient takes to fully smelt (when fuel is present).
 */
public class SmeltingManager {

    private static final Logger logger = LoggerFactory.getLogger(SmeltingManager.class);

    /** Game-ticks required to smelt one unit of ingredient (200 ticks = 10 seconds). */
    public static final int TICKS_PER_SMELT = 200;

    private final List<SmeltingRecipe> recipes = new ArrayList<>();

    /** Item → burn-time in ticks. */
    private final Map<Item, Integer> fuelTimes = new HashMap<>();

    /**
     * Registers a smelting recipe.
     */
    public void registerRecipe(SmeltingRecipe recipe) {
        if (recipe != null && !recipes.contains(recipe)) {
            recipes.add(recipe);
            logger.debug("Registered smelting recipe: {}", recipe);
        }
    }

    /**
     * Returns the smelting recipe that matches the given ingredient, or null.
     */
    public SmeltingRecipe getRecipe(ItemStack input) {
        if (input == null || input.isEmpty()) return null;
        for (SmeltingRecipe r : recipes) {
            if (r.matches(input)) return r;
        }
        return null;
    }

    /**
     * Registers a fuel item and its burn-time (in game-ticks).
     */
    public void registerFuel(Item item, int burnTicks) {
        if (burnTicks < 1) {
            logger.warn("Ignoring zero/negative burn time for {}", item.getName());
            return;
        }
        fuelTimes.put(item, burnTicks);
    }

    /**
     * Returns burn-time in ticks for the given item, or 0 if not a fuel.
     */
    public int getBurnTime(ItemStack fuel) {
        if (fuel == null || fuel.isEmpty()) return 0;
        Integer ticks = fuelTimes.get(fuel.getItem());
        return ticks == null ? 0 : ticks * fuel.getCount();
    }

    /**
     * Returns the burn-time for a single unit of the given item.
     */
    public int getBurnTimePerUnit(Item item) {
        return fuelTimes.getOrDefault(item, 0);
    }

    public List<SmeltingRecipe> getAllRecipes() {
        return new ArrayList<>(recipes);
    }

    public int getRecipeCount() {
        return recipes.size();
    }
}
