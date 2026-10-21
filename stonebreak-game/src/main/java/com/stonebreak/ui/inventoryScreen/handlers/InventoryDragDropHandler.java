package com.stonebreak.ui.inventoryScreen.handlers;

import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
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
        return placeDraggedItem(dragState, inventory, craftingInputSlots, mousePos,
                InventoryLayoutCalculator.calculateLayout(screenWidth, screenHeight), updateCraftingOutput);
    }

    /**
     * Places the held stack against an explicit {@code layout} — the one the screen hit-tests its
     * clicks against, so placement and pick-up always agree on where each slot is.
     */
    public static boolean placeDraggedItem(DragState dragState, Inventory inventory, ItemStack[] craftingInputSlots,
                                         Vector2f mousePos, InventoryLayoutCalculator.InventoryLayout layout,
                                         Runnable updateCraftingOutput) {
        return placeDraggedItem(dragState, inventory, craftingInputSlots, mousePos, layout,
                isMouseOutsideInventoryBounds(mousePos.x, mousePos.y, layout), updateCraftingOutput);
    }

    /**
     * As above, with the caller deciding whether the pointer is beyond the screen's panel: a stack
     * that lands on no slot is dropped into the world only then, and otherwise goes back to where it
     * came from. The inventory screen's panel is wider than the slot layout (its side columns and
     * tab strip), so it cannot be derived from {@code layout}.
     */
    public static boolean placeDraggedItem(DragState dragState, Inventory inventory, ItemStack[] craftingInputSlots,
                                         Vector2f mousePos, InventoryLayoutCalculator.InventoryLayout layout,
                                         boolean outsidePanel, Runnable updateCraftingOutput) {
        return placeDraggedItem(dragState, inventory, craftingInputSlots, mousePos, layout,
                outsidePanel, updateCraftingOutput, null,
                com.stonebreak.network.packet.player.ContainerActionC2S.Region.INV_CRAFT_GRID);
    }

    /**
     * As above, for a server-authoritative screen (issue #334): every placement outcome also
     * emits the matching atomic {@code ContainerActionC2S} through {@code sink} (null = no
     * session semantics / tests). {@code craftGridRegion} maps the crafting grid (inventory
     * 2×2 vs workbench 3×3).
     */
    public static boolean placeDraggedItem(DragState dragState, Inventory inventory, ItemStack[] craftingInputSlots,
                                         Vector2f mousePos, InventoryLayoutCalculator.InventoryLayout layout,
                                         boolean outsidePanel, Runnable updateCraftingOutput,
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
            placed = tryPlaceInCraftingSlots(dragState, craftingInputSlots, layout, mouseX, mouseY,
                    updateCraftingOutput, sink, craftGridRegion);
        }

        // Handle placement results
        if (placed && !dragState.isDragging()) {
            dragState.clear();
        } else if (!placed && dragState.isDragging()) {
            if (outsidePanel) {
                if (sink != null) {
                    // Server toss: the carried stack lives in the server replica's cursor,
                    // not in the inventory mirror — DROP_CARRIED is the debit path.
                    short n = (short) dragState.draggedItemStack.getCount();
                    dragState.clear();
                    sink.sendWithCount(com.stonebreak.network.packet.player.ContainerActionC2S.Action.DROP_CARRIED, n);
                } else {
                    dropEntireStackIntoWorld(dragState);
                }
            } else {
                tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput, sink);
            }
        } else if (placed && dragState.isDragging()) {
            // Swap happened, try to place the new dragged item back
            tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput, sink);
        }

        // Final cleanup
        if (!dragState.isDragging()) {
            dragState.clear();
        }

        return placed;
    }

    private static boolean tryPlaceInMainInventory(DragState dragState, Inventory inventory,
                                                 InventoryLayoutCalculator.InventoryLayout layout,
                                                 float mouseX, float mouseY, SlotActionSink sink) {
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            final int slotIndex = i; // Make effectively final for lambda
            int row = slotIndex / Inventory.MAIN_INVENTORY_COLS;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.inventorySectionStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() +
                       row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                ItemStack target = inventory.getMainInventorySlot(slotIndex);
                byte action = placementAction(dragState, target);
                boolean placed = handleSlotPlacement(dragState, target,
                                         (stack) -> inventory.setMainInventorySlot(slotIndex, stack), slotIndex, inventory);
                if (placed && sink != null) {
                    sink.send(com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_MAIN,
                            (byte) slotIndex, originRegionOf(dragState, sink), originSlotOf(dragState), action, false);
                }
                return placed;
            }
        }
        return false;
    }

    private static boolean tryPlaceInHotbar(DragState dragState, Inventory inventory,
                                          InventoryLayoutCalculator.InventoryLayout layout,
                                          float mouseX, float mouseY, SlotActionSink sink) {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            final int slotIndex = i; // Make effectively final for lambda
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.inventorySectionStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                ItemStack target = inventory.getHotbarSlot(slotIndex);
                byte action = placementAction(dragState, target);
                boolean placed = handleSlotPlacement(dragState, target,
                                         (stack) -> inventory.setHotbarSlot(slotIndex, stack), slotIndex, inventory);
                if (placed && sink != null) {
                    sink.send(com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_HOTBAR,
                            (byte) slotIndex, originRegionOf(dragState, sink), originSlotOf(dragState), action, false);
                }
                return placed;
            }
        }
        return false;
    }

    private static boolean tryPlaceInCraftingSlots(DragState dragState, ItemStack[] craftingInputSlots,
                                                 InventoryLayoutCalculator.InventoryLayout layout,
                                                 float mouseX, float mouseY, Runnable updateCraftingOutput,
                                                 SlotActionSink sink, byte craftGridRegion) {
        for (int i = 0; i < InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getCraftingGridSize(); i++) {
            int r = i / InventoryLayoutCalculator.getCraftingGridSize();
            int c = i % InventoryLayoutCalculator.getCraftingGridSize();
            int slotX = layout.craftingElementsStartX + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                byte action = placementAction(dragState, craftingInputSlots[i]);
                boolean placed = handleCraftingSlotPlacement(dragState, craftingInputSlots, i);
                if (placed) {
                    updateCraftingOutput.run();
                    if (sink != null) {
                        sink.send(craftGridRegion, (byte) i, originRegionOf(dragState, sink),
                                originSlotOf(dragState), action, false);
                    }
                }
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
        tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput, null);
    }

    /**
     * Return-to-origin with a server sink (issue #334): the local return is the prediction;
     * the server replays it as PUT_ALL (empty origin) or MERGE (stackable origin) against
     * the replica's carried stack.
     */
    public static void tryReturnToOriginalSlot(DragState dragState, Inventory inventory,
                                             ItemStack[] craftingInputSlots, Runnable updateCraftingOutput,
                                             SlotActionSink sink) {
        byte originRegion = originRegionOf(dragState, sink);
        byte originSlot = originSlotOf(dragState);
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
        returnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput);
        if (sink != null && originRegion != com.stonebreak.network.packet.player.ContainerActionC2S.Region.NONE
                && carried != null && !carried.isEmpty()) {
            sink.send(originRegion, originSlot, action);
        }
    }

    private static void returnToOriginalSlot(DragState dragState, Inventory inventory,
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

        worldDrop.accept(dragState.draggedItemStack.copy());
        dragState.clear();
    }

    /** Where a dropped stack goes: in front of the local player (nowhere without one). */
    private static java.util.function.Consumer<ItemStack> worldDrop = InventoryDragDropHandler::dropFromPlayer;

    private static void dropFromPlayer(ItemStack stack) {
        Player player = Game.getPlayer();
        if (player != null) {
            com.stonebreak.util.DropUtil.dropItemFromPlayer(player, stack);
        }
    }

    /**
     * Sends every stack the container screens drop into the world to {@code sink} until the returned
     * handle is closed (interaction tests count them instead of losing them). Main thread only.
     */
    public static AutoCloseable redirectWorldDrops(java.util.function.Consumer<ItemStack> sink) {
        java.util.function.Consumer<ItemStack> previous = worldDrop;
        worldDrop = sink;
        return () -> worldDrop = previous;
    }

    /** The atomic action a placement onto {@code target} maps to (issue #334). */
    public static byte placementAction(DragState dragState, ItemStack target) {
        if (target == null || target.isEmpty()) {
            return com.stonebreak.network.packet.player.ContainerActionC2S.Action.PUT_ALL;
        }
        return target.canStackWith(dragState.draggedItemStack)
                ? com.stonebreak.network.packet.player.ContainerActionC2S.Action.MERGE
                : com.stonebreak.network.packet.player.ContainerActionC2S.Action.SWAP;
    }

    /** The region the carried stack came from (for the server's swap-back), or NONE. */
    public static byte originRegionOf(DragState dragState, SlotActionSink sink) {
        if (sink == null || !dragState.isDragging()) {
            return com.stonebreak.network.packet.player.ContainerActionC2S.Region.NONE;
        }
        return switch (dragState.dragSource) {
            case HOTBAR -> com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_HOTBAR;
            case MAIN_INVENTORY -> com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_MAIN;
            default -> com.stonebreak.network.packet.player.ContainerActionC2S.Region.NONE;
        };
    }

    /** The slot index within {@code originRegionOf} the carried stack came from, or -1. */
    public static byte originSlotOf(DragState dragState) {
        if (!dragState.isDragging()) {
            return -1;
        }
        return switch (dragState.dragSource) {
            case HOTBAR, MAIN_INVENTORY -> (byte) dragState.draggedItemOriginalSlotIndex;
            default -> -1;
        };
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