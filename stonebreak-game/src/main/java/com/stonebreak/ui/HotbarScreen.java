package com.stonebreak.ui;

import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.Item;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.ui.hotbar.styling.HotbarTheme;

/**
 * The hotbar's state for the HUD: its slots, the selected slot and the selected item's name tooltip
 * (shown, then faded out), which {@code stonebreak:hud} publishes.
 */
public class HotbarScreen {
    
    private final Inventory inventory;
    
    // Tooltip state
    private String selectedItemName;
    private float tooltipAlpha;
    private float tooltipTimer;

    // Use constants from theme for consistency
    private static final float TOOLTIP_DISPLAY_DURATION = HotbarTheme.Animation.TOOLTIP_DISPLAY_DURATION;
    private static final float TOOLTIP_FADE_DURATION = HotbarTheme.Animation.TOOLTIP_FADE_DURATION;

    public HotbarScreen(Inventory inventory) {
        this.inventory = inventory;
        this.selectedItemName = null;
        this.tooltipAlpha = 0.0f;
        this.tooltipTimer = 0.0f;
    }
    
    /**
     * Updates the hotbar tooltip animation.
     */
    public void update(float deltaTime) {
        if (tooltipTimer > 0) {
            tooltipTimer -= deltaTime;
            if (tooltipTimer <= 0) {
                tooltipTimer = 0;
                tooltipAlpha = 0;
                selectedItemName = null;
            } else if (tooltipTimer <= TOOLTIP_FADE_DURATION) {
                tooltipAlpha = Math.max(0.0f, tooltipTimer / TOOLTIP_FADE_DURATION);
            } else {
                tooltipAlpha = 1.0f;
            }
        }
    }
    
    /**
     * Displays a tooltip for the selected hotbar item (supports all Item types).
     */
    public void displayItemTooltip(Item item) {
        if (item != null && (!(item instanceof BlockType) || item != BlockType.AIR)) {
            this.selectedItemName = item.getName();
            this.tooltipTimer = TOOLTIP_DISPLAY_DURATION + TOOLTIP_FADE_DURATION;
            this.tooltipAlpha = 1.0f;
        } else {
            this.selectedItemName = null;
            this.tooltipTimer = 0.0f;
            this.tooltipAlpha = 0.0f;
        }
    }

    /**
     * Displays a tooltip for the selected hotbar item from an ItemStack.
     */
    public void displayItemTooltip(ItemStack itemStack) {
        if (itemStack != null && !itemStack.isEmpty()) {
            displayItemTooltip(itemStack.getItem());
        } else {
            this.selectedItemName = null;
            this.tooltipTimer = 0.0f;
            this.tooltipAlpha = 0.0f;
        }
    }

    /**
     * Displays a tooltip for the selected hotbar item (legacy BlockType support).
     * @deprecated Use displayItemTooltip(Item) or displayItemTooltip(ItemStack) instead
     */
    @Deprecated
    public void displayItemTooltip(BlockType blockType) {
        displayItemTooltip((Item) blockType);
    }
    
    /**
     * Gets the hotbar slots from the inventory.
     */
    public ItemStack[] getHotbarSlots() {
        return inventory.getHotbarSlots();
    }
    
    /**
     * Gets the selected hotbar slot index.
     */
    public int getSelectedSlotIndex() {
        return inventory.getSelectedHotbarSlotIndex();
    }
    
    /**
     * Gets the current tooltip text.
     */
    public String getTooltipText() {
        return selectedItemName;
    }
    
    /**
     * Gets the current tooltip alpha for fade animations.
     */
    public float getTooltipAlpha() {
        return tooltipAlpha;
    }
    
    /**
     * Checks if a tooltip should be displayed.
     */
    public boolean shouldShowTooltip() {
        return selectedItemName != null && tooltipAlpha > 0.0f;
    }
}
