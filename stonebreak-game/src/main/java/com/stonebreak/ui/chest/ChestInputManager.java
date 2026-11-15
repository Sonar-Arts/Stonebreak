package com.stonebreak.ui.chest;

import com.stonebreak.blocks.container.ChestState;
import com.stonebreak.blocks.container.SlotGridState;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.inventoryScreen.handlers.PointerFrame;
import com.stonebreak.ui.inventoryScreen.handlers.SlotActionSink;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static com.stonebreak.network.packet.player.ContainerActionC2S.Action;
import static com.stonebreak.network.packet.player.ContainerActionC2S.Region;

/**
 * The chest screen's slot rules: picking up, placing, stacking, swapping and shift-moving between
 * the chest's 27 slots and the player's inventory. Every local move is the prediction of an
 * atomic {@code ContainerActionC2S} the server replays (issue #334); the chest's echo
 * re-baselines it.
 *
 * <p>Layout: the furnace's panel ({@link InventoryLayoutCalculator#calculateWorkbenchLayout}),
 * with the chest's three rows of nine in the band the furnace's crucible fills ({@link ChestLayout}).
 * Slot addresses: {@code chest:<0-26>}, {@code main:<0-26>}, {@code hotbar:<0-8>}, {@code panel},
 * {@code outside}.
 */
public class ChestInputManager implements ContainerSlotInput {

    /** A carried stack's origin index for a chest slot: {@code CHEST_ORIGIN + slot}. */
    static final int CHEST_ORIGIN = 3000;

    private final InputHandler inputHandler;
    private final Inventory inventory;
    private final ChestController controller;
    private final InventoryDragDropHandler.DragState dragState = new InventoryDragDropHandler.DragState();
    private final Set<String> rightDragVisited = new HashSet<>();
    private SlotActionSink actions = new SlotActionSink();
    private Consumer<InventoryDragDropHandler.DragState> worldDrop = InventoryDragDropHandler::dropEntireStackIntoWorld;

    public ChestInputManager(InputHandler inputHandler, Inventory inventory, ChestController controller) {
        this.inputHandler = inputHandler;
        this.inventory = inventory;
        this.controller = controller;
    }

    public void bindActions(SlotActionSink sink) {
        this.actions = sink;
    }

    @Override
    public InventoryDragDropHandler.DragState getDragState() {
        return dragState;
    }

    @Override
    public ItemStack[] containerSlots() {
        return controller.slots();
    }

    /* ── Slot geometry ───────────────────────────────────── */

    /** A slot under the pointer: which region and index. */
    record Hit(byte region, int index) { }

