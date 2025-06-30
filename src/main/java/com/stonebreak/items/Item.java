package com.stonebreak.items;

/**
 * Base interface for all items in the game (blocks and non-placeable items).
 * This interface unifies blocks and items under a common contract for inventory,
 * crafting, and UI systems.
 */
public interface Item {
    /**
     * Gets the unique ID of this item.
     * @return The item ID
     */
    int getId();
    
    /**
     * Gets the display name of this item.
     * @return The item name
     */
    String getName();
    
    /**
     * Gets the X coordinate in the texture atlas for UI rendering.
     * @return The atlas X coordinate
     */
    int getAtlasX();
    
    /**
     * Gets the Y coordinate in the texture atlas for UI rendering.
     * @return The atlas Y coordinate
     */
    int getAtlasY();
    
    /**
     * Gets the maximum stack size for this item type.
     * @return The maximum number of items that can be stacked together
     */
    int getMaxStackSize();
    
    /**
     * Gets the category this item belongs to.
     * @return The item category for organization and filtering
     */
    ItemCategory getCategory();
    
    /**
     * Checks if two items are the same type and can potentially stack.
     * @param other The other item to compare with
     * @return True if items are the same type
     */
    default boolean isSameType(Item other) {
        return other != null && this.getId() == other.getId();
    }
}