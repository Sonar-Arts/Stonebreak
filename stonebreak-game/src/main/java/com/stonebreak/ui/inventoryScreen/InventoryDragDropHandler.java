package com.stonebreak.ui.inventoryScreen;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import org.joml.Vector2f;

public class InventoryDragDropHandler {

    public enum DragSource { NONE, HOTBAR, MAIN_INVENTORY, CRAFTING_INPUT }

    private static final int CRAFTING_INPUT_SLOT_START_INDEX = 1000;
    private static final int CRAFTING_OUTPUT_SLOT_INDEX = 2000;

    public static int getCraftingInputSlotStartIndex() { return CRAFTING_INPUT_SLOT_START_INDEX; }
    public static int getCraftingOutputSlotIndex() { return CRAFTING_OUTPUT_SLOT_INDEX; }

    public static class DragState {
        public ItemStack draggedItemStack;
        public int draggedItemOriginalSlotIndex = -1;
        public DragSource dragSource = DragSource.NONE;

        public boolean isDragging() {
            return draggedItemStack != null && !draggedItemStack.isEmpty();
        }

        public void clear() {
            draggedItemStack = null;
            draggedItemOriginalSlotIndex = -1;
            dragSource = DragSource.NONE;
        }
    }

    public static boolean placeDraggedItem(DragState dragState, Inventory inventory, ItemStack[] craftingInputSlots,
                                         Vector2f mousePos, int screenWidth, int screenHeight,
                                         Runnable updateCraftingOutput) {
        if (!dragState.isDragging()) return false;

        InventoryLayoutCalculator.InventoryLayout layout = InventoryLayoutCalculator.calculateLayout(screenWidth, screenHeight);
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
            placed = tryPlaceInCraftingSlots(dragState, craftingInputSlots, layout, mouseX, mouseY, updateCraftingOutput);
        }

