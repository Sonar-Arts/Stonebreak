package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.ui.inventoryScreen.renderers.InventoryRenderCoordinator;
import com.stonebreak.ui.inventoryScreen.renderers.WorkbenchRenderCoordinator;

/**
 * Controller responsible for coordinating workbench UI operations.
 * Extends InventoryController to reuse inventory functionality while adding workbench-specific behavior.
 * Follows Single Responsibility Principle by handling only workbench coordination logic.
 */
public class WorkbenchController extends InventoryController {

    private final Game game;
    private WorkbenchRenderCoordinator workbenchRenderCoordinator;

    public WorkbenchController(Game game,
                              Inventory inventory,
                              InventoryInputManager inputManager,
                              InventoryCraftingManager craftingManager,
                              InventoryRenderCoordinator renderCoordinator) {
        super(inventory, inputManager, craftingManager, renderCoordinator);
        this.game = game;
    }

    /**
     * Workbench-specific visibility handling.
     * Unlike inventory, workbench can be closed by interacting with the workbench block again
     * or by pressing escape/inventory key.
     */
    @Override
    public void toggleVisibility() {
        if (isVisible()) {
            close();
        } else {
            open();
        }
    }

    /**
     * Opens the workbench screen and ensures proper game state.
     */
    public void open() {
        setVisible(true);
        // Ensure crafting output is updated when opening
        if (getWorkbenchCraftingManager() != null) {
            getWorkbenchCraftingManager().updateCraftingOutput();
        }

        // Update mouse capture state when workbench opens
        if (game.getMouseCaptureManager() != null) {
            game.getMouseCaptureManager().updateCaptureState();
        }
    }

    /**
     * Closes the workbench screen and handles cleanup.
     */
    public void close() {
        setVisible(false);
        // Items remain in crafting grid when closing (consistent with original behavior)

        // Update mouse capture state when workbench closes
        if (game.getMouseCaptureManager() != null) {
            game.getMouseCaptureManager().updateCaptureState();
        }
    }

    /**
     * Handles close request from input (Escape key, etc.).
     * Includes drag state cleanup before closing.
     */
    public void handleCloseRequest() {
        if (isVisible()) {
            // Handle any dragged items before closing
            handleDraggedItemsOnClose();
            close();
        }
    }

    /**
     * Handles dragged items when closing the workbench.
     * Attempts to return items to original slots or player inventory.
     */
    private void handleDraggedItemsOnClose() {
        // This functionality would be handled by the InputManager
        // The controller delegates to the InputManager for drag state management
        InventoryInputManager inputManager = getInputManager();
        if (inputManager != null) {
            inputManager.handleCloseWithDraggedItems();
        }
    }

    /**
     * Gets the crafting manager for workbench operations.
     */
    private InventoryCraftingManager getWorkbenchCraftingManager() {
        return getCraftingManager();
    }

    /**
     * Opens the recipe book screen from the workbench.
     */
    public void openRecipeBook() {
        if (game != null) {
            game.openRecipeBookScreen();
        }
    }

    /**
     * Sets the workbench render coordinator.
     */
    public void setRenderCoordinator(WorkbenchRenderCoordinator renderCoordinator) {
        this.workbenchRenderCoordinator = renderCoordinator;
    }

    @Override
    public void render(int screenWidth, int screenHeight) {
        if (!isVisible() || workbenchRenderCoordinator == null) return;
        workbenchRenderCoordinator.render(screenWidth, screenHeight);
    }

    @Override
    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        if (!isVisible() || workbenchRenderCoordinator == null) return;
        workbenchRenderCoordinator.renderWithoutTooltips(screenWidth, screenHeight);
    }

    @Override
    public void renderTooltipsOnly(int screenWidth, int screenHeight) {
        if (!isVisible() || workbenchRenderCoordinator == null) return;
        workbenchRenderCoordinator.renderTooltipsOnly(screenWidth, screenHeight);
    }

    @Override
    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        if (!isVisible() || workbenchRenderCoordinator == null) return;
        workbenchRenderCoordinator.renderDraggedItemOnly(screenWidth, screenHeight);
    }

    @Override
    public void renderHotbar(int screenWidth, int screenHeight) {
        if (!isVisible() && workbenchRenderCoordinator != null) {
            workbenchRenderCoordinator.renderHotbar(screenWidth, screenHeight);
        }
    }

    @Override
    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        if (!isVisible() && workbenchRenderCoordinator != null) {
            workbenchRenderCoordinator.renderHotbarWithoutTooltips(screenWidth, screenHeight);
        }
    }

    @Override
    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        if (!isVisible() && workbenchRenderCoordinator != null) {
            workbenchRenderCoordinator.renderHotbarTooltipsOnly(screenWidth, screenHeight);
        }
    }
}