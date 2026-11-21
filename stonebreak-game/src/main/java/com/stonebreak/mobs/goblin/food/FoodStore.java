package com.stonebreak.mobs.goblin.food;

import com.stonebreak.items.ItemStack;

import java.util.List;

/**
 * Somewhere goblins keep meat: a goblin's pockets, a pack's pockets together, later a camp's chest.
 * Food is counted in goblin-meals ({@link com.stonebreak.items.FoodRules#goblinFood}).
 */
public interface FoodStore {

    /** Goblin-meals held. */
    int food();

    /** Takes one meat item out to eat; its food value, or zero when there is no meat. */
    int eatOne();

    /** Puts {@code stack} in as far as there is room; what did not fit, or {@code null} when all of it did. */
    ItemStack add(ItemStack stack);

    /** Several stores seen as one: food sums, eating and adding go to the first that can. */
    static FoodStore union(List<? extends FoodStore> stores) {
        return new FoodStore() {
            @Override
            public int food() {
                int total = 0;
                for (FoodStore s : stores) {
                    total += s.food();
                }
                return total;
            }

            @Override
            public int eatOne() {
                for (FoodStore s : stores) {
                    int value = s.eatOne();
                    if (value > 0) {
                        return value;
                    }
                }
                return 0;
            }

            @Override
            public ItemStack add(ItemStack stack) {
                ItemStack left = stack;
                for (FoodStore s : stores) {
                    if (left == null) {
                        break;
                    }
                    left = s.add(left);
                }
                return left;
            }
        };
    }
}
