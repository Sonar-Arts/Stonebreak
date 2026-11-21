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
    private final Ingredient ingredient;
    private final ItemStack input;
    private final ItemStack output;

    /**
     * @param id     unique recipe id (e.g. "stonebreak:cobblestone_to_stone")
     * @param input  the ingredient (count should be 1)
     * @param output the smelted result
     */
    public SmeltingRecipe(String id, ItemStack input, ItemStack output) {
        this(id, new Ingredient.Single(Objects.requireNonNull(input, "Input cannot be null").getItem()),
                input, output);
    }

    /** A recipe whose input may be any member of a dictionary (or one specific item). */
    public static SmeltingRecipe ofIngredient(String id, Ingredient ingredient, ItemStack output) {
        Objects.requireNonNull(ingredient, "Input cannot be null");
        return new SmeltingRecipe(id, ingredient, new ItemStack(ingredient.representative(), 1), output);
    }

    private SmeltingRecipe(String id, Ingredient ingredient, ItemStack input, ItemStack output) {
        Objects.requireNonNull(id, "Recipe ID cannot be null");
        Objects.requireNonNull(output, "Output cannot be null");
        this.id = id;
        this.ingredient = ingredient;
        this.input = input;
        this.output = output;
    }

    public String getId() {
        return id;
    }

    /** What this recipe accepts as input (an item, or any member of a dictionary). */
    public Ingredient getIngredient() {
        return ingredient;
    }

    /** A representative input stack (a dictionary input shows its first member). */
    public ItemStack getInput() {
        return input;
    }

    public ItemStack getOutput() {
        return output;
    }

    /**
     * Checks whether the supplied ItemStack matches this recipe's input.
     * Uses singleton identity on the Item (reference equality), which is the
     * canonical "same kind" test in this codebase.
     */
    public boolean matches(ItemStack candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        return ingredient.test(candidate.getItem());
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
