package com.stonebreak.ui.hotbar.core;

import com.stonebreak.items.Inventory;
import com.stonebreak.ui.hotbar.styling.HotbarTheme;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;

/**
 * Layout calculator for the hotbar UI system.
 * Maintains consistency with the inventory layout system while providing hotbar-specific calculations.
 */
public class HotbarLayoutCalculator {

    // Use consistent slot sizing with inventory system
    private static final int SLOT_SIZE = InventoryLayoutCalculator.getSlotSize(); // 40px
    private static final int SLOT_PADDING = InventoryLayoutCalculator.getSlotPadding(); // 8px
    private static final int HOTBAR_Y_OFFSET = HotbarTheme.Measurements.HOTBAR_Y_OFFSET; // 50px

    /**
     * Layout data class for hotbar positioning and dimensions.
     */
    public static class HotbarLayout {
        public final int startX, startY, width, height;
        public final int backgroundX, backgroundY, backgroundWidth, backgroundHeight;
        public final int slotCount;

        public HotbarLayout(int startX, int startY, int width, int height,
                           int backgroundX, int backgroundY, int backgroundWidth, int backgroundHeight,
                           int slotCount) {
            this.startX = startX;
            this.startY = startY;
            this.width = width;
            this.height = height;
            this.backgroundX = backgroundX;
            this.backgroundY = backgroundY;
            this.backgroundWidth = backgroundWidth;
            this.backgroundHeight = backgroundHeight;
            this.slotCount = slotCount;
        }
    }

    /**
     * Position data class for individual slot positioning.
     */
    public static class SlotPosition {
        public final int x, y, width, height;
        public final int centerX, centerY;

        public SlotPosition(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.centerX = x + width / 2;
            this.centerY = y + height / 2;
        }
    }

    /**
     * Calculates the complete hotbar layout for the given screen dimensions.
     */
    public static HotbarLayout calculateLayout(int screenWidth, int screenHeight) {
        // Calculate hotbar dimensions
        int slotCount = Inventory.HOTBAR_SIZE;
        int hotbarContentWidth = slotCount * SLOT_SIZE + (slotCount - 1) * SLOT_PADDING;
        int hotbarContentHeight = SLOT_SIZE;

        // Calculate background dimensions with padding
        int backgroundPadding = (int) HotbarTheme.Measurements.PADDING_SMALL;
        int backgroundWidth = hotbarContentWidth + (backgroundPadding * 2);
        int backgroundHeight = hotbarContentHeight + (backgroundPadding * 2);

        // Center horizontally, position at bottom with offset
        int backgroundX = (screenWidth - backgroundWidth) / 2;
        int backgroundY = screenHeight - backgroundHeight - HOTBAR_Y_OFFSET;

        // Content starts inside the background padding
        int contentStartX = backgroundX + backgroundPadding;
        int contentStartY = backgroundY + backgroundPadding;

        return new HotbarLayout(
            contentStartX, contentStartY, hotbarContentWidth, hotbarContentHeight,
            backgroundX, backgroundY, backgroundWidth, backgroundHeight,
            slotCount
        );
    }

    /**
     * Calculates the position of a specific hotbar slot.
     */
    public static SlotPosition calculateSlotPosition(int slotIndex, HotbarLayout layout) {
        if (slotIndex < 0 || slotIndex >= layout.slotCount) {
            throw new IllegalArgumentException("Slot index out of bounds: " + slotIndex);
        }

        int slotX = layout.startX + slotIndex * (SLOT_SIZE + SLOT_PADDING);
        int slotY = layout.startY;

        return new SlotPosition(slotX, slotY, SLOT_SIZE, SLOT_SIZE);
    }

    /**
     * Checks if a point is within a specific hotbar slot.
     */
    public static boolean isPointInSlot(float mouseX, float mouseY, int slotIndex, HotbarLayout layout) {
        SlotPosition pos = calculateSlotPosition(slotIndex, layout);
        return mouseX >= pos.x && mouseX <= pos.x + pos.width &&
               mouseY >= pos.y && mouseY <= pos.y + pos.height;
    }

