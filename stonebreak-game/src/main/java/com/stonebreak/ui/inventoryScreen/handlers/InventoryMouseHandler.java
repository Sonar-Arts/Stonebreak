package com.stonebreak.ui.inventoryScreen.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

public class InventoryMouseHandler {

    private static final int CRAFTING_INPUT_SLOT_START_INDEX = 1000;
    private static final int CRAFTING_OUTPUT_SLOT_INDEX = 2000;

    public static void handleMouseInput(InventoryDragDropHandler.DragState dragState,
                                        Inventory inventory, ItemStack[] craftingInputSlots,
                                        ItemStack craftingOutputSlot, InputHandler inputHandler,
                                        int screenWidth, int screenHeight,
                                        float recipeButtonX, float recipeButtonY,
                                        float recipeButtonWidth, float recipeButtonHeight,
                                        Runnable updateCraftingOutput,
                                        Runnable openRecipeBook) {

        InventoryLayoutCalculator.InventoryLayout layout = InventoryLayoutCalculator.calculateLayout(screenWidth, screenHeight);
        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;
        boolean shiftDown = inputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || inputHandler.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);

        boolean leftMouseButtonPressed = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean rightMouseButtonPressed = inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        if (leftMouseButtonPressed) {
            if (shiftDown) {
                boolean transferred = tryHandleShiftClickTransfer(inventory, craftingInputSlots, craftingOutputSlot,
                                                                mouseX, mouseY, layout, updateCraftingOutput);
                if (transferred) {
                    inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    return;
                }
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return;
            }

            // Normal Left Click
            if (!dragState.isDragging()) {
                // Check Recipe Book Button FIRST
                if (mouseX >= recipeButtonX && mouseX <= recipeButtonX + recipeButtonWidth &&
                    mouseY >= recipeButtonY && mouseY <= recipeButtonY + recipeButtonHeight) {
                    openRecipeBook.run();
                    inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    return;
                }
                // Attempt to PICK UP an item
                tryPickUpItem(dragState, inventory, craftingInputSlots, craftingOutputSlot,
                            mouseX, mouseY, layout, updateCraftingOutput);
            }

        } else if (rightMouseButtonPressed) {
            if (dragState.isDragging()) {
                boolean placedOne = tryHandleRightClickDropSingle(dragState, inventory, craftingInputSlots,
                                                                mouseX, mouseY, layout, updateCraftingOutput);
                if (placedOne) {
                    inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                }
            }
        } else {
            // Handle drag release
            if (dragState.isDragging() && !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                InventoryDragDropHandler.placeDraggedItem(dragState, inventory, craftingInputSlots,
                                                        mousePos, screenWidth, screenHeight, updateCraftingOutput);
            } else if (dragState.isDragging() &&
                       !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT) &&
                       !inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
                InventoryDragDropHandler.tryReturnToOriginalSlot(dragState, inventory, craftingInputSlots, updateCraftingOutput);
                if (dragState.isDragging()) {
                    InventoryDragDropHandler.dropEntireStackIntoWorld(dragState);
                } else {
                    dragState.clear();
                }
            } else if (!dragState.isDragging()) {
                dragState.clear();
            }
        }
    }

    private static void tryPickUpItem(InventoryDragDropHandler.DragState dragState,
                                    Inventory inventory, ItemStack[] craftingInputSlots,
                                    ItemStack craftingOutputSlot, float mouseX, float mouseY,
                                    InventoryLayoutCalculator.InventoryLayout layout,
                                    Runnable updateCraftingOutput) {

        // Check main inventory slots
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() +
                       row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                ItemStack currentStack = inventory.getMainInventorySlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    dragState.draggedItemStack = currentStack.copy();
                    inventory.setMainInventorySlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                    dragState.draggedItemOriginalSlotIndex = i;
                    dragState.dragSource = InventoryDragDropHandler.DragSource.MAIN_INVENTORY;
                    return;
                }
            }
        }

        // Check hotbar slots
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                ItemStack currentStack = inventory.getHotbarSlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    dragState.draggedItemStack = currentStack.copy();
                    inventory.setHotbarSlot(i, new ItemStack(BlockType.AIR.getId(), 0));
                    dragState.draggedItemOriginalSlotIndex = i;
                    dragState.dragSource = InventoryDragDropHandler.DragSource.HOTBAR;
                    return;
                }
            }
        }

        // Check crafting input slots
        for (int i = 0; i < InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getCraftingGridSize(); i++) {
            int r = i / InventoryLayoutCalculator.getCraftingGridSize();
            int c = i % InventoryLayoutCalculator.getCraftingGridSize();
            int slotX = layout.craftingElementsStartX + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                if (craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                    dragState.draggedItemStack = craftingInputSlots[i].copy();
                    craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
                    dragState.draggedItemOriginalSlotIndex = CRAFTING_INPUT_SLOT_START_INDEX + i;
                    dragState.dragSource = InventoryDragDropHandler.DragSource.CRAFTING_INPUT;
                    updateCraftingOutput.run();
                    return;
                }
            }
        }

        // Check crafting output slot (pickup only)
        if (isMouseOverSlot(mouseX, mouseY, layout.outputSlotX, layout.outputSlotY)) {
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                dragState.draggedItemStack = craftingOutputSlot.copy();
                // Note: consumeCraftingIngredients would need to be called here
                // This is simplified for the refactoring
                dragState.draggedItemOriginalSlotIndex = CRAFTING_OUTPUT_SLOT_INDEX;
                dragState.dragSource = InventoryDragDropHandler.DragSource.NONE;
                updateCraftingOutput.run();
            }
        }
    }

    private static boolean tryHandleShiftClickTransfer(Inventory inventory, ItemStack[] craftingInputSlots,
                                                     ItemStack craftingOutputSlot, float mouseX, float mouseY,
                                                     InventoryLayoutCalculator.InventoryLayout layout,
                                                     Runnable updateCraftingOutput) {

        // Check Crafting Output Slot
        if (isMouseOverSlot(mouseX, mouseY, layout.outputSlotX, layout.outputSlotY)) {
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty()) {
                ItemStack itemsInOutput = craftingOutputSlot.copy();
                boolean wasAdded = inventory.addItem(itemsInOutput);

                if (wasAdded) {
                    // Note: consumeCraftingIngredients would need to be called here
                    updateCraftingOutput.run();
                    return true;
                } else {
                    Player player = Game.getPlayer();
                    if (player != null) {
                        com.stonebreak.util.DropUtil.dropItemFromPlayer(player, itemsInOutput);
                        updateCraftingOutput.run();
                        return true;
                    }
                    return false;
                }
            }
        }

        // Check Crafting Input Slots
        for (int i = 0; i < InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getCraftingGridSize(); i++) {
            int r = i / InventoryLayoutCalculator.getCraftingGridSize();
            int c = i % InventoryLayoutCalculator.getCraftingGridSize();
            int slotX = layout.craftingElementsStartX + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                if (craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                    ItemStack itemInInputSlot = craftingInputSlots[i];
                    int typeId = itemInInputSlot.getBlockTypeId();
                    int countInInputSlot = itemInInputSlot.getCount();

                    int itemsOfTypeInInventoryBefore = inventory.getItemCount(typeId);
                    inventory.addItem(typeId, countInInputSlot);
                    int itemsOfTypeInInventoryAfter = inventory.getItemCount(typeId);
                    int numAdded = itemsOfTypeInInventoryAfter - itemsOfTypeInInventoryBefore;

                    if (numAdded > 0) {
                        itemInInputSlot.decrementCount(numAdded);
                        if (itemInInputSlot.isEmpty()) {
                            craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
                        }
                        updateCraftingOutput.run();
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    private static boolean tryHandleRightClickDropSingle(InventoryDragDropHandler.DragState dragState,
                                                       Inventory inventory, ItemStack[] craftingInputSlots,
                                                       float mouseX, float mouseY,
                                                       InventoryLayoutCalculator.InventoryLayout layout,
                                                       Runnable updateCraftingOutput) {
        if (!dragState.isDragging()) {
            return false;
        }

        // Try Main Inventory Slots
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            final int slotIndex = i; // Make effectively final for lambda
            int row = slotIndex / Inventory.MAIN_INVENTORY_COLS;
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() +
                       row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                return attemptDropOneToSlot(dragState, inventory.getMainInventorySlot(slotIndex),
                                          (stack) -> inventory.setMainInventorySlot(slotIndex, stack), false, updateCraftingOutput);
            }
        }

        // Try Hotbar Slots
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            final int slotIndex = i; // Make effectively final for lambda
            int col = slotIndex % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.panelStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                return attemptDropOneToSlot(dragState, inventory.getHotbarSlot(slotIndex),
                                          (stack) -> inventory.setHotbarSlot(slotIndex, stack), false, updateCraftingOutput);
            }
        }

        // Try Crafting Input Slots
        for (int i = 0; i < InventoryLayoutCalculator.getCraftingGridSize() * InventoryLayoutCalculator.getCraftingGridSize(); i++) {
            final int slotIndex = i; // Make effectively final for lambda
            int r = slotIndex / InventoryLayoutCalculator.getCraftingGridSize();
            int c = slotIndex % InventoryLayoutCalculator.getCraftingGridSize();
            int slotX = layout.craftingElementsStartX + c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY + r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            if (isMouseOverSlot(mouseX, mouseY, slotX, slotY)) {
                return attemptDropOneToSlot(dragState, craftingInputSlots[slotIndex],
                                          (stack) -> craftingInputSlots[slotIndex] = stack, true, updateCraftingOutput);
            }
        }
        return false;
    }

    private static boolean attemptDropOneToSlot(InventoryDragDropHandler.DragState dragState, ItemStack targetSlot,
                                              java.util.function.Consumer<ItemStack> directSlotSetter,
                                              boolean isCraftingSlot, Runnable updateCraftingOutput) {
        if (targetSlot.isEmpty()) {
            ItemStack newItem = new ItemStack(dragState.draggedItemStack.getItem(), 1);
            directSlotSetter.accept(newItem);
            dragState.draggedItemStack.decrementCount(1);
            if (dragState.draggedItemStack.isEmpty()) dragState.clear();
            if (isCraftingSlot) updateCraftingOutput.run();
            return true;
        } else if (targetSlot.canStackWith(dragState.draggedItemStack) && targetSlot.getCount() < targetSlot.getMaxStackSize()) {
            targetSlot.incrementCount(1);
            dragState.draggedItemStack.decrementCount(1);
            if (dragState.draggedItemStack.isEmpty()) dragState.clear();
            if (isCraftingSlot) updateCraftingOutput.run();
            return true;
        }
        return false;
    }

    private static boolean isMouseOverSlot(float mouseX, float mouseY, int slotX, int slotY) {
        return mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() &&
               mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize();
    }
}