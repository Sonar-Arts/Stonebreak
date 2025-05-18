package com.stonebreak;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the player's inventory of blocks.
 */
public class Inventory {
    
    // Maps block type ID to count
    private Map<Integer, Integer> items;
    private int selectedBlockTypeId;
    
    /**
     * Creates a new inventory.
     */
    public Inventory() {
        this.items = new HashMap<>();
        this.selectedBlockTypeId = BlockType.AIR.getId(); // Default to no block selected
        
        // Start with some basic blocks
        addItem(BlockType.GRASS.getId(), 10);
        addItem(BlockType.DIRT.getId(), 10);
        addItem(BlockType.STONE.getId(), 10);
        addItem(BlockType.WOOD.getId(), 10);

        // Automatically select the first available block if inventory is not empty
        if (!items.isEmpty()) {
            // Find the first non-air block to select
            for (Map.Entry<Integer, Integer> entry : items.entrySet()) {
                if (entry.getKey() != BlockType.AIR.getId() && entry.getValue() > 0) {
                    this.selectedBlockTypeId = entry.getKey();
                    break;
                }
            }
        }
    }
    
    /**
     * Adds an item to the inventory.
     */
    public void addItem(int blockTypeId) {
        addItem(blockTypeId, 1);
    }
    
    /**
     * Adds multiple items to the inventory.
     */
    public void addItem(int blockTypeId, int count) {
        // Ignore air blocks
        if (blockTypeId == BlockType.AIR.getId()) {
            return;
        }
        
        int currentCount = items.getOrDefault(blockTypeId, 0);
        items.put(blockTypeId, currentCount + count);
    }
    
    /**
     * Removes an item from the inventory.
     * @return True if the item was removed, false if not enough items.
     */
    public boolean removeItem(int blockTypeId) {
        return removeItem(blockTypeId, 1);
    }
    
    /**
     * Removes multiple items from the inventory.
     * @return True if the items were removed, false if not enough items.
     */
    public boolean removeItem(int blockTypeId, int count) {
        int currentCount = items.getOrDefault(blockTypeId, 0);
        
        if (currentCount >= count) {
            items.put(blockTypeId, currentCount - count);
            return true;
        }
        
        return false;
    }
    
    /**
     * Gets the count of a specific item in the inventory.
     */
    public int getItemCount(int blockTypeId) {
        return items.getOrDefault(blockTypeId, 0);
    }
    
    /**
     * Checks if the inventory contains at least one of the specified item.
     */
    public boolean hasItem(int blockTypeId) {
        return getItemCount(blockTypeId) > 0;
    }
    
    /**
     * Gets all items in the inventory.
     */
    public Map<Integer, Integer> getAllItems() {
        return new HashMap<>(items);
    }

    /**
     * Gets the ID of the currently selected block.
     * @return The block type ID of the selected block.
     */
    public int getSelectedBlockTypeId() {
        return selectedBlockTypeId;
    }

    /**
     * Sets the currently selected block.
     * @param blockTypeId The block type ID to select.
     */
    public void setSelectedBlockTypeId(int blockTypeId) {
        // Ensure the selected block type is actually in the inventory (optional, but good practice)
        // For now, we'll allow selecting any ID, assuming the game logic elsewhere handles validity.
        this.selectedBlockTypeId = blockTypeId;
    }
}
