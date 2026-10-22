package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.inventoryScreen.handlers.PointerFrame;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

/**
 * The inventory screen's slot rules, and — through a few layout/drop hooks — those of every screen
 * built on it (the crafting table's {@link WorkbenchInputManager}). The pointer dispatch lives only
 * here so each crafting/inventory interaction reaches every screen: subclasses supply
 * {@link #layoutFor} and {@link #placeDraggedItem}, never their own copy of {@link #handlePointer}
 * (issue #317). The screens' buttons and tabs are the documents' own: they reach {@link #craftAll}
 * and {@link #sort} as host actions, never through a pointer frame.
 */
public class InventoryInputManager implements ContainerSlotInput {

    protected final InputHandler inputHandler;
    protected final Inventory inventory;
    protected final InventorySlotManager slotManager;
    protected final InventoryDragDropHandler.DragState dragState;
    protected final InventoryCraftingManager craftingManager;

    // Screen dimensions (updated each frame for drag release)
    protected int lastScreenWidth, lastScreenHeight;

    // Pointer of the frame being handled
    private float pointerX, pointerY;

    // Double-click gather (#352): a second press, within the window, on the slot the held stack
    // was just picked up from. Every other press clears it, so only a pick-up arms the gather.
    private static final long DOUBLE_CLICK_THRESHOLD_MS = 350L;
    private java.util.function.LongSupplier clock = System::currentTimeMillis;
    private long pickUpTimeMs = 0L;
    private String pickUpSlot;

    // Right-click drag state
    private final java.util.Set<Integer> rightDragVisitedSlots = new java.util.HashSet<>();

    public InventoryInputManager(InputHandler inputHandler,
                                Inventory inventory,
                                InventorySlotManager slotManager,
                                InventoryCraftingManager craftingManager) {
        this.inputHandler = inputHandler;
        this.inventory = inventory;
        this.slotManager = slotManager;
        this.craftingManager = craftingManager;
        this.dragState = new InventoryDragDropHandler.DragState();
    }

    /**
     * One frame of pointer input, synthesized at a slot by the screen's document (#289,
     * {@link ContainerSlotInput}): every rule below runs from it.
     */
    @Override
    public final void handlePointer(PointerFrame pointer, int screenWidth, int screenHeight) {
        InventoryLayoutCalculator.InventoryLayout layout = layoutFor(screenWidth, screenHeight);

        float mouseX = pointer.x();
        float mouseY = pointer.y();
        this.pointerX = mouseX;
        this.pointerY = mouseY;
        this.lastScreenWidth = screenWidth;
        this.lastScreenHeight = screenHeight;
        boolean shiftDown = pointer.shift();

        boolean leftMouseButtonPressed = pointer.leftPressed();
        boolean rightMouseButtonPressed = pointer.rightPressed();
        boolean rightMouseButtonDown = pointer.rightDown();

        if (pointer.middlePressed() || rightMouseButtonPressed) {
            pickUpSlot = null; // not a pick-up: the next left press never gathers
        }

        // Middle click: balance the crafting grid when aimed at a cell, otherwise sort.
        if (tryHandleMiddleClick(pointer.middlePressed(), mouseX, mouseY, layout)) {
            return;
        }

        if (leftMouseButtonPressed) {
            handleLeftClick(mouseX, mouseY, shiftDown, layout);
        } else if (rightMouseButtonDown && dragState.isDragging()) {
            handleRightDrag(mouseX, mouseY, layout);
        } else if (rightMouseButtonPressed) {
            handleRightClick(mouseX, mouseY, layout);
        } else {
            handleDragRelease(screenWidth, screenHeight);
        }

        if (!rightMouseButtonDown) {
            clearRightDrag();
        }
    }

    @Override
    public float[] slotOrigin(String slot, int screenWidth, int screenHeight) {
        if ("outside".equals(slot)) {
            return new float[]{OUTSIDE, OUTSIDE};
        }
        if ("panel".equals(slot)) {
            // just inside the slot panel's top-left corner, which no slot, button or tab covers
            InventoryLayoutCalculator.InventoryLayout layout = layoutFor(screenWidth, screenHeight);
            float half = slotSize() / 2f;
            return new float[]{layout.panelStartX + 1 - half, layout.panelStartY + 1 - half};
        }
        int[] o = slotManager.slotOrigin(slot, layoutFor(screenWidth, screenHeight));
        return o == null ? null : new float[]{o[0], o[1]};
    }

