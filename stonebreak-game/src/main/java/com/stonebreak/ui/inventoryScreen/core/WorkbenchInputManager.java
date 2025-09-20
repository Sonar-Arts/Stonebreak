package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import org.joml.Vector2f;

/**
 * Manages input handling for the workbench screen.
 * Extends InventoryInputManager to add workbench-specific behavior (3x3 crafting grid).
 * Follows Single Responsibility Principle by handling only workbench input logic.
 */
public class WorkbenchInputManager extends InventoryInputManager {

    public WorkbenchInputManager(InputHandler inputHandler,
                                Inventory inventory,
                                InventorySlotManager slotManager,
                                InventoryCraftingManager craftingManager) {
        super(inputHandler, inventory, slotManager, craftingManager);
    }

    @Override
    public void handleMouseInput(int screenWidth, int screenHeight) {
        // Use workbench layout instead of inventory layout
        InventoryLayoutCalculator.InventoryLayout layout = InventoryLayoutCalculator.calculateWorkbenchLayout(screenWidth, screenHeight);

        Vector2f mousePos = super.inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;
        boolean shiftDown = super.inputHandler.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) ||
                           super.inputHandler.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);

        boolean leftMouseButtonPressed = super.inputHandler.isMouseButtonPressed(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean rightMouseButtonPressed = super.inputHandler.isMouseButtonPressed(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        if (leftMouseButtonPressed) {
            handleLeftClick(mouseX, mouseY, shiftDown, layout);
        } else if (rightMouseButtonPressed) {
            handleRightClick(mouseX, mouseY, layout);
        } else {
            handleDragRelease(screenWidth, screenHeight);
        }
    }

    // The rest of the methods can be inherited from InventoryInputManager
    // since they work with the layout parameter and don't need to know
    // about the specific grid size
}