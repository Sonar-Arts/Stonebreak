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
        return placeDraggedItem(dragState, inventory, craftingInputSlots, mousePos, layout,
                updateCraftingOutput, null,
                com.stonebreak.network.packet.player.ContainerActionC2S.Region.WORKBENCH_GRID);
    }

    /**
     * Server-authoritative variant (issue #334): every placement outcome also emits the
     * matching atomic {@code ContainerActionC2S} through {@code sink} (null = tests).
     */
    public static boolean placeDraggedItem(InventoryDragDropHandler.DragState dragState,
                                         Inventory inventory,
                                         ItemStack[] craftingInputSlots,
                                         Vector2f mousePos,
                                         InventoryLayoutCalculator.InventoryLayout layout,
                                         Runnable updateCraftingOutput,
                                         SlotActionSink sink,
                                         byte craftGridRegion) {
        if (!dragState.isDragging()) return false;

        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        boolean placed = false;

        // Try to place in main inventory
        placed = tryPlaceInMainInventory(dragState, inventory, layout, mouseX, mouseY, sink);

        // Try to place in hotbar if not placed
        if (!placed) {
            placed = tryPlaceInHotbar(dragState, inventory, layout, mouseX, mouseY, sink);
        }

        // Try to place in crafting input slots if not placed
        if (!placed) {
            placed = tryPlaceInWorkbenchCraftingSlots(dragState, craftingInputSlots, layout, mouseX, mouseY,
                    updateCraftingOutput, sink, craftGridRegion);
        }

        // Handle placement results
        if (placed && !dragState.isDragging()) {
            dragState.clear();
        } else if (!placed && dragState.isDragging()) {
            if (isMouseOutsideInventoryBounds(mouseX, mouseY, layout)) {
                if (sink != null) {
                    short n = (short) dragState.draggedItemStack.getCount();
                    dragState.clear();
                    sink.sendWithCount(com.stonebreak.network.packet.player.ContainerActionC2S.Action.DROP_CARRIED, n);
                } else {
                    dropEntireStackIntoWorld(dragState);
                }
            } else {
                tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, layout, updateCraftingOutput, sink);
            }
        } else if (placed && dragState.isDragging()) {
            // Swap happened, try to place the new dragged item back
            tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, layout, updateCraftingOutput, sink);
        }

        // Final cleanup
        if (!dragState.isDragging()) {
            dragState.clear();
        }

        return placed;
    }

    /** Return-to-origin with emission (see InventoryDragDropHandler's sink overload). */
    public static void tryReturnToOriginalSlot(InventoryDragDropHandler.DragState dragState,
                                             Inventory inventory, ItemStack[] craftingInputSlots,
                                             InventoryLayoutCalculator.InventoryLayout layout,
                                             Runnable updateCraftingOutput, SlotActionSink sink) {
        byte originRegion = InventoryDragDropHandler.originRegionOf(dragState, sink);
        byte originSlot = InventoryDragDropHandler.originSlotOf(dragState);
        ItemStack carried = dragState.draggedItemStack;
        ItemStack originStack = null;
        if (originRegion == com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_HOTBAR) {
            originStack = inventory.getHotbarSlot(dragState.draggedItemOriginalSlotIndex);
        } else if (originRegion == com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_MAIN) {
            originStack = inventory.getMainInventorySlot(dragState.draggedItemOriginalSlotIndex);
        }
        byte action = (originStack == null || originStack.isEmpty())
                ? com.stonebreak.network.packet.player.ContainerActionC2S.Action.PUT_ALL
                : com.stonebreak.network.packet.player.ContainerActionC2S.Action.MERGE;
        InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput);
        if (sink != null && originRegion != com.stonebreak.network.packet.player.ContainerActionC2S.Region.NONE
                && carried != null && !carried.isEmpty()) {
            sink.send(originRegion, originSlot, action);
        }
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
                                                 float mouseX, float mouseY, SlotActionSink sink) {
        // Reuse the main inventory placement logic
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            final int slotIndex = i;
            int row = slotIndex / Inventory.MAIN_INVENTORY_COLS;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.inventorySectionStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() +
                       row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                byte action = InventoryDragDropHandler.placementAction(dragState,
                        inventory.getMainInventorySlot(slotIndex));
                boolean placed = handleMainInventorySlotPlacement(dragState, inventory, slotIndex);
                if (placed && sink != null) {
                    sink.send(com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_MAIN,
                            (byte) slotIndex, InventoryDragDropHandler.originRegionOf(dragState, sink),
                            InventoryDragDropHandler.originSlotOf(dragState), action, false);
                }
                return placed;
            }
        }
        return false;
    }

    private static boolean tryPlaceInHotbar(InventoryDragDropHandler.DragState dragState,
                                          Inventory inventory,
                                          InventoryLayoutCalculator.InventoryLayout layout,
                                          float mouseX, float mouseY, SlotActionSink sink) {
        // Reuse the hotbar placement logic
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            final int slotIndex = i;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.inventorySectionStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                byte action = InventoryDragDropHandler.placementAction(dragState,
                        inventory.getHotbarSlot(slotIndex));
                boolean placed = handleHotbarSlotPlacement(dragState, inventory, slotIndex);
                if (placed && sink != null) {
                    sink.send(com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_HOTBAR,
                            (byte) slotIndex, InventoryDragDropHandler.originRegionOf(dragState, sink),
                            InventoryDragDropHandler.originSlotOf(dragState), action, false);
                }
                return placed;
            }
        }
        return false;
    }

    private static boolean tryPlaceInWorkbenchCraftingSlots(InventoryDragDropHandler.DragState dragState,
                                                          ItemStack[] craftingInputSlots,
                                                          InventoryLayoutCalculator.InventoryLayout layout,
                                                          float mouseX, float mouseY,
                                                          Runnable updateCraftingOutput,
                                                          SlotActionSink sink, byte craftGridRegion) {
        // Use the workbench 3x3 grid size
        int gridSize = InventoryLayoutCalculator.getWorkbenchCraftingGridSize();
        int totalSlots = gridSize * gridSize;

        for (int i = 0; i < totalSlots; i++) {
            int r = i / gridSize;
            int c = i % gridSize;
            int slotX = layout.craftingElementsStartX + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                byte action = InventoryDragDropHandler.placementAction(dragState, craftingInputSlots[i]);
                boolean placed = handleCraftingSlotPlacement(dragState, craftingInputSlots, i);
                if (placed) {
                    updateCraftingOutput.run();
                    if (sink != null) {
                        sink.send(craftGridRegion, (byte) i, InventoryDragDropHandler.originRegionOf(dragState, sink),
                                InventoryDragDropHandler.originSlotOf(dragState), action, false);
                    }
                }
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