package com.stonebreak.ui.recipeScreen.core;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemCategory;
import com.stonebreak.items.ItemType;

public final class ItemCategoryMapper {

    private ItemCategoryMapper() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String getCategoryForItem(Item item) {
        if (item == null) {
            return "All";
        }

        if (item instanceof ItemType || item instanceof BlockType) {
            ItemCategory category = item.getCategory();
            return switch (category) {
                case TOOLS -> "Tools";
                case FOOD -> "Food";
                case DECORATIVE -> "Decorative";
                case MATERIALS, BLOCKS -> "Building";
                default -> "Building";
            };
        }

        return getCategoryFromName(item.getName());
    }

    private static String getCategoryFromName(String itemName) {
        String name = itemName.toLowerCase();

        if (name.contains("tool") || name.contains("pick") ||
            name.contains("axe") || name.contains("shovel")) {
            return "Tools";
        } else if (name.contains("food") || name.contains("bread") ||
                   name.contains("apple")) {
            return "Food";
        } else if (name.contains("flower") || name.contains("decoration")) {
            return "Decorative";
        } else {
            return "Building";
        }
    }
}