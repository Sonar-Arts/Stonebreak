package com.stonebreak.ui.inventoryScreen.handlers;

import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import org.joml.Vector2f;

/**
 * Specialized drag and drop handler for workbench screens.
 * Extends InventoryDragDropHandler functionality to work with 3x3 crafting grids.
 * Follows Single Responsibility Principle by handling only workbench drag/drop operations.
 */
public class WorkbenchDragDropHandler {

    /**
     * Places a dragged item in the workbench using the provided layout.
     * This version uses the layout parameter instead of calculating it internally.
     */
    public static boolean placeDraggedItem(InventoryDragDropHandler.DragState dragState,
                                         Inventory inventory,
                                         ItemStack[] craftingInputSlots,
                                         Vector2f mousePos,
                                         InventoryLayoutCalculator.InventoryLayout layout,
                                         Runnable updateCraftingOutput) {
        if (!dragState.isDragging()) return false;

        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        boolean placed = false;

        // Try to place in main inventory
        placed = tryPlaceInMainInventory(dragState, inventory, layout, mouseX, mouseY);

        // Try to place in hotbar if not placed
        if (!placed) {
            placed = tryPlaceInHotbar(dragState, inventory, layout, mouseX, mouseY);
        }

        // Try to place in crafting input slots if not placed
        if (!placed) {
            placed = tryPlaceInWorkbenchCraftingSlots(dragState, craftingInputSlots, layout, mouseX, mouseY, updateCraftingOutput);
        }

        // Handle placement results
        if (placed && !dragState.isDragging()) {
            dragState.clear();
        } else if (!placed && dragState.isDragging()) {
            if (isMouseOutsideInventoryBounds(mouseX, mouseY, layout)) {
                dropEntireStackIntoWorld(dragState);
            } else {
                tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, layout, updateCraftingOutput);
            }
        } else if (placed && dragState.isDragging()) {
            // Swap happened, try to place the new dragged item back
            tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, layout, updateCraftingOutput);
        }

        // Final cleanup
        if (!dragState.isDragging()) {
            dragState.clear();
        }

        return placed;
    }

    /**
     * Attempts to return dragged item to its original slot using workbench layout.
     */
    public static void tryReturnToOriginalSlot(InventoryDragDropHandler.DragState dragState,
                                             Inventory inventory,
                                             ItemStack[] craftingInputSlots,
                                             InventoryLayoutCalculator.InventoryLayout layout,
                                             Runnable updateCraftingOutput) {
        // Delegate to the main handler with the correct layout - reuse the main inventory/hotbar logic
        // but override the crafting slot handling
        InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput);
    }

    /**
     * Drops the entire stack into the world.
     */
    public static void dropEntireStackIntoWorld(InventoryDragDropHandler.DragState dragState) {
        InventoryDragDropHandler.dropEntireStackIntoWorld(dragState);
    }

    // Private helper methods that work with the workbench 3x3 grid

    private static boolean tryPlaceInMainInventory(InventoryDragDropHandler.DragState dragState,
                                                 Inventory inventory,
                                                 InventoryLayoutCalculator.InventoryLayout layout,
                                                 float mouseX, float mouseY) {
        // Reuse the main inventory placement logic
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            final int slotIndex = i;
            int row = slotIndex / Inventory.MAIN_INVENTORY_COLS;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() +
                       row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                return handleMainInventorySlotPlacement(dragState, inventory, slotIndex);
            }
        }
        return false;
    }

    private static boolean tryPlaceInHotbar(InventoryDragDropHandler.DragState dragState,
                                          Inventory inventory,
                                          InventoryLayoutCalculator.InventoryLayout layout,
                                          float mouseX, float mouseY) {
        // Reuse the hotbar placement logic
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            final int slotIndex = i;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                return handleHotbarSlotPlacement(dragState, inventory, slotIndex);
            }
        }
        return false;
    }

    private static boolean tryPlaceInWorkbenchCraftingSlots(InventoryDragDropHandler.DragState dragState,
                                                          ItemStack[] craftingInputSlots,
                                                          InventoryLayoutCalculator.InventoryLayout layout,
                                                          float mouseX, float mouseY,
                                                          Runnable updateCraftingOutput) {
        // Use the workbench 3x3 grid size
        int gridSize = InventoryLayoutCalculator.getWorkbenchCraftingGridSize();
        int totalSlots = gridSize * gridSize;

        for (int i = 0; i < totalSlots; i++) {
            int r = i / gridSize;
            int c = i % gridSize;
            int slotX = layout.craftingElementsStartX + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                boolean placed = handleCraftingSlotPlacement(dragState, craftingInputSlots, i);
                if (placed) updateCraftingOutput.run();
                return placed;
            }
        }
        return false;
    }

    private static boolean isMouseOverSlot(float mouseX, float mouseY, int slotX, int slotY) {
        return mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() &&
               mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize();
    }

    private static boolean isMouseOutsideInventoryBounds(float mouseX, float mouseY, InventoryLayoutCalculator.InventoryLayout layout) {
        return mouseX < layout.panelStartX || mouseX > layout.panelStartX + layout.inventoryPanelWidth ||
               mouseY < layout.panelStartY || mouseY > layout.panelStartY + layout.inventoryPanelHeight;
    }

    private static boolean handleMainInventorySlotPlacement(InventoryDragDropHandler.DragState dragState, Inventory inventory, int slotIndex) {
        ItemStack currentSlot = inventory.getMainInventorySlot(slotIndex);
        return handleSlotPlacement(dragState, currentSlot, (stack) -> inventory.setMainInventorySlot(slotIndex, stack));
    }

    private static boolean handleHotbarSlotPlacement(InventoryDragDropHandler.DragState dragState, Inventory inventory, int slotIndex) {
        ItemStack currentSlot = inventory.getHotbarSlot(slotIndex);
        return handleSlotPlacement(dragState, currentSlot, (stack) -> inventory.setHotbarSlot(slotIndex, stack));
    }

    private static boolean handleCraftingSlotPlacement(InventoryDragDropHandler.DragState dragState, ItemStack[] craftingInputSlots, int slotIndex) {
        ItemStack currentSlot = craftingInputSlots[slotIndex];
        return handleSlotPlacement(dragState, currentSlot, (stack) -> craftingInputSlots[slotIndex] = stack);
    }

    private static boolean handleSlotPlacement(InventoryDragDropHandler.DragState dragState, ItemStack currentSlot, java.util.function.Consumer<ItemStack> slotSetter) {
        if (currentSlot.isEmpty()) {
            // Place entire stack in empty slot
            slotSetter.accept(dragState.draggedItemStack);
            dragState.clear();
            return true;
        } else if (currentSlot.canStackWith(dragState.draggedItemStack)) {
            // Stack compatible items
            int spaceAvailable = currentSlot.getMaxStackSize() - currentSlot.getCount();
            int amountToAdd = Math.min(spaceAvailable, dragState.draggedItemStack.getCount());

            if (amountToAdd > 0) {
                currentSlot.incrementCount(amountToAdd);
                dragState.draggedItemStack.decrementCount(amountToAdd);

                if (dragState.draggedItemStack.isEmpty()) {
                    dragState.clear();
                }
                return true;
            }
        } else {
            // Swap items
            ItemStack temp = currentSlot.copy();
            slotSetter.accept(dragState.draggedItemStack);
            dragState.draggedItemStack = temp;
            return true;
        }
        return false;
    }
}