    @Override
    public float slotSize() {
        return InventoryLayoutCalculator.getSlotSize();
    }

    /** Where the pointer of the frame being handled is. */
    protected Vector2f pointerPosition() {
        return new Vector2f(pointerX, pointerY);
    }

    /**
     * The slot layout this screen's clicks are hit-tested against — the same layout its document
     * draws. The inventory screen uses the centre column of its three-column layout.
     */
    protected InventoryLayoutCalculator.InventoryLayout layoutFor(int screenWidth, int screenHeight) {
        return InventoryLayoutCalculator.calculateThreeColumnLayout(screenWidth, screenHeight).center;
    }

    /**
     * Middle-click helper shared by the inventory and workbench screens: balances
     * the crafting grid when aimed at a cell, otherwise sorts the inventory.
     * Consumes the press so it does not leak into further handling. Returns true
     * when a middle-click was processed.
     */
    protected boolean tryHandleMiddleClick(boolean middlePressed, float mouseX, float mouseY,
                                           InventoryLayoutCalculator.InventoryLayout layout) {
        if (!middlePressed) {
            return false;
        }
        handleMiddleClick(mouseX, mouseY, layout);
        consumePress(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        return true;
    }

    /** Marks the real button press handled, so nothing else acts on it this frame. */
    protected final void consumePress(int button) {
        if (inputHandler != null) {
            inputHandler.consumeMouseButtonPress(button);
        }
    }

    private void handleMiddleClick(float mouseX, float mouseY,
                                   InventoryLayoutCalculator.InventoryLayout layout) {
        if (slotManager.tryBalanceCraftingSlot(mouseX, mouseY, layout)) {
            craftingManager.updateCraftingOutput();
        } else {
            // Never sort mid-drag (handleSort guards): it re-populates the drag's source slot
            // and corrupts the later swap-by-index placement. The sort ships as SORT like the
            // Sort button's — a local-only sort would be undone by the next sync (#334).
            handleSort();
        }
    }

    protected void handleLeftClick(float mouseX, float mouseY, boolean shiftDown,
                                  InventoryLayoutCalculator.InventoryLayout layout) {
        String slot = slotManager.slotAt(mouseX, mouseY, layout);
        boolean doubleClick = isDoubleClick(slot);
        pickUpSlot = null;
        if (shiftDown) {
            handleShiftClickTransfer(mouseX, mouseY, layout);
            consumePress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return;
        }

        // If dragging, craft another batch onto the cursor when aiming at the
        // output slot, then check for double-click gather before placing.
        if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
            if (tryCraftOntoDraggedStack(mouseX, mouseY, layout)) {
                consumePress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }
            if (doubleClick) {
                handleDoubleClickGather();
                recordPickUp(slot);
                consumePress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }
            placeDraggedItem(layout);
            consumePress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return;
        }

        if (dragState.draggedItemStack == null) {
            // Try to pick up item
            if (tryPickUpItem(mouseX, mouseY, layout)) {
                recordPickUp(slot);
                consumePress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            }
        }
    }

    protected void handleRightClick(float mouseX, float mouseY,
                                   InventoryLayoutCalculator.InventoryLayout layout) {
        // Single right-click just drops one item when dragging (old behavior)
        if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
            boolean placedOne = tryHandleRightClickDropSingle(mouseX, mouseY, layout);
            if (placedOne) {
                consumePress(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            }
        }
    }

    protected void handleDragRelease(int screenWidth, int screenHeight) {
        if (dragState.draggedItemStack == null || dragState.draggedItemStack.isEmpty()) {
            clearDraggedItemState();
        }
        // Otherwise: dragging in progress — wait for the second left-click (handled in handleLeftClick)
    }

    @Override
    public ItemStack[] craftingSlots() {
        return craftingManager.getCraftingInputSlots();
    }