    @Override
    public float[] slotOrigin(String slot, int screenWidth, int screenHeight) {
        if (slot == null) {
            return null;
        }
        InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(screenWidth, screenHeight);
        int ss = InventoryLayoutCalculator.getSlotSize();
        switch (slot) {
            case "outside" -> {
                return new float[]{OUTSIDE, OUTSIDE};
            }
            case "panel" -> {
                float half = ss / 2f;
                return new float[]{layout.panelStartX + 1 - half, layout.panelStartY + 1 - half};
            }
            default -> { }
        }
        int colon = slot.indexOf(':');
        if (colon < 0) {
            return null;
        }
        int i;
        try {
            i = Integer.parseInt(slot.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        int[] xy = switch (slot.substring(0, colon)) {
            case "chest" -> i < 0 || i >= ChestState.SLOT_COUNT ? null : ChestLayout.chestSlot(layout, i);
            case "main" -> i < 0 || i >= Inventory.MAIN_INVENTORY_SIZE ? null : ChestLayout.mainSlot(layout, i);
            case "hotbar" -> i < 0 || i >= Inventory.HOTBAR_SIZE ? null : ChestLayout.hotbarSlot(layout, i);
            default -> null;
        };
        return xy == null ? null : new float[]{xy[0], xy[1]};
    }

    @Override
    public float slotSize() {
        return InventoryLayoutCalculator.getSlotSize();
    }

    private Hit hitTest(float mx, float my, InventoryLayoutCalculator.InventoryLayout layout) {
        for (int i = 0; i < ChestState.SLOT_COUNT; i++) {
            if (over(mx, my, ChestLayout.chestSlot(layout, i))) return new Hit(Region.CHEST_SLOTS, i);
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            if (over(mx, my, ChestLayout.mainSlot(layout, i))) return new Hit(Region.PLAYER_MAIN, i);
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            if (over(mx, my, ChestLayout.hotbarSlot(layout, i))) return new Hit(Region.PLAYER_HOTBAR, i);
        }
        return null;
    }

    private static boolean over(float mx, float my, int[] xy) {
        int ss = InventoryLayoutCalculator.getSlotSize();
        return mx >= xy[0] && mx <= xy[0] + ss && my >= xy[1] && my <= xy[1] + ss;
    }

    /* ── Slot access ─────────────────────────────────────── */

    private ItemStack get(Hit h) {
        return switch (h.region()) {
            case Region.CHEST_SLOTS -> controller.getSlot(h.index());
            case Region.PLAYER_MAIN -> inventory.getMainInventorySlot(h.index());
            default -> inventory.getHotbarSlot(h.index());
        };
    }

    private void set(Hit h, ItemStack s) {
        switch (h.region()) {
            case Region.CHEST_SLOTS -> controller.setSlot(h.index(), s);
            case Region.PLAYER_MAIN -> inventory.setMainInventorySlot(h.index(), s);
            default -> inventory.setHotbarSlot(h.index(), s);
        }
    }

    private static InventoryDragDropHandler.DragSource sourceOf(Hit h) {
        return switch (h.region()) {
            case Region.PLAYER_MAIN -> InventoryDragDropHandler.DragSource.MAIN_INVENTORY;
            case Region.PLAYER_HOTBAR -> InventoryDragDropHandler.DragSource.HOTBAR;
            default -> InventoryDragDropHandler.DragSource.NONE;
        };
    }

    /* ── Pointer ─────────────────────────────────────────── */

    @Override
    public void handlePointer(PointerFrame pointer, int screenWidth, int screenHeight) {
        InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(screenWidth, screenHeight);
        float mx = pointer.x();
        float my = pointer.y();
        if (pointer.leftPressed()) {
            if (pointer.shift()) {
                shiftClick(hitTest(mx, my, layout));
            } else {
                leftClick(mx, my, layout);
            }
            if (inputHandler != null) {
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            }
        } else if (pointer.rightDown() && dragState.isDragging()) {
            rightDrag(hitTest(mx, my, layout));
        } else if (pointer.rightPressed()) {
            rightClick(hitTest(mx, my, layout));
        } else if (!dragState.isDragging()) {
            dragState.clear();
        }
        if (!pointer.rightDown()) {
            rightDragVisited.clear();
        }
    }

    private void leftClick(float mx, float my, InventoryLayoutCalculator.InventoryLayout layout) {
        Hit hit = hitTest(mx, my, layout);
        if (dragState.isDragging()) {
            if (hit != null) {
                place(hit);
                return;
            }
            returnCarriedHome();
            boolean outside = mx < layout.panelStartX || mx > layout.panelStartX + layout.inventoryPanelWidth
                    || my < layout.panelStartY || my > layout.panelStartY + layout.inventoryPanelHeight;
            if (dragState.isDragging() && outside) {
                InventoryDragDropHandler.tossCarried(dragState, actions);
            }
            return;
        }
        if (hit == null) {
            return;
        }
        ItemStack s = get(hit);
        if (s.isEmpty()) {
            return;
        }
        dragState.draggedItemStack = s.copy();
        set(hit, new ItemStack(0, 0));
        dragState.draggedItemOriginalSlotIndex = hit.region() == Region.CHEST_SLOTS ? CHEST_ORIGIN + hit.index() : hit.index();
        dragState.dragSource = sourceOf(hit);
        actions.send(hit.region(), (byte) hit.index(), Action.TAKE_ALL);
    }

    /** The carried stack onto a slot: into it if empty, merged if the same kind, else swapped. */
    private void place(Hit hit) {
        ItemStack target = get(hit);
        byte action = InventoryDragDropHandler.placementAction(dragState, target);
        byte originRegion = InventoryDragDropHandler.originRegionOf(dragState, actions);
        byte originSlot = InventoryDragDropHandler.originSlotOf(dragState);
        if (target.isEmpty()) {
            set(hit, dragState.draggedItemStack.copy());
            dragState.draggedItemStack = null;
        } else if (InventoryDragDropHandler.sameKind(target, dragState.draggedItemStack)) {
            int toAdd = Math.min(target.getMaxStackSize() - target.getCount(), dragState.draggedItemStack.getCount());
            if (toAdd <= 0) {
                return; // full: nothing moves, nothing to send
            }
            target.incrementCount(toAdd);
            dragState.draggedItemStack.decrementCount(toAdd);
            if (dragState.draggedItemStack.isEmpty()) {
                dragState.draggedItemStack = null;
            }
        } else {
            // The server's swap (issue #334): the displaced stack goes to an empty player
            // origin slot, otherwise it stays on the cursor.
            InventoryDragDropHandler.swapIntoSlot(dragState, target, st -> set(hit, st), inventory, null, null);
            if (dragState.isDragging() && dragState.dragSource == InventoryDragDropHandler.DragSource.NONE) {
                dragState.draggedItemOriginalSlotIndex = hit.region() == Region.CHEST_SLOTS
                        ? CHEST_ORIGIN + hit.index() : hit.index();
                dragState.dragSource = sourceOf(hit);
            }
        }
        actions.send(hit.region(), (byte) hit.index(), originRegion, originSlot, action, false);
    }

    /** Shift-click: chest → inventory, inventory → chest; whatever fits moves. */
    private void shiftClick(Hit hit) {
        if (hit == null || dragState.isDragging()) {
            return;
        }
        ItemStack s = get(hit);
        if (s.isEmpty()) {
            return;
        }
        if (hit.region() == Region.CHEST_SLOTS) {
            int added = inventory.addItemAndReturnCount(s);
            if (added <= 0) {
                return;
            }
            actions.send(hit.region(), (byte) hit.index(), Action.QUICK_MOVE, true);
            ItemStack rest = s.copy();
            rest.decrementCount(added);
            set(hit, rest.isEmpty() ? new ItemStack(0, 0) : rest);
            return;
        }
        ItemStack[] chest = controller.slots();
        if (chest == null) {
            return;
        }
        ItemStack moving = s.copy();
        if (!SlotGridState.fill(chest, moving)) {
            return;
        }
        actions.send(hit.region(), (byte) hit.index(), Action.QUICK_MOVE, true);
        set(hit, moving.isEmpty() ? new ItemStack(0, 0) : moving);
    }

    /** Right-click with an empty hand on a chest slot: take one. */
    private void rightClick(Hit hit) {
        if (hit == null || dragState.isDragging() || hit.region() != Region.CHEST_SLOTS) {
            return;
        }
        ItemStack s = get(hit);
        if (s.isEmpty()) {
            return;
        }
        dragState.draggedItemStack = new ItemStack(s.getItem(), 1, s.getState());
        s.decrementCount(1);
        if (s.isEmpty()) {
            set(hit, new ItemStack(0, 0));
        }
        dragState.draggedItemOriginalSlotIndex = CHEST_ORIGIN + hit.index();
        dragState.dragSource = InventoryDragDropHandler.DragSource.NONE;
        actions.send(hit.region(), (byte) hit.index(), Action.TAKE_ONE);
    }

    /** Right-drag while carrying: one into each slot swept over. */
    private void rightDrag(Hit hit) {
        if (hit == null || !dragState.isDragging() || !rightDragVisited.add(hit.region() + ":" + hit.index())) {
            return;
        }
        ItemStack target = get(hit);
        boolean fits = target.isEmpty() || (target.canStackWith(dragState.draggedItemStack)
                && target.getCount() < target.getMaxStackSize());
        if (!fits) {
            return;
        }
        if (target.isEmpty()) {
            set(hit, new ItemStack(dragState.draggedItemStack.getItem(), 1, dragState.draggedItemStack.getState()));
        } else {
            target.incrementCount(1);
        }
        dragState.draggedItemStack.decrementCount(1);
        if (dragState.draggedItemStack.isEmpty()) {
            dragState.draggedItemStack = null;
        }
        actions.send(hit.region(), (byte) hit.index(), Action.DROP_ONE);
    }

    /* ── Returning the carried stack ─────────────────────── */

    /** Back where it came from: a chest slot, or (through the shared rule) a player slot. */
    private void returnCarriedHome() {
        if (!dragState.isDragging()) {
            return;
        }
        if (dragState.draggedItemOriginalSlotIndex >= CHEST_ORIGIN) {
            Hit home = new Hit(Region.CHEST_SLOTS, dragState.draggedItemOriginalSlotIndex - CHEST_ORIGIN);
            ItemStack current = get(home);
            ItemStack carried = dragState.draggedItemStack;
            if (current.isEmpty()) {
                set(home, carried.copy());
                dragState.clear();
                actions.send(Region.CHEST_SLOTS, (byte) home.index(), Action.PUT_ALL);
            } else if (InventoryDragDropHandler.sameKind(current, carried) && current.getCount() < current.getMaxStackSize()) {
                int toAdd = Math.min(current.getMaxStackSize() - current.getCount(), carried.getCount());
                current.incrementCount(toAdd);
                carried.decrementCount(toAdd);
                if (carried.isEmpty()) {
                    dragState.clear();
                }
                actions.send(Region.CHEST_SLOTS, (byte) home.index(), Action.MERGE);
            }
        } else {
            InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, new ItemStack[0], null, actions);
        }
    }

    /** The chest is gone: a stack taken from it cannot go back, so it goes to the inventory or the world. */
    void returnCarriedToPlayer() {
        if (!dragState.isDragging()) {
            return;
        }
        if (dragState.draggedItemOriginalSlotIndex < CHEST_ORIGIN) {
            InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, new ItemStack[0], null, actions);
        }
        intoInventoryOrWorld();
    }

    private void intoInventoryOrWorld() {
        if (!dragState.isDragging()) {
            return;
        }
        actions.send(Action.DEPOSIT_CARRIED);
        int added = inventory.addItemAndReturnCount(dragState.draggedItemStack);
        if (added >= dragState.draggedItemStack.getCount() || actions.live()) {
            dragState.clear();
        } else {
            dragState.draggedItemStack.decrementCount(added);
            worldDrop.accept(dragState);
        }
    }

    /**
     * Closing: the carried stack goes back to its slot, else into the inventory, else the world;
     * and a screen that sent anything always ends with a DEPOSIT_CARRIED (issue #334).
     */
    @Override
    public void handleCloseWithDraggedItems() {
        boolean mayCarry = actions.hasSentSinceBind();
        if (dragState.isDragging()) {
            returnCarriedHome();
            intoInventoryOrWorld();
        }
        if (mayCarry && actions.lastAction() != Action.DEPOSIT_CARRIED) {
            actions.send(Action.DEPOSIT_CARRIED);
        }
    }

    /** Test seam: where stacks dropped into the world go. */
    void setWorldDrop(Consumer<InventoryDragDropHandler.DragState> drop) {
        this.worldDrop = drop;
    }
}
