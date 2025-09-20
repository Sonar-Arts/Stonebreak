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
        // This should ideally be handled by a button manager, but keeping here for simplicity
        recipeButtonWidth = 100; // Should be calculated based on text width
        recipeButtonHeight = InventoryLayoutCalculator.getSlotSize();
        recipeButtonX = layout.outputSlotX + InventoryLayoutCalculator.getSlotSize() +
                       InventoryLayoutCalculator.getSlotPadding() * 3;
        recipeButtonY = layout.outputSlotY;
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

    /**
     * Updates recipe button bounds for rendering. Should be called before rendering the button.
     */
    public void updateRecipeButtonBoundsForRendering(InventoryLayoutCalculator.InventoryLayout layout) {
        updateRecipeButtonBounds(layout);
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