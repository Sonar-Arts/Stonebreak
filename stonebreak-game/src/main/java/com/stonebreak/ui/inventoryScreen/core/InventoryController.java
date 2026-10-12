package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.util.DropUtil;
import com.stonebreak.ui.HotbarScreen;

/**
 * Controller responsible for coordinating inventory UI operations.
 * Follows Single Responsibility Principle by handling only coordination logic.
 */
public class InventoryController {

    private final Inventory inventory;
    private final HotbarScreen hotbarScreen;
    private final InventoryInputManager inputManager;
    private final InventoryCraftingManager craftingManager;

    private boolean visible;

    public InventoryController(Inventory inventory,
                              InventoryInputManager inputManager,
                              InventoryCraftingManager craftingManager) {
        this.inventory = inventory;
        this.inputManager = inputManager;
        this.craftingManager = craftingManager;
        this.hotbarScreen = new HotbarScreen(inventory);
        this.visible = false;
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

    public HotbarScreen getHotbarScreen() {
        return hotbarScreen;
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

    protected InventoryInputManager getInputManager() {
        return inputManager;
    }

    protected InventoryCraftingManager getCraftingManager() {
        return craftingManager;
    }
}