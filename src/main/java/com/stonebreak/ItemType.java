package com.stonebreak;

public enum ItemType {
    AIR("Air", 0), // Represents an empty slot or no item
    STICK("Stick", 64),
    WOODEN_PICKAXE("Wooden Pickaxe", 1);

    private final String displayName;
    private final int maxStackSize;

    ItemType(String displayName, int maxStackSize) {
        this.displayName = displayName;
        this.maxStackSize = maxStackSize;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getMaxStackSize() {
        return maxStackSize;
    }
}