    /**
     * Gets the slot index at the given coordinates, or -1 if none.
     */
    public static int getSlotIndexAt(float mouseX, float mouseY, HotbarLayout layout) {
        for (int i = 0; i < layout.slotCount; i++) {
            if (isPointInSlot(mouseX, mouseY, i, layout)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Calculates tooltip position above the selected slot.
     */
    public static TooltipPosition calculateTooltipPosition(int slotIndex, HotbarLayout layout,
                                                         float tooltipWidth, float tooltipHeight,
                                                         int screenWidth) {
        SlotPosition slotPos = calculateSlotPosition(slotIndex, layout);

        // Center tooltip above the slot
        float tooltipX = slotPos.centerX - tooltipWidth / 2.0f;
        float tooltipY = layout.backgroundY - tooltipHeight - HotbarTheme.Measurements.PADDING_MEDIUM;

        // Keep tooltip within screen bounds
        if (tooltipX < HotbarTheme.Measurements.PADDING_MEDIUM) {
            tooltipX = HotbarTheme.Measurements.PADDING_MEDIUM;
        }
        if (tooltipX + tooltipWidth > screenWidth - HotbarTheme.Measurements.PADDING_MEDIUM) {
            tooltipX = screenWidth - tooltipWidth - HotbarTheme.Measurements.PADDING_MEDIUM;
        }

        return new TooltipPosition((int)tooltipX, (int)tooltipY, (int)tooltipWidth, (int)tooltipHeight);
    }

    /**
     * Position data class for tooltip positioning.
     */
    public static class TooltipPosition {
        public final int x, y, width, height;

        public TooltipPosition(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    // ==================== GETTER METHODS ====================

    public static int getSlotSize() {
        return SLOT_SIZE;
    }

    public static int getSlotPadding() {
        return SLOT_PADDING;
    }

    public static int getHotbarYOffset() {
        return HOTBAR_Y_OFFSET;
    }

    /**
     * Calculates the minimum screen width required for optimal hotbar display.
     */
    public static int getMinimumRecommendedWidth() {
        return Inventory.HOTBAR_SIZE * (SLOT_SIZE + SLOT_PADDING) +
               (int)(HotbarTheme.Measurements.PADDING_LARGE * 4);
    }

    /**
     * Calculates the minimum screen height required for optimal hotbar display.
     */
    public static int getMinimumRecommendedHeight() {
        return SLOT_SIZE + HOTBAR_Y_OFFSET + (int)(HotbarTheme.Measurements.PADDING_LARGE * 2);
    }

    /**
     * Determines if the current screen size supports the hotbar layout comfortably.
     */
    public static boolean isScreenSizeAdequate(int screenWidth, int screenHeight) {
        return screenWidth >= getMinimumRecommendedWidth() &&
               screenHeight >= getMinimumRecommendedHeight();
    }

    /**
     * Calculates responsive scale factor for smaller screens.
     */
    public static float calculateScaleFactor(int screenWidth, int screenHeight) {
        if (isScreenSizeAdequate(screenWidth, screenHeight)) {
            return 1.0f;
        }

        float widthScale = (float) screenWidth / getMinimumRecommendedWidth();
        float heightScale = (float) screenHeight / getMinimumRecommendedHeight();
        return Math.max(0.7f, Math.min(widthScale, heightScale)); // Don't scale below 70%
    }

    /**
     * Gets the visual theme-compatible corner radius for hotbar elements.
     */
    public static float getCornerRadius() {
        return HotbarTheme.Measurements.CORNER_RADIUS_LARGE;
    }

    /**
     * Calculates item icon size based on slot size and padding.
     */
    public static int calculateIconSize() {
        int iconPadding = Math.max(2, SLOT_SIZE / 12); // Generous padding ratio
        return SLOT_SIZE - (iconPadding * 2);
    }

    /**
     * Calculates item icon padding for consistent positioning.
     */
    public static int calculateIconPadding() {
        return Math.max(2, SLOT_SIZE / 12);
    }
}