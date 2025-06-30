package com.stonebreak.items;

/**
 * Defines non-placeable items in the game (tools, materials, consumables, etc.).
 * These items exist only in inventory and cannot be placed in the world.
 */
public enum ItemType implements Item {
    // Materials
    STICK(1001, "Stick", 1, 3, ItemCategory.MATERIALS, 64),
    
    // Tools
    WOODEN_PICKAXE(1002, "Wooden Pickaxe", 3, 3, ItemCategory.TOOLS, 1),
    WOODEN_AXE(1003, "Wooden Axe", 8, 3, ItemCategory.TOOLS, 1);
    
    private final int id;
    private final String name;
    private final int atlasX;
    private final int atlasY;
    private final ItemCategory category;
    private final int maxStackSize;
    
    ItemType(int id, String name, int atlasX, int atlasY, ItemCategory category, int maxStackSize) {
        this.id = id;
        this.name = name;
        this.atlasX = atlasX;
        this.atlasY = atlasY;
        this.category = category;
        this.maxStackSize = maxStackSize;
    }
    
    @Override
    public int getId() {
        return id;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public int getAtlasX() {
        return atlasX;
    }
    
    @Override
    public int getAtlasY() {
        return atlasY;
    }
    
    @Override
    public int getMaxStackSize() {
        return maxStackSize;
    }
    
    @Override
    public ItemCategory getCategory() {
        return category;
    }
    
    /**
     * Checks if this item is a tool.
     * @return True if this item is in the TOOLS category
     */
    public boolean isTool() {
        return category == ItemCategory.TOOLS;
    }
    
    /**
     * Checks if this item is a material.
     * @return True if this item is in the MATERIALS category
     */
    public boolean isMaterial() {
        return category == ItemCategory.MATERIALS;
    }
    
    /**
     * Gets item type by ID.
     * @param id The item ID to look up
     * @return The ItemType with the given ID, or null if not found
     */
    public static ItemType getById(int id) {
        for (ItemType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }
    
    /**
     * Gets item type by name (case-insensitive).
     * @param name The item name to look up
     * @return The ItemType with the given name, or null if not found
     */
    public static ItemType getByName(String name) {
        if (name == null) return null;
        for (ItemType type : values()) {
            if (type.name.equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
    
}