package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.core.Game;
import com.stonebreak.rpg.CharacterPanelTab;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

/**
 * Manages all input handling for the inventory screen, and — through a few layout/drop hooks —
 * for every screen built on it (the crafting table's {@link WorkbenchInputManager}). The mouse
 * dispatch lives only here so each crafting/inventory interaction reaches every screen: subclasses
 * supply {@link #layoutFor}, {@link #placeDraggedItem} and {@link #hasCharacterTabs}, never their
 * own copy of {@link #handleMouseInput} (issue #317).
 */
public class InventoryInputManager {

    protected final InputHandler inputHandler;
    protected final Inventory inventory;
    protected final InventorySlotManager slotManager;
    protected final InventoryDragDropHandler.DragState dragState;
    protected final InventoryCraftingManager craftingManager;

    // Screen dimensions (updated each frame for drag release)
    protected int lastScreenWidth, lastScreenHeight;

    // Double-click detection
    private static final long DOUBLE_CLICK_THRESHOLD_MS = 350L;
    private static final float DOUBLE_CLICK_RADIUS = 40f;
    private long lastClickTimeMs = 0L;
    private float lastClickX = 0f, lastClickY = 0f;

    // Right-click drag state
    private boolean rightDragActive = false;
    private final java.util.Set<Integer> rightDragVisitedSlots = new java.util.HashSet<>();

    // Recipe button properties
    private float recipeButtonX, recipeButtonY, recipeButtonWidth, recipeButtonHeight;

    // Craft All button properties
    private float craftAllButtonX, craftAllButtonY, craftAllButtonWidth, craftAllButtonHeight;

    // Sort button properties
    private float sortButtonX, sortButtonY, sortButtonWidth, sortButtonHeight;

    // Tab bounds (mirroring InventoryRenderCoordinator tab geometry)
    private float charTabX, charTabY, charTabWidth, charTabHeight;
    private float classesTabX, skillsTabX, featsTabX;

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

