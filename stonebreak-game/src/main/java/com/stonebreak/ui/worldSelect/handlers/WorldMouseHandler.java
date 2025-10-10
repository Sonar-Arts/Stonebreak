package com.stonebreak.ui.worldSelect.handlers;

import com.stonebreak.ui.worldSelect.config.WorldSelectConfig;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;
import static org.lwjgl.glfw.GLFW.*;

/**
 * Handles mouse input and interactions for the WorldSelectScreen.
 * Manages mouse movement, clicks, hover states, and scrolling.
 */
public class WorldMouseHandler {

    private final WorldStateManager stateManager;
    private final WorldActionHandler actionHandler;

    public WorldMouseHandler(WorldStateManager stateManager, WorldActionHandler actionHandler) {
        this.stateManager = stateManager;
        this.actionHandler = actionHandler;
    }

    /**
     * Handles mouse movement for hover effects.
     */
    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;

        if (stateManager.isShowCreateDialog()) {
            handleCreateDialogMouseMove((float) mouseX, (float) mouseY, centerX, centerY);
        } else {
            handleWorldListMouseMove((float) mouseX, (float) mouseY, centerX, centerY);
        }
    }

    /**
     * Handles mouse movement for the world list.
     */
    private void handleWorldListMouseMove(float mouseX, float mouseY, float centerX, float centerY) {
        // Calculate world list area
        float listY = centerY - WorldSelectConfig.LIST_HEIGHT / 2.0f;
        float listX = centerX - WorldSelectConfig.LIST_WIDTH / 2.0f;

        // Check if mouse is within the world list area
        if (mouseX >= listX && mouseX <= listX + WorldSelectConfig.LIST_WIDTH &&
            mouseY >= listY && mouseY <= listY + WorldSelectConfig.LIST_HEIGHT) {

            // Calculate which item the mouse is over
            float relativeY = mouseY - listY;
            int hoveredItemIndex = (int) (relativeY / WorldSelectConfig.ITEM_HEIGHT) + stateManager.getScrollOffset();

            // Set hovered index if valid
            if (hoveredItemIndex >= 0 && hoveredItemIndex < stateManager.getWorldList().size()) {
                stateManager.setHoveredIndex(hoveredItemIndex);
            } else {
                stateManager.clearHover();
            }
        } else {
            stateManager.clearHover();
        }
    }

    /**
     * Handles mouse movement for the create dialog.
     */
    private void handleCreateDialogMouseMove(float mouseX, float mouseY, float centerX, float centerY) {
        // For now, just clear hover since we're not implementing button hover in dialogs
        stateManager.clearHover();
    }

    /**
     * Handles mouse click events.
     */
    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight, int button, int action) {
        if (action != GLFW_PRESS) {
            return; // Only handle press events
        }

        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;

        if (stateManager.isShowCreateDialog()) {
            handleCreateDialogMouseClick((float) mouseX, (float) mouseY, centerX, centerY, button);
        } else {
            handleWorldListMouseClick((float) mouseX, (float) mouseY, centerX, centerY, button);
        }
    }

    /**
     * Handles mouse clicks for the world list.
     */
    private void handleWorldListMouseClick(float mouseX, float mouseY, float centerX, float centerY, int button) {
        if (button != GLFW_MOUSE_BUTTON_LEFT) {
            return; // Only handle left clicks
        }

        // Check if click is on a world item
        float listY = centerY - WorldSelectConfig.LIST_HEIGHT / 2.0f;
        float listX = centerX - WorldSelectConfig.LIST_WIDTH / 2.0f;

        if (mouseX >= listX && mouseX <= listX + WorldSelectConfig.LIST_WIDTH &&
            mouseY >= listY && mouseY <= listY + WorldSelectConfig.LIST_HEIGHT) {

            // Calculate clicked item
            float relativeY = mouseY - listY;
            int clickedItemIndex = (int) (relativeY / WorldSelectConfig.ITEM_HEIGHT) + stateManager.getScrollOffset();

            // Select and load the clicked world
            if (clickedItemIndex >= 0 && clickedItemIndex < stateManager.getWorldList().size()) {
                stateManager.setSelectedIndex(clickedItemIndex);
                actionHandler.loadSelectedWorld();
                return;
            }
        }

        // Check if click is on "Create New World" button
        float buttonY = centerY + WorldSelectConfig.LIST_HEIGHT / 2.0f + WorldSelectConfig.BUTTON_MARGIN;
        float buttonX = centerX - WorldSelectConfig.BUTTON_WIDTH / 2.0f;

        if (mouseX >= buttonX && mouseX <= buttonX + WorldSelectConfig.BUTTON_WIDTH &&
            mouseY >= buttonY && mouseY <= buttonY + WorldSelectConfig.BUTTON_HEIGHT) {
            actionHandler.openCreateWorldDialog();
            return;
        }

        // Check if click is on "Back" button
        buttonY += WorldSelectConfig.BUTTON_HEIGHT + WorldSelectConfig.BUTTON_SPACING;

        if (mouseX >= buttonX && mouseX <= buttonX + WorldSelectConfig.BUTTON_WIDTH &&
            mouseY >= buttonY && mouseY <= buttonY + WorldSelectConfig.BUTTON_HEIGHT) {
            actionHandler.returnToMainMenu();
        }
    }

    /**
     * Handles mouse clicks for the create dialog.
     */
    private void handleCreateDialogMouseClick(float mouseX, float mouseY, float centerX, float centerY, int button) {
        if (button != GLFW_MOUSE_BUTTON_LEFT) {
            return; // Only handle left clicks
        }

        // Calculate dialog bounds
        float dialogWidth = WorldSelectConfig.DIALOG_WIDTH;
        float dialogHeight = WorldSelectConfig.DIALOG_HEIGHT;
        float dialogX = centerX - dialogWidth / 2.0f;
        float dialogY = centerY - dialogHeight / 2.0f;

        // Check if click is outside dialog (close dialog)
        if (mouseX < dialogX || mouseX > dialogX + dialogWidth ||
            mouseY < dialogY || mouseY > dialogY + dialogHeight) {
            actionHandler.closeCreateWorldDialog();
            return;
        }

        // Check if click is on "Create" button
        float buttonY = dialogY + dialogHeight - WorldSelectConfig.BUTTON_HEIGHT - 20;
        float createButtonX = centerX - WorldSelectConfig.BUTTON_WIDTH - 10;

        if (mouseX >= createButtonX && mouseX <= createButtonX + WorldSelectConfig.BUTTON_WIDTH &&
            mouseY >= buttonY && mouseY <= buttonY + WorldSelectConfig.BUTTON_HEIGHT) {
            actionHandler.createWorldFromDialog();
            return;
        }

        // Check if click is on "Cancel" button
        float cancelButtonX = centerX + 10;

        if (mouseX >= cancelButtonX && mouseX <= cancelButtonX + WorldSelectConfig.BUTTON_WIDTH &&
            mouseY >= buttonY && mouseY <= buttonY + WorldSelectConfig.BUTTON_HEIGHT) {
            actionHandler.closeCreateWorldDialog();
            return;
        }

        // Check if click is on name input field
        float nameFieldY = dialogY + 80;
        float fieldX = dialogX + 20;
        float fieldWidth = dialogWidth - 40;
        float fieldHeight = 30;

        if (mouseX >= fieldX && mouseX <= fieldX + fieldWidth &&
            mouseY >= nameFieldY && mouseY <= nameFieldY + fieldHeight) {
            // Switch to name input mode - handled by input handler
            return;
        }

        // Check if click is on seed input field
        float seedFieldY = nameFieldY + 60;

        if (mouseX >= fieldX && mouseX <= fieldX + fieldWidth &&
            mouseY >= seedFieldY && mouseY <= seedFieldY + fieldHeight) {
            // Switch to seed input mode - handled by input handler
            return;
        }
    }

    /**
     * Handles mouse wheel scrolling.
     */
    public void handleMouseWheel(double yOffset) {
        if (!stateManager.isShowCreateDialog()) {
            stateManager.scroll(-yOffset); // Invert scroll direction for natural feel
        }
    }

    /**
     * Checks if mouse is over a specific rectangular area.
     */
    private boolean isMouseOver(float mouseX, float mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /**
     * Gets the index of the world item under the mouse cursor.
     */
    public int getMouseOverWorldIndex(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        float listY = centerY - WorldSelectConfig.LIST_HEIGHT / 2.0f;
        float listX = centerX - WorldSelectConfig.LIST_WIDTH / 2.0f;

        if (mouseX >= listX && mouseX <= listX + WorldSelectConfig.LIST_WIDTH &&
            mouseY >= listY && mouseY <= listY + WorldSelectConfig.LIST_HEIGHT) {

            float relativeY = (float) mouseY - listY;
            int itemIndex = (int) (relativeY / WorldSelectConfig.ITEM_HEIGHT) + stateManager.getScrollOffset();

            if (itemIndex >= 0 && itemIndex < stateManager.getWorldList().size()) {
                return itemIndex;
            }
        }

        return -1;
    }
}