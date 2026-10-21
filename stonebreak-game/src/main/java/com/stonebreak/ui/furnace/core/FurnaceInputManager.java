package com.stonebreak.ui.furnace.core;

import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.inventoryScreen.handlers.PointerFrame;
import com.stonebreak.ui.inventoryScreen.handlers.SlotActionSink;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import org.lwjgl.glfw.GLFW;

import static com.stonebreak.network.packet.player.ContainerActionC2S.Action;
import static com.stonebreak.network.packet.player.ContainerActionC2S.Region;

/**
 * Handles mouse input for the furnace screen: clicking furnace slots,
 * drag-and-drop into furnace, and normal inventory drag-and-drop.
 */
public class FurnaceInputManager implements ContainerSlotInput {

    protected final InputHandler inputHandler;
    protected final Inventory inventory;
    protected final InventoryDragDropHandler.DragState dragState;
    protected final FurnaceController controller;

    /** Drops a carried stack at the player's feet; tests observe it. */
    private java.util.function.Consumer<InventoryDragDropHandler.DragState> worldDrop =
        InventoryDragDropHandler::dropEntireStackIntoWorld;

    // Right-click drag state
    private final java.util.Set<Integer> rightDragVisitedSlots = new java.util.HashSet<>();

    /** Where slot actions go (issue #334) — bound by FurnaceController when the UI opens. */
    private SlotActionSink actions = new SlotActionSink();

    /** Binds the container-action sink for the open furnace (test seam re-binds too). */
    public void bindActions(SlotActionSink sink) {
        this.actions = sink;
    }

    /** Furnace slot id → its container region. */
    private static byte regionOf(int slotId) {
        return switch (slotId) {
            case FurnaceController.SLOT_INGREDIENT -> Region.FURNACE_ING;
            case FurnaceController.SLOT_FUEL -> Region.FURNACE_FUEL;
            case FurnaceController.SLOT_OUTPUT -> Region.FURNACE_OUT;
            default -> Region.NONE;
        };
    }

    public FurnaceInputManager(InputHandler inputHandler, Inventory inventory,
                               FurnaceController controller) {
        this.inputHandler = inputHandler;
        this.inventory = inventory;
        this.controller = controller;
        this.dragState = new InventoryDragDropHandler.DragState();
    }

    public InventoryDragDropHandler.DragState getDragState() {
        return dragState;
    }

    /**
     * One frame of pointer input, synthesized at a slot by the furnace document (#289,
     * {@link ContainerSlotInput}): every rule below runs from it.
     */
    @Override
    public void handlePointer(PointerFrame pointer, int screenWidth, int screenHeight) {
        InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(screenWidth, screenHeight);

        float mouseX = pointer.x();
        float mouseY = pointer.y();
        boolean shiftDown = pointer.shift();

        boolean leftPressed = pointer.leftPressed();
        boolean rightPressed = pointer.rightPressed();
        boolean rightDown = pointer.rightDown();

        if (leftPressed) {
            if (shiftDown) {
                handleShiftClick(mouseX, mouseY, layout);
            } else {
                handleLeftClick(mouseX, mouseY, layout);
            }
            if (inputHandler != null) {
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            }
        } else if (rightDown && dragState.isDragging()) {
            handleRightDrag(mouseX, mouseY, layout);
        } else if (rightPressed) {
            handleRightClick(mouseX, mouseY, layout);
        } else {
            handleDragRelease(screenWidth, screenHeight);
        }

        if (!rightDown) {
            rightDragVisitedSlots.clear();
        }
    }

