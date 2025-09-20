package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;


/**
 * Represents the player's inventory of blocks using fixed slots.
 */
public class Inventory {

    public static final int HOTBAR_SIZE = 9;
    public static final int MAIN_INVENTORY_ROWS = 3;
    public static final int MAIN_INVENTORY_COLS = 9;
    public static final int MAIN_INVENTORY_SIZE = MAIN_INVENTORY_ROWS * MAIN_INVENTORY_COLS; // 27 slots
    public static final int TOTAL_SLOTS = HOTBAR_SIZE + MAIN_INVENTORY_SIZE; // 36 slots
    
    private final ItemStack[] hotbarSlots;
    private final ItemStack[] mainInventorySlots;
    private int selectedHotbarSlotIndex; // 0-8
    private InventoryScreen inventoryScreen; // Reference to InventoryScreen for tooltip

    /**
     * Creates a new inventory with fixed slots.
     */
    public Inventory() {
        // this.inventoryScreen = inventoryScreen; // Removed: Will be set via setter
        this.hotbarSlots = new ItemStack[HOTBAR_SIZE];
        this.mainInventorySlots = new ItemStack[MAIN_INVENTORY_SIZE];
        
        // Initialize all slots with empty items
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            hotbarSlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
        }
        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            mainInventorySlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
        }
        this.selectedHotbarSlotIndex = 0;

        // Initialize with starting items (extracted to a private method to avoid overridable method calls in constructor)
        initializeStartingItems();    }
    
    /**
     * Initialize inventory with starting items.
     * Extracted to a separate method to avoid overridable method calls in constructor.
     */
    private void initializeStartingItems() {
        // Start with some basic blocks
        addItem(BlockType.GRASS, 10);
        addItem(BlockType.DIRT, 10);
        addItem(BlockType.STONE, 10);
        addItem(BlockType.WOOD, 10);
        addItem(BlockType.WATER, 5); // Add water blocks for testing
        
        // Add some tools and materials
        addItem(ItemType.STICK, 5);
        addItem(ItemType.WOODEN_PICKAXE, 1);

        // Initial tooltip will be triggered by Game.init() after setInventoryScreen is called.
    }    /**
     * Adds a single item to the inventory.
     * @param item The item to add
     * @return True if the item was successfully added
     */
    public final boolean addItem(Item item) {
        return addItem(item, 1);
    }
    
    /**
     * Adds multiple items to the inventory.
     * @param item The item to add
     * @param count The number of items to add
     * @return True if all items were successfully added
     */
    public final boolean addItem(Item item, int count) {
        return addItem(new ItemStack(item, count));
    }
    
    /**
     * Adds a single item of the given block type to the inventory (backwards compatibility).
     * Tries to stack with existing items or find an empty slot.
     * @param blockTypeId The ID of the block type to add.
     * @return True if the item was successfully added (or partially added), false otherwise.
     */
    public final boolean addItem(int blockTypeId) {
        return addItem(blockTypeId, 1);
    }    /**
     * Adds multiple items of the given block type to the inventory.
     * Tries to stack with existing items or find an empty slot.
     * @param blockTypeId The ID of the block type to add.
     * @param count The number of items to add.
     * @return True if all items were successfully added, false if inventory is full or couldn't add all.
     */
    public final boolean addItem(int blockTypeId, int count) {
        if (blockTypeId == BlockType.AIR.getId() || count <= 0) {
            return true; // Or false, depending on desired behavior for adding air/zero
        }

        int remainingCount = count;

        // First, try to stack with existing items in hotbar
        for (ItemStack stack : hotbarSlots) {
            if (remainingCount == 0) break;
            if (stack.getBlockTypeId() == blockTypeId && stack.getCount() < stack.getMaxStackSize()) {
                int canAdd = Math.min(remainingCount, stack.getMaxStackSize() - stack.getCount());
                stack.incrementCount(canAdd);
                remainingCount -= canAdd;
            }
        }

        // Then, try to stack with existing items in main inventory
        for (ItemStack stack : mainInventorySlots) {
            if (remainingCount == 0) break;
            if (stack.getBlockTypeId() == blockTypeId && stack.getCount() < stack.getMaxStackSize()) {
                int canAdd = Math.min(remainingCount, stack.getMaxStackSize() - stack.getCount());
                stack.incrementCount(canAdd);
                remainingCount -= canAdd;
            }
        }

        // If still items left, try to fill empty slots in hotbar
        if (remainingCount > 0) {
            for (ItemStack stack : hotbarSlots) {
                if (remainingCount == 0) break;
                if (stack.isEmpty()) {
                    int canAdd = Math.min(remainingCount, new ItemStack(blockTypeId, 0).getMaxStackSize());
                    stack.setBlockTypeId(blockTypeId);
                    stack.setCount(canAdd);
                    remainingCount -= canAdd;
                }
            }
        }

        // Then, try to fill empty slots in main inventory
        if (remainingCount > 0) {
            for (ItemStack stack : mainInventorySlots) {
                if (remainingCount == 0) break;
                if (stack.isEmpty()) {
                    int canAdd = Math.min(remainingCount, new ItemStack(blockTypeId, 0).getMaxStackSize());
                    stack.setBlockTypeId(blockTypeId);
                    stack.setCount(canAdd);
                    remainingCount -= canAdd;
                }
            }
        }
        return remainingCount == 0;
    }
