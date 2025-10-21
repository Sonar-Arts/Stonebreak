package com.openmason.ui.components.modelBrowser.categorizers;

import com.stonebreak.items.ItemType;

import java.util.*;

/**
 * Categorizes items into logical groups for the Model Browser.
 *
 * <p>This class follows the Single Responsibility Principle by focusing solely on
 * item categorization logic. It provides a pure function that maps ItemTypes to
 * categories, making it easily testable and reusable.</p>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li>Single Responsibility: Only handles item categorization</li>
 *   <li>Open/Closed: New categories can be added without modifying existing code</li>
 * </ul>
 *
 * <p>Following KISS: Simple string matching logic, easy to understand and maintain.</p>
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
     *
     * <p>This is a pure function - same input always produces same output,
     * no side effects, making it easy to test and reason about.</p>
     *
     * @param itemType The item type to categorize
     * @return The category this item belongs to
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
     *
     * <p>This method organizes items into a map where keys are categories and
     * values are lists of items in that category. Following DRY principle by
     * reusing the categorize() method.</p>
     *
     * @param items The list of items to categorize
     * @return A map of categories to lists of items in each category
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

    /**
     * Gets all items in a specific category from a list of items.
     *
     * @param items The list of items to filter
     * @param category The category to filter by
     * @return A list of items in the specified category
     */
    public static List<ItemType> getItemsInCategory(List<ItemType> items, Category category) {
        List<ItemType> filtered = new ArrayList<>();
        for (ItemType item : items) {
            if (categorize(item) == category) {
                filtered.add(item);
            }
        }
        return filtered;
    }
}
