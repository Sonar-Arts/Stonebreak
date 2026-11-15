package com.stonebreak.items;

import java.util.Map;

/**
 * What every edible item is worth, in one table: the health a player gets back from eating it, and
 * how many goblins one of it feeds (camp food accounting). Items not listed are not food.
 *
 * <p>Heal is in health points ({@code PlayerConstants.HEALTH_PER_HEART} = 2 per heart). Cooked meat
 * always heals more than the raw cut it came from. Goblin food is per item: a sheep's mutton feeds
 * two goblins, a cow's beef three, a chicken or goose leg one — and cooking does not change it.
 */
public final class FoodRules {

    /** @param heal health restored when a player eats it; @param goblinFood goblins one item feeds */
    public record FoodValue(float heal, int goblinFood) {
        static final FoodValue NONE = new FoodValue(0.0f, 0);
    }

    /** Built on first use: the meat constants are only valid once ItemType has initialised. */
    private static volatile Map<ItemType, FoodValue> table;

    private FoodRules() {
    }

    private static Map<ItemType, FoodValue> table() {
        Map<ItemType, FoodValue> t = table;
        if (t == null) {
            t = Map.ofEntries(
                    Map.entry(ItemType.BANANA, new FoodValue(4.0f, 0)),
                    Map.entry(ItemType.BLUEBERRY, new FoodValue(4.0f, 0)),
                    Map.entry(ItemType.RAW_MUTTON, new FoodValue(2.0f, 2)),
                    Map.entry(ItemType.COOKED_MUTTON, new FoodValue(6.0f, 2)),
                    Map.entry(ItemType.RAW_BEEF, new FoodValue(3.0f, 3)),
                    Map.entry(ItemType.COOKED_BEEF, new FoodValue(8.0f, 3)),
                    Map.entry(ItemType.RAW_CHICKEN, new FoodValue(2.0f, 1)),
                    Map.entry(ItemType.COOKED_CHICKEN, new FoodValue(5.0f, 1)),
                    Map.entry(ItemType.RAW_GOOSE, new FoodValue(2.0f, 1)),
                    Map.entry(ItemType.COOKED_GOOSE, new FoodValue(5.0f, 1)));
            table = t;
        }
        return t;
    }

    public static FoodValue valueOf(Item item) {
        return item instanceof ItemType type ? table().getOrDefault(type, FoodValue.NONE) : FoodValue.NONE;
    }

    /** Health a player gets back from eating {@code item}; zero when it is not edible. */
    public static float heal(Item item) {
        return valueOf(item).heal();
    }

    /** How many goblins one {@code item} feeds; zero for anything that is not meat. */
    public static int goblinFood(Item item) {
        return valueOf(item).goblinFood();
    }

    /** Meat — what goblins hunt for, carry home and eat. */
    public static boolean isMeat(Item item) {
        return goblinFood(item) > 0;
    }
}
