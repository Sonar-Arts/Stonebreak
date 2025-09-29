package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.core.Game;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

/**
 * Manages all input handling for the inventory screen.
 * Follows Single Responsibility Principle by handling only input logic.
 */
public class InventoryInputManager {

    protected final InputHandler inputHandler;
    protected final Inventory inventory;
    protected final InventorySlotManager slotManager;
    protected final InventoryDragDropHandler.DragState dragState;
    protected final InventoryCraftingManager craftingManager;

    // Recipe button properties
    private float recipeButtonX, recipeButtonY, recipeButtonWidth, recipeButtonHeight;

    // Craft All button properties
    private float craftAllButtonX, craftAllButtonY, craftAllButtonWidth, craftAllButtonHeight;

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

    public void handleMouseInput(int screenWidth, int screenHeight) {
        InventoryLayoutCalculator.InventoryLayout layout = InventoryLayoutCalculator.calculateLayout(screenWidth, screenHeight);

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;
        boolean shiftDown = inputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) ||
                           inputHandler.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);

        boolean leftMouseButtonPressed = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean rightMouseButtonPressed = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        if (leftMouseButtonPressed) {
            handleLeftClick(mouseX, mouseY, shiftDown, layout);
        } else if (rightMouseButtonPressed) {
            handleRightClick(mouseX, mouseY, layout);
        } else {
            handleDragRelease(screenWidth, screenHeight);
        }
    }

    protected void handleLeftClick(float mouseX, float mouseY, boolean shiftDown,
                                  InventoryLayoutCalculator.InventoryLayout layout) {
        if (shiftDown) {
            handleShiftClickTransfer(mouseX, mouseY, layout);
            inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return;
        }

        if (dragState.draggedItemStack == null) {
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

            // Try to pick up item
            tryPickUpItem(mouseX, mouseY, layout);
        }
    }

    protected void handleRightClick(float mouseX, float mouseY,
                                   InventoryLayoutCalculator.InventoryLayout layout) {
        if (dragState.draggedItemStack != null && !dragState.draggedItemStack.isEmpty()) {
            boolean placedOne = tryHandleRightClickDropSingle(mouseX, mouseY, layout);
            if (placedOne) {
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            }
        }
    }

    protected void handleDragRelease(int screenWidth, int screenHeight) {
        if (dragState.draggedItemStack != null &&
            !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            placeDraggedItem(screenWidth, screenHeight);
        } else if (dragState.draggedItemStack != null &&
                   !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT) &&
                   !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            handleFailedDrop();
        } else if (dragState.draggedItemStack == null) {
            clearDraggedItemState();
        }
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

    private void handleCraftAll() {
        if (craftingManager.getCraftingOutputSlot() != null &&
            !craftingManager.getCraftingOutputSlot().isEmpty()) {

            // Store all crafted items temporarily
            java.util.List<com.stonebreak.items.ItemStack> craftedItems = new java.util.ArrayList<>();

            // Craft as many as possible
            while (!craftingManager.getCraftingOutputSlot().isEmpty()) {
                // Store the output before consuming
                com.stonebreak.items.ItemStack output = craftingManager.getCraftingOutputSlot().copy();

                // Check if we still have materials
                boolean canCraft = true;
                for (com.stonebreak.items.ItemStack inputSlot : craftingManager.getCraftingInputSlots()) {
                    if (inputSlot != null && !inputSlot.isEmpty() && inputSlot.getCount() < 1) {
                        canCraft = false;
                        break;
                    }
                }

                if (!canCraft) {
                    break;
                }

                // Consume ingredients
                craftingManager.consumeCraftingIngredients();
                craftedItems.add(output);

                // Update output for next iteration
                craftingManager.updateCraftingOutput();
            }

            // Try to add all crafted items to inventory
            for (com.stonebreak.items.ItemStack stack : craftedItems) {
                if (!inventory.addItem(stack)) {
                    // If inventory is full, drop remaining items
                    if (Game.getPlayer() != null) {
                        com.stonebreak.util.DropUtil.dropItemFromPlayer(Game.getPlayer(), stack);
                    }
                }
            }
        }
    }

    private void tryPickUpItem(float mouseX, float mouseY,
                              InventoryLayoutCalculator.InventoryLayout layout) {
        // Try main inventory slots
        if (slotManager.tryPickUpFromMainInventory(mouseX, mouseY, layout, dragState)) return;

        // Try hotbar slots
        if (slotManager.tryPickUpFromHotbar(mouseX, mouseY, layout, dragState)) return;

        // Try crafting input slots
        if (slotManager.tryPickUpFromCraftingInput(mouseX, mouseY, layout, dragState)) {
            craftingManager.updateCraftingOutput();
            return;
        }

        // Try crafting output slot
        if (slotManager.tryPickUpFromCraftingOutput(mouseX, mouseY, layout, dragState)) {
            craftingManager.consumeCraftingIngredients();
            craftingManager.updateCraftingOutput();
        }
    }

    private void handleShiftClickTransfer(float mouseX, float mouseY,
                                         InventoryLayoutCalculator.InventoryLayout layout) {
        // Check crafting output slot first
        if (slotManager.tryShiftClickCraftingOutput(mouseX, mouseY, layout)) {
            craftingManager.consumeCraftingIngredients();
            craftingManager.updateCraftingOutput();
            return;
        }

        // Check crafting input slots
        if (slotManager.tryShiftClickCraftingInput(mouseX, mouseY, layout)) {
            craftingManager.updateCraftingOutput();
        }
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

    private void placeDraggedItem(int screenWidth, int screenHeight) {
        Vector2f mousePos = inputHandler.getMousePosition();
        InventoryDragDropHandler.placeDraggedItem(dragState, inventory,
                                                craftingManager.getCraftingInputSlots(),
                                                mousePos, screenWidth, screenHeight,
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
                if (!inventory.addItem(dragState.draggedItemStack)) {
                    // If can't add to inventory, drop into world
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