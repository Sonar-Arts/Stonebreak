package com.stonebreak.crafting;

import com.stonebreak.items.ItemStack;

import java.util.Objects;

/**
 * Represents one single-slot smelting recipe (input → output).
 *
 * <p>Smelting differs from crafting: it takes a single ingredient, requires fuel
 * (burn time), and runs over time in the furnace. This record only describes the
 * ingredient-to-output mapping; fuel consumption and progress are managed by
 * {@link SmeltingManager}.
 */
public class SmeltingRecipe {

    private final String id;
    private final ItemStack input;
    private final ItemStack output;
    private final float xpReward;

    /**
     * @param id       unique recipe id (e.g. "stonebreak:cobblestone_to_stone")
     * @param input    the ingredient (count should be 1)
     * @param output   the smelted result
     * @param xpReward XP granted when the player collects the output (0 if none)
     */
    public SmeltingRecipe(String id, ItemStack input, ItemStack output, float xpReward) {
        Objects.requireNonNull(id, "Recipe ID cannot be null");
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(output, "Output cannot be null");
        if (xpReward < 0f) {
            throw new IllegalArgumentException("XP reward cannot be negative");
        }
        this.id = id;
        this.input = input;
        this.output = output;
        this.xpReward = xpReward;
    }

    public String getId() {
        return id;
    }

    public ItemStack getInput() {
        return input;
    }

    public ItemStack getOutput() {
        return output;
    }

    public float getXpReward() {
        return xpReward;
    }

    /**
     * Checks whether the supplied ItemStack matches this recipe's input.
     * Uses singleton identity on the Item (reference equality), which is the
     * canonical "same kind" test in this codebase.
     */
    public boolean matches(ItemStack candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        return candidate.getItem() == input.getItem();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SmeltingRecipe that = (SmeltingRecipe) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "SmeltingRecipe{id='" + id + "', input=" + input.getItem().getName()
               + ", output=" + output.getItem().getName() + '}';
    }
}