        // Handle placement results
        if (placed && !dragState.isDragging()) {
            dragState.clear();
        } else if (!placed && dragState.isDragging()) {
            if (isMouseOutsideInventoryBounds(mouseX, mouseY, layout)) {
                dropEntireStackIntoWorld(dragState);
            } else {
                tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput);
            }
        } else if (placed && dragState.isDragging()) {
            // Swap happened, try to place the new dragged item back
            tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput);
        }

        // Final cleanup
        if (!dragState.isDragging()) {
            dragState.clear();
        }

        return placed;
    }

    private static boolean tryPlaceInMainInventory(DragState dragState, Inventory inventory,
                                                 InventoryLayoutCalculator.InventoryLayout layout,
                                                 float mouseX, float mouseY) {
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            final int slotIndex = i; // Make effectively final for lambda
            int row = slotIndex / Inventory.MAIN_INVENTORY_COLS;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() +
                       row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                return handleSlotPlacement(dragState, inventory.getMainInventorySlot(slotIndex),
                                         (stack) -> inventory.setMainInventorySlot(slotIndex, stack), slotIndex, inventory);
            }
        }
        return false;
    }

    private static boolean tryPlaceInHotbar(DragState dragState, Inventory inventory,
                                          InventoryLayoutCalculator.InventoryLayout layout,
                                          float mouseX, float mouseY) {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            final int slotIndex = i; // Make effectively final for lambda
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                return handleSlotPlacement(dragState, inventory.getHotbarSlot(slotIndex),
                                         (stack) -> inventory.setHotbarSlot(slotIndex, stack), slotIndex, inventory);
            }
        }
        return false;
    }

    private static boolean tryPlaceInCraftingSlots(DragState dragState, ItemStack[] craftingInputSlots,
                                                 InventoryLayoutCalculator.InventoryLayout layout,
                                                 float mouseX, float mouseY, Runnable updateCraftingOutput) {
        for (int i = 0; i < InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getCraftingGridSize(); i++) {
            int r = i / InventoryLayoutCalculator.getCraftingGridSize();
            int c = i % InventoryLayoutCalculator.getCraftingGridSize();
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

    private static boolean handleSlotPlacement(DragState dragState, ItemStack targetStack,
                                             java.util.function.Consumer<ItemStack> slotSetter, int slotIndex, Inventory inventory) {
        if (targetStack.isEmpty()) {
            slotSetter.accept(dragState.draggedItemStack);
            dragState.draggedItemStack = null;
            return true;
        } else if (targetStack.canStackWith(dragState.draggedItemStack)) {
            int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
            int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
            targetStack.incrementCount(toAdd);
            dragState.draggedItemStack.decrementCount(toAdd);
            if (dragState.draggedItemStack.isEmpty()) {
                dragState.draggedItemStack = null;
                return true;
            }
        } else {
            // Swap logic - now we have inventory access
            return handleSwap(dragState, targetStack, slotSetter, inventory);
        }
        return false;
    }

    private static boolean handleSwap(DragState dragState, ItemStack targetStack,
                                    java.util.function.Consumer<ItemStack> slotSetter, Inventory inventory) {
        if (targetStack.getBlockTypeId() != dragState.draggedItemStack.getBlockTypeId()) {
            ItemStack itemFromTargetSlot = targetStack.copy();
            ItemStack originalDraggedItem = dragState.draggedItemStack.copy();

            // Place dragged item in target slot
            slotSetter.accept(originalDraggedItem);

            // Place target item back in original slot
            switch (dragState.dragSource) {
                case HOTBAR -> inventory.setHotbarSlot(dragState.draggedItemOriginalSlotIndex, itemFromTargetSlot);
                case MAIN_INVENTORY -> inventory.setMainInventorySlot(dragState.draggedItemOriginalSlotIndex, itemFromTargetSlot);
                // Note: CRAFTING_INPUT swaps are handled differently in handleCraftingSlotPlacement
            }

            // Clear drag state since swap is complete
            dragState.clear();
            return true;
        }
        return false;
    }

    private static boolean handleCraftingSlotPlacement(DragState dragState, ItemStack[] craftingInputSlots, int slotIndex) {
        ItemStack targetStack = craftingInputSlots[slotIndex];
        if (targetStack.isEmpty()) {
            craftingInputSlots[slotIndex] = dragState.draggedItemStack;
            dragState.draggedItemStack = null;
            return true;
        } else if (targetStack.canStackWith(dragState.draggedItemStack)) {
            int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
            int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
            targetStack.incrementCount(toAdd);
            dragState.draggedItemStack.decrementCount(toAdd);
            if (dragState.draggedItemStack.isEmpty()) {
                dragState.draggedItemStack = null;
                return true;
            }
        } else {
            // Simplified swap for crafting slots
            ItemStack itemFromTargetSlot = targetStack.copy();
            craftingInputSlots[slotIndex] = dragState.draggedItemStack;
            dragState.draggedItemStack = itemFromTargetSlot;
            dragState.dragSource = DragSource.CRAFTING_INPUT;
            dragState.draggedItemOriginalSlotIndex = CRAFTING_INPUT_SLOT_START_INDEX + slotIndex;
            return true;
        }
        return false;
    }

    public static void tryReturnToOriginalSlot(DragState dragState, Inventory inventory,
                                             ItemStack[] craftingInputSlots, Runnable updateCraftingOutput) {
        if (!dragState.isDragging() || dragState.draggedItemOriginalSlotIndex == -1) {
            if (dragState.draggedItemStack != null) dragState.draggedItemStack = null;
            dragState.dragSource = DragSource.NONE;
            return;
        }

        ItemStack originalSlotItemStack;
        switch (dragState.dragSource) {
            case HOTBAR -> {
                originalSlotItemStack = inventory.getHotbarSlot(dragState.draggedItemOriginalSlotIndex);
                if (tryStackOrPlace(dragState, originalSlotItemStack,
                                   (stack) -> inventory.setHotbarSlot(dragState.draggedItemOriginalSlotIndex, stack))) {
                    dragState.clear();
                }
            }
            case MAIN_INVENTORY -> {
                originalSlotItemStack = inventory.getMainInventorySlot(dragState.draggedItemOriginalSlotIndex);
                if (tryStackOrPlace(dragState, originalSlotItemStack,
                                   (stack) -> inventory.setMainInventorySlot(dragState.draggedItemOriginalSlotIndex, stack))) {
                    dragState.clear();
                }
            }
            case CRAFTING_INPUT -> {
                int craftingSlotIndex = dragState.draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX;
                if (craftingSlotIndex >= 0 && craftingSlotIndex < craftingInputSlots.length) {
                    originalSlotItemStack = craftingInputSlots[craftingSlotIndex];
                    if (tryStackOrPlace(dragState, originalSlotItemStack,
                                       (stack) -> craftingInputSlots[craftingSlotIndex] = stack)) {
                        updateCraftingOutput.run();
                        dragState.clear();
                    }
                }
            }
            case NONE -> {
                // Item from crafting output, can't return
            }
        }

        if (dragState.draggedItemStack != null && dragState.draggedItemStack.isEmpty()) {
            dragState.clear();
        }
    }

    private static boolean tryStackOrPlace(DragState dragState, ItemStack targetSlot,
                                         java.util.function.Consumer<ItemStack> slotSetter) {
        if (targetSlot.isEmpty()) {
            slotSetter.accept(dragState.draggedItemStack);
            dragState.draggedItemStack = null;
            return true;
        } else if (targetSlot.canStackWith(dragState.draggedItemStack)) {
            int canAdd = targetSlot.getMaxStackSize() - targetSlot.getCount();
            int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
            targetSlot.incrementCount(toAdd);
            dragState.draggedItemStack.decrementCount(toAdd);
            if (dragState.draggedItemStack.isEmpty()) {
                dragState.draggedItemStack = null;
                return true;
            }
        }
        return false;
    }

    public static void dropEntireStackIntoWorld(DragState dragState) {
        if (!dragState.isDragging()) {
            dragState.clear();
            return;
        }

        Player player = Game.getPlayer();
        if (player != null) {
            com.stonebreak.util.DropUtil.dropItemFromPlayer(player, dragState.draggedItemStack.copy());
        }

        dragState.clear();
    }

    private static boolean isMouseOverSlot(float mouseX, float mouseY, int slotX, int slotY) {
        return mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() &&
               mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize();
    }

    private static boolean isMouseOutsideInventoryBounds(float mouseX, float mouseY,
                                                       InventoryLayoutCalculator.InventoryLayout layout) {
        return mouseX < layout.panelStartX || mouseX > layout.panelStartX + layout.inventoryPanelWidth ||
               mouseY < layout.panelStartY || mouseY > layout.panelStartY + layout.inventoryPanelHeight;
    }
}