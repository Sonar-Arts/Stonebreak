package com.stonebreak;

public class ItemStack {
    private Item item;
    private int count;

    /**
     * Creates an ItemStack with the given item and count.
     * @param item The item (BlockType or ItemType)
     * @param count The quantity
     */
    public ItemStack(Item item, int count) {
        if (item == null || count < 0) {
            this.item = BlockType.AIR;
            this.count = 0;
        } else {
            this.item = item;
            this.count = count;
        }
    }

    /**
     * Creates an ItemStack from a block type ID (backwards compatibility).
     * @param blockTypeId The block type ID
     * @param count The quantity
     */
    public ItemStack(int blockTypeId, int count) {
        // First try to find it as a BlockType
        BlockType blockType = BlockType.getById(blockTypeId);
        if (blockType != null) {
            this.item = blockType;
        } else {
            // Try to find it as an ItemType
            ItemType itemType = ItemType.getById(blockTypeId);
            if (itemType != null) {
                this.item = itemType;
            } else {
                // Default to AIR if not found
                this.item = BlockType.AIR;
            }
        }
        
        if (this.item == BlockType.AIR && count > 0) {
            this.count = 0;
        } else {
            this.count = Math.max(0, count);
        }
    }

    /**
     * Gets the item in this stack.
     * @return The item (BlockType or ItemType)
     */
    public Item getItem() {
        return item;
    }

    /**
     * Gets the block type ID (backwards compatibility).
     * @return The item's ID
     */
    public int getBlockTypeId() {
        return item.getId();
    }

    /**
     * Sets the block type ID (backwards compatibility).
     * @param blockTypeId The new block type ID
     */
    public void setBlockTypeId(int blockTypeId) {
        // First try BlockType, then ItemType
        BlockType blockType = BlockType.getById(blockTypeId);
        if (blockType != null) {
            this.item = blockType;
        } else {
            ItemType itemType = ItemType.getById(blockTypeId);
            if (itemType != null) {
                this.item = itemType;
            } else {
                this.item = BlockType.AIR;
            }
        }
        
        if (this.item == BlockType.AIR) {
            this.count = 0;
        }
    }

    /**
     * Gets the count of items in this stack.
     * @return The item count
     */
    public int getCount() {
        return count;
    }

    /**
     * Sets the count of items in this stack.
     * @param count The new count
     */
    public void setCount(int count) {
        if (this.item == BlockType.AIR && count > 0) {
            this.count = 0;
        } else {
            this.count = Math.max(0, count);
        }
    }

    /**
     * Increments the count by the specified amount.
     * @param amount The amount to increment
     */
    public void incrementCount(int amount) {
        if (this.item != BlockType.AIR) {
            this.count += amount;
        }
    }

    /**
     * Decrements the count by the specified amount.
     * @param amount The amount to decrement
     */
    public void decrementCount(int amount) {
        if (this.item != BlockType.AIR) {
            this.count -= amount;
            if (this.count < 0) {
                this.count = 0;
            }
        }
    }

    /**
     * Checks if this ItemStack is empty.
     * @return True if empty or air
     */
    public boolean isEmpty() {
        return count <= 0 || item == BlockType.AIR;
    }

    /**
     * Gets the maximum stack size for this item.
     * @return The maximum stack size
     */
    public int getMaxStackSize() {
        return item.getMaxStackSize();
    }

    /**
     * Checks if this ItemStack can be stacked with another ItemStack.
     * @param other The other ItemStack to check against
     * @return True if they can stack, false otherwise
     */
    public boolean canStackWith(ItemStack other) {
        if (other == null || other.isEmpty() || this.isEmpty()) {
            return false;
        }
        return this.item.isSameType(other.item) && this.count < getMaxStackSize();
    }

    /**
     * Creates a copy of this ItemStack.
     * @return A new ItemStack instance or null if empty
     */
    public ItemStack copy() {
        if (isEmpty()) {
            return null;
        }
        return new ItemStack(this.item, this.count);
    }

    /**
     * Clears the item stack, making it empty.
     */
    public void clear() {
        this.item = BlockType.AIR;
        this.count = 0;
    }

    /**
     * Checks if this item can be placed as a block in the world.
     * @return True if it's a BlockType and placeable
     */
    public boolean isPlaceable() {
        return item instanceof BlockType && ((BlockType) item).isPlaceable();
    }

    /**
     * Gets the item as a BlockType if it is one.
     * @return BlockType if placeable, null otherwise
     */
    public BlockType asBlockType() {
        return item instanceof BlockType ? (BlockType) item : null;
    }

    /**
     * Gets the item as an ItemType if it is one.
     * @return ItemType if it's an ItemType, null otherwise
     */
    public ItemType asItemType() {
        return item instanceof ItemType ? (ItemType) item : null;
    }

    /**
     * Checks if this item is a tool.
     * @return True if it's an ItemType tool
     */
    public boolean isTool() {
        return item instanceof ItemType && ((ItemType) item).isTool();
    }

    /**
     * Checks if this item is a material.
     * @return True if it's an ItemType material
     */
    public boolean isMaterial() {
        return item instanceof ItemType && ((ItemType) item).isMaterial();
    }

    /**
     * Gets the item's category.
     * @return The item category
     */
    public ItemCategory getCategory() {
        return item.getCategory();
    }

    /**
     * Gets the display name of the item.
     * @return The item's name
     */
    public String getName() {
        return item.getName();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ItemStack itemStack = (ItemStack) obj;
        return count == itemStack.count && item.equals(itemStack.item);
    }

    @Override
    public int hashCode() {
        return item.hashCode() * 31 + count;
    }

    @Override
    public String toString() {
        return "ItemStack{" +
                "item=" + item.getName() +
                ", count=" + count +
                '}';
    }
}