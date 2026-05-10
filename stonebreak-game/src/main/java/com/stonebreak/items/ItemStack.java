package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;

public class ItemStack {
    private Item item;
    private int count;
    /**
     * Optional named state for SBO 1.3+ items with multiple visual variants
     * (e.g. {@code "empty"}, {@code "water"}, {@code "milk"} for a wooden
     * bucket). {@code null} means "use the default state" — and is the only
     * legal value for items that don't declare any states.
     */
    private String state;

    /**
     * Creates an ItemStack with the given item and count.
     * @param item The item (BlockType or ItemType)
     * @param count The quantity
     */
    public ItemStack(Item item, int count) {
        this(item, count, null);
    }

    /**
     * Creates an ItemStack with an explicit named state (SBO 1.3+).
     */
    public ItemStack(Item item, int count, String state) {
        if (item == null || count < 0) {
            this.item = BlockType.AIR;
            this.count = 0;
        } else {
            this.item = item;
            this.count = count;
        }
        this.state = (state != null && !state.isBlank()) ? state : null;
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
     * Checks if this ItemStack can be stacked with another ItemStack. Two
     * stacks may only merge if they share both the item type AND the state
     * — empty buckets and water buckets do not stack even though both are
     * the same item.
     */
    public boolean canStackWith(ItemStack other) {
        if (other == null || other.isEmpty() || this.isEmpty()) {
            return false;
        }
        return this.item.isSameType(other.item)
                && java.util.Objects.equals(this.state, other.state)
                && this.count < getMaxStackSize();
    }

    /**
     * Creates a copy of this ItemStack.
     * @return A new ItemStack instance or null if empty
     */
    public ItemStack copy() {
        if (isEmpty()) {
            return null;
        }
        return new ItemStack(this.item, this.count, this.state);
    }

    /**
     * Clears the item stack, making it empty.
     */
    public void clear() {
        this.item = BlockType.AIR;
        this.count = 0;
        this.state = null;
    }

    /**
     * Returns the SBO state name for this stack, or {@code null} if the
     * default state (or the item has no states).
     */
    public String getState() {
        return state;
    }

    /** Sets the SBO state name. Pass null/blank to clear (use default). */
    public void setState(String state) {
        this.state = (state != null && !state.isBlank()) ? state : null;
    }

    /**
     * Returns a copy of this stack with the given state. The original stack
     * is unchanged. Use this when transitioning between states (e.g. empty
     * bucket → water bucket).
     */
    public ItemStack withState(String newState) {
        if (isEmpty()) return null;
        return new ItemStack(this.item, this.count, newState);
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
        return count == itemStack.count
                && item.equals(itemStack.item)
                && java.util.Objects.equals(state, itemStack.state);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(item, count, state);
    }

    @Override
    public String toString() {
        return "ItemStack{" +
                "item=" + item.getName() +
                ", count=" + count +
                (state != null ? ", state=" + state : "") +
                '}';
    }
}