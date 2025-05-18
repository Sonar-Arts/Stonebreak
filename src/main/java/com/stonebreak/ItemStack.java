package com.stonebreak;

public class ItemStack {
    private int blockTypeId;
    private int count;

    public ItemStack(int blockTypeId, int count) {
        if (blockTypeId == BlockType.AIR.getId() && count > 0) {
            // Air blocks should not have a count or exist as ItemStacks in slots
            this.blockTypeId = BlockType.AIR.getId();
            this.count = 0;
        } else {
            this.blockTypeId = blockTypeId;
            this.count = count;
        }
    }

    public int getBlockTypeId() {
        return blockTypeId;
    }

    public void setBlockTypeId(int blockTypeId) {
        this.blockTypeId = blockTypeId;
        if (this.blockTypeId == BlockType.AIR.getId()) {
            this.count = 0; // Air blocks shouldn't have a count
        }
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        if (this.blockTypeId == BlockType.AIR.getId() && count > 0) {
            this.count = 0;
        } else {
            this.count = count;
        }
    }

    public void incrementCount(int amount) {
        if (this.blockTypeId != BlockType.AIR.getId()) {
            this.count += amount;
        }
    }

    public void decrementCount(int amount) {
        if (this.blockTypeId != BlockType.AIR.getId()) {
            this.count -= amount;
            if (this.count < 0) {
                this.count = 0;
            }
        }
        // If count reaches 0, it effectively becomes an empty slot (or could be set to AIR)
        if (this.count == 0 && this.blockTypeId != BlockType.AIR.getId()) {
            // this.blockTypeId = BlockType.AIR.getId(); // Or handle this in Inventory logic
        }
    }

    public boolean isEmpty() {
        return count <= 0 || blockTypeId == BlockType.AIR.getId();
    }

    // Placeholder for max stack size, can be made dynamic later
    public int getMaxStackSize() {
        // This could depend on BlockType in the future
        // For most blocks, 64 is common.
        return 64; 
    }

    /**
     * Creates a new ItemStack instance if this one is not empty, otherwise returns null.
     * Useful for preventing modification of internal inventory ItemStacks directly.
     * @return A new ItemStack instance or null if this stack is empty.
     */
    public ItemStack copy() {
        if (isEmpty()) {
            return null;
        }
        return new ItemStack(this.blockTypeId, this.count);
    }

    /**
     * Clears the item stack, effectively making it an empty slot.
     */
    public void clear() {
        this.blockTypeId = BlockType.AIR.getId();
        this.count = 0;
    }
}