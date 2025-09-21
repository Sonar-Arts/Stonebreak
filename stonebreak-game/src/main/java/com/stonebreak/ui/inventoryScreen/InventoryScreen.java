package com.stonebreak.ui.inventoryScreen;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Inventory;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.input.InputHandler;
import com.stonebreak.ui.Font;
import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.ui.inventoryScreen.core.InventoryController;
import com.stonebreak.ui.inventoryScreen.core.InventoryInputManager;
import com.stonebreak.ui.inventoryScreen.core.InventoryCraftingManager;
import com.stonebreak.ui.inventoryScreen.renderers.InventoryRenderCoordinator;
import com.stonebreak.ui.inventoryScreen.core.InventorySlotManager;


/**
 * A 2D UI for displaying the player's inventory.
 * Refactored to follow SOLID principles using composition and delegation.
 */
public class InventoryScreen {

    private final InventoryController controller;
    
    /**
     * Creates a new inventory screen using modular architecture.
     */
    public InventoryScreen(Inventory inventory, Font font, Renderer renderer, UIRenderer uiRenderer, InputHandler inputHandler, CraftingManager craftingManager) {
        // Create specialized managers
        InventoryCraftingManager craftingManagerModule = new InventoryCraftingManager(craftingManager);
        InventorySlotManager slotManager = new InventorySlotManager(inventory, craftingManagerModule);
        InventoryInputManager inputManager = new InventoryInputManager(inputHandler, inventory, slotManager, craftingManagerModule);

        // Create controller first
        this.controller = new InventoryController(inventory, inputManager, craftingManagerModule, null);

        // Create render coordinator with controller reference
        InventoryRenderCoordinator renderCoordinator = new InventoryRenderCoordinator(uiRenderer, renderer, inputHandler, inventory, controller, inputManager, craftingManagerModule);

        // Set the render coordinator in the controller
        this.controller.setRenderCoordinator(renderCoordinator);
    }

    /**
     * Toggles the visibility of the inventory screen.
     */
    public void toggleVisibility() {
        controller.toggleVisibility();
    }

    /**
     * Returns whether the inventory screen is currently visible.
     */
    public boolean isVisible() {
        return controller.isVisible();
    }

    public void update(float deltaTime) {
        controller.update(deltaTime);
    }

    /**
     * Call this when a hotbar item is selected to show its name.
     */
    public void displayHotbarItemTooltip(BlockType blockType) {
        controller.displayHotbarItemTooltip(blockType);
    }

    /**
     * Call this when a hotbar item is selected to show its name (supports all Item types).
     */
    public void displayHotbarItemTooltip(com.stonebreak.items.Item item) {
        controller.displayHotbarItemTooltip(item);
    }

    /**
     * Call this when a hotbar item is selected to show its name from an ItemStack.
     */
    public void displayHotbarItemTooltip(com.stonebreak.items.ItemStack itemStack) {
        controller.displayHotbarItemTooltip(itemStack);
    }

    /**
     * Gets the hotbar screen instance.
     */
    public HotbarScreen getHotbarScreen() {
        return controller.getHotbarScreen();
    }

    /**
     * Renders the inventory screen.
     */
    public void render(int screenWidth, int screenHeight) {
        controller.render(screenWidth, screenHeight);
    }
    /**
     * Render the full inventory screen without tooltips.
     * This method is called during the main UI phase, before block drops are rendered.
     */
    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        controller.renderWithoutTooltips(screenWidth, screenHeight);
    }
    
    /**
     * Render only tooltips for the full inventory screen.
     * This method is called after block drops are rendered to ensure tooltips appear above them.
     */
    public void renderTooltipsOnly(int screenWidth, int screenHeight) {
        controller.renderTooltipsOnly(screenWidth, screenHeight);
    }

    /**
     * Render only the dragged item for the inventory screen.
     * This method is called during the overlay phase to ensure dragged items appear above all other UI.
     */
    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        controller.renderDraggedItemOnly(screenWidth, screenHeight);
    }

    // All rendering methods have been delegated to the InventoryRenderCoordinator


    /**
     * Renders the separate hotbar at the bottom of the screen when inventory is closed.
     */
    public void renderHotbar(int screenWidth, int screenHeight) {
        controller.renderHotbar(screenWidth, screenHeight);
    }

    /**
     * Renders the separate hotbar at the bottom of the screen without tooltips.
     * This method is called during the main UI phase, before block drops are rendered.
     */
    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        controller.renderHotbarWithoutTooltips(screenWidth, screenHeight);
    }

    /**
     * Renders only the hotbar tooltip.
     * This method is called after block drops are rendered to ensure tooltips appear above them.
     */
    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        controller.renderHotbarTooltipsOnly(screenWidth, screenHeight);
    }

    // Method to handle mouse clicks for drag and drop
    public void handleMouseInput(int screenWidth, int screenHeight) {
        controller.handleInput(screenWidth, screenHeight);
    }

    // All input handling methods have been delegated to the InventoryInputManager

}