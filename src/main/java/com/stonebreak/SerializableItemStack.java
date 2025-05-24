package com.stonebreak;

import java.io.Serializable;

public class SerializableItemStack implements Serializable {
    private static final long serialVersionUID = 1L;

    private final boolean isBlock;
    private final int blockId; // Only used if isBlock is true
    private final String itemTypeName; // Only used if isBlock is false, stores ItemType.name()
    private final int count;

    public SerializableItemStack(ItemStack itemStack) {
        this.isBlock = itemStack.isBlock();
        this.count = itemStack.getCount();
        if (this.isBlock) {
            this.blockId = itemStack.getBlockTypeId();
            this.itemTypeName = null;
        } else {
            this.blockId = -1;
            ItemType type = itemStack.getItemType();
            this.itemTypeName = (type != null) ? type.name() : null;
        }
    }

    public boolean isBlock() {
        return isBlock;
    }

    public int getBlockId() {
        return blockId;
    }

    public String getItemTypeName() {
        return itemTypeName;
    }

    public int getCount() {
        return count;
    }

    // Helper method to reconstruct an ItemStack (will be used during loading)
    public ItemStack toItemStack() {
        if (isBlock) {
            BlockType type = BlockType.getById(blockId);
            if (type == BlockType.AIR && count == 0) { // Explicitly handle serialized empty AIR stacks
                 return ItemStack.empty();
            }
            return new ItemStack(type, count);
        } else {
            if (itemTypeName != null) {
                try {
                    ItemType type = ItemType.valueOf(itemTypeName);
                    return new ItemStack(type, count);
                } catch (IllegalArgumentException e) {
                    // Handle case where item type name is invalid (e.g., old save compatibility)
                    System.err.println("Could not find ItemType for name: " + itemTypeName);
                    return ItemStack.empty(); // Fallback to empty stack
                }
            } else {
                 // Non-block but null itemTypeName usually means an empty/invalid non-block item
                return ItemStack.empty(); // Fallback to empty stack
            }
        }
    }
}