    public final void handleMouseInput(int screenWidth, int screenHeight) {
        InventoryLayoutCalculator.InventoryLayout layout = layoutFor(screenWidth, screenHeight);

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;
        this.lastScreenWidth = screenWidth;
        this.lastScreenHeight = screenHeight;
        boolean shiftDown = inputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) ||
                           inputHandler.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);

        boolean leftMouseButtonPressed = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean rightMouseButtonPressed = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        boolean rightMouseButtonDown = inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        // Middle click: balance the crafting grid when aimed at a cell, otherwise sort.
        if (tryHandleMiddleClick(mouseX, mouseY, layout)) {
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

    /**
     * The slot layout this screen's clicks are hit-tested against — the same layout its render
     * coordinator draws. The inventory screen uses the centre column of its three-column layout.
     */
    protected InventoryLayoutCalculator.InventoryLayout layoutFor(int screenWidth, int screenHeight) {
        return InventoryLayoutCalculator.calculateThreeColumnLayout(screenWidth, screenHeight).center;
    }

    /**
     * Whether this screen draws the Inventory/Character/Classes/Skills/Feats tab strip. Screens
     * without it must not hit-test the tabs, or clicks above their panel would switch screens
     * through invisible tabs.
     */
    protected boolean hasCharacterTabs() {
        return true;
    }

    /**
     * Middle-click helper shared by the inventory and workbench screens: balances
     * the crafting grid when aimed at a cell, otherwise sorts the inventory.
     * Consumes the press so it does not leak into further handling. Returns true
     * when a middle-click was processed.
     */
    protected boolean tryHandleMiddleClick(float mouseX, float mouseY,
                                           InventoryLayoutCalculator.InventoryLayout layout) {
        if (!inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) {
            return false;
        }
        handleMiddleClick(mouseX, mouseY, layout);
        inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        return true;
    }

    private void handleMiddleClick(float mouseX, float mouseY,
                                   InventoryLayoutCalculator.InventoryLayout layout) {
        if (slotManager.tryBalanceCraftingSlot(mouseX, mouseY, layout)) {
            craftingManager.updateCraftingOutput();
        } else if (!dragState.isDragging()) {
            // Never sort mid-drag: it re-populates the drag's source slot and
            // corrupts the later swap-by-index placement.
            inventory.sortInventory();
        }
    }

    protected void handleLeftClick(float mouseX, float mouseY, boolean shiftDown,
                                  InventoryLayoutCalculator.InventoryLayout layout) {
        if (shiftDown) {
            handleShiftClickTransfer(mouseX, mouseY, layout);
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return;
        }

        // If dragging, craft another batch onto the cursor when aiming at the
        // output slot, then check for double-click gather before placing.
        if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
            if (tryCraftOntoDraggedStack(mouseX, mouseY, layout)) {
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }
            if (isDoubleClick(mouseX, mouseY)) {
                handleDoubleClickGather();
                recordClick(mouseX, mouseY);
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }
            placeDraggedItem(layout);
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return;
        }

        if (dragState.draggedItemStack == null) {
            // Check character-group tabs (above panel) before slot interactions
            if (hasCharacterTabs() && isCharTabClicked(mouseX, mouseY, layout)) {
                Game.getInstance().toggleInventoryScreen();
                Game.getInstance().toggleCharacterScreen();
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }
            if (hasCharacterTabs() && isClassesTabClicked(mouseX, mouseY, layout)) {
                Game.getInstance().toggleInventoryScreen();
                Game.getInstance().openCharacterTab(CharacterPanelTab.CLASSES);
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }
            if (hasCharacterTabs() && isSkillsTabClicked(mouseX, mouseY, layout)) {
                Game.getInstance().toggleInventoryScreen();
                Game.getInstance().openCharacterTab(CharacterPanelTab.SKILLS);
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }
            if (hasCharacterTabs() && isFeatsTabClicked(mouseX, mouseY, layout)) {
                Game.getInstance().toggleInventoryScreen();
                Game.getInstance().openCharacterTab(CharacterPanelTab.FEATS);
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }

            // Check recipe button first
            if (isRecipeButtonClicked(mouseX, mouseY, layout)) {
                Game.getInstance().openRecipeBookScreen();
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }

            // Check craft all button
            if (isCraftAllButtonClicked(mouseX, mouseY, layout)) {
                handleCraftAll();
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }

            // Check sort button
            if (isSortButtonClicked(mouseX, mouseY, layout)) {
                handleSort();
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }

            // Try to pick up item
            if (tryPickUpItem(mouseX, mouseY, layout)) {
                recordClick(mouseX, mouseY);
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            }
        }
    }

    protected void handleRightClick(float mouseX, float mouseY,
                                   InventoryLayoutCalculator.InventoryLayout layout) {
        // Single right-click just drops one item when dragging (old behavior)
        if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
            boolean placedOne = tryHandleRightClickDropSingle(mouseX, mouseY, layout);
            if (placedOne) {
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            }
        }
    }

    protected void handleDragRelease(int screenWidth, int screenHeight) {
        if (dragState.draggedItemStack == null || dragState.draggedItemStack.isEmpty()) {
            clearDraggedItemState();
        }
        // Otherwise: dragging in progress — wait for the second left-click (handled in handleLeftClick)
    }

    private boolean isRecipeButtonClicked(float mouseX, float mouseY,
                                         InventoryLayoutCalculator.InventoryLayout layout) {
        updateRecipeButtonBounds(layout);
        return mouseX >= recipeButtonX && mouseX <= recipeButtonX + recipeButtonWidth &&
               mouseY >= recipeButtonY && mouseY <= recipeButtonY + recipeButtonHeight;
    }

    private void updateRecipeButtonBounds(InventoryLayoutCalculator.InventoryLayout layout) {
        // Scale button width as a proportion of the panel width for better responsiveness
        float panelWidthRatio = 0.25f; // Button takes up 25% of panel width
        recipeButtonWidth = Math.max(80, (int)(layout.inventoryPanelWidth * panelWidthRatio));
        recipeButtonHeight = InventoryLayoutCalculator.getSlotSize();

        // Position button to the right of the output slot with proper spacing
        int spacingAfterOutput = InventoryLayoutCalculator.getSlotPadding() * 2;
        recipeButtonX = layout.outputSlotX + InventoryLayoutCalculator.getSlotSize() + spacingAfterOutput;
        recipeButtonY = layout.outputSlotY;
    }

    private boolean isCraftAllButtonClicked(float mouseX, float mouseY,
                                           InventoryLayoutCalculator.InventoryLayout layout) {
        updateCraftAllButtonBounds(layout);
        return mouseX >= craftAllButtonX && mouseX <= craftAllButtonX + craftAllButtonWidth &&
               mouseY >= craftAllButtonY && mouseY <= craftAllButtonY + craftAllButtonHeight;
    }

    private void updateCraftAllButtonBounds(InventoryLayoutCalculator.InventoryLayout layout) {
        // Make button width smaller for compact layout
        craftAllButtonWidth = Math.max(70, InventoryLayoutCalculator.getSlotSize() * 1.5f);
        craftAllButtonHeight = InventoryLayoutCalculator.getSlotSize() / 2;

        // Position button below the output slot
        craftAllButtonX = layout.outputSlotX + (InventoryLayoutCalculator.getSlotSize() - craftAllButtonWidth) / 2;
        craftAllButtonY = layout.outputSlotY + InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding();
    }

    private boolean isSortButtonClicked(float mouseX, float mouseY,
                                       InventoryLayoutCalculator.InventoryLayout layout) {
        updateSortButtonBounds(layout);
        return mouseX >= sortButtonX && mouseX <= sortButtonX + sortButtonWidth &&
               mouseY >= sortButtonY && mouseY <= sortButtonY + sortButtonHeight;
    }

    private void updateSortButtonBounds(InventoryLayoutCalculator.InventoryLayout layout) {
        sortButtonWidth = Math.max(70, InventoryLayoutCalculator.getSlotSize() * 1.5f);
        sortButtonHeight = InventoryLayoutCalculator.getSlotSize() / 2f;

        int gridWidth = Inventory.MAIN_INVENTORY_COLS *
            (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding())
            - InventoryLayoutCalculator.getSlotPadding();

        // Right-aligned above the main-inventory grid.
        sortButtonX = layout.inventorySectionStartX + gridWidth - sortButtonWidth;
        sortButtonY = layout.mainInvContentStartY - sortButtonHeight - InventoryLayoutCalculator.getSlotPadding();
    }

    private boolean isCharTabClicked(float mouseX, float mouseY,
                                     InventoryLayoutCalculator.InventoryLayout layout) {
        updateCharTabBounds(layout);
        return mouseX >= charTabX && mouseX <= charTabX + charTabWidth
                && mouseY >= charTabY && mouseY <= charTabY + charTabHeight;
    }

    private void updateCharTabBounds(InventoryLayoutCalculator.InventoryLayout layout) {
        charTabWidth  = com.stonebreak.ui.TabStripLayout.tabWidth();
        charTabHeight = com.stonebreak.ui.TabStripLayout.tabHeight();
        int stride = com.stonebreak.ui.TabStripLayout.stride();
        int startX = com.stonebreak.ui.TabStripLayout.startX(lastScreenWidth);
        charTabX    = startX + stride;
        charTabY    = com.stonebreak.ui.TabStripLayout.tabY(layout.panelStartY);
        classesTabX = startX + stride * 2;
        skillsTabX  = startX + stride * 3;
        featsTabX   = startX + stride * 4;
    }

    private boolean isClassesTabClicked(float mouseX, float mouseY,
                                        InventoryLayoutCalculator.InventoryLayout layout) {
        updateCharTabBounds(layout);
        return mouseX >= classesTabX && mouseX <= classesTabX + charTabWidth
            && mouseY >= charTabY    && mouseY <= charTabY    + charTabHeight;
    }

    private boolean isSkillsTabClicked(float mouseX, float mouseY,
                                       InventoryLayoutCalculator.InventoryLayout layout) {
        updateCharTabBounds(layout);
        return mouseX >= skillsTabX && mouseX <= skillsTabX + charTabWidth
            && mouseY >= charTabY   && mouseY <= charTabY   + charTabHeight;
    }

    private boolean isFeatsTabClicked(float mouseX, float mouseY,
                                      InventoryLayoutCalculator.InventoryLayout layout) {
        updateCharTabBounds(layout);
        return mouseX >= featsTabX && mouseX <= featsTabX + charTabWidth
            && mouseY >= charTabY  && mouseY <= charTabY  + charTabHeight;
    }

    private void handleCraftAll() {
        if (craftingManager.getCraftingOutputSlot() == null ||
            craftingManager.getCraftingOutputSlot().isEmpty()) {
            return;
        }
        slotManager.depositCraftedStacks(craftingManager.craftAll());
    }

    private void handleSort() {
        if (dragState.isDragging()) return; // defensive; unreachable via handleLeftClick anyway
        inventory.sortInventory();
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

    private boolean isDoubleClick(float mouseX, float mouseY) {
        long now = System.currentTimeMillis();
        if (now - lastClickTimeMs > DOUBLE_CLICK_THRESHOLD_MS) return false;
        float dx = mouseX - lastClickX;
        float dy = mouseY - lastClickY;
        return dx * dx + dy * dy <= DOUBLE_CLICK_RADIUS * DOUBLE_CLICK_RADIUS;
    }

    private void recordClick(float mouseX, float mouseY) {
        lastClickTimeMs = System.currentTimeMillis();
        lastClickX = mouseX;
        lastClickY = mouseY;
    }

    private void handleDoubleClickGather() {
        slotManager.gatherMatchingItemsToStack(dragState);
    }

    private void handleRightDrag(float mouseX, float mouseY,
                                 InventoryLayoutCalculator.InventoryLayout layout) {
        rightDragActive = true;
        boolean placed = slotManager.tryRightDragDepositToSlot(
                mouseX, mouseY, layout, dragState, rightDragVisitedSlots);
        if (placed) {
            craftingManager.updateCraftingOutput();
        }
    }

    private void clearRightDrag() {
        rightDragActive = false;
        rightDragVisitedSlots.clear();
    }

    /**
     * Places (or swaps/stacks) the held stack into the slot under the cursor, falling back to
     * its original slot or a world drop. Screens with a different grid override this to use
     * their own drop handler.
     */
    protected void placeDraggedItem(InventoryLayoutCalculator.InventoryLayout layout) {
        Vector2f mousePos = inputHandler.getMousePosition();
        InventoryDragDropHandler.placeDraggedItem(dragState, inventory,
                                                craftingManager.getCraftingInputSlots(),
                                                mousePos, layout,
                                                craftingManager::updateCraftingOutput);
    }

    private void handleFailedDrop() {
        InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory,
                                                        craftingManager.getCraftingInputSlots(),
                                                        craftingManager::updateCraftingOutput);
        if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
            InventoryDragDropHandler.dropEntireStackIntoWorld(dragState);
        } else {
            clearDraggedItemState();
        }
    }

    private void clearDraggedItemState() {
        dragState.clear();
    }

    public InventoryDragDropHandler.DragState getDragState() {
        return dragState;
    }

    public float getRecipeButtonX() { return recipeButtonX; }
    public float getRecipeButtonY() { return recipeButtonY; }
    public float getRecipeButtonWidth() { return recipeButtonWidth; }
    public float getRecipeButtonHeight() { return recipeButtonHeight; }

    public float getCraftAllButtonX() { return craftAllButtonX; }
    public float getCraftAllButtonY() { return craftAllButtonY; }
    public float getCraftAllButtonWidth() { return craftAllButtonWidth; }
    public float getCraftAllButtonHeight() { return craftAllButtonHeight; }

    public float getSortButtonX() { return sortButtonX; }
    public float getSortButtonY() { return sortButtonY; }
    public float getSortButtonWidth() { return sortButtonWidth; }
    public float getSortButtonHeight() { return sortButtonHeight; }

    /**
     * Updates recipe button bounds for rendering. Should be called before rendering the button.
     */
    public void updateRecipeButtonBoundsForRendering(InventoryLayoutCalculator.InventoryLayout layout) {
        updateRecipeButtonBounds(layout);
    }

    /**
     * Updates craft all button bounds for rendering. Should be called before rendering the button.
     */
    public void updateCraftAllButtonBoundsForRendering(InventoryLayoutCalculator.InventoryLayout layout) {
        updateCraftAllButtonBounds(layout);
    }

    /**
     * Updates sort button bounds for rendering. Should be called before rendering the button.
     */
    public void updateSortButtonBoundsForRendering(InventoryLayoutCalculator.InventoryLayout layout) {
        updateSortButtonBounds(layout);
    }

    /**
     * Handles dragged items when closing the screen.
     * Attempts to return items to original slots or player inventory.
     */
    public void handleCloseWithDraggedItems() {
        if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
            // Try to return to original slot first
            InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory,
                                                            craftingManager.getCraftingInputSlots(),
                                                            craftingManager::updateCraftingOutput);

            // If still dragging after trying to return, try to add to player inventory
            if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
                // addItem fills partially before reporting failure, so drop only the remainder
                // (dropping the whole stack after a partial add duplicated the difference).
                ItemStack held = dragState.draggedItemStack;
                held.setCount(held.getCount() - inventory.addItemAndReturnCount(held));
                if (!held.isEmpty()) {
                    InventoryDragDropHandler.dropEntireStackIntoWorld(dragState);
                }
            }

            // Clear drag state
            clearDraggedItemState();
        }
    }

    public InventoryCraftingManager getCraftingManager() {
        return craftingManager;
    }
}