    @Override
    public ItemStack craftingOutput() {
        return craftingManager.getCraftingOutputSlot();
    }

    /** The Craft All button's rule, for UI documents (#289). */
    @Override
    public boolean craftAll() {
        handleCraftAll();
        return true;
    }

    /** The Sort button's rule (never mid-drag), for UI documents (#289). */
    @Override
    public boolean sort() {
        handleSort();
        return true;
    }

    private void handleCraftAll() {
        if (craftingManager.getCraftingOutputSlot() == null ||
            craftingManager.getCraftingOutputSlot().isEmpty()) {
            return;
        }
        slotManager.depositCraftedStacks(craftingManager.craftAll());
    }

    private void handleSort() {
        if (dragState.isDragging()) return; // never mid-drag: it would re-populate the drag's source slot
        inventory.sortInventory();
        slotManager.actions.send(com.stonebreak.network.packet.player.ContainerActionC2S.Action.SORT);
    }

    private boolean tryPickUpItem(float mouseX, float mouseY,
                              InventoryLayoutCalculator.InventoryLayout layout) {
        // Try main inventory slots
        if (slotManager.tryPickUpFromMainInventory(mouseX, mouseY, layout, dragState)) return true;

        // Try hotbar slots
        if (slotManager.tryPickUpFromHotbar(mouseX, mouseY, layout, dragState)) return true;

        // Try crafting input slots
        if (slotManager.tryPickUpFromCraftingInput(mouseX, mouseY, layout, dragState)) {
            craftingManager.updateCraftingOutput();
            return true;
        }

        // Try crafting output slot: take one batch onto the cursor
        if (slotManager.isMouseOverCraftingOutput(mouseX, mouseY, layout)) {
            ItemStack batch = craftingManager.takeCraftBatch();
            if (batch != null) {
                slotManager.startDragFromCraftingOutput(batch, dragState);
                // The SERVER computes the result and consumes the inputs (issue #334);
                // this local take is the prediction.
                slotManager.actions.send(slotManager.craftOutRegion, (byte) 0,
                        com.stonebreak.network.packet.player.ContainerActionC2S.Action.TAKE_OUTPUT);
                return true;
            }
        }

        return false;
    }

    /**
     * Clicking the output slot while dragging a compatible stack crafts another
     * batch and accumulates it onto the cursor (up to the stack limit), instead
     * of placing the held stack. Returns true when another batch was crafted.
     */
    protected boolean tryCraftOntoDraggedStack(float mouseX, float mouseY,
                                               InventoryLayoutCalculator.InventoryLayout layout) {
        if (dragState.draggedItemStack == null || dragState.draggedItemStack.isEmpty()) {
            return false;
        }
        if (!slotManager.isMouseOverCraftingOutput(mouseX, mouseY, layout)) {
            return false;
        }
        ItemStack dragged = dragState.draggedItemStack;
        ItemStack output = craftingManager.getCraftingOutputSlot();
        if (output == null || output.isEmpty()) {
            return false;
        }
        if (!dragged.canStackWith(output)) {
            return false;
        }
        if (dragged.getCount() + output.getCount() > dragged.getMaxStackSize()) {
            return false;
        }
        ItemStack batch = craftingManager.takeCraftBatch();
        if (batch == null) {
            return false;
        }
        dragged.incrementCount(batch.getCount());
        slotManager.actions.send(slotManager.craftOutRegion, (byte) 0,
                com.stonebreak.network.packet.player.ContainerActionC2S.Action.TAKE_OUTPUT);
        return true;
    }

    private void handleShiftClickTransfer(float mouseX, float mouseY,
                                         InventoryLayoutCalculator.InventoryLayout layout) {
        // Check crafting output slot first — shift-clicking it crafts all possible
        if (slotManager.tryShiftClickCraftingOutput(mouseX, mouseY, layout)) {
            return;
        }

        // Check crafting input slots
        if (slotManager.tryShiftClickCraftingInput(mouseX, mouseY, layout)) {
            craftingManager.updateCraftingOutput();
            return;
        }

        // Check main inventory -> hotbar transfer
        if (slotManager.tryShiftClickMainInventoryToHotbar(mouseX, mouseY, layout)) return;

        // Check hotbar -> main inventory transfer
        if (slotManager.tryShiftClickHotbarToMainInventory(mouseX, mouseY, layout)) return;
    }

