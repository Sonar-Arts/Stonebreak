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
        // The grid is not per-block world state: empty it back into the inventory
        // (overflow dropped) so nothing is lost on world exit (issue #307).
        returnHeldItemsToPlayer();
        setVisible(false);

        // Update mouse capture state when workbench closes
        if (game.getMouseCaptureManager() != null) {
            game.getMouseCaptureManager().updateCaptureState();
        }
    }

    /**
     * Handles close request from input (Escape key, etc.).
     */
    public void handleCloseRequest() {
        if (isVisible()) {
            // Game's close method handles the state transition and calls close(),
            // which returns the dragged stack and the grid to the player.
            game.closeWorkbenchScreen();
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