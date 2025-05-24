package com.stonebreak;

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
        this.hotbarSlots = new ItemStack[HOTBAR_SIZE];
        this.mainInventorySlots = new ItemStack[MAIN_INVENTORY_SIZE];
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            hotbarSlots[i] = ItemStack.empty();
        }
        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            mainInventorySlots[i] = ItemStack.empty();
        }
        this.selectedHotbarSlotIndex = 0;

        initializeDefaultItems();
    }

    private void initializeDefaultItems() {
        // Start with some basic blocks/items
        // This method calls addItem, which is fine now as it's a private helper.
        addItem(new ItemStack(BlockType.GRASS, 10));
        addItem(new ItemStack(BlockType.DIRT, 10));
        addItem(new ItemStack(BlockType.STONE, 10));
        addItem(new ItemStack(BlockType.WOOD, 10));
        addItem(new ItemStack(ItemType.STICK, 5)); // Example Item
    }

    /**
     * Adds an ItemStack to the inventory.
     * Tries to stack with existing items or find an empty slot.
     * @param itemStackToAdd The ItemStack to add.
     * @return True if all items from the stack were successfully added, false if inventory is full or couldn't add all.
     */
    public boolean addItem(ItemStack itemStackToAdd) {
        if (itemStackToAdd == null || itemStackToAdd.isEmpty() || itemStackToAdd.getCount() <= 0) {
            return true; // Adding nothing is considered success or no-op
        }

        int remainingCount = itemStackToAdd.getCount();
        ItemStack actualItemStackToAdd = itemStackToAdd.copy(); // Work with a copy

        // First, try to stack with existing items in hotbar
        for (ItemStack slotStack : hotbarSlots) {
            if (remainingCount == 0) break;
            if (slotStack.equals(actualItemStackToAdd) && slotStack.getCount() < slotStack.getMaxStackSize()) {
                int canAdd = Math.min(remainingCount, slotStack.getMaxStackSize() - slotStack.getCount());
                slotStack.incrementCount(canAdd);
                remainingCount -= canAdd;
            }
        }

        // Then, try to stack with existing items in main inventory
        for (ItemStack slotStack : mainInventorySlots) {
            if (remainingCount == 0) break;
            if (slotStack.equals(actualItemStackToAdd) && slotStack.getCount() < slotStack.getMaxStackSize()) {
                int canAdd = Math.min(remainingCount, slotStack.getMaxStackSize() - slotStack.getCount());
                slotStack.incrementCount(canAdd);
                remainingCount -= canAdd;
            }
        }

        // If still items left, try to fill empty slots in hotbar
        if (remainingCount > 0) {
            for (int i = 0; i < HOTBAR_SIZE; i++) {
                if (remainingCount == 0) break;
                if (hotbarSlots[i].isEmpty()) {
                    int canAdd = Math.min(remainingCount, actualItemStackToAdd.getMaxStackSize());
                    hotbarSlots[i] = actualItemStackToAdd.copy(canAdd);
                    remainingCount -= canAdd;
                }
            }
        }

        // Then, try to fill empty slots in main inventory
        if (remainingCount > 0) {
            for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
                if (remainingCount == 0) break;
                if (mainInventorySlots[i].isEmpty()) {
                    int canAdd = Math.min(remainingCount, actualItemStackToAdd.getMaxStackSize());
                    mainInventorySlots[i] = actualItemStackToAdd.copy(canAdd);
                    remainingCount -= canAdd;
                }
            }
        }
        // Return true if all items were successfully added (remainingCount is 0).
        // Otherwise, return false. The caller can check the original itemStackToAdd's count
        // if they need to know how many were not added (assuming it was mutable or they kept a reference).
        return remainingCount == 0;
    }


    /**
     * Removes multiple items of the given ItemStack's type from the inventory.
     * @param itemStackToRemove The ItemStack specifying the type and count to remove.
     * @return True if all requested items were removed, false otherwise.
     */
    public boolean removeItem(ItemStack itemStackToRemove) {
        if (itemStackToRemove == null || itemStackToRemove.isEmpty() || itemStackToRemove.getCount() <= 0) {
            return true;
        }

        int remainingToRemove = itemStackToRemove.getCount();

        // Try removing from selected hotbar slot first if it matches
        ItemStack selectedStack = hotbarSlots[selectedHotbarSlotIndex];
        if (selectedStack.equals(itemStackToRemove) && !selectedStack.isEmpty()) {
            int canRemove = Math.min(remainingToRemove, selectedStack.getCount());
            selectedStack.decrementCount(canRemove);
            remainingToRemove -= canRemove;
            if (selectedStack.isEmpty()) {
                selectedStack.clear();
            }
        }

        // Then from other hotbar slots
        for (int i = 0; i < HOTBAR_SIZE && remainingToRemove > 0; i++) {
            if (i == selectedHotbarSlotIndex) continue;
            ItemStack stack = hotbarSlots[i];
            if (stack.equals(itemStackToRemove) && !stack.isEmpty()) {
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
            if (stack.equals(itemStackToRemove) && !stack.isEmpty()) {
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
     * Gets the total count of a specific item type across all inventory slots.
     * @param itemTypeToCount The ItemStack specifying the type to count.
     * @return The total count of the item.
     */
    public int getItemCount(ItemStack itemTypeToCount) {
        if (itemTypeToCount == null || itemTypeToCount.isEmpty()) return 0;
        int totalCount = 0;
        for (ItemStack stack : hotbarSlots) {
            if (stack.equals(itemTypeToCount)) {
                totalCount += stack.getCount();
            }
        }
        for (ItemStack stack : mainInventorySlots) {
            if (stack.equals(itemTypeToCount)) {
                totalCount += stack.getCount();
            }
        }
        return totalCount;
    }

    /**
     * Checks if the inventory contains at least one of the specified item.
     */
    public boolean hasItem(ItemStack itemTypeToFind) {
        return getItemCount(itemTypeToFind) > 0;
    }

    /**
     * Gets all ItemStacks in the main inventory.
     * Returns a copy to prevent direct modification.
     */
    public ItemStack[] getMainInventorySlots() {
        ItemStack[] copy = new ItemStack[MAIN_INVENTORY_SIZE];
        for(int i=0; i < MAIN_INVENTORY_SIZE; i++) {
            copy[i] = mainInventorySlots[i].copy(); 
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
            copy[i] = hotbarSlots[i].copy();
        }
        return copy;
    }

    /**
     * Gets the ItemStack in a specific hotbar slot. (Direct reference for modification)
     */
    public ItemStack getHotbarSlot(int index) {
        if (index >= 0 && index < HOTBAR_SIZE) {
            return hotbarSlots[index];
        }
        return ItemStack.empty(); // Should not happen if index is valid
    }

    /**
     * Gets the ItemStack in a specific main inventory slot. (Direct reference for modification)
     */
    public ItemStack getMainInventorySlot(int index) {
        if (index >= 0 && index < MAIN_INVENTORY_SIZE) {
            return mainInventorySlots[index];
        }
        return ItemStack.empty(); // Should not happen
    }
    
    /**
     * Sets the ItemStack in a specific hotbar slot.
     */
    public void setHotbarSlot(int index, ItemStack stack) {
        if (index >= 0 && index < HOTBAR_SIZE) {
            hotbarSlots[index] = (stack == null || stack.isEmpty()) ? ItemStack.empty() : stack.copy();
        }
    }

    /**
     * Sets the ItemStack in a specific main inventory slot.
     */
    public void setMainInventorySlot(int index, ItemStack stack) {
        if (index >= 0 && index < MAIN_INVENTORY_SIZE) {
            mainInventorySlots[index] = (stack == null || stack.isEmpty()) ? ItemStack.empty() : stack.copy();
        }
    }

    /**
     * Gets the ID of the block type in the currently selected hotbar slot.
     * @return The block type ID, or BlockType.AIR.getId() if the slot is empty or item.
     */
    public int getSelectedBlockTypeId() {
        ItemStack selectedStack = hotbarSlots[selectedHotbarSlotIndex];
        if (!selectedStack.isEmpty() && selectedStack.isBlock()) {
            BlockType bt = selectedStack.getBlockType();
            return bt != null ? bt.getId() : BlockType.AIR.getId();
        }
        return BlockType.AIR.getId();
    }
    
    /**
     * Gets the ItemStack in the currently selected hotbar slot.
     * @return A copy of the ItemStack, or an empty ItemStack if slot is invalid/empty.
     */
    public ItemStack getSelectedItemStack() {
        if (selectedHotbarSlotIndex >= 0 && selectedHotbarSlotIndex < HOTBAR_SIZE) {
            return hotbarSlots[selectedHotbarSlotIndex].copy();
        }
        return ItemStack.empty();
    }


    /**
     * Gets the currently selected hotbar slot index.
     */
    public int getSelectedHotbarSlotIndex() {
        return selectedHotbarSlotIndex;
    }

    /**
     * Sets the currently selected hotbar slot index.
     */
    public void setSelectedHotbarSlotIndex(int selectedHotbarSlotIndex) {
        if (selectedHotbarSlotIndex >= 0 && selectedHotbarSlotIndex < HOTBAR_SIZE) {
            boolean changed = this.selectedHotbarSlotIndex != selectedHotbarSlotIndex;
            this.selectedHotbarSlotIndex = selectedHotbarSlotIndex;
            if (changed && inventoryScreen != null) {
                ItemStack newItem = hotbarSlots[this.selectedHotbarSlotIndex];
                if (!newItem.isEmpty()) {
                    if (newItem.isBlock()) { //Only show tooltip for blocks for now
                        inventoryScreen.displayHotbarItemTooltip(newItem.getBlockType());
                    } else { // Is an ItemType (non-block)
                        // Potentially extend displayHotbarItemTooltip to take ItemType or handle display name.
                        // For now, hide tooltip for non-block items as per InventoryScreen's current capability.
                        inventoryScreen.displayHotbarItemTooltip(null);
                    }
                } else { // Slot is empty
                     inventoryScreen.displayHotbarItemTooltip(null); // Hide tooltip if empty
                }
            }
        }
    }

    /**
     * Sets the InventoryScreen reference.
     */
    public void setInventoryScreen(InventoryScreen screen) {
        this.inventoryScreen = screen;
    }
}