    private boolean tryHandleRightClickDropSingle(float mouseX, float mouseY,
                                                 InventoryLayoutCalculator.InventoryLayout layout) {
        if (dragState.draggedItemStack == null || dragState.draggedItemStack.isEmpty()) {
            return false;
        }

        // Try main inventory slots
        if (slotManager.tryDropOneToMainInventory(mouseX, mouseY, layout, dragState)) return true;

        // Try hotbar slots
        if (slotManager.tryDropOneToHotbar(mouseX, mouseY, layout, dragState)) return true;

        // Try crafting input slots
        if (slotManager.tryDropOneToCraftingInput(mouseX, mouseY, layout, dragState)) {
            craftingManager.updateCraftingOutput();
            return true;
        }

        return false;
    }

    /**
     * Whether a press on {@code slot} gathers: it is the slot the held stack was just picked up
     * from, within the window. Same slot only (#352): a distance radius misread a click on the next
     * slot as a double-click once the slot pitch dropped below it at small UI scales. The crafting
     * output never gathers; a press there that cannot craft another batch places as usual.
     */
    private boolean isDoubleClick(String slot) {
        return slot != null && slot.equals(pickUpSlot) && !"craft-output".equals(slot)
            && clock.getAsLong() - pickUpTimeMs <= DOUBLE_CLICK_THRESHOLD_MS;
    }

    private void recordPickUp(String slot) {
        pickUpTimeMs = clock.getAsLong();
        pickUpSlot = slot;
    }

    /** Test seam: the clock the double-click window is measured on, in milliseconds. */
    void useClock(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    private void handleDoubleClickGather() {
        slotManager.gatherMatchingItemsToStack(dragState);
    }

    private void handleRightDrag(float mouseX, float mouseY,
                                 InventoryLayoutCalculator.InventoryLayout layout) {
        boolean placed = slotManager.tryRightDragDepositToSlot(
                mouseX, mouseY, layout, dragState, rightDragVisitedSlots);
        if (placed) {
            craftingManager.updateCraftingOutput();
        }
    }

    private void clearRightDrag() {
        rightDragVisitedSlots.clear();
    }

    /**
     * Places (or swaps/stacks) the held stack into the slot under the cursor, falling back to
     * its original slot or a world drop. Screens with a different grid override this to use
     * their own drop handler.
     */
    protected void placeDraggedItem(InventoryLayoutCalculator.InventoryLayout layout) {
        Vector2f mousePos = pointerPosition();
        InventoryDragDropHandler.placeDraggedItem(dragState, inventory,
                                                craftingManager.getCraftingInputSlots(),
                                                mousePos, layout,
                                                isOutsidePanel(mousePos.x, mousePos.y, lastScreenWidth, lastScreenHeight),
                                                craftingManager::updateCraftingOutput,
                                                slotManager.actions, slotManager.craftGridRegion);
    }

    /**
     * Whether a point is beyond everything the inventory screen draws: all three columns and the
     * tab strip above them. Only there does a stack that landed on no slot go into the world; on
     * the side columns or a tab it goes back where it came from.
     */
    protected boolean isOutsidePanel(float x, float y, int screenWidth, int screenHeight) {
        InventoryLayoutCalculator.InventoryLayout3Col l =
            InventoryLayoutCalculator.calculateThreeColumnLayout(screenWidth, screenHeight);
        int top = com.stonebreak.ui.TabStripLayout.tabY(l.panelStartY);
        return x < l.panelStartX || x > l.panelStartX + l.totalPanelWidth
            || y < top || y > l.panelStartY + l.totalPanelHeight;
    }

    private void clearDraggedItemState() {
        dragState.clear();
    }

    public InventoryDragDropHandler.DragState getDragState() {
        return dragState;
    }

    /** The slot manager owning the region bindings + action sink (issue #334). */
    public InventorySlotManager getSlotManager() {
        return slotManager;
    }

    /**
     * Handles dragged items when closing the screen.
     * Attempts to return items to original slots or player inventory.
     */
    public void handleCloseWithDraggedItems() {
        closeWithDraggedItems();
    }

    /**
     * The close return of the carried stack; true when it sent DEPOSIT_CARRIED. Every local
     * step is mirrored by the matching action (issue #334): the return to the origin slot —
     * a crafting-grid origin included — as PUT_ALL/MERGE, the rest as DEPOSIT_CARRIED.
     */
    private boolean closeWithDraggedItems() {
        boolean deposited = false;
        if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
            // Try to return to original slot first
            InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory,
                                                            craftingManager.getCraftingInputSlots(),
                                                            craftingManager::updateCraftingOutput,
                                                            slotManager.actions, slotManager.craftGridRegion);

            // If still dragging after trying to return, try to add to player inventory
            if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
                // addItem fills partially before reporting failure, so drop only the remainder
                // (dropping the whole stack after a partial add duplicated the difference).
                ItemStack held = dragState.draggedItemStack;
                held.setCount(held.getCount() - inventory.addItemAndReturnCount(held));
                // The SERVER deposits the carried stack into the replica and drops whatever
                // does not fit at the player (issue #334); the local add above is the
                // prediction only. With a live session the client must NOT also drop the
                // remainder — DropItemC2S debits the 36 slots, not the server cursor, so it
                // would consume unrelated stock. Without a session the local drop is the
                // only one (issue #307: closing a screen never destroys items).
                slotManager.actions.send(com.stonebreak.network.packet.player.ContainerActionC2S.Action.DEPOSIT_CARRIED);
                deposited = true;
                if (!held.isEmpty() && !com.stonebreak.network.MultiplayerSession.isClient()) {
                    InventoryDragDropHandler.dropEntireStackIntoWorld(dragState);
                }
            }

