package com.stonebreak.items;

import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.crafting.SmeltingRecipe;
import com.stonebreak.crafting.SmeltingRecipeLoader;
import com.stonebreak.items.registry.ItemRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Meat: what the four animals drop, what it is worth to a player and to a goblin pack, and that
 * a furnace turns each raw cut into its cooked one.
 */
class FoodRulesTest {

    private static final ItemType[][] RAW_TO_COOKED = {
            {ItemType.RAW_MUTTON, ItemType.COOKED_MUTTON},
            {ItemType.RAW_BEEF, ItemType.COOKED_BEEF},
            {ItemType.RAW_CHICKEN, ItemType.COOKED_CHICKEN},
            {ItemType.RAW_GOOSE, ItemType.COOKED_GOOSE},
    };

    @Test
    void cookedMeatHealsMoreThanTheRawCut() {
        for (ItemType[] pair : RAW_TO_COOKED) {
            assertTrue(pair[0].getHealAmount() > 0, pair[0] + " is edible raw");
            assertTrue(pair[1].getHealAmount() > pair[0].getHealAmount(), pair[1] + " beats " + pair[0]);
            assertEquals(ItemCategory.FOOD, pair[0].getCategory());
            assertEquals(ItemCategory.FOOD, pair[1].getCategory());
        }
    }

    @Test
    void aSheepFeedsTwoGoblinsACowThreeAndALegOne() {
        assertEquals(2, FoodRules.goblinFood(ItemType.RAW_MUTTON));
        assertEquals(3, FoodRules.goblinFood(ItemType.RAW_BEEF));
        assertEquals(1, FoodRules.goblinFood(ItemType.RAW_CHICKEN));
        assertEquals(1, FoodRules.goblinFood(ItemType.RAW_GOOSE));
        for (ItemType[] pair : RAW_TO_COOKED) {
            assertEquals(FoodRules.goblinFood(pair[0]), FoodRules.goblinFood(pair[1]), "cooking does not change it");
        }
    }

    @Test
    void fruitStillHealsButIsNotMeat() {
        assertEquals(4.0f, ItemType.BANANA.getHealAmount());
        assertEquals(4.0f, ItemType.BLUEBERRY.getHealAmount());
        assertTrue(!FoodRules.isMeat(ItemType.BANANA));
        assertEquals(0.0f, ItemType.STICK.getHealAmount());
    }

    @Test
    void aFurnaceCooksEveryRawCut() {
        SmeltingManager smelting = new SmeltingManager();
        SmeltingRecipeLoader.loadFromSBOs(smelting);
        for (ItemType[] pair : RAW_TO_COOKED) {
            SmeltingRecipe recipe = smelting.getRecipe(new ItemStack(pair[0], 1));
            assertNotNull(recipe, "no smelting recipe for " + pair[0]);
            assertSame(pair[1], recipe.getOutput().getItem());
        }
    }

    /**
     * Item IDs share a numeric space with blocks and save files store them: every item SBO must
     * claim its own ID, at 1000 or above ({@code new ItemStack(int)} reads anything lower as a block).
     */
    @Test
    void noTwoItemSbosShareANumericId() {
        Map<Integer, List<String>> byId = new TreeMap<>();
        for (ItemRegistry.ItemEntry entry : ItemRegistry.getInstance().all()) {
            int id = entry.properties().numericId();
            assertTrue(id >= 1000, entry.objectId() + " has block-range id " + id);
            byId.computeIfAbsent(id, k -> new ArrayList<>()).add(entry.objectId());
        }
        byId.values().removeIf(list -> list.size() < 2);
        assertTrue(byId.isEmpty(), "item numeric ID collisions: " + byId);
    }
}
