package com.stonebreak.ui.workbench;

import com.stonebreak.core.Game;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.inventoryScreen.core.*;
import com.stonebreak.ui.inventoryScreen.renderers.WorkbenchRenderCoordinator;

/**
 * A workbench screen that extends the inventory system architecture.
 * Uses a 3x3 crafting grid instead of the 2x2 inventory crafting grid.
 * Follows SOLID principles by composing existing modular components.
 */
public class WorkbenchScreen {

    private final WorkbenchController controller;

    /**
     * Creates a new workbench screen using the modular inventory architecture.
     */
    public WorkbenchScreen(Game game, Inventory inventory, Renderer renderer, UIRenderer uiRenderer,
                          InputHandler inputHandler, CraftingManager craftingManager) {
        // Create workbench-specific crafting manager with 3x3 grid
        InventoryCraftingManager workbenchCraftingManager = new InventoryCraftingManager(
            craftingManager,
            InventoryLayoutCalculator.getWorkbenchCraftingGridSize()
        );

        // Create slot manager for workbench
        InventorySlotManager slotManager = new InventorySlotManager(inventory, workbenchCraftingManager);

        // Create workbench input manager that uses 3x3 layout
        WorkbenchInputManager inputManager = new WorkbenchInputManager(
            inputHandler, inventory, slotManager, workbenchCraftingManager
        );

        // Create workbench controller
        this.controller = new WorkbenchController(
            game, inventory, inputManager, workbenchCraftingManager, null
        );

        // Create workbench render coordinator
        WorkbenchRenderCoordinator renderCoordinator = new WorkbenchRenderCoordinator(
            uiRenderer, renderer, inputHandler, inventory, controller, inputManager, workbenchCraftingManager
        );

        // Set the render coordinator in the controller
        this.controller.setRenderCoordinator(renderCoordinator);
    }

    /**
     * Opens the workbench screen.
     */
    public void open() {
        controller.open();
    }

    /**
     * Closes the workbench screen.
     */
    public void close() {
        controller.close();
    }

    /**
     * Toggles the visibility of the workbench screen.
     */
    public void toggleVisibility() {
        controller.toggleVisibility();
    }

    /**
     * Returns whether the workbench screen is currently visible.
     */
    public boolean isVisible() {
        return controller.isVisible();
    }

    /**
     * Updates the workbench screen.
     */
    public void update(float deltaTime) {
        controller.update(deltaTime);
    }

    /**
     * Renders the workbench screen.
     */
    public void render() {
        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();
        controller.render(screenWidth, screenHeight);
    }

    /**
     * Renders the workbench screen without tooltips.
     */
    public void renderWithoutTooltips() {
        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();
        controller.renderWithoutTooltips(screenWidth, screenHeight);
    }

    /**
     * Renders only tooltips for the workbench screen.
     */
    public void renderTooltipsOnly() {
        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();
        controller.renderTooltipsOnly(screenWidth, screenHeight);
    }

    /**
     * Renders only the dragged item for the workbench screen.
     */
    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        controller.renderDraggedItemOnly(screenWidth, screenHeight);
    }

    /**
     * Handles input for the workbench screen.
     */
    public void handleInput(InputHandler inputHandler) {
        if (!isVisible()) return;

        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();
        controller.handleInput(screenWidth, screenHeight);
    }

    /**
     * Handles close request (Escape key, etc.).
     */
    public void handleCloseRequest() {
        controller.handleCloseRequest();
    }

    /**
     * Renders the hotbar when workbench is not visible.
     */
    public void renderHotbar(int screenWidth, int screenHeight) {
        controller.renderHotbar(screenWidth, screenHeight);
    }

    /**
     * Renders the hotbar without tooltips when workbench is not visible.
     */
    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        controller.renderHotbarWithoutTooltips(screenWidth, screenHeight);
    }

    /**
     * Renders only hotbar tooltips when workbench is not visible.
     */
    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        controller.renderHotbarTooltipsOnly(screenWidth, screenHeight);
    }
}