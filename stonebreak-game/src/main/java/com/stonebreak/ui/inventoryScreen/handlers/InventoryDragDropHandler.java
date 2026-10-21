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
        placed = tryPlaceInMainInventory(dragState, inventory, craftingInputSlots, layout, mouseX, mouseY,
                updateCraftingOutput, sink, craftGridRegion);

        // Try to place in hotbar if not placed
        if (!placed) {
            placed = tryPlaceInHotbar(dragState, inventory, craftingInputSlots, layout, mouseX, mouseY,
                    updateCraftingOutput, sink, craftGridRegion);
        }

        // Try to place in crafting input slots if not placed
        if (!placed) {
            placed = tryPlaceInCraftingSlots(dragState, inventory, craftingInputSlots, layout, mouseX, mouseY,
                    updateCraftingOutput, sink, craftGridRegion);
        }

        // Handle placement results. A placement that leaves something on the cursor (a
        // partial merge's remainder, a swap's displaced stack whose origin is occupied)
        // keeps it there — exactly what the server's MERGE / SWAP leave carried (#334).
        if (placed && !dragState.isDragging()) {
            dragState.clear();
        } else if (!placed && dragState.isDragging()) {
            if (outsidePanel) {
                tossCarried(dragState, sink);
            } else {
                tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput, sink,
                        craftGridRegion);
            }
        }

        // Final cleanup
        if (!dragState.isDragging()) {
            dragState.clear();
        }

        return placed;
    }

    private static boolean tryPlaceInMainInventory(DragState dragState, Inventory inventory,
                                                 ItemStack[] craftingInputSlots,
                                                 InventoryLayoutCalculator.InventoryLayout layout,
                                                 float mouseX, float mouseY, Runnable updateCraftingOutput,
                                                 SlotActionSink sink, byte craftGridRegion) {
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
                // Action + drag origin are captured BEFORE the placement mutates the drag
                // state (a swap clears it), so the server replays the same swap-back (#334).
                byte action = placementAction(dragState, target);
                byte originRegion = originRegionOf(dragState, sink); // swap origins: player slots only
                byte originSlot = originSlotOf(dragState);
                boolean placed = handleSlotPlacement(dragState, target,
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

    private static boolean tryPlaceInHotbar(DragState dragState, Inventory inventory,
                                          ItemStack[] craftingInputSlots,
                                          InventoryLayoutCalculator.InventoryLayout layout,
                                          float mouseX, float mouseY, Runnable updateCraftingOutput,
                                          SlotActionSink sink, byte craftGridRegion) {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            final int slotIndex = i; // Make effectively final for lambda
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.inventorySectionStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                ItemStack target = inventory.getHotbarSlot(slotIndex);
                byte action = placementAction(dragState, target);
                byte originRegion = originRegionOf(dragState, sink);
                byte originSlot = originSlotOf(dragState);
                boolean placed = handleSlotPlacement(dragState, target,
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

    private static boolean tryPlaceInCraftingSlots(DragState dragState, Inventory inventory,
                                                 ItemStack[] craftingInputSlots,
                                                 InventoryLayoutCalculator.InventoryLayout layout,
                                                 float mouseX, float mouseY, Runnable updateCraftingOutput,
                                                 SlotActionSink sink, byte craftGridRegion) {
        for (int i = 0; i < InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getCraftingGridSize(); i++) {
            final int slotIndex = i;
            int r = i / InventoryLayoutCalculator.getCraftingGridSize();
            int c = i % InventoryLayoutCalculator.getCraftingGridSize();
            int slotX = layout.craftingElementsStartX + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                byte action = placementAction(dragState, craftingInputSlots[i]);
                byte originRegion = originRegionOf(dragState, sink);
                byte originSlot = originSlotOf(dragState);
                boolean placed = handleSlotPlacement(dragState, craftingInputSlots[i],
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

    /**
     * One placement of the carried stack onto {@code targetStack}, with the server's rules
     * (issue #334): an empty slot takes all (PUT_ALL); a stackable one takes what fits and
     * the remainder STAYS carried (MERGE — a full slot is no placement at all); anything
     * else swaps ({@link #swapIntoSlot}). Returns false when nothing was placed.
     */
    static boolean handleSlotPlacement(DragState dragState, ItemStack targetStack,
                                       java.util.function.Consumer<ItemStack> slotSetter,
                                       Inventory inventory, ItemStack[] craftingInputSlots,
                                       Runnable updateCraftingOutput) {
        if (targetStack.isEmpty()) {
            slotSetter.accept(dragState.draggedItemStack);
            dragState.draggedItemStack = null;
            return true;
        } else if (sameKind(targetStack, dragState.draggedItemStack)) {
            // Same item + state: merge what fits. A FULL same-kind slot is no placement
            // (the server refuses both MERGE and SWAP there) — nothing moves or is sent.
            int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
            int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
            if (toAdd <= 0) {
                return false;
            }
            targetStack.incrementCount(toAdd);
            dragState.draggedItemStack.decrementCount(toAdd);
            if (dragState.draggedItemStack.isEmpty()) {
                dragState.draggedItemStack = null;
            }
            return true;
        }
        swapIntoSlot(dragState, targetStack, slotSetter, inventory, craftingInputSlots, updateCraftingOutput);
        return true;
    }

    /**
     * The server's SWAP (issue #334): the carried stack goes into the slot; the displaced
     * stack goes to the drag's ORIGIN slot when that is an EMPTY hotbar/main slot (checked
     * after the target is written, as the server does), otherwise — an occupied origin, a
     * grid/furnace/output-sourced drag (the server only knows player origins) — it stays
     * carried.
     */
    public static void swapIntoSlot(DragState dragState, ItemStack targetStack,
                                    java.util.function.Consumer<ItemStack> slotSetter,
                                    Inventory inventory, ItemStack[] craftingInputSlots,
                                    Runnable updateCraftingOutput) {
        ItemStack displaced = targetStack.copy();
        slotSetter.accept(dragState.draggedItemStack.copy());
        dragState.draggedItemStack = displaced;
        if (placeIntoEmptyOrigin(dragState, inventory, craftingInputSlots, updateCraftingOutput)) {
            dragState.clear();
        }
    }

    /** Puts the whole carried stack into its origin slot iff that slot is empty. */
    private static boolean placeIntoEmptyOrigin(DragState dragState, Inventory inventory,
                                                ItemStack[] craftingInputSlots, Runnable updateCraftingOutput) {
        int idx = dragState.draggedItemOriginalSlotIndex;
        switch (dragState.dragSource) {
            case HOTBAR -> {
                if (idx >= 0 && idx < Inventory.HOTBAR_SIZE && inventory.getHotbarSlot(idx).isEmpty()) {
                    inventory.setHotbarSlot(idx, dragState.draggedItemStack);
                    return true;
                }
            }
            case MAIN_INVENTORY -> {
                if (idx >= 0 && idx < Inventory.MAIN_INVENTORY_SIZE && inventory.getMainInventorySlot(idx).isEmpty()) {
                    inventory.setMainInventorySlot(idx, dragState.draggedItemStack);
                    return true;
                }
            }
            default -> { }
        }
        return false;
    }

    /**
     * Throws the whole carried stack into the world. With a replaying sink the server owns
     * the carried stack, so the toss ships as DROP_CARRIED (DropItemC2S would debit the 36
     * slots, not the server cursor); in local-only play it drops locally (issue #334).
     */
    public static void tossCarried(DragState dragState, SlotActionSink sink) {
        if (!dragState.isDragging()) {
            dragState.clear();
            return;
        }
        if (sink != null && sink.live()) {
            short n = (short) dragState.draggedItemStack.getCount();
            dragState.clear();
            sink.sendWithCount(com.stonebreak.network.packet.player.ContainerActionC2S.Action.DROP_CARRIED, n);
        } else {
            dropEntireStackIntoWorld(dragState);
        }
    }

    public static void tryReturnToOriginalSlot(DragState dragState, Inventory inventory,
                                             ItemStack[] craftingInputSlots, Runnable updateCraftingOutput) {
        tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput, null);
    }

    /** Return-to-origin with a server sink; the crafting grid is the inventory's 2×2. */
    public static void tryReturnToOriginalSlot(DragState dragState, Inventory inventory,
                                             ItemStack[] craftingInputSlots, Runnable updateCraftingOutput,
                                             SlotActionSink sink) {
        tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput, sink,
                com.stonebreak.network.packet.player.ContainerActionC2S.Region.INV_CRAFT_GRID);
    }

    /**
     * Return-to-origin with a server sink (issue #334): the local return is the prediction;
     * the server replays it as PUT_ALL (empty origin) or MERGE (stackable origin) against
     * the replica's carried stack. Sent only when the return moved something, so the action
     * always matches the local outcome. {@code craftGridRegion} addresses a crafting-grid
     * origin (inventory 2×2 or workbench 3×3).
     */
    public static void tryReturnToOriginalSlot(DragState dragState, Inventory inventory,
                                             ItemStack[] craftingInputSlots, Runnable updateCraftingOutput,
                                             SlotActionSink sink, byte craftGridRegion) {
        byte originRegion = originRegionOf(dragState, sink, craftGridRegion);
        byte originSlot = originSlotOf(dragState);
        ItemStack originStack = originStackOf(dragState, inventory, craftingInputSlots);
        int before = dragState.isDragging() ? dragState.draggedItemStack.getCount() : 0;
        byte action = (originStack == null || originStack.isEmpty())
                ? com.stonebreak.network.packet.player.ContainerActionC2S.Action.PUT_ALL
                : com.stonebreak.network.packet.player.ContainerActionC2S.Action.MERGE;
        returnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput);
        int after = dragState.isDragging() ? dragState.draggedItemStack.getCount() : 0;
        if (sink != null && originRegion != com.stonebreak.network.packet.player.ContainerActionC2S.Region.NONE
                && after < before) {
            sink.send(originRegion, originSlot, action);
        }
    }

    /** The stack currently in the drag's origin slot, or null when it has none. */
    private static ItemStack originStackOf(DragState dragState, Inventory inventory, ItemStack[] craftingInputSlots) {
        int idx = dragState.draggedItemOriginalSlotIndex;
        return switch (dragState.dragSource) {
            case HOTBAR -> idx >= 0 && idx < Inventory.HOTBAR_SIZE ? inventory.getHotbarSlot(idx) : null;
            case MAIN_INVENTORY -> idx >= 0 && idx < Inventory.MAIN_INVENTORY_SIZE
                    ? inventory.getMainInventorySlot(idx) : null;
            case CRAFTING_INPUT -> {
                int cell = idx - CRAFTING_INPUT_SLOT_START_INDEX;
                yield craftingInputSlots != null && cell >= 0 && cell < craftingInputSlots.length
                        ? craftingInputSlots[cell] : null;
            }
            default -> null;
        };
    }

    private static void returnToOriginalSlot(DragState dragState, Inventory inventory,
                                             ItemStack[] craftingInputSlots, Runnable updateCraftingOutput) {
        if (!dragState.isDragging()) {
            if (dragState.draggedItemStack != null) dragState.draggedItemStack = null;
            dragState.dragSource = DragSource.NONE;
            return;
        }
        if (dragState.draggedItemOriginalSlotIndex == -1) {
            // No origin (e.g. a stack adopted from the server's cursor by a sync): it stays
            // on the cursor. Nulling it here deleted it from the prediction while the server
            // still carried it; the close's DEPOSIT_CARRIED hands it back (issue #334).
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
                if (craftingInputSlots != null && craftingSlotIndex >= 0 && craftingSlotIndex < craftingInputSlots.length) {
                    originalSlotItemStack = craftingInputSlots[craftingSlotIndex];
                    int before = dragState.draggedItemStack.getCount();
                    boolean done = tryStackOrPlace(dragState, originalSlotItemStack,
                                       (stack) -> craftingInputSlots[craftingSlotIndex] = stack);
                    boolean moved = done || (dragState.draggedItemStack != null
                            && dragState.draggedItemStack.getCount() < before);
                    if (moved && updateCraftingOutput != null) {
                        updateCraftingOutput.run();
                    }
                    if (done) {
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

    /**
     * The atomic action a placement onto {@code target} maps to (issue #334): a same-kind
     * target (type + state, full or not) is a MERGE, never a SWAP — a full one is no
     * placement at all, and callers send nothing then.
     */
    public static byte placementAction(DragState dragState, ItemStack target) {
        if (target == null || target.isEmpty()) {
            return com.stonebreak.network.packet.player.ContainerActionC2S.Action.PUT_ALL;
        }
        return sameKind(target, dragState.draggedItemStack)
                ? com.stonebreak.network.packet.player.ContainerActionC2S.Action.MERGE
                : com.stonebreak.network.packet.player.ContainerActionC2S.Action.SWAP;
    }

    /**
     * Same item AND same state ({@link ItemStack#statesMatch}) — the server's "not a different
     * item" test for SWAP. Unlike {@link ItemStack#canStackWith} it ignores room, so a full
     * same-kind slot is still "same kind" (no swap), while a water bucket and an empty
     * bucket are different kinds (they swap).
     */
    public static boolean sameKind(ItemStack a, ItemStack b) {
        return a != null && b != null && !a.isEmpty() && !b.isEmpty()
                && a.getItem().isSameType(b.getItem())
                && ItemStack.statesMatch(a.getItem(), a.getState(), b.getState());
    }

    /**
     * The region the carried stack came from (for the server's swap-back), or NONE. A
     * crafting-grid origin has no region here; screens with a grid use the overload.
     */
    public static byte originRegionOf(DragState dragState, SlotActionSink sink) {
        return originRegionOf(dragState, sink, com.stonebreak.network.packet.player.ContainerActionC2S.Region.NONE);
    }

    /**
     * As above, mapping a crafting-grid origin to {@code craftGridRegion} (the screen's bound
     * grid: INV_CRAFT_GRID or WORKBENCH_GRID), so a stack taken from the grid and put back
     * or swapped is replayed against that cell (issue #334).
     */
    public static byte originRegionOf(DragState dragState, SlotActionSink sink, byte craftGridRegion) {
        if (sink == null || !dragState.isDragging()) {
            return com.stonebreak.network.packet.player.ContainerActionC2S.Region.NONE;
        }
        return switch (dragState.dragSource) {
            case HOTBAR -> com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_HOTBAR;
            case MAIN_INVENTORY -> com.stonebreak.network.packet.player.ContainerActionC2S.Region.PLAYER_MAIN;
            case CRAFTING_INPUT -> {
                int cell = dragState.draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX;
                yield cell >= 0 && cell < 9 ? craftGridRegion
                        : com.stonebreak.network.packet.player.ContainerActionC2S.Region.NONE;
            }
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
            case CRAFTING_INPUT -> (byte) (dragState.draggedItemOriginalSlotIndex - CRAFTING_INPUT_SLOT_START_INDEX);
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