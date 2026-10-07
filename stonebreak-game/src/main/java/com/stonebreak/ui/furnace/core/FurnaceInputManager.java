package com.stonebreak.ui.furnace.core;

import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.inventoryScreen.handlers.PointerFrame;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import org.lwjgl.glfw.GLFW;

/**
 * Handles mouse input for the furnace screen: clicking furnace slots,
 * drag-and-drop into furnace, and normal inventory drag-and-drop.
 */
public class FurnaceInputManager implements ContainerSlotInput {

    protected final InputHandler inputHandler;
    protected final Inventory inventory;
    protected final InventoryDragDropHandler.DragState dragState;
    protected final FurnaceController controller;

    private static final long DOUBLE_CLICK_THRESHOLD_MS = 350L;
    private static final float DOUBLE_CLICK_RADIUS = 40f;
    private long lastClickTimeMs = 0L;
    private float lastClickX = 0f, lastClickY = 0f;

    /** Drops a carried stack at the player's feet; tests observe it. */
    private java.util.function.Consumer<InventoryDragDropHandler.DragState> worldDrop =
        InventoryDragDropHandler::dropEntireStackIntoWorld;

    // Right-click drag state
    private boolean rightDragActive = false;
    private final java.util.Set<Integer> rightDragVisitedSlots = new java.util.HashSet<>();

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

    public void handleMouseInput(int screenWidth, int screenHeight) {
        handlePointer(PointerFrame.poll(inputHandler), screenWidth, screenHeight);
    }

