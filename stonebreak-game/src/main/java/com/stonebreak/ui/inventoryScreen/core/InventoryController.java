package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.util.DropUtil;
import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.ui.inventoryScreen.renderers.InventoryRenderCoordinator;

/**
 * Controller responsible for coordinating inventory UI operations.
 * Follows Single Responsibility Principle by handling only coordination logic.
 */
public class InventoryController {

    private final Inventory inventory;
    private final HotbarScreen hotbarScreen;
    private final InventoryInputManager inputManager;
    private final InventoryCraftingManager craftingManager;
    private InventoryRenderCoordinator renderCoordinator;

    private boolean visible;
    private ItemStack hoveredItemStack;

    public InventoryController(Inventory inventory,
                              InventoryInputManager inputManager,
                              InventoryCraftingManager craftingManager,
                              InventoryRenderCoordinator renderCoordinator) {
        this.inventory = inventory;
        this.inputManager = inputManager;
        this.craftingManager = craftingManager;
        this.renderCoordinator = renderCoordinator;
        this.hotbarScreen = new HotbarScreen(inventory);
        this.visible = false;
        this.hoveredItemStack = null;
    }

    public void toggleVisibility() {
        if (this.visible) {
            returnHeldItemsToPlayer();
        }
        this.visible = !this.visible;
    }

    /**
     * Hands back everything this screen is holding when it closes: a stack still
     * on the cursor, then the crafting grid. Whatever the inventory cannot take is
     * dropped at the player's feet, so closing a crafting screen never destroys
     * items (issue #307). Safe to call when nothing is held.
     */
    public void returnHeldItemsToPlayer() {
        if (inputManager != null) {
            inputManager.handleCloseWithDraggedItems();
        }
        if (craftingManager == null) return;
        for (ItemStack overflow : craftingManager.returnInputsTo(inventory)) {
            DropUtil.dropItemFromPlayer(Game.getPlayer(), overflow);
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void update(float deltaTime) {
        hotbarScreen.update(deltaTime);
    }

    public void handleInput(int screenWidth, int screenHeight) {
        if (!visible) return;
        inputManager.handleMouseInput(screenWidth, screenHeight);
    }

    public void render(int screenWidth, int screenHeight) {
        if (!visible || renderCoordinator == null) return;
        renderCoordinator.render(screenWidth, screenHeight);
    }

    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        if (!visible || renderCoordinator == null) return;
        renderCoordinator.renderWithoutTooltips(screenWidth, screenHeight);
    }

    public void renderTooltipsOnly(int screenWidth, int screenHeight) {
        if (!visible || renderCoordinator == null) return;
        renderCoordinator.renderTooltipsOnly(screenWidth, screenHeight);
    }

    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        if (!visible || renderCoordinator == null) return;
        renderCoordinator.renderDraggedItemOnly(screenWidth, screenHeight);
    }

    public void renderHotbar(int screenWidth, int screenHeight) {
        if (renderCoordinator != null) {
            renderCoordinator.renderHotbar(screenWidth, screenHeight);
        }
    }

    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        if (renderCoordinator != null) {
            renderCoordinator.renderHotbarWithoutTooltips(screenWidth, screenHeight);
        }
    }

    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        if (renderCoordinator != null) {
            renderCoordinator.renderHotbarTooltipsOnly(screenWidth, screenHeight);
        }
    }

    public HotbarScreen getHotbarScreen() {
        return hotbarScreen;
    }

    public ItemStack getHoveredItemStack() {
        return hoveredItemStack;
    }

    public void setHoveredItemStack(ItemStack itemStack) {
        this.hoveredItemStack = itemStack;
    }

    public void displayHotbarItemTooltip(com.stonebreak.blocks.BlockType blockType) {
        hotbarScreen.displayItemTooltip(blockType);
    }

    public void displayHotbarItemTooltip(com.stonebreak.items.Item item) {
        hotbarScreen.displayItemTooltip(item);
    }

    public void displayHotbarItemTooltip(com.stonebreak.items.ItemStack itemStack) {
        hotbarScreen.displayItemTooltip(itemStack);
    }

    public void setRenderCoordinator(InventoryRenderCoordinator renderCoordinator) {
        this.renderCoordinator = renderCoordinator;
    }

    protected InventoryInputManager getInputManager() {
        return inputManager;
    }

    protected InventoryCraftingManager getCraftingManager() {
        return craftingManager;
    }
}