/**
     * Adds an entire ItemStack to the inventory.
     * Tries to stack with existing items or find an empty slot.
     * @param itemStack The ItemStack to add.
     * @return True if the entire itemStack was successfully added, false otherwise.
     */
    public boolean addItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return true; // Adding nothing is success
        }
        
        int remainingCount = itemStack.getCount();
        Item item = itemStack.getItem();
        
        // First, try to stack with existing items in hotbar
        for (ItemStack stack : hotbarSlots) {
            if (remainingCount == 0) break;
            if (stack.getItem().isSameType(item) && stack.getCount() < stack.getMaxStackSize()) {
                int canAdd = Math.min(remainingCount, stack.getMaxStackSize() - stack.getCount());
                stack.incrementCount(canAdd);
                remainingCount -= canAdd;
            }
        }
        
        // Then, try to stack with existing items in main inventory
        for (ItemStack stack : mainInventorySlots) {
            if (remainingCount == 0) break;
            if (stack.getItem().isSameType(item) && stack.getCount() < stack.getMaxStackSize()) {
                int canAdd = Math.min(remainingCount, stack.getMaxStackSize() - stack.getCount());
                stack.incrementCount(canAdd);
                remainingCount -= canAdd;
            }
        }
        
        // If still items left, try to fill empty slots in hotbar
        if (remainingCount > 0) {
            for (int i = 0; i < hotbarSlots.length; i++) {
                if (remainingCount == 0) break;
                if (hotbarSlots[i].isEmpty()) {
                    int canAdd = Math.min(remainingCount, itemStack.getMaxStackSize());
                    // Replace the empty stack with a new one containing our item
                    hotbarSlots[i] = new ItemStack(item, canAdd);
                    remainingCount -= canAdd;
                }
            }
        }
        
        // Then, try to fill empty slots in main inventory
        if (remainingCount > 0) {
            for (int i = 0; i < mainInventorySlots.length; i++) {
                if (remainingCount == 0) break;
                if (mainInventorySlots[i].isEmpty()) {
                    int canAdd = Math.min(remainingCount, itemStack.getMaxStackSize());
                    // Replace the empty stack with a new one containing our item
                    mainInventorySlots[i] = new ItemStack(item, canAdd);
                    remainingCount -= canAdd;
                }
            }
        }
        
        return remainingCount == 0; // Return true if all items were added
    }

    /**
     * Adds multiple items of the given block type to the inventory.
     * @param blockTypeId The ID of the block type to add.
     * @param count The number of items to add.
     * @return The number of items that were actually added to the inventory.
     */
    public int addItemsAndReturnCount(int blockTypeId, int count) {
        if (blockTypeId == BlockType.AIR.getId() || count <= 0) {
            return 0;
        }

        int remainingCount = count;

        // First, try to stack with existing items in hotbar
        for (ItemStack stack : hotbarSlots) {
            if (remainingCount == 0) break;
            if (stack.getBlockTypeId() == blockTypeId && stack.getCount() < stack.getMaxStackSize()) {
                int canAdd = Math.min(remainingCount, stack.getMaxStackSize() - stack.getCount());
                stack.incrementCount(canAdd);
                remainingCount -= canAdd;
            }
        }

        // Then, try to stack with existing items in main inventory
        for (ItemStack stack : mainInventorySlots) {
            if (remainingCount == 0) break;
            if (stack.getBlockTypeId() == blockTypeId && stack.getCount() < stack.getMaxStackSize()) {
                int canAdd = Math.min(remainingCount, stack.getMaxStackSize() - stack.getCount());
                stack.incrementCount(canAdd);
                remainingCount -= canAdd;
            }
        }

        // If still items left, try to fill empty slots in hotbar
        if (remainingCount > 0) {
            for (ItemStack stack : hotbarSlots) {
                if (remainingCount == 0) break;
                if (stack.isEmpty()) {
                    int canAdd = Math.min(remainingCount, new ItemStack(blockTypeId, 0).getMaxStackSize());
                    stack.setBlockTypeId(blockTypeId);
                    stack.setCount(canAdd);
                    remainingCount -= canAdd;
                }
            }
        }

        // Then, try to fill empty slots in main inventory
        if (remainingCount > 0) {
            for (ItemStack stack : mainInventorySlots) {
                if (remainingCount == 0) break;
                if (stack.isEmpty()) {
                    int canAdd = Math.min(remainingCount, new ItemStack(blockTypeId, 0).getMaxStackSize());
                    stack.setBlockTypeId(blockTypeId);
                    stack.setCount(canAdd);
                    remainingCount -= canAdd;
                }
            }
        }
        
        return count - remainingCount; // Return how many items were actually added
    }

    /**
     * Removes a single item from the inventory.
     * @param item The item to remove
     * @return True if the item was removed
     */
    public boolean removeItem(Item item) {
        return removeItem(item, 1);
    }
    
    /**
     * Removes multiple items from the inventory.
     * @param item The item to remove
     * @param count The number of items to remove
     * @return True if all items were removed
     */
    public boolean removeItem(Item item, int count) {
        return removeItem(item.getId(), count);
    }
    
    /**
     * Removes a single item of the given block type from the inventory (backwards compatibility).
     * Prioritizes removing from the selected hotbar slot, then other hotbar slots, then main inventory.
     * @param blockTypeId The ID of the block type to remove.
     * @return True if the item was removed, false if not found.
     */
    public boolean removeItem(int blockTypeId) {
        return removeItem(blockTypeId, 1);
    }

    /**
     * Removes multiple items of the given block type from the inventory.
     * @param blockTypeId The ID of the block type to remove.
     * @param count The number of items to remove.
     * @return True if all requested items were removed, false otherwise.
     */
    public boolean removeItem(int blockTypeId, int count) {
        if (blockTypeId == BlockType.AIR.getId() || count <= 0) {
            return true;
        }

        int remainingToRemove = count;

        // Try removing from selected hotbar slot first if it matches
        ItemStack selectedStack = hotbarSlots[selectedHotbarSlotIndex];
        if (selectedStack.getBlockTypeId() == blockTypeId && !selectedStack.isEmpty()) {
            int canRemove = Math.min(remainingToRemove, selectedStack.getCount());
            selectedStack.decrementCount(canRemove);
            remainingToRemove -= canRemove;
            if (selectedStack.isEmpty()) {
                selectedStack.clear();
            }
        }

        // Then from other hotbar slots
        for (int i = 0; i < HOTBAR_SIZE && remainingToRemove > 0; i++) {
            if (i == selectedHotbarSlotIndex) continue; // Skip already processed selected slot
            ItemStack stack = hotbarSlots[i];
            if (stack.getBlockTypeId() == blockTypeId && !stack.isEmpty()) {
                int canRemove = Math.min(remainingToRemove, stack.getCount());
                stack.decrementCount(canRemove);
                remainingToRemove -= canRemove;
                if (stack.isEmpty()) {
                    stack.clear();
                }
            }
        }

        // Then from main inventory slots
        for (int i = 0; i < MAIN_INVENTORY_SIZE && remainingToRemove > 0; i++) {
            ItemStack stack = mainInventorySlots[i];
            if (stack.getBlockTypeId() == blockTypeId && !stack.isEmpty()) {
                int canRemove = Math.min(remainingToRemove, stack.getCount());
                stack.decrementCount(canRemove);
                remainingToRemove -= canRemove;
                if (stack.isEmpty()) {
                    stack.clear();
                }
            }
        }
        return remainingToRemove == 0;
    }

    /**
     * Gets the total count of a specific item across all inventory slots.
     * @param item The item to count
     * @return The total count of the item
     */
    public int getItemCount(Item item) {
        return getItemCount(item.getId());
    }
    
    /**
     * Gets the total count of a specific block type across all inventory slots (backwards compatibility).
     * @param blockTypeId The ID of the block type.
     * @return The total count of the item.
     */
    public int getItemCount(int blockTypeId) {
        if (blockTypeId == BlockType.AIR.getId()) return 0; // Or Integer.MAX_VALUE if air is "infinite"
        int totalCount = 0;
        for (ItemStack stack : hotbarSlots) {
            if (stack.getBlockTypeId() == blockTypeId) {
                totalCount += stack.getCount();
            }
        }
        for (ItemStack stack : mainInventorySlots) {
            if (stack.getBlockTypeId() == blockTypeId) {
                totalCount += stack.getCount();
            }
        }
        return totalCount;
    }

    /**
     * Checks if the inventory contains at least one of the specified item.
     * @param item The item to check for
     * @return True if the inventory contains the item
     */
    public boolean hasItem(Item item) {
        return getItemCount(item) > 0;
    }
    
    /**
     * Checks if the inventory contains at least one of the specified item (backwards compatibility).
     */
    public boolean hasItem(int blockTypeId) {
        return getItemCount(blockTypeId) > 0;
    }

    /**
     * Gets all ItemStacks in the main inventory.
     * Returns a copy to prevent direct modification.
     */
    public ItemStack[] getMainInventorySlots() {
        ItemStack[] copy = new ItemStack[MAIN_INVENTORY_SIZE];
        for(int i=0; i < MAIN_INVENTORY_SIZE; i++) {
            copy[i] = mainInventorySlots[i].copy(); // Use copy to prevent external modification
        }
        return copy;
    }
    
    /**
     * Gets all ItemStacks in the hotbar.
     * Returns a copy to prevent direct modification.
     */
    public ItemStack[] getHotbarSlots() {
        ItemStack[] copy = new ItemStack[HOTBAR_SIZE];
        for(int i=0; i < HOTBAR_SIZE; i++) {
            copy[i] = hotbarSlots[i].copy(); // Use copy to prevent external modification
        }
        return copy;
    }

    /**
     * Gets the ItemStack in a specific hotbar slot.
     * @param index The index of the hotbar slot (0-8).
     * @return The ItemStack in that slot, or null if index is invalid.
     */
    public ItemStack getHotbarSlot(int index) {
        if (index >= 0 && index < HOTBAR_SIZE) {
            return hotbarSlots[index]; // Return direct reference for modification by InventoryScreen drag/drop
        }
        return null;
    }

    /**
     * Gets the ItemStack in a specific main inventory slot.
     * @param index The index of the main inventory slot (0 to MAIN_INVENTORY_SIZE-1).
     * @return The ItemStack in that slot, or null if index is invalid.
     */
    public ItemStack getMainInventorySlot(int index) {
        if (index >= 0 && index < MAIN_INVENTORY_SIZE) {
            return mainInventorySlots[index]; // Return direct reference for modification
        }
        return null;
    }
    
    /**
     * Sets the ItemStack in a specific hotbar slot.
     * Used for drag-and-drop operations.
     * @param index The index of the hotbar slot.
     * @param stack The ItemStack to place in the slot.
     */
    public void setHotbarSlot(int index, ItemStack stack) {
        if (index >= 0 && index < HOTBAR_SIZE) {
            hotbarSlots[index] = (stack == null || stack.isEmpty()) ? new ItemStack(BlockType.AIR.getId(), 0) : stack;
        }
    }

    /**
     * Sets the ItemStack in a specific main inventory slot.
     * Used for drag-and-drop operations.
     * @param index The index of the main inventory slot.
     * @param stack The ItemStack to place in the slot.
     */
    public void setMainInventorySlot(int index, ItemStack stack) {
        if (index >= 0 && index < MAIN_INVENTORY_SIZE) {
            mainInventorySlots[index] = (stack == null || stack.isEmpty()) ? new ItemStack(BlockType.AIR.getId(), 0) : stack;
        }
    }


    /**
     * Gets the ItemStack in the currently selected hotbar slot.
     * @return The selected ItemStack, never null (but may be empty)
     */
    public ItemStack getSelectedHotbarSlot() {
        return hotbarSlots[selectedHotbarSlotIndex];
    }
    
    /**
     * Gets the ID of the block type in the currently selected hotbar slot.
     * @return The block type ID, or BlockType.AIR.getId() if the slot is empty.
     */
    public int getSelectedBlockTypeId() {
        ItemStack selectedStack = hotbarSlots[selectedHotbarSlotIndex];
        return selectedStack.isEmpty() ? BlockType.AIR.getId() : selectedStack.getBlockTypeId();
    }

    /**
     * Gets the currently selected hotbar slot index.
     * @return The index (0-8).
     */
    public int getSelectedHotbarSlotIndex() {
        return selectedHotbarSlotIndex;
    }

    /**
     * Sets the currently selected hotbar slot index.
     * @param selectedHotbarSlotIndex The index (0-8).
     */
    public void setSelectedHotbarSlotIndex(int selectedHotbarSlotIndex) {
        if (selectedHotbarSlotIndex >= 0 && selectedHotbarSlotIndex < HOTBAR_SIZE) {
            boolean changed = this.selectedHotbarSlotIndex != selectedHotbarSlotIndex;
            this.selectedHotbarSlotIndex = selectedHotbarSlotIndex;
            if (changed && inventoryScreen != null) {
                ItemStack newItem = hotbarSlots[this.selectedHotbarSlotIndex];
                if (newItem.getItem() instanceof BlockType blockType) {
                    inventoryScreen.displayHotbarItemTooltip(blockType);
                }
            }
        }
    }

    /**
     * Sets the InventoryScreen reference.
     * This is called by Game after InventoryScreen is initialized.
     * @param screen The InventoryScreen instance.
     */
    public void setInventoryScreen(InventoryScreen screen) {
        this.inventoryScreen = screen;
    }
}