    /**
     * One frame of pointer input. The legacy mouse path polls it; UI documents synthesize it at a
     * slot (#289, {@link ContainerSlotInput}), so both run every rule below.
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
            rightDragActive = false;
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
            if (isDoubleClick(mouseX, mouseY)) {
                recordClick(mouseX, mouseY);
                return;
            }
            if (tryDropToFurnaceSlots(mouseX, mouseY, layout)) {
                recordClick(mouseX, mouseY);
                return;
            }
            if (!tryDropToInventory(mouseX, mouseY, layout) && !tryDropToHotbar(mouseX, mouseY, layout)) {
                if (dragState.isDragging()) {
                    // Return inventory/hotbar-sourced items to their origin
                    InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, new ItemStack[0], null);

                    // Return furnace-sourced items (index >= 3000) to their furnace slot
                    if (dragState.isDragging() && dragState.draggedItemOriginalSlotIndex >= 3000) {
                        int slotId = dragState.draggedItemOriginalSlotIndex - 3000;
                        ItemStack current = getFurnaceSlot(slotId);
                        if (current.isEmpty()) {
                            setFurnaceSlot(slotId, dragState.draggedItemStack.copy());
                            dragState.clear();
                        }
                    }

                    // Click outside the panel bounds → drop into world
                    if (dragState.isDragging() &&
                        (mouseX < layout.panelStartX ||
                         mouseX > layout.panelStartX + layout.inventoryPanelWidth ||
                         mouseY < layout.panelStartY ||
                         mouseY > layout.panelStartY + layout.inventoryPanelHeight)) {
                        InventoryDragDropHandler.dropEntireStackIntoWorld(dragState);
                    }
                }
            }
            recordClick(mouseX, mouseY);
            return;
        }

        if (tryPickUpFromFurnaceSlots(mouseX, mouseY, layout) ||
            tryPickUpFromInventory(mouseX, mouseY, layout) ||
            tryPickUpFromHotbar(mouseX, mouseY, layout)) {
            recordClick(mouseX, mouseY);
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
     * an ingredient/fuel remainder stays in its slot; output overflow keeps its
     * drop-at-feet behaviour, but only the overflow is dropped (#319 — the old
     * code cleared the slot regardless, deleting the remainder, and dropped the
     * whole output stack after a partial add, duplicating it).
     */
    void shiftClickSlot(int slotId) {
        ItemStack item = getFurnaceSlot(slotId);
        if (item.isEmpty()) return;
        ItemStack remainder = FurnaceShiftTransfer.intoInventory(item, inventory);
        if (slotId == FurnaceController.SLOT_OUTPUT && !remainder.isEmpty()) {
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
                    placeOne(target);
                    rightDragVisitedSlots.add(i);
                }
                break;
            }
        }
    }

    private boolean canPlaceOne(ItemStack target) {
        return target.isEmpty() || target.canStackWith(dragState.draggedItemStack);
    }

    private void placeOne(ItemStack target) {
        if (target.isEmpty()) {
            target.setBlockTypeId(dragState.draggedItemStack.getBlockTypeId());
            target.setCount(1);
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
        return true;
    }

    /* ── Drop to furnace slots ───────────────────────────── */

    private boolean tryDropToFurnaceSlots(float mouseX, float mouseY,
                                          InventoryLayoutCalculator.InventoryLayout layout) {
        FurnaceSlot slot = hitTestFurnaceSlots(mouseX, mouseY, layout);
        if (slot == null) return false;
        if (slot.id == FurnaceController.SLOT_OUTPUT) return false; // can't drop into output

        // Fuel slot — check burn time
        if (slot.id == FurnaceController.SLOT_FUEL) {
            int burn = controller.getSmeltingManager().getBurnTimePerUnit(dragState.draggedItemStack.getItem());
            if (burn <= 0) return false;

            ItemStack fuel = controller.getFuelSlot();
            if (!fuel.isEmpty()) {
                if (fuel.canStackWith(dragState.draggedItemStack)) {
                    int canAdd = fuel.getMaxStackSize() - fuel.getCount();
                    int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
                    if (toAdd <= 0) return false;
                    fuel.incrementCount(toAdd);
                    dragState.draggedItemStack.decrementCount(toAdd);
                    if (dragState.draggedItemStack.isEmpty()) dragState.draggedItemStack = null;
                    return true;
                }
                return false;
            }
            // Add as new fuel — do NOT pre-credit burnTimeRemaining here.
            // Fuel is consumed one unit at a time by FurnaceState.tick (run by the registry),
            // so removing the stack mid-burn only refunds un-started items.
            controller.setFuelSlot(dragState.draggedItemStack.copy());
            dragState.draggedItemStack = null;
            return true;
        }

        // Ingredient slot
        ItemStack current = getFurnaceSlot(slot.id);
        if (current.isEmpty()) {
            setFurnaceSlot(slot.id, dragState.draggedItemStack.copy());
            dragState.draggedItemStack = null;
            return true;
        } else if (current.canStackWith(dragState.draggedItemStack)) {
            int canAdd = current.getMaxStackSize() - current.getCount();
            int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
            current.incrementCount(toAdd);
            dragState.draggedItemStack.decrementCount(toAdd);
            if (dragState.draggedItemStack.isEmpty()) dragState.draggedItemStack = null;
            return true;
        } else {
            // Swap
            ItemStack temp = current.copy();
            setFurnaceSlot(slot.id, dragState.draggedItemStack.copy());
            dragState.draggedItemStack = temp;
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
                return true;
            }
        }
        return false;
    }

    /* ── Double-click helpers (YAGNI placeholder) ────────── */

    private boolean isDoubleClick(float x, float y) {
        long now = System.currentTimeMillis();
        float dx = x - lastClickX, dy = y - lastClickY;
        return (now - lastClickTimeMs < DOUBLE_CLICK_THRESHOLD_MS &&
                dx * dx + dy * dy < DOUBLE_CLICK_RADIUS * DOUBLE_CLICK_RADIUS);
    }

    private void recordClick(float x, float y) {
        lastClickTimeMs = System.currentTimeMillis();
        lastClickX = x;
        lastClickY = y;
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
                if (target.isEmpty()) {
                    inventory.setMainInventorySlot(i, dragState.draggedItemStack.copy());
                    dragState.draggedItemStack = null;
                } else if (target.canStackWith(dragState.draggedItemStack)) {
                    int canAdd = target.getMaxStackSize() - target.getCount();
                    int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
                    target.incrementCount(toAdd);
                    dragState.draggedItemStack.decrementCount(toAdd);
                    if (dragState.draggedItemStack.isEmpty()) dragState.draggedItemStack = null;
                } else {
                    ItemStack temp = target.copy();
                    inventory.setMainInventorySlot(i, dragState.draggedItemStack.copy());
                    if (dragState.dragSource == InventoryDragDropHandler.DragSource.MAIN_INVENTORY) {
                        inventory.setMainInventorySlot(dragState.draggedItemOriginalSlotIndex, temp);
                        dragState.clear();
                    } else if (dragState.dragSource == InventoryDragDropHandler.DragSource.HOTBAR) {
                        inventory.setHotbarSlot(dragState.draggedItemOriginalSlotIndex, temp);
                        dragState.clear();
                    } else {
                        // DragSource.NONE (furnace-sourced) — leave displaced item on cursor
                        // so user can place it in a furnace slot
                        dragState.draggedItemStack = temp;
                        dragState.draggedItemOriginalSlotIndex = i;
                        dragState.dragSource = InventoryDragDropHandler.DragSource.MAIN_INVENTORY;
                    }
                }
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
                if (target.isEmpty()) {
                    inventory.setHotbarSlot(i, dragState.draggedItemStack.copy());
                    dragState.draggedItemStack = null;
                } else if (target.canStackWith(dragState.draggedItemStack)) {
                    int canAdd = target.getMaxStackSize() - target.getCount();
                    int toAdd = Math.min(canAdd, dragState.draggedItemStack.getCount());
                    target.incrementCount(toAdd);
                    dragState.draggedItemStack.decrementCount(toAdd);
                    if (dragState.draggedItemStack.isEmpty()) dragState.draggedItemStack = null;
                } else {
                    ItemStack temp = target.copy();
                    inventory.setHotbarSlot(i, dragState.draggedItemStack.copy());
                    if (dragState.dragSource == InventoryDragDropHandler.DragSource.HOTBAR) {
                        inventory.setHotbarSlot(dragState.draggedItemOriginalSlotIndex, temp);
                        dragState.clear();
                    } else if (dragState.dragSource == InventoryDragDropHandler.DragSource.MAIN_INVENTORY) {
                        inventory.setMainInventorySlot(dragState.draggedItemOriginalSlotIndex, temp);
                        dragState.clear();
                    } else {
                        // DragSource.NONE (furnace-sourced) — leave displaced item on cursor
                        dragState.draggedItemStack = temp;
                        dragState.draggedItemOriginalSlotIndex = i;
                        dragState.dragSource = InventoryDragDropHandler.DragSource.HOTBAR;
                    }
                }
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
            InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, new ItemStack[0], null);
        }
        intoInventoryOrWorld();
    }

    /**
     * Whatever of the carried stack fits goes into the inventory; only the rest is dropped. (Adding
     * part of it and then dropping the whole stack duplicated the part that fit.)
     */
    private void intoInventoryOrWorld() {
        if (!dragState.isDragging()) return;
        ItemStack rest = FurnaceShiftTransfer.intoInventory(dragState.draggedItemStack, inventory);
        if (rest.isEmpty()) {
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

    public void handleCloseWithDraggedItems() {
        if (!dragState.isDragging()) return;

        // Try to return to furnace slot of origin
        if (dragState.draggedItemOriginalSlotIndex >= 3000) {
            int slotId = dragState.draggedItemOriginalSlotIndex - 3000;
            ItemStack current = getFurnaceSlot(slotId);
            if (current.isEmpty()) {
                setFurnaceSlot(slotId, dragState.draggedItemStack.copy());
                dragState.clear();
                return;
            }
        }

        // Try returning to original inventory/hotbar slot
        if (dragState.isDragging()) {
            InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, new ItemStack[0], null);
        }

        // Then any inventory space; only what does not fit is dropped
        intoInventoryOrWorld();
    }

    record FurnaceSlot(int id, int x, int y) {}
}
