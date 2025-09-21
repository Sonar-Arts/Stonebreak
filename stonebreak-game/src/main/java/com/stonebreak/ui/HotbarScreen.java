package com.stonebreak.ui;

import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.blocks.BlockType;

/**
 * UI module for managing hotbar display and interaction logic.
 * This class handles the business logic for the hotbar UI component.
 */
public class HotbarScreen {
    
    private final Inventory inventory;
    
    // Tooltip state
    private String selectedItemName;
    private float tooltipAlpha;
    private float tooltipTimer;
    private static final float TOOLTIP_DISPLAY_DURATION = 1.5f; // seconds
    private static final float TOOLTIP_FADE_DURATION = 0.5f;   // seconds
    
    // Hotbar visual constants - synchronized with InventoryLayoutCalculator
    public static final int SLOT_SIZE = 44;
    public static final int SLOT_PADDING = 8;
    public static final int HOTBAR_Y_OFFSET = 50;
    
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
     * Displays a tooltip for the selected hotbar item.
     */
    public void displayItemTooltip(BlockType blockType) {
        if (blockType != null && blockType != BlockType.AIR) {
            this.selectedItemName = blockType.getName();
            this.tooltipTimer = TOOLTIP_DISPLAY_DURATION + TOOLTIP_FADE_DURATION;
            this.tooltipAlpha = 1.0f;
        } else {
            this.selectedItemName = null;
            this.tooltipTimer = 0.0f;
            this.tooltipAlpha = 0.0f;
        }
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
    
    /**
     * Calculates hotbar dimensions and positioning.
     */
    public HotbarLayout calculateLayout(int screenWidth, int screenHeight) {
        ItemStack[] hotbarItems = getHotbarSlots();
        int hotbarWidth = hotbarItems.length * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int hotbarStartX = (screenWidth - hotbarWidth) / 2;
        int hotbarStartY = screenHeight - SLOT_SIZE - HOTBAR_Y_OFFSET;
        
        return new HotbarLayout(hotbarStartX, hotbarStartY, hotbarWidth, SLOT_SIZE + SLOT_PADDING * 2);
    }
    
    /**
     * Calculates the position of a specific hotbar slot.
     */
    public SlotPosition calculateSlotPosition(int slotIndex, HotbarLayout layout) {
        int slotX = layout.startX + SLOT_PADDING + slotIndex * (SLOT_SIZE + SLOT_PADDING);
        int slotY = layout.startY;
        return new SlotPosition(slotX, slotY, SLOT_SIZE, SLOT_SIZE);
    }
    
    /**
     * Checks if a point is within a hotbar slot.
     */
    public boolean isPointInSlot(float mouseX, float mouseY, int slotIndex, HotbarLayout layout) {
        SlotPosition pos = calculateSlotPosition(slotIndex, layout);
        return mouseX >= pos.x && mouseX <= pos.x + pos.width &&
               mouseY >= pos.y && mouseY <= pos.y + pos.height;
    }
    
    /**
     * Gets the slot index at the given coordinates, or -1 if none.
     */
    public int getSlotIndexAt(float mouseX, float mouseY, HotbarLayout layout) {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            if (isPointInSlot(mouseX, mouseY, i, layout)) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Data class for hotbar layout information.
     */
    public static class HotbarLayout {
        public final int startX, startY, width, height;
        
        public HotbarLayout(int startX, int startY, int width, int height) {
            this.startX = startX;
            this.startY = startY;
            this.width = width;
            this.height = height;
        }
    }
    
    /**
     * Data class for slot position information.
     */
    public static class SlotPosition {
        public final int x, y, width, height;
        
        public SlotPosition(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}