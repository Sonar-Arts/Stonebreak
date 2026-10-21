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
     *
     * <p>With a live session the same return also runs on the SERVER's authoritative
     * copy ({@link InventoryInputManager#sendCloseReturnOps()}): the local return below
     * is the prediction, the sends are the authority — otherwise the next
     * InventorySyncS2C would overwrite the mirror without the grid's items (issue #334).
     * The SERVER's closing DEPOSIT_CARRIED returns the grid's leftovers and drops their
     * overflow at the player, so with a session the local overflow drops here would
     * duplicate it as slot debits and are skipped.
     */
    public void returnHeldItemsToPlayer() {
        if (inputManager != null) {
            inputManager.handleCloseWithDraggedItems();
            inputManager.sendCloseReturnOps();
        }
        if (craftingManager == null) return;
        boolean sessionLive = com.stonebreak.network.MultiplayerSession.isClient();
        for (ItemStack overflow : craftingManager.returnInputsTo(inventory)) {
            if (!sessionLive) {
                DropUtil.dropItemFromPlayer(Game.getPlayer(), overflow);
            }
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