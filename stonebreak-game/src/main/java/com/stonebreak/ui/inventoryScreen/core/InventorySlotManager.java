package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.items.Inventory;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;

/**
 * Manages slot operations for the inventory screen.
 * Abstracts slot interaction logic following Single Responsibility Principle.
 */
public class InventorySlotManager {

    private final Inventory inventory;
    private final InventoryCraftingManager craftingManager;

    public InventorySlotManager(Inventory inventory, InventoryCraftingManager craftingManager) {
        this.inventory = inventory;
        this.craftingManager = craftingManager;
    }

    public boolean tryPickUpFromMainInventory(float mouseX, float mouseY,
                                             InventoryLayoutCalculator.InventoryLayout layout,
                                             InventoryDragDropHandler.DragState dragState) {
        // Calculate main inventory centering within the panel (same as rendering)
        int inventoryWidth = Inventory.MAIN_INVENTORY_COLS * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) - InventoryLayoutCalculator.getSlotPadding();
        int inventoryStartX = layout.panelStartX + (layout.inventoryPanelWidth - inventoryWidth) / 2;

        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = inventoryStartX + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() +
                       row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                ItemStack currentStack = inventory.getMainInventorySlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    dragState.draggedItemStack = currentStack.copy();
                    inventory.setMainInventorySlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                    dragState.draggedItemOriginalSlotIndex = i;
                    dragState.dragSource = InventoryDragDropHandler.DragSource.MAIN_INVENTORY;
                    return true;
                }
            }
        }
        return false;
    }

    public boolean tryPickUpFromHotbar(float mouseX, float mouseY,
                                      InventoryLayoutCalculator.InventoryLayout layout,
                                      InventoryDragDropHandler.DragState dragState) {
        // Calculate hotbar centering within the panel (same as rendering)
        int hotbarWidth = Inventory.HOTBAR_SIZE * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) - InventoryLayoutCalculator.getSlotPadding();
        int hotbarStartX = layout.panelStartX + (layout.inventoryPanelWidth - hotbarWidth) / 2;

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int slotX = hotbarStartX + i * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                ItemStack currentStack = inventory.getHotbarSlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    dragState.draggedItemStack = currentStack.copy();
                    inventory.setHotbarSlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                    dragState.draggedItemOriginalSlotIndex = i;
                    dragState.dragSource = InventoryDragDropHandler.DragSource.HOTBAR;
                    return true;
                }
            }
        }
        return false;
    }

    public boolean tryPickUpFromCraftingInput(float mouseX, float mouseY,
                                             InventoryLayoutCalculator.InventoryLayout layout,
                                             InventoryDragDropHandler.DragState dragState) {
        ItemStack[] craftingInputSlots = craftingManager.getCraftingInputSlots();
        int craftingGridSize = craftingManager.getCraftingGridSize();
        int craftingInputSlotsCount = craftingGridSize * craftingGridSize;

        for (int i = 0; i < craftingInputSlotsCount; i++) {
            int r = i / craftingGridSize;
            int c = i % craftingGridSize;
            int slotX = layout.craftingElementsStartX +
                       c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY +
                       r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                if (craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                    dragState.draggedItemStack = craftingInputSlots[i].copy();
                    craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
                    dragState.draggedItemOriginalSlotIndex = InventoryDragDropHandler.getCraftingInputSlotStartIndex() + i;
                    dragState.dragSource = InventoryDragDropHandler.DragSource.CRAFTING_INPUT;
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isMouseOverCraftingOutput(float mouseX, float mouseY,
                                             InventoryLayoutCalculator.InventoryLayout layout) {
        return isMouseOverSlot(mouseX, mouseY, layout.outputSlotX, layout.outputSlotY);
    }

    /**
     * Middle-click on a crafting input cell: redistributes the clicked item evenly
     * across every cell holding the same item, so a recipe spanning several cells
     * (e.g. log + log -> sticks) is satisfied from one dropped stack. Returns true
     * when the click landed on a crafting input cell (even if nothing needed
     * balancing); false when it did not.
     */
    public boolean tryBalanceCraftingSlot(float mouseX, float mouseY,
                                          InventoryLayoutCalculator.InventoryLayout layout) {
        ItemStack[] slots = craftingManager.getCraftingInputSlots();
        int gridSize = craftingManager.getCraftingGridSize();
        int slotCount = gridSize * gridSize;

        for (int i = 0; i < slotCount; i++) {
            int r = i / gridSize;
            int c = i % gridSize;
            int slotX = layout.craftingElementsStartX +
                       c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY +
                       r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                balanceCraftingCells(slots, i);
                return true;
            }
        }
        return false;
    }

    /**
     * Evens out the counts of the item in {@code clickedIndex} across all cells
     * holding the same item + state. The total count is preserved; each matching
     * cell gets floor(total / n), with the remainder spread one-at-a-time to the
     * first cells. Cells of any other item are never touched.
     */
    private void balanceCraftingCells(ItemStack[] slots, int clickedIndex) {
        ItemStack clicked = slots[clickedIndex];
        if (clicked == null || clicked.isEmpty()) {
            return;
        }
        Item clickedItem = clicked.getItem();
        String clickedState = clicked.getState();

        java.util.List<Integer> matching = new java.util.ArrayList<>();
        int total = 0;
        for (int i = 0; i < slots.length; i++) {
            ItemStack slot = slots[i];
            if (slot != null && !slot.isEmpty()
                    && slot.getItem().isSameType(clickedItem)
                    && java.util.Objects.equals(slot.getState(), clickedState)) {
                matching.add(i);
                total += slot.getCount();
            }
        }

        int n = matching.size();
        if (n <= 1) {
            return;
        }
        int base = total / n;
        int remainder = total % n;
        for (int k = 0; k < n; k++) {
            slots[matching.get(k)].setCount(base + (k < remainder ? 1 : 0));
        }
    }

    /**
     * Starts a drag from the crafting output with a freshly taken craft batch.
     * The batch was produced by {@link InventoryCraftingManager#takeCraftBatch()}
     * and is already detached from the output slot.
     */
    public void startDragFromCraftingOutput(ItemStack batch,
                                            InventoryDragDropHandler.DragState dragState) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        dragState.draggedItemStack = batch;
        dragState.draggedItemOriginalSlotIndex = InventoryDragDropHandler.getCraftingOutputSlotIndex();
        dragState.dragSource = InventoryDragDropHandler.DragSource.NONE;
    }

    public boolean tryShiftClickCraftingOutput(float mouseX, float mouseY,
                                              InventoryLayoutCalculator.InventoryLayout layout) {
        if (!isMouseOverCraftingOutput(mouseX, mouseY, layout)) {
            return false;
        }
        ItemStack craftingOutputSlot = craftingManager.getCraftingOutputSlot();
        if (craftingOutputSlot == null || craftingOutputSlot.isEmpty()) {
            return false;
        }
        // Shift-clicking the result crafts as many batches as the inputs allow
        // and deposits them all (dropping whatever the inventory cannot hold).
        depositCraftedStacks(craftingManager.craftAll());
        return true;
    }

    /**
     * Adds every crafted stack to the player inventory, dropping (into the world)
     * whatever does not fit, so no crafted items are ever lost.
     */
    void depositCraftedStacks(java.util.List<ItemStack> crafted) {
        Player player = Game.getPlayer();
        for (ItemStack stack : crafted) {
            int added = inventory.addItemAndReturnCount(stack);
            int remainder = stack.getCount() - added;
            if (remainder > 0 && player != null) {
                com.stonebreak.util.DropUtil.dropItemFromPlayer(player,
                    new ItemStack(stack.getItem(), remainder, stack.getState()));
            }
        }
    }

    public boolean tryShiftClickCraftingInput(float mouseX, float mouseY,
                                             InventoryLayoutCalculator.InventoryLayout layout) {
        ItemStack[] craftingInputSlots = craftingManager.getCraftingInputSlots();
        int craftingGridSize = craftingManager.getCraftingGridSize();
        int craftingInputSlotsCount = craftingGridSize * craftingGridSize;

        for (int i = 0; i < craftingInputSlotsCount; i++) {
            int r = i / craftingGridSize;
            int c = i % craftingGridSize;
            int slotX = layout.craftingElementsStartX +
                       c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY +
                       r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                if (craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                    ItemStack itemInInputSlot = craftingInputSlots[i];
                    int typeId = itemInInputSlot.getBlockTypeId();
                    int countInInputSlot = itemInInputSlot.getCount();

                    int itemsOfTypeInInventoryBefore = inventory.getItemCount(typeId);
                    inventory.addItem(typeId, countInInputSlot);
                    int itemsOfTypeInInventoryAfter = inventory.getItemCount(typeId);
                    int numAdded = itemsOfTypeInInventoryAfter - itemsOfTypeInInventoryBefore;

                    if (numAdded > 0) {
                        itemInInputSlot.decrementCount(numAdded);
                        if (itemInInputSlot.isEmpty()) {
                            craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
                        }
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Shift-click on a main inventory slot: move item from main inventory to hotbar.
     * Consolidates with existing stacks first, then fills empty slots.
     */
    public boolean tryShiftClickMainInventoryToHotbar(float mouseX, float mouseY,
                                                       InventoryLayoutCalculator.InventoryLayout layout) {
        // Calculate main inventory centering within the panel (same as rendering)
        int inventoryWidth = Inventory.MAIN_INVENTORY_COLS * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) - InventoryLayoutCalculator.getSlotPadding();
        int inventoryStartX = layout.panelStartX + (layout.inventoryPanelWidth - inventoryWidth) / 2;

        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = inventoryStartX + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() +
                       row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                ItemStack currentStack = inventory.getMainInventorySlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    // Try to move this item to hotbar (consolidate first, then empty slots)
                    ItemStack itemToMove = currentStack.copy();
                    int moved = addToHotbar(itemToMove);
                    
                    if (moved > 0) {
                        currentStack.decrementCount(moved);
                        if (currentStack.isEmpty()) {
                            inventory.setMainInventorySlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                        }
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Shift-click on a hotbar slot: move item from hotbar to main inventory.
     * Consolidates with existing stacks first, then fills empty slots.
     */
    public boolean tryShiftClickHotbarToMainInventory(float mouseX, float mouseY,
                                                       InventoryLayoutCalculator.InventoryLayout layout) {
        // Calculate hotbar centering within the panel (same as rendering)
        int hotbarWidth = Inventory.HOTBAR_SIZE * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) - InventoryLayoutCalculator.getSlotPadding();
        int hotbarStartX = layout.panelStartX + (layout.inventoryPanelWidth - hotbarWidth) / 2;

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int slotX = hotbarStartX + i * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                ItemStack currentStack = inventory.getHotbarSlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    // Try to move this item to main inventory (consolidate first, then empty slots)
                    ItemStack itemToMove = currentStack.copy();
                    int moved = addToMainInventory(itemToMove);
                    
                    if (moved > 0) {
                        currentStack.decrementCount(moved);
                        if (currentStack.isEmpty()) {
                            inventory.setHotbarSlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                        }
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Adds an item to the hotbar, consolidating with existing stacks first, then filling empty slots.
     * Returns the number of items actually added.
     */
    private int addToHotbar(ItemStack itemStack) {
        if (itemStack.isEmpty()) return 0;
        
        int remaining = itemStack.getCount();
        Item item = itemStack.getItem();
        String state = itemStack.getState();
        
        // First pass: consolidate with existing stacks
        for (int i = 0; i < Inventory.HOTBAR_SIZE && remaining > 0; i++) {
            ItemStack slot = inventory.getHotbarSlot(i);
            if (!slot.isEmpty() && slot.getItem().isSameType(item) 
                    && java.util.Objects.equals(slot.getState(), state)
                    && slot.getCount() < slot.getMaxStackSize()) {
                int canAdd = Math.min(remaining, slot.getMaxStackSize() - slot.getCount());
                slot.incrementCount(canAdd);
                remaining -= canAdd;
            }
        }
        
        // Second pass: fill empty slots
        for (int i = 0; i < Inventory.HOTBAR_SIZE && remaining > 0; i++) {
            if (inventory.getHotbarSlot(i).isEmpty()) {
                int canAdd = Math.min(remaining, itemStack.getMaxStackSize());
                inventory.setHotbarSlot(i, new ItemStack(item, canAdd, state));
                remaining -= canAdd;
            }
        }
        
        return itemStack.getCount() - remaining;
    }

    /**
     * Adds an item to the main inventory, consolidating with existing stacks first, then filling empty slots.
     * Returns the number of items actually added.
     */
    private int addToMainInventory(ItemStack itemStack) {
        if (itemStack.isEmpty()) return 0;
        
        int remaining = itemStack.getCount();
        Item item = itemStack.getItem();
        String state = itemStack.getState();
        
        // First pass: consolidate with existing stacks
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE && remaining > 0; i++) {
            ItemStack slot = inventory.getMainInventorySlot(i);
            if (!slot.isEmpty() && slot.getItem().isSameType(item) 
                    && java.util.Objects.equals(slot.getState(), state)
                    && slot.getCount() < slot.getMaxStackSize()) {
                int canAdd = Math.min(remaining, slot.getMaxStackSize() - slot.getCount());
                slot.incrementCount(canAdd);
                remaining -= canAdd;
            }
        }
        
        // Second pass: fill empty slots
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE && remaining > 0; i++) {
            if (inventory.getMainInventorySlot(i).isEmpty()) {
                int canAdd = Math.min(remaining, itemStack.getMaxStackSize());
                inventory.setMainInventorySlot(i, new ItemStack(item, canAdd, state));
                remaining -= canAdd;
            }
        }
        
        return itemStack.getCount() - remaining;
    }

    public boolean tryDropOneToMainInventory(float mouseX, float mouseY,
                                            InventoryLayoutCalculator.InventoryLayout layout,
                                            InventoryDragDropHandler.DragState dragState) {
        // Calculate main inventory centering within the panel (same as rendering)
        int inventoryWidth = Inventory.MAIN_INVENTORY_COLS * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) - InventoryLayoutCalculator.getSlotPadding();
        int inventoryStartX = layout.panelStartX + (layout.inventoryPanelWidth - inventoryWidth) / 2;

        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            final int slotIndex = i; // Make effectively final for lambda
            int row = slotIndex / Inventory.MAIN_INVENTORY_COLS;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = inventoryStartX + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() +
                       row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                return attemptDropOneToSlot(inventory.getMainInventorySlot(slotIndex),
                                          (stack) -> inventory.setMainInventorySlot(slotIndex, stack),
                                          false, dragState);
            }
        }
        return false;
    }

    public boolean tryDropOneToHotbar(float mouseX, float mouseY,
                                     InventoryLayoutCalculator.InventoryLayout layout,
                                     InventoryDragDropHandler.DragState dragState) {
        // Calculate hotbar centering within the panel (same as rendering)
        int hotbarWidth = Inventory.HOTBAR_SIZE * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding()) - InventoryLayoutCalculator.getSlotPadding();
        int hotbarStartX = layout.panelStartX + (layout.inventoryPanelWidth - hotbarWidth) / 2;

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            final int slotIndex = i; // Make effectively final for lambda
            int slotX = hotbarStartX + slotIndex * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                return attemptDropOneToSlot(inventory.getHotbarSlot(slotIndex),
                                          (stack) -> inventory.setHotbarSlot(slotIndex, stack),
                                          false, dragState);
            }
        }
        return false;
    }

    public boolean tryDropOneToCraftingInput(float mouseX, float mouseY,
                                            InventoryLayoutCalculator.InventoryLayout layout,
                                            InventoryDragDropHandler.DragState dragState) {
        ItemStack[] craftingInputSlots = craftingManager.getCraftingInputSlots();
        int craftingGridSize = craftingManager.getCraftingGridSize();
        int craftingInputSlotsCount = craftingGridSize * craftingGridSize;

        for (int i = 0; i < craftingInputSlotsCount; i++) {
            final int slotIndex = i; // Make effectively final for lambda
            int r = slotIndex / craftingGridSize;
            int c = slotIndex % craftingGridSize;
            int slotX = layout.craftingElementsStartX +
                       c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY +
                       r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                return attemptDropOneToSlot(craftingInputSlots[slotIndex],
                                          (stack) -> craftingInputSlots[slotIndex] = stack,
                                          true, dragState);
            }
        }
        return false;
    }

    public boolean tryRightDragDepositToSlot(float mouseX, float mouseY,
                                              InventoryLayoutCalculator.InventoryLayout layout,
                                              InventoryDragDropHandler.DragState dragState,
                                              java.util.Set<Integer> visitedSlots) {
        if (!dragState.isDragging()) return false;

        // Main inventory (slot IDs 0..MAIN_INVENTORY_SIZE-1)
        int inventoryWidth = Inventory.MAIN_INVENTORY_COLS
                * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding())
                - InventoryLayoutCalculator.getSlotPadding();
        int inventoryStartX = layout.panelStartX + (layout.inventoryPanelWidth - inventoryWidth) / 2;

        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = inventoryStartX + col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding()
                      + row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                int slotId = i;
                if (visitedSlots.contains(slotId)) return false;
                final int idx = i;
                boolean placed = attemptDropOneToSlot(
                        inventory.getMainInventorySlot(idx),
                        stack -> inventory.setMainInventorySlot(idx, stack),
                        false, dragState);
                if (placed) visitedSlots.add(slotId);
                return placed;
            }
        }

        // Hotbar (slot IDs 27..35)
        int hotbarWidth = Inventory.HOTBAR_SIZE
                * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding())
                - InventoryLayoutCalculator.getSlotPadding();
        int hotbarStartX = layout.panelStartX + (layout.inventoryPanelWidth - hotbarWidth) / 2;

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int slotX = hotbarStartX + i * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;
            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                int slotId = Inventory.MAIN_INVENTORY_SIZE + i;
                if (visitedSlots.contains(slotId)) return false;
                final int idx = i;
                boolean placed = attemptDropOneToSlot(
                        inventory.getHotbarSlot(idx),
                        stack -> inventory.setHotbarSlot(idx, stack),
                        false, dragState);
                if (placed) visitedSlots.add(slotId);
                return placed;
            }
        }

        // Crafting input (slot IDs 36..39)
        ItemStack[] craftingInputSlots = craftingManager.getCraftingInputSlots();
        int craftingGridSize = craftingManager.getCraftingGridSize();
        int craftingInputSlotsCount = craftingGridSize * craftingGridSize;

        for (int i = 0; i < craftingInputSlotsCount; i++) {
            int r = i / craftingGridSize;
            int c = i % craftingGridSize;
            int slotX = layout.craftingElementsStartX
                      + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY
                      + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                int slotId = Inventory.MAIN_INVENTORY_SIZE + Inventory.HOTBAR_SIZE + i;
                if (visitedSlots.contains(slotId)) return false;
                final int idx = i;
                boolean placed = attemptDropOneToSlot(
                        craftingInputSlots[idx],
                        stack -> craftingInputSlots[idx] = stack,
                        true, dragState);
                if (placed) visitedSlots.add(slotId);
                return placed;
            }
        }

        return false;
    }

    public void gatherMatchingItemsToStack(InventoryDragDropHandler.DragState dragState) {
        if (dragState.draggedItemStack == null || dragState.draggedItemStack.isEmpty()) return;

        ItemStack dragged = dragState.draggedItemStack;
        int maxStack = dragged.getMaxStackSize();
        if (dragged.getCount() >= maxStack) return;

        Item draggedItem = dragged.getItem();
        String draggedState = dragged.getState();

        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE && dragged.getCount() < maxStack; i++) {
            ItemStack slot = inventory.getMainInventorySlot(i);
            if (!slot.isEmpty() && slot.getItem().isSameType(draggedItem)
                    && java.util.Objects.equals(slot.getState(), draggedState)) {
                int canTake = Math.min(slot.getCount(), maxStack - dragged.getCount());
                dragged.incrementCount(canTake);
                slot.decrementCount(canTake);
                if (slot.isEmpty()) {
                    inventory.setMainInventorySlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                }
            }
        }

        for (int i = 0; i < Inventory.HOTBAR_SIZE && dragged.getCount() < maxStack; i++) {
            ItemStack slot = inventory.getHotbarSlot(i);
            if (!slot.isEmpty() && slot.getItem().isSameType(draggedItem)
                    && java.util.Objects.equals(slot.getState(), draggedState)) {
                int canTake = Math.min(slot.getCount(), maxStack - dragged.getCount());
                dragged.incrementCount(canTake);
                slot.decrementCount(canTake);
                if (slot.isEmpty()) {
                    inventory.setHotbarSlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                }
            }
        }
    }

    private boolean isMouseOverSlot(float mouseX, float mouseY, int slotX, int slotY) {
        return mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() &&
               mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize();
    }

    private boolean attemptDropOneToSlot(ItemStack targetSlot,
                                        java.util.function.Consumer<ItemStack> slotSetter,
                                        boolean isCraftingSlot,
                                        InventoryDragDropHandler.DragState dragState) {
        if (targetSlot.isEmpty()) {
            ItemStack newItem = new ItemStack(dragState.draggedItemStack.getItem(), 1);
            slotSetter.accept(newItem);
            dragState.draggedItemStack.decrementCount(1);
            if (dragState.draggedItemStack.isEmpty()) {
                dragState.clear();
            }
            return true;
        } else if (targetSlot.canStackWith(dragState.draggedItemStack) &&
                   targetSlot.getCount() < targetSlot.getMaxStackSize()) {
            targetSlot.incrementCount(1);
            dragState.draggedItemStack.decrementCount(1);
            if (dragState.draggedItemStack.isEmpty()) {
                dragState.clear();
            }
            return true;
        }
        return false;
    }
}