    @Override
    public float[] slotOrigin(String slot, int screenWidth, int screenHeight) {
        if (slot == null) {
            return null;
        }
        InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(screenWidth, screenHeight);
        FurnaceLayout.Slots f = FurnaceLayout.compute(layout);
        int ss = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        switch (slot) {
            case "outside" -> {
                return new float[]{OUTSIDE, OUTSIDE};
            }
            case "ingredient" -> {
                return new float[]{f.ingredientX, f.ingredientY};
            }
            case "fuel" -> {
                return new float[]{f.fuelX, f.fuelY};
            }
            case "output" -> {
                return new float[]{f.outputX, f.outputY};
            }
            case "panel" -> {
                // the panel's top-left inner corner: inside the panel, on no slot (a held stack
                // goes back where it came from instead of into the world)
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
        return switch (slot.substring(0, colon)) {
            case "main" -> i < 0 || i >= Inventory.MAIN_INVENTORY_SIZE ? null : new float[]{
                layout.inventorySectionStartX + pad + (i % Inventory.MAIN_INVENTORY_COLS) * (ss + pad),
                layout.mainInvContentStartY + pad + (i / Inventory.MAIN_INVENTORY_COLS) * (ss + pad)};
            case "hotbar" -> i < 0 || i >= Inventory.HOTBAR_SIZE ? null : new float[]{
                layout.inventorySectionStartX + pad + i * (ss + pad), layout.hotbarRowY};
            default -> null;
        };
    }

    @Override
    public float slotSize() {
        return InventoryLayoutCalculator.getSlotSize();
    }

    /* ── Left-click ──────────────────────────────────────── */

    private void handleLeftClick(float mouseX, float mouseY,
                                 InventoryLayoutCalculator.InventoryLayout layout) {
        if (dragState.isDragging()) {
            if (tryDropToFurnaceSlots(mouseX, mouseY, layout)) {
                return;
            }
            if (!tryDropToInventory(mouseX, mouseY, layout) && !tryDropToHotbar(mouseX, mouseY, layout)) {
                if (dragState.isDragging()) {
                    // Back to where it came from — an inventory/hotbar slot or its furnace
                    // slot — each step sent as the matching action (issue #334).
                    returnCarriedHome();

                    // Click outside the panel bounds → drop into world (DROP_CARRIED when
                    // the server owns the carried stack)
                    if (dragState.isDragging() &&
                        (mouseX < layout.panelStartX ||
                         mouseX > layout.panelStartX + layout.inventoryPanelWidth ||
                         mouseY < layout.panelStartY ||
                         mouseY > layout.panelStartY + layout.inventoryPanelHeight)) {
                        InventoryDragDropHandler.tossCarried(dragState, actions);
                    }
                }
            }
            return;
        }

        // No double-click rule here (#352): a quick second press always drops or swaps.
        if (!tryPickUpFromFurnaceSlots(mouseX, mouseY, layout) &&
            !tryPickUpFromInventory(mouseX, mouseY, layout)) {
            tryPickUpFromHotbar(mouseX, mouseY, layout);
        }
    }

    /* ── Shift-click ─────────────────────────────────────── */

    private void handleShiftClick(float mouseX, float mouseY,
                                  InventoryLayoutCalculator.InventoryLayout layout) {
        FurnaceSlot slot = hitTestFurnaceSlots(mouseX, mouseY, layout);
        if (slot == null) return;
        shiftClickSlot(slot.id);
    }

    /**
     * Moves a furnace slot's stack into the inventory. Only what fits moves:
     * an ingredient/fuel remainder stays in its slot; output overflow drops at
     * the feet in local-only play, but only the overflow is dropped (#319 — the
     * old code cleared the slot regardless, deleting the remainder, and dropped
     * the whole output stack after a partial add, duplicating it). With a live
     * session the SERVER keeps the output overflow in the slot instead (issue #334).
     */
    void shiftClickSlot(int slotId) {
        ItemStack item = getFurnaceSlot(slotId);
        if (item.isEmpty()) return;
        // The SERVER moves the stack into the replica (issue #334); this local move is the
        // prediction. The SERVER keeps the output overflow IN the slot (quickMove), so with
        // a live session the remainder stays there and the BlockStateS2C echo re-baselines
        // — dropping it here would send a slot debit for a stack that lives in the furnace,
        // not the inventory. Local-only play keeps the #319 drop-at-feet behaviour.
        actions.send(regionOf(slotId), (byte) slotId, Action.QUICK_MOVE, true);
        ItemStack remainder = FurnaceShiftTransfer.intoInventory(item, inventory);
        if (slotId == FurnaceController.SLOT_OUTPUT && !remainder.isEmpty()
                && !com.stonebreak.network.MultiplayerSession.isClient()) {
            com.stonebreak.player.Player player = Game.getPlayer();
            if (player != null) {
                com.stonebreak.util.DropUtil.dropItemFromPlayer(player, remainder);
                remainder = new ItemStack(0, 0);
            }
        }
        setFurnaceSlot(slotId, remainder);
    }

    /* ── Right-click (drop one) ──────────────────────────── */

    private void handleRightClick(float mouseX, float mouseY,
                                  InventoryLayoutCalculator.InventoryLayout layout) {
        if (dragState.isDragging()) return;

        FurnaceSlot slot = hitTestFurnaceSlots(mouseX, mouseY, layout);
        if (slot != null) {
            ItemStack s = getFurnaceSlot(slot.id);
            if (!s.isEmpty()) {
                dragState.draggedItemStack = new ItemStack(s.getItem(), 1, s.getState());
                s.decrementCount(1);
                if (s.isEmpty()) setFurnaceSlot(slot.id, new ItemStack(0, 0));
                dragState.draggedItemOriginalSlotIndex = 3000 + slot.id;
                dragState.dragSource = InventoryDragDropHandler.DragSource.NONE;
                actions.send(regionOf(slot.id), (byte) slot.id, Action.TAKE_ONE);
            }
        }
    }

    /* ── Right drag (deposit one at a time) ──────────────── */

    private void handleRightDrag(float mouseX, float mouseY,
                                 InventoryLayoutCalculator.InventoryLayout layout) {
        if (!dragState.isDragging()) return;

        int ss = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();

        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int sx = layout.inventorySectionStartX + pad + col * (ss + pad);
            int sy = layout.mainInvContentStartY + pad + row * (ss + pad);
            if (isOverSlot(mouseX, mouseY, sx, sy) && !rightDragVisitedSlots.contains(i)) {
                ItemStack target = inventory.getMainInventorySlot(i);
                if (canPlaceOne(target)) {
                    placeOne(target, i);
                    rightDragVisitedSlots.add(i);
                    actions.send(Region.PLAYER_MAIN, (byte) i, Action.DROP_ONE);
                }
                break;
            }
        }
    }

    /** The server's DROP_ONE rule: an empty slot, or a stackable one with room. */
    private boolean canPlaceOne(ItemStack target) {
        return target.isEmpty() || (target.canStackWith(dragState.draggedItemStack)
                && target.getCount() < target.getMaxStackSize());
    }

    private void placeOne(ItemStack target, int mainSlot) {
        if (target.isEmpty()) {
            // one of THIS stack (state included), as the server's DROP_ONE writes it
            inventory.setMainInventorySlot(mainSlot, new ItemStack(dragState.draggedItemStack.getItem(), 1,
                    dragState.draggedItemStack.getState()));
        } else {
            target.incrementCount(1);
        }
        dragState.draggedItemStack.decrementCount(1);
        if (dragState.draggedItemStack.isEmpty()) dragState.draggedItemStack = null;
    }

    /* ── Drag release ────────────────────────────────────── */

    private void handleDragRelease(int screenWidth, int screenHeight) {
        if (!dragState.isDragging()) {
            dragState.clear();
        }
        // Otherwise: dragging in progress — wait for second left-click
    }

    /* ── Furnace slot hit-testing ────────────────────────── */

    private FurnaceSlot hitTestFurnaceSlots(float mouseX, float mouseY,
                                            InventoryLayoutCalculator.InventoryLayout layout) {
        FurnaceLayout.Slots s = FurnaceLayout.compute(layout);

        if (isOverSlot(mouseX, mouseY, s.ingredientX, s.ingredientY))
            return new FurnaceSlot(FurnaceController.SLOT_INGREDIENT, s.ingredientX, s.ingredientY);
        if (isOverSlot(mouseX, mouseY, s.fuelX, s.fuelY))
            return new FurnaceSlot(FurnaceController.SLOT_FUEL, s.fuelX, s.fuelY);
        if (isOverSlot(mouseX, mouseY, s.outputX, s.outputY))
            return new FurnaceSlot(FurnaceController.SLOT_OUTPUT, s.outputX, s.outputY);

        return null;
    }

    private boolean isOverSlot(float mx, float my, int sx, int sy) {
        int ss = InventoryLayoutCalculator.getSlotSize();
        return mx >= sx && mx <= sx + ss && my >= sy && my <= sy + ss;
    }

    /* ── Pick up from furnace ────────────────────────────── */

    private boolean tryPickUpFromFurnaceSlots(float mouseX, float mouseY,
                                              InventoryLayoutCalculator.InventoryLayout layout) {
        FurnaceSlot slot = hitTestFurnaceSlots(mouseX, mouseY, layout);
        if (slot == null) return false;

        ItemStack current = getFurnaceSlot(slot.id);
        if (current.isEmpty()) return false;

        dragState.draggedItemStack = current.copy();
        setFurnaceSlot(slot.id, new ItemStack(0, 0));
        dragState.draggedItemOriginalSlotIndex = 3000 + slot.id;
        dragState.dragSource = InventoryDragDropHandler.DragSource.NONE;
        actions.send(regionOf(slot.id), (byte) slot.id, Action.TAKE_ALL);
        return true;
    }

    /* ── Drop to furnace slots ───────────────────────────── */

    private boolean tryDropToFurnaceSlots(float mouseX, float mouseY,
                                          InventoryLayoutCalculator.InventoryLayout layout) {
        FurnaceSlot slot = hitTestFurnaceSlots(mouseX, mouseY, layout);
        if (slot == null) return false;
        if (slot.id == FurnaceController.SLOT_OUTPUT) return false; // can't drop into output

        // Fuel slot — check burn time (the SERVER re-checks with its own SmeltingManager).
        if (slot.id == FurnaceController.SLOT_FUEL) {
            if (!furnaceAccepts(FurnaceController.SLOT_FUEL, dragState.draggedItemStack)) return false;

            ItemStack fuel = controller.getFuelSlot();
            if (!fuel.isEmpty()) {
                if (fuel.canStackWith(dragState.draggedItemStack)) {
                    int canAdd = fuel.getMaxStackSize() - fuel.getCount();
                    int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
                    if (toAdd <= 0) return false;
                    fuel.incrementCount(toAdd);
                    dragState.draggedItemStack.decrementCount(toAdd);
                    if (dragState.draggedItemStack.isEmpty()) dragState.draggedItemStack = null;
                    actions.send(Region.FURNACE_FUEL, (byte) slot.id, Action.MERGE);
                    return true;
                }
                return false;
            }
            // Add as new fuel — do NOT pre-credit burnTimeRemaining here.
            // Fuel is consumed one unit at a time by FurnaceState.tick (run by the registry),
            // so removing the stack mid-burn only refunds un-started items.
            controller.setFuelSlot(dragState.draggedItemStack.copy());
            dragState.draggedItemStack = null;
            actions.send(Region.FURNACE_FUEL, (byte) slot.id, Action.PUT_ALL);
            return true;
        }

        // Ingredient slot — smeltables only (the SERVER refuses anything else, so placing
        // it here would be a prediction the next echo reverts).
        if (!furnaceAccepts(FurnaceController.SLOT_INGREDIENT, dragState.draggedItemStack)) return false;
        ItemStack current = getFurnaceSlot(slot.id);
        if (current.isEmpty()) {
            setFurnaceSlot(slot.id, dragState.draggedItemStack.copy());
            dragState.draggedItemStack = null;
            actions.send(Region.FURNACE_ING, (byte) slot.id, Action.PUT_ALL);
            return true;
        } else if (InventoryDragDropHandler.sameKind(current, dragState.draggedItemStack)) {
            int canAdd = current.getMaxStackSize() - current.getCount();
            int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
            if (toAdd <= 0) return false;
            current.incrementCount(toAdd);
            dragState.draggedItemStack.decrementCount(toAdd);
            if (dragState.draggedItemStack.isEmpty()) dragState.draggedItemStack = null;
            actions.send(Region.FURNACE_ING, (byte) slot.id, Action.MERGE);
            return true;
        } else {
            // Swap, by the server's rule (issue #334): the displaced stack goes to the drag's
            // origin slot when that is an empty inventory/hotbar slot, otherwise (a
            // furnace-sourced drag, or an occupied origin) it stays on the cursor.
            byte action = InventoryDragDropHandler.placementAction(dragState, current);
            byte originRegion = InventoryDragDropHandler.originRegionOf(dragState, actions);
            byte originSlot = InventoryDragDropHandler.originSlotOf(dragState);
            final int ingredientSlot = slot.id;
            InventoryDragDropHandler.swapIntoSlot(dragState, current, st -> setFurnaceSlot(ingredientSlot, st),
                    inventory, null, null);
            actions.send(Region.FURNACE_ING, (byte) slot.id, originRegion, originSlot, action, false);
            return true;
        }
    }

    /* ── Pick up from inventory ──────────────────────────── */

    private boolean tryPickUpFromInventory(float mouseX, float mouseY,
                                           InventoryLayoutCalculator.InventoryLayout layout) {
        int ss  = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int sx = layout.inventorySectionStartX + pad + col * (ss + pad);
            int sy = layout.mainInvContentStartY + pad + row * (ss + pad);
            if (isOverSlot(mouseX, mouseY, sx, sy)) {
                ItemStack s = inventory.getMainInventorySlot(i);
                if (s.isEmpty()) return false;
                dragState.draggedItemStack = s.copy();
                inventory.setMainInventorySlot(i, new ItemStack(0, 0));
                dragState.draggedItemOriginalSlotIndex = i;
                dragState.dragSource = InventoryDragDropHandler.DragSource.MAIN_INVENTORY;
                actions.send(Region.PLAYER_MAIN, (byte) i, Action.TAKE_ALL);
                return true;
            }
        }
        return false;
    }

