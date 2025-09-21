package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.items.Inventory;
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

    public boolean tryPickUpFromCraftingOutput(float mouseX, float mouseY,
                                              InventoryLayoutCalculator.InventoryLayout layout,
                                              InventoryDragDropHandler.DragState dragState) {
        if (isMouseOverSlot(mouseX, mouseY, layout.outputSlotX, layout.outputSlotY)) {
            ItemStack craftingOutputSlot = craftingManager.getCraftingOutputSlot();
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                dragState.draggedItemStack = craftingOutputSlot.copy();
                craftingManager.setCraftingOutputSlot(new ItemStack(BlockType.AIR.getId(), 0));
                dragState.draggedItemOriginalSlotIndex = InventoryDragDropHandler.getCraftingOutputSlotIndex();
                dragState.dragSource = InventoryDragDropHandler.DragSource.NONE;
                return true;
            }
        }
        return false;
    }

    public boolean tryShiftClickCraftingOutput(float mouseX, float mouseY,
                                              InventoryLayoutCalculator.InventoryLayout layout) {
        if (isMouseOverSlot(mouseX, mouseY, layout.outputSlotX, layout.outputSlotY)) {
            ItemStack craftingOutputSlot = craftingManager.getCraftingOutputSlot();
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                ItemStack itemsInOutput = craftingOutputSlot.copy();

                boolean wasAdded = inventory.addItem(itemsInOutput);
                if (wasAdded) {
                    craftingManager.setCraftingOutputSlot(new ItemStack(BlockType.AIR.getId(), 0));
                    return true;
                } else {
                    // Inventory is full - drop the item
                    Player player = Game.getPlayer();
                    if (player != null) {
                        com.stonebreak.util.DropUtil.dropItemFromPlayer(player, itemsInOutput);
                        craftingManager.setCraftingOutputSlot(new ItemStack(BlockType.AIR.getId(), 0));
                        return true;
                    }
                }
            }
        }
        return false;
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