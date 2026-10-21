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
        placed = tryPlaceInMainInventory(dragState, inventory, craftingInputSlots, layout, mouseX, mouseY,
                updateCraftingOutput, sink, craftGridRegion);

        // Try to place in hotbar if not placed
        if (!placed) {
            placed = tryPlaceInHotbar(dragState, inventory, craftingInputSlots, layout, mouseX, mouseY,
                    updateCraftingOutput, sink, craftGridRegion);
        }

        // Try to place in crafting input slots if not placed
        if (!placed) {
            placed = tryPlaceInWorkbenchCraftingSlots(dragState, inventory, craftingInputSlots, layout, mouseX, mouseY,
                    updateCraftingOutput, sink, craftGridRegion);
        }

        // Handle placement results. What a placement leaves on the cursor (a partial merge's
        // remainder, a swap's displaced stack whose origin is occupied) stays there, as the
        // server's MERGE / SWAP leave it carried (issue #334).
        if (placed && !dragState.isDragging()) {
            dragState.clear();
        } else if (!placed && dragState.isDragging()) {
            if (isMouseOutsideInventoryBounds(mouseX, mouseY, layout)) {
                InventoryDragDropHandler.tossCarried(dragState, sink);
            } else {
                tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, layout, updateCraftingOutput, sink,
                        craftGridRegion);
            }
        }

        // Final cleanup
        if (!dragState.isDragging()) {
            dragState.clear();
        }

        return placed;
    }

    /**
     * Return-to-origin with emission (see InventoryDragDropHandler's sink overload); a stack
     * taken from the 3×3 grid goes back to its {@code craftGridRegion} cell and is sent too.
     */
    public static void tryReturnToOriginalSlot(InventoryDragDropHandler.DragState dragState,
                                             Inventory inventory, ItemStack[] craftingInputSlots,
                                             InventoryLayoutCalculator.InventoryLayout layout,
                                             Runnable updateCraftingOutput, SlotActionSink sink,
                                             byte craftGridRegion) {
        InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots,
                updateCraftingOutput, sink, craftGridRegion);
    }

    /** As above, for the workbench grid region. */
    public static void tryReturnToOriginalSlot(InventoryDragDropHandler.DragState dragState,
                                             Inventory inventory, ItemStack[] craftingInputSlots,
                                             InventoryLayoutCalculator.InventoryLayout layout,
                                             Runnable updateCraftingOutput, SlotActionSink sink) {
        tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, layout, updateCraftingOutput, sink,
                com.stonebreak.network.packet.player.ContainerActionC2S.Region.WORKBENCH_GRID);
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
                                                 Inventory inventory, ItemStack[] craftingInputSlots,
                                                 InventoryLayoutCalculator.InventoryLayout layout,
                                                 float mouseX, float mouseY, Runnable updateCraftingOutput,
                                                 SlotActionSink sink, byte craftGridRegion) {
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            final int slotIndex = i;
            int row = slotIndex / Inventory.MAIN_INVENTORY_COLS;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.inventorySectionStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() +
                       row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                ItemStack target = inventory.getMainInventorySlot(slotIndex);
                // Captured before the placement mutates the drag state (issue #334). Swap
                // origins are player slots only: a grid-sourced drag sends NONE.
                byte action = InventoryDragDropHandler.placementAction(dragState, target);
                byte originRegion = InventoryDragDropHandler.originRegionOf(dragState, sink);
                byte originSlot = InventoryDragDropHandler.originSlotOf(dragState);
                boolean placed = InventoryDragDropHandler.handleSlotPlacement(dragState, target,
                        (stack) -> inventory.setMainInventorySlot(slotIndex, stack),
                        inventory, craftingInputSlots, updateCraftingOutput);
                if (placed && sink != null) {
                    sink.send(com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_MAIN,
                            (byte) slotIndex, originRegion, originSlot, action, false);
                }
                return placed;
            }
        }
        return false;
    }

    private static boolean tryPlaceInHotbar(InventoryDragDropHandler.DragState dragState,
                                          Inventory inventory, ItemStack[] craftingInputSlots,
                                          InventoryLayoutCalculator.InventoryLayout layout,
                                          float mouseX, float mouseY, Runnable updateCraftingOutput,
                                          SlotActionSink sink, byte craftGridRegion) {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            final int slotIndex = i;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.inventorySectionStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                ItemStack target = inventory.getHotbarSlot(slotIndex);
                byte action = InventoryDragDropHandler.placementAction(dragState, target);
                byte originRegion = InventoryDragDropHandler.originRegionOf(dragState, sink);
                byte originSlot = InventoryDragDropHandler.originSlotOf(dragState);
                boolean placed = InventoryDragDropHandler.handleSlotPlacement(dragState, target,
                        (stack) -> inventory.setHotbarSlot(slotIndex, stack),
                        inventory, craftingInputSlots, updateCraftingOutput);
                if (placed && sink != null) {
                    sink.send(com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_HOTBAR,
                            (byte) slotIndex, originRegion, originSlot, action, false);
                }
                return placed;
            }
        }
        return false;
    }

    private static boolean tryPlaceInWorkbenchCraftingSlots(InventoryDragDropHandler.DragState dragState,
                                                          Inventory inventory, ItemStack[] craftingInputSlots,
                                                          InventoryLayoutCalculator.InventoryLayout layout,
                                                          float mouseX, float mouseY,
                                                          Runnable updateCraftingOutput,
                                                          SlotActionSink sink, byte craftGridRegion) {
        // Use the workbench 3x3 grid size
        int gridSize = InventoryLayoutCalculator.getWorkbenchCraftingGridSize();
        int totalSlots = gridSize * gridSize;

        for (int i = 0; i < totalSlots; i++) {
            final int slotIndex = i;
            int r = i / gridSize;
            int c = i % gridSize;
            int slotX = layout.craftingElementsStartX + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                byte action = InventoryDragDropHandler.placementAction(dragState, craftingInputSlots[i]);
                byte originRegion = InventoryDragDropHandler.originRegionOf(dragState, sink);
                byte originSlot = InventoryDragDropHandler.originSlotOf(dragState);
                boolean placed = InventoryDragDropHandler.handleSlotPlacement(dragState, craftingInputSlots[i],
                        (stack) -> craftingInputSlots[slotIndex] = stack,
                        inventory, craftingInputSlots, updateCraftingOutput);
                if (placed) {
                    if (updateCraftingOutput != null) {
                        updateCraftingOutput.run();
                    }
                    if (sink != null) {
                        sink.send(craftGridRegion, (byte) i, originRegion, originSlot, action, false);
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
}