            // Clear drag state
            clearDraggedItemState();
        }
        return deposited;
    }

    /**
     * Closes a block container screen (the crafting table) whose grid STAYS in the block
     * (issue #307): the carried stack goes back as above, and — whenever this screen sent
     * any action since it opened, so the server's cursor may still hold a stack the
     * prediction thinks is placed — one DEPOSIT_CARRIED hands it back (issue #334). The grid
     * itself is never quick-moved out (that is the inventory's 2×2 rule).
     */
    public void closeBlockContainer() {
        boolean mayCarry = slotManager.actions.hasSentSinceBind();
        closeWithDraggedItems();
        if (mayCarry && slotManager.actions.lastAction()
                != com.stonebreak.network.packet.player.ContainerActionC2S.Action.DEPOSIT_CARRIED) {
            slotManager.actions.send(com.stonebreak.network.packet.player.ContainerActionC2S.Action.DEPOSIT_CARRIED);
        }
    }

    /** A sync rewrote the grid (issue #334): recompute what it makes, so no stale output shows. */
    @Override
    public void refreshCraftingOutput() {
        craftingManager.updateCraftingOutput();
    }

    /**
     * Returns the SERVER's copy of this screen's held items on close (issue #334): one
     * QUICK_MOVE per non-empty craft-grid cell into the replica's 36 slots (what does not
     * fit stays in the cell, as tryShiftClickCraftingInput predicts), then DEPOSIT_CARRIED,
     * which for kind INVENTORY returns the server cursor AND grid cells 0..3 to the
     * inventory and drops only the overflow at the player. Without
     * this the local return empties only the client's prediction — the next
     * InventorySyncS2C would overwrite the mirror without those items and the ephemeral
     * server grid would strand them. Sends no-op without a live session; local-only play
     * relies on the direct return in the controller.
     */
    public void sendCloseReturnOps() {
        ItemStack[] cells = craftingManager.getCraftingInputSlots();
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != null && !cells[i].isEmpty()) {
                slotManager.actions.send(slotManager.craftGridRegion, (byte) i,
                        com.stonebreak.network.packet.player.ContainerActionC2S.Action.QUICK_MOVE);
            }
        }
        slotManager.actions.send(com.stonebreak.network.packet.player.ContainerActionC2S.Action.DEPOSIT_CARRIED);
    }

    public InventoryCraftingManager getCraftingManager() {
        return craftingManager;
    }
}