    private boolean tryPickUpFromHotbar(float mouseX, float mouseY,
                                        InventoryLayoutCalculator.InventoryLayout layout) {
        int ss  = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int sx = layout.inventorySectionStartX + pad + i * (ss + pad);
            int sy = layout.hotbarRowY;
            if (isOverSlot(mouseX, mouseY, sx, sy)) {
                ItemStack s = inventory.getHotbarSlot(i);
                if (s.isEmpty()) return false;
                dragState.draggedItemStack = s.copy();
                inventory.setHotbarSlot(i, new ItemStack(0, 0));
                dragState.draggedItemOriginalSlotIndex = i;
                dragState.dragSource = InventoryDragDropHandler.DragSource.HOTBAR;
                actions.send(Region.PLAYER_HOTBAR, (byte) i, Action.TAKE_ALL);
                return true;
            }
        }
        return false;
    }

    /* ── Read/write furnace slots ────────────────────────── */

    private ItemStack getFurnaceSlot(int id) {
        return switch (id) {
            case FurnaceController.SLOT_INGREDIENT -> controller.getIngredientSlot();
            case FurnaceController.SLOT_FUEL       -> controller.getFuelSlot();
            case FurnaceController.SLOT_OUTPUT     -> controller.getOutputSlot();
            default                                -> new ItemStack(0, 0);
        };
    }

    private void setFurnaceSlot(int id, ItemStack s) {
        switch (id) {
            case FurnaceController.SLOT_INGREDIENT -> controller.setIngredientSlot(s);
            case FurnaceController.SLOT_FUEL       -> controller.setFuelSlot(s);
            case FurnaceController.SLOT_OUTPUT     -> controller.setOutputSlot(s);
        }
    }

    /* ── Drop to inventory / hotbar ─────────────────────────── */

    private boolean tryDropToInventory(float mouseX, float mouseY,
                                       InventoryLayoutCalculator.InventoryLayout layout) {
        int ss  = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int sx = layout.inventorySectionStartX + pad + col * (ss + pad);
            int sy = layout.mainInvContentStartY + pad + row * (ss + pad);
            if (isOverSlot(mouseX, mouseY, sx, sy)) {
                ItemStack target = inventory.getMainInventorySlot(i);
                byte action = InventoryDragDropHandler.placementAction(dragState, target);
                byte originRegion = InventoryDragDropHandler.originRegionOf(dragState, actions);
                byte originSlot = InventoryDragDropHandler.originSlotOf(dragState);
                if (target.isEmpty()) {
                    inventory.setMainInventorySlot(i, dragState.draggedItemStack.copy());
                    dragState.draggedItemStack = null;
                } else if (InventoryDragDropHandler.sameKind(target, dragState.draggedItemStack)) {
                    int canAdd = target.getMaxStackSize() - target.getCount();
                    int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
                    if (toAdd <= 0) return true; // full: nothing moves, nothing to send
                    target.incrementCount(toAdd);
                    dragState.draggedItemStack.decrementCount(toAdd);
                    if (dragState.draggedItemStack.isEmpty()) dragState.draggedItemStack = null;
                } else {
                    if (dragState.dragSource == InventoryDragDropHandler.DragSource.MAIN_INVENTORY
                            || dragState.dragSource == InventoryDragDropHandler.DragSource.HOTBAR) {
                        // The server's swap (issue #334): displaced → the origin slot when it
                        // is empty, else it stays carried (never overwrites the origin).
                        final int idx = i;
                        InventoryDragDropHandler.swapIntoSlot(dragState, target,
                                st -> inventory.setMainInventorySlot(idx, st), inventory, null, null);
                    } else {
                        ItemStack temp = target.copy();
                        inventory.setMainInventorySlot(i, dragState.draggedItemStack.copy());
                        // DragSource.NONE (furnace-sourced) — leave displaced item on cursor
                        // so user can place it in a furnace slot
                        dragState.draggedItemStack = temp;
                        dragState.draggedItemOriginalSlotIndex = i;
                        dragState.dragSource = InventoryDragDropHandler.DragSource.MAIN_INVENTORY;
                    }
                }
                actions.send(Region.PLAYER_MAIN, (byte) i, originRegion, originSlot, action, false);
                return true;
            }
        }
        return false;
    }

    private boolean tryDropToHotbar(float mouseX, float mouseY,
                                    InventoryLayoutCalculator.InventoryLayout layout) {
        int ss  = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int sx = layout.inventorySectionStartX + pad + i * (ss + pad);
            int sy = layout.hotbarRowY;
            if (isOverSlot(mouseX, mouseY, sx, sy)) {
                ItemStack target = inventory.getHotbarSlot(i);
                byte action = InventoryDragDropHandler.placementAction(dragState, target);
                byte originRegion = InventoryDragDropHandler.originRegionOf(dragState, actions);
                byte originSlot = InventoryDragDropHandler.originSlotOf(dragState);
                if (target.isEmpty()) {
                    inventory.setHotbarSlot(i, dragState.draggedItemStack.copy());
                    dragState.draggedItemStack = null;
                } else if (InventoryDragDropHandler.sameKind(target, dragState.draggedItemStack)) {
                    int canAdd = target.getMaxStackSize() - target.getCount();
                    int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
                    if (toAdd <= 0) return true; // full: nothing moves, nothing to send
                    target.incrementCount(toAdd);
                    dragState.draggedItemStack.decrementCount(toAdd);
                    if (dragState.draggedItemStack.isEmpty()) dragState.draggedItemStack = null;
                } else {
                    if (dragState.dragSource == InventoryDragDropHandler.DragSource.MAIN_INVENTORY
                            || dragState.dragSource == InventoryDragDropHandler.DragSource.HOTBAR) {
                        // The server's swap (issue #334), as above.
                        final int idx = i;
                        InventoryDragDropHandler.swapIntoSlot(dragState, target,
                                st -> inventory.setHotbarSlot(idx, st), inventory, null, null);
                    } else {
                        ItemStack temp = target.copy();
                        inventory.setHotbarSlot(i, dragState.draggedItemStack.copy());
                        // DragSource.NONE (furnace-sourced) — leave displaced item on cursor
                        dragState.draggedItemStack = temp;
                        dragState.draggedItemOriginalSlotIndex = i;
                        dragState.dragSource = InventoryDragDropHandler.DragSource.HOTBAR;
                    }
                }
                actions.send(Region.PLAYER_HOTBAR, (byte) i, originRegion, originSlot, action, false);
                return true;
            }
        }
        return false;
    }

    /**
     * The furnace this screen showed is gone (its block was broken): a stack taken from one of its
     * slots can no longer go back there, so the carried stack goes to the inventory, else the
     * world. Never into a furnace slot: that state is dead.
     */
    void returnCarriedToPlayer() {
        if (!dragState.isDragging()) return;
        if (dragState.draggedItemOriginalSlotIndex < 3000) {
            InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, new ItemStack[0], null, actions);
        }
        // A furnace-sourced carried stack cannot go home (the furnace is dead): the single
        // DEPOSIT_CARRIED below puts it into the replica (issue #334).
        intoInventoryOrWorld();
    }

    /**
     * Puts the carried stack back where it came from, sending each step as the action the
     * server replays to the same outcome (issue #334): an inventory/hotbar origin as
     * PUT_ALL/MERGE (InventoryDragDropHandler's sink return), a furnace slot likewise.
     */
    private void returnCarriedHome() {
        if (!dragState.isDragging()) return;
        if (dragState.draggedItemOriginalSlotIndex >= 3000) {
            returnToFurnaceSlot(dragState.draggedItemOriginalSlotIndex - 3000);
        } else {
            InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, new ItemStack[0], null, actions);
        }
    }

    /**
     * A furnace-sourced carried stack back into its slot: PUT_ALL into an empty slot, MERGE
     * into a stackable one with room. The output slot is TAKE-only on the server, so with a
     * replaying sink an output stack never goes back (it stays carried; close deposits it);
     * local-only play keeps the old put-back-into-an-empty-output rule.
     */
    private void returnToFurnaceSlot(int slotId) {
        ItemStack carried = dragState.draggedItemStack;
        if (slotId == FurnaceController.SLOT_OUTPUT) {
            if (!actions.live() && getFurnaceSlot(slotId).isEmpty()) {
                setFurnaceSlot(slotId, carried.copy());
                dragState.clear();
            }
            return;
        }
        if (!furnaceAccepts(slotId, carried)) return;
        ItemStack current = getFurnaceSlot(slotId);
        if (current.isEmpty()) {
            setFurnaceSlot(slotId, carried.copy());
            dragState.clear();
            actions.send(regionOf(slotId), (byte) slotId, Action.PUT_ALL);
        } else if (InventoryDragDropHandler.sameKind(current, carried)
                && current.getCount() < current.getMaxStackSize()) {
            int toAdd = Math.min(current.getMaxStackSize() - current.getCount(), carried.getCount());
            current.incrementCount(toAdd);
            carried.decrementCount(toAdd);
            if (carried.isEmpty()) dragState.clear();
            actions.send(regionOf(slotId), (byte) slotId, Action.MERGE);
        }
    }

    /**
     * Whether a furnace input slot takes {@code stack} — the server's furnaceDepositAllowed
     * rule (fuels only in the fuel slot, smeltables only in the ingredient slot). Without a
     * smelting manager (tests) every stack is accepted.
     */
    private boolean furnaceAccepts(int slotId, ItemStack stack) {
        com.stonebreak.crafting.SmeltingManager m = controller.getSmeltingManager();
        if (m == null || stack == null || stack.isEmpty()) return true;
        return switch (slotId) {
            case FurnaceController.SLOT_FUEL -> m.getBurnTimePerUnit(stack.getItem()) > 0;
            case FurnaceController.SLOT_INGREDIENT -> m.getRecipe(stack) != null;
            default -> true;
        };
    }

    /**
     * Whatever of the carried stack fits goes into the inventory; only the rest is dropped. (Adding
     * part of it and then dropping the whole stack duplicated the part that fit.)
     */
    private void intoInventoryOrWorld() {
        if (!dragState.isDragging()) return;
        // The SERVER deposits the carried stack into the replica and drops whatever does
        // not fit at the player (issue #334); the local pass below is the prediction —
        // its remainder is discarded from the mirror and the next sync repairs it. Without
        // a session the local drop is the only one (issue #307: closing never destroys
        // items), so the remainder goes to the world here.
        actions.send(Action.DEPOSIT_CARRIED);
        ItemStack rest = FurnaceShiftTransfer.intoInventory(dragState.draggedItemStack, inventory);
        if (rest.isEmpty() || actions.live()) {
            dragState.clear();
        } else {
            dragState.draggedItemStack = rest;
            worldDrop.accept(dragState);
        }
    }

    /** Test seam: where stacks dropped into the world go. */
    void setWorldDrop(java.util.function.Consumer<InventoryDragDropHandler.DragState> drop) {
        this.worldDrop = drop;
    }

    /**
     * Closing: the carried stack goes back to its slot (furnace or inventory), else into the
     * inventory (DEPOSIT_CARRIED), else the world. Whenever this screen sent any action since
     * it opened, the server's cursor may still hold a stack the prediction placed (a refused
     * action), so the close always ends with a DEPOSIT_CARRIED then (issue #334).
     */
    public void handleCloseWithDraggedItems() {
        boolean mayCarry = actions.hasSentSinceBind();
        if (dragState.isDragging()) {
            returnCarriedHome();
            // Then any inventory space; only what does not fit is dropped
            intoInventoryOrWorld();
        }
        if (mayCarry && actions.lastAction() != Action.DEPOSIT_CARRIED) {
            actions.send(Action.DEPOSIT_CARRIED);
        }
    }

    record FurnaceSlot(int id, int x, int y) {}
}
