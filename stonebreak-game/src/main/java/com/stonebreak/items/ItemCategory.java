package com.stonebreak.items;

/**
 * Categories for organizing items in the game.
 * Used for UI filtering, inventory organization, and logical grouping.
 */
public enum ItemCategory {
    /**
     * Blocks that can be placed in the world.
     * Examples: Stone, Wood, Dirt, Grass
     */
    BLOCKS("Blocks"),
    
    /**
     * Tools and equipment used by the player.
     * Examples: Pickaxes, Swords, Axes
     */
    TOOLS("Tools"),
    
    /**
     * Raw materials and crafting components.
     * Examples: Sticks, Ingots, Coal
     */
    MATERIALS("Materials"),
    
    /**
     * Consumable items that provide benefits.
     * Examples: Food, Potions, Medicine
     */
    FOOD("Food"),
    
    /**
     * Items used for decoration and aesthetics.
     * Examples: Flowers, Paintings, Furniture
     */
    DECORATIVE("Decorative");
    
    private final String displayName;
    
    ItemCategory(String displayName) {
        this.displayName = displayName;
    }
    
    /**
     * Gets the human-readable display name for this category.
     * @return The display name for UI purposes
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Gets all categories as an array for iteration.
     * @return Array of all item categories
     */
    public static ItemCategory[] getAllCategories() {
        return values();
    }
}