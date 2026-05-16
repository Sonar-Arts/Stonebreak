package com.stonebreak.ui.furnace.core;

import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

/**
 * Handles mouse input for the furnace screen: clicking furnace slots,
 * drag-and-drop into furnace, and normal inventory drag-and-drop.
 */
public class FurnaceInputManager {

    protected final InputHandler inputHandler;
    protected final Inventory inventory;
    protected final InventoryDragDropHandler.DragState dragState;
    protected final FurnaceController controller;

    private static final long DOUBLE_CLICK_THRESHOLD_MS = 350L;
    private static final float DOUBLE_CLICK_RADIUS = 40f;
    private long lastClickTimeMs = 0L;
    private float lastClickX = 0f, lastClickY = 0f;

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
        InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(screenWidth, screenHeight);

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;
        boolean shiftDown = inputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) ||
                           inputHandler.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);

        boolean leftPressed = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean rightPressed = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        boolean rightDown = inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        if (leftPressed) {
            if (shiftDown) {
                handleShiftClick(mouseX, mouseY, layout);
            } else {
                handleLeftClick(mouseX, mouseY, layout);
            }
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
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

        if (slot.id == FurnaceController.SLOT_OUTPUT) {
            // Move output to inventory
            ItemStack out = controller.getOutputSlot();
            if (!out.isEmpty()) {
                if (!inventory.addItem(out.copy())) {
                    com.stonebreak.player.Player player = Game.getPlayer();
                    if (player != null)
                        com.stonebreak.util.DropUtil.dropItemFromPlayer(player, out.copy());
                }
                controller.setOutputSlot(new ItemStack(0, 0));
            }
        } else {
            // Move ingredient/fuel to inventory
            ItemStack item = getFurnaceSlot(slot.id);
            if (!item.isEmpty()) {
                inventory.addItem(item.copy());
                setFurnaceSlot(slot.id, new ItemStack(0, 0));
            }
        }
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
        int ss   = InventoryLayoutCalculator.getSlotSize();
        int pad  = InventoryLayoutCalculator.getSlotPadding();
        int ppad = InventoryLayoutCalculator.getPanelPadding();
        int th   = InventoryLayoutCalculator.getTitleHeight();
        int sp   = InventoryLayoutCalculator.getSectionSpacing();

        float titleY = layout.panelStartY + ppad + th + sp;
        int furnaceStartX = layout.panelStartX + ppad + (layout.inventoryPanelWidth - ppad * 2 - (ss * 2 + pad)) / 2;
        int furnaceY = (int) titleY + (int)(th / 2f);

        // Ingredient
        if (isOverSlot(mouseX, mouseY, furnaceStartX, furnaceY))
            return new FurnaceSlot(FurnaceController.SLOT_INGREDIENT, furnaceStartX, furnaceY);
        // Fuel
        int fuelY = furnaceY + ss + pad;
        if (isOverSlot(mouseX, mouseY, furnaceStartX, fuelY))
            return new FurnaceSlot(FurnaceController.SLOT_FUEL, furnaceStartX, fuelY);
        // Output
        int outputX = furnaceStartX + ss + pad;
        if (isOverSlot(mouseX, mouseY, outputX, furnaceY))
            return new FurnaceSlot(FurnaceController.SLOT_OUTPUT, outputX, furnaceY);

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
                if (fuel.getItem() == dragState.draggedItemStack.getItem()) {
                    fuel.incrementCount(1);
                    dragState.draggedItemStack.decrementCount(1);
                    if (dragState.draggedItemStack.isEmpty()) dragState.draggedItemStack = null;
                    return true;
                }
                return false;
            }
            // Add as new fuel
            int totalBurn = burn * dragState.draggedItemStack.getCount();
            controller.setFuelSlot(dragState.draggedItemStack.copy());
            controller.setBurnTimeRemaining(totalBurn);
            dragState.draggedItemStack = null;
            return true;
        }

        // Ingredient slot
        ItemStack current = getFurnaceSlot(slot.id);
        if (current.isEmpty()) {
            setFurnaceSlot(slot.id, dragState.draggedItemStack.copy());
            dragState.draggedItemStack.decrementCount(1);
            if (dragState.draggedItemStack.isEmpty()) dragState.draggedItemStack = null;
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

        // Try adding to any available inventory space
        if (dragState.isDragging()) {
            if (inventory.addItem(dragState.draggedItemStack)) {
                dragState.clear();
            } else {
                InventoryDragDropHandler.dropEntireStackIntoWorld(dragState);
            }
        }
    }

    record FurnaceSlot(int id, int x, int y) {}
}
