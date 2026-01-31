package com.openmason.main.systems.menus.panes.modelBrowser.categorizers;

import com.stonebreak.items.ItemType;

import java.util.*;

/**
 * Categorizes items into logical groups for the Model Browser.
 */
public class ItemCategorizer {

    /**
     * Item categories for organization in the Model Browser.
     */
    public enum Category {
        TOOLS("Tools"),
        MATERIALS("Materials"),
        OTHER("Other Items");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Categorizes an item based on its name.
     */
    public static Category categorize(ItemType itemType) {
        String name = itemType.name().toUpperCase();

        // Check for tool items
        if (name.contains("PICKAXE") || name.contains("AXE") || name.contains("BUCKET")) {
            return Category.TOOLS;
        }

        // Check for material items
        if (name.contains("STICK")) {
            return Category.MATERIALS;
        }

        // Default to OTHER
        return Category.OTHER;
    }

    /**
     * Groups a list of items by their categories.
     */
    public static Map<Category, List<ItemType>> categorizeAll(List<ItemType> items) {
        Map<Category, List<ItemType>> categorized = new EnumMap<>(Category.class);

        // Initialize all categories with empty lists
        for (Category category : Category.values()) {
            categorized.put(category, new ArrayList<>());
        }

        // Categorize each item
        for (ItemType item : items) {
            Category category = categorize(item);
            categorized.get(category).add(item);
        }

        return categorized;
    }
}
