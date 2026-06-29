package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.items.Inventory;

/**
 * Enhanced layout calculator for modern inventory UI with improved spacing and proportions.
 * Follows contemporary UI design principles with proper visual hierarchy and breathing room.
 */
public class InventoryLayoutCalculator {

    // Modern slot sizing for better visibility and interaction
    private static final int SLOT_SIZE = 40; // Updated slot size
    private static final int SLOT_PADDING = 8; // Increased from 5 for better spacing
    private static final int SECTION_SPACING = 24; // Spacing between major sections
    private static final int TITLE_HEIGHT = 36; // Increased for better typography
    private static final int PANEL_PADDING = 20; // Padding around the entire panel
    private static final int CRAFTING_GRID_SIZE = 2;
    private static final int WORKBENCH_CRAFTING_GRID_SIZE = 3;

    // Three-column layout constants (unscaled)
    private static final int LEFT_COL_WIDTH = 180;
    private static final int RIGHT_COL_WIDTH = 180;
    private static final int COL_GAP = 10;

    public static class InventoryLayout {
        public final int panelStartX;
        public final int panelStartY;
        public final int inventoryPanelWidth;
        public final int inventoryPanelHeight;
        public final int craftingGridStartY;
        public final int craftingElementsStartX;
        public final int craftInputGridVisualWidth;
        public final int mainInvContentStartY;
        public final int hotbarRowY;
        public final int outputSlotX;
        public final int outputSlotY;
        public final int inventorySectionStartX;

        public InventoryLayout(int panelStartX, int panelStartY, int inventoryPanelWidth, int inventoryPanelHeight,
                             int craftingGridStartY, int craftingElementsStartX, int craftInputGridVisualWidth,
                             int mainInvContentStartY, int hotbarRowY, int outputSlotX, int outputSlotY) {
            this(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight,
                 craftingGridStartY, craftingElementsStartX, craftInputGridVisualWidth,
                 mainInvContentStartY, hotbarRowY, outputSlotX, outputSlotY,
                 panelStartX + PANEL_PADDING); // Default inventory section start for regular inventory
        }

        public InventoryLayout(int panelStartX, int panelStartY, int inventoryPanelWidth, int inventoryPanelHeight,
                             int craftingGridStartY, int craftingElementsStartX, int craftInputGridVisualWidth,
                             int mainInvContentStartY, int hotbarRowY, int outputSlotX, int outputSlotY,
                             int inventorySectionStartX) {
            this.panelStartX = panelStartX;
            this.panelStartY = panelStartY;
            this.inventoryPanelWidth = inventoryPanelWidth;
            this.inventoryPanelHeight = inventoryPanelHeight;
            this.craftingGridStartY = craftingGridStartY;
            this.craftingElementsStartX = craftingElementsStartX;
            this.craftInputGridVisualWidth = craftInputGridVisualWidth;
            this.mainInvContentStartY = mainInvContentStartY;
            this.hotbarRowY = hotbarRowY;
            this.outputSlotX = outputSlotX;
            this.outputSlotY = outputSlotY;
            this.inventorySectionStartX = inventorySectionStartX;
        }
    }

    /** Positions for the full three-column inventory panel. */
    public static class InventoryLayout3Col {
        public final int panelStartX, panelStartY;
        public final int totalPanelWidth, totalPanelHeight;
        public final int leftColX, leftColY, leftColW, leftColH;
        public final InventoryLayout center;
        public final int rightColX, rightColY, rightColW, rightColH;

        public InventoryLayout3Col(int panelStartX, int panelStartY,
                                   int totalPanelWidth, int totalPanelHeight,
                                   int leftColX, int leftColY, int leftColW, int leftColH,
                                   InventoryLayout center,
                                   int rightColX, int rightColY, int rightColW, int rightColH) {
            this.panelStartX = panelStartX;
            this.panelStartY = panelStartY;
            this.totalPanelWidth = totalPanelWidth;
            this.totalPanelHeight = totalPanelHeight;
            this.leftColX = leftColX;
            this.leftColY = leftColY;
            this.leftColW = leftColW;
            this.leftColH = leftColH;
            this.center = center;
            this.rightColX = rightColX;
            this.rightColY = rightColY;
            this.rightColW = rightColW;
            this.rightColH = rightColH;
        }
    }

    /**
     * Computes the three-column inventory layout: equipment panel (left),
     * crafting+items (center), and character stats (right).
     *
     * The center column reuses {@link #calculateLayout} geometry but shifts all
     * X coordinates to account for the left column.
     */
    public static InventoryLayout3Col calculateThreeColumnLayout(int screenWidth, int screenHeight) {
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        int leftColW = Math.round(LEFT_COL_WIDTH * scale);
        int rightColW = Math.round(RIGHT_COL_WIDTH * scale);
        int colGap = Math.round(COL_GAP * scale);

        InventoryLayout rawCenter = calculateLayout(screenWidth, screenHeight);
        int centerW = rawCenter.inventoryPanelWidth;
        int centerH = rawCenter.inventoryPanelHeight;

        int totalW = leftColW + colGap + centerW + colGap + rightColW;
        int panelStartX = (screenWidth - totalW) / 2;
        int panelStartY = rawCenter.panelStartY;
        int centerStartX = panelStartX + leftColW + colGap;
        int rightStartX = centerStartX + centerW + colGap;

        int xShift = centerStartX - rawCenter.panelStartX;
        InventoryLayout shiftedCenter = new InventoryLayout(
            centerStartX,
            rawCenter.panelStartY,
            centerW,
            centerH,
            rawCenter.craftingGridStartY,
            rawCenter.craftingElementsStartX + xShift,
            rawCenter.craftInputGridVisualWidth,
            rawCenter.mainInvContentStartY,
            rawCenter.hotbarRowY,
            rawCenter.outputSlotX + xShift,
            rawCenter.outputSlotY,
            rawCenter.inventorySectionStartX + xShift
        );

        return new InventoryLayout3Col(
            panelStartX, panelStartY,
            totalW, centerH,
            panelStartX, panelStartY, leftColW, centerH,
            shiftedCenter,
            rightStartX, panelStartY, rightColW, centerH
        );
    }

    /**
     * Calculates modern inventory layout with improved spacing and visual hierarchy.
     */
    public static InventoryLayout calculateLayout(int screenWidth, int screenHeight) {
        float uiScale = com.stonebreak.config.Settings.getInstance().getUiScale();
        int slotSize = Math.round(SLOT_SIZE * uiScale);
        int slotPadding = Math.round(SLOT_PADDING * uiScale);
        int sectionSpacing = Math.round(SECTION_SPACING * uiScale);
        int titleHeight = Math.round(TITLE_HEIGHT * uiScale);
        int panelPadding = Math.round(PANEL_PADDING * uiScale);

        // Calculate base inventory panel width with improved spacing
        int baseInventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (slotSize + slotPadding) + slotPadding + (panelPadding * 2);

        // Crafting section with better proportions
        int craftingSectionHeight = CRAFTING_GRID_SIZE * (slotSize + slotPadding) + slotPadding + slotSize + sectionSpacing;

        // Calculate main inventory and hotbar heights separately for better control
        int mainInventoryHeight = Inventory.MAIN_INVENTORY_ROWS * (slotSize + slotPadding) + slotPadding;
        int hotbarHeight = slotSize + slotPadding;
        int mainAndHotbarHeight = mainInventoryHeight + hotbarHeight + sectionSpacing;

        // Calculate required width for crafting section with improved button sizing
        int craftInputGridVisualWidth = CRAFTING_GRID_SIZE * slotSize + (CRAFTING_GRID_SIZE - 1) * slotPadding;
        int recipeButtonMinWidth = Math.round(100 * uiScale);
        int recipeButtonWidth = Math.max(recipeButtonMinWidth, (int)(baseInventoryPanelWidth * 0.28f));
        int craftingElementsTotalWidth = craftInputGridVisualWidth + slotSize + slotPadding + slotSize + (slotPadding * 2) + recipeButtonWidth;

        // Use modern spacing for panel dimensions
        int inventoryPanelWidth = Math.max(baseInventoryPanelWidth, craftingElementsTotalWidth + (panelPadding * 2));
        // Panel height: crafting title + crafting section + inventory title + main inventory + hotbar + padding
        int inventoryPanelHeight = titleHeight + craftingSectionHeight + titleHeight + mainAndHotbarHeight + (panelPadding * 2);

        // Center the panel on screen
        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

        // Crafting area calculations with improved spacing
        int craftingGridStartY = panelStartY + panelPadding + titleHeight + sectionSpacing;
        int craftingElementsStartX = panelStartX + panelPadding + (inventoryPanelWidth - craftingElementsTotalWidth - (panelPadding * 2)) / 2;
        int craftingAreaHeight = CRAFTING_GRID_SIZE * (slotSize + slotPadding) + slotPadding;

        // Main inventory area calculations with better section separation
        float inventoryTitleY = craftingGridStartY + craftingSectionHeight - sectionSpacing;
        int mainInvContentStartY = (int)(inventoryTitleY + titleHeight / 2f + sectionSpacing);

        // Hotbar Y calculation with improved spacing - ensure it stays within panel bounds
        int hotbarRowY = mainInvContentStartY + slotPadding + Inventory.MAIN_INVENTORY_ROWS * (slotSize + slotPadding) + sectionSpacing;

        // Output slot calculation with centered positioning
        int outputSlotX = craftingElementsStartX + craftInputGridVisualWidth + slotPadding + slotSize + slotPadding;
        int outputSlotY = craftingGridStartY + (craftingAreaHeight - slotSize) / 2;

        // Validate that hotbar is within panel bounds
        int panelBottomY = panelStartY + inventoryPanelHeight;
        int hotbarBottomY = hotbarRowY + slotSize;
        if (hotbarBottomY > panelBottomY - panelPadding) {
            System.err.println("WARNING: Hotbar extends beyond panel bounds! " +
                             "Hotbar bottom: " + hotbarBottomY +
                             ", Panel bottom (with padding): " + (panelBottomY - panelPadding));
        }

        return new InventoryLayout(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight,
                                 craftingGridStartY, craftingElementsStartX, craftInputGridVisualWidth,
                                 mainInvContentStartY, hotbarRowY, outputSlotX, outputSlotY,
                                 panelStartX + panelPadding);
    }

    // ==================== GETTER METHODS ====================

    // All dimensional getters return values scaled by the current uiScale so
    // external consumers (furnace layout, slot/input managers) stay aligned with
    // the scaled panel geometry produced by calculateLayout/calculateWorkbenchLayout.
    private static float uiScale() { return com.stonebreak.config.Settings.getInstance().getUiScale(); }

    public static int getSlotSize() { return Math.round(SLOT_SIZE * uiScale()); }
    public static int getSlotPadding() { return Math.round(SLOT_PADDING * uiScale()); }
    public static int getSectionSpacing() { return Math.round(SECTION_SPACING * uiScale()); }
    public static int getTitleHeight() { return Math.round(TITLE_HEIGHT * uiScale()); }
    public static int getPanelPadding() { return Math.round(PANEL_PADDING * uiScale()); }
    public static int getCraftingGridSize() { return CRAFTING_GRID_SIZE; }
    public static int getWorkbenchCraftingGridSize() { return WORKBENCH_CRAFTING_GRID_SIZE; }
    public static int getCraftingInputSlotsCount() { return CRAFTING_GRID_SIZE * CRAFTING_GRID_SIZE; }
    public static int getWorkbenchCraftingInputSlotsCount() { return WORKBENCH_CRAFTING_GRID_SIZE * WORKBENCH_CRAFTING_GRID_SIZE; }

    // ==================== MODERN UI HELPERS ====================

    /**
     * Calculates the minimum screen width required for optimal inventory display.
     */
    public static int getMinimumRecommendedWidth() {
        return (Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING)) + (PANEL_PADDING * 4) + 200; // Extra space for crafting
    }

    /**
     * Calculates the minimum screen height required for optimal inventory display.
     */
    public static int getMinimumRecommendedHeight() {
        // Calculate separate heights for main inventory and hotbar with proper spacing
        int mainInventoryHeight = Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int hotbarHeight = SLOT_SIZE + SLOT_PADDING;
        return mainInventoryHeight + hotbarHeight + (SECTION_SPACING * 4) + (TITLE_HEIGHT * 2) + (PANEL_PADDING * 2) + 150; // Extra space for crafting
    }

    /**
     * Determines if the current screen size supports the modern layout comfortably.
     */
    public static boolean isScreenSizeAdequate(int screenWidth, int screenHeight) {
        return screenWidth >= getMinimumRecommendedWidth() && screenHeight >= getMinimumRecommendedHeight();
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
     * Calculate modern layout for workbench screen with 3x3 crafting grid and improved spacing.
     */
    public static InventoryLayout calculateWorkbenchLayout(int screenWidth, int screenHeight) {
        float uiScale = com.stonebreak.config.Settings.getInstance().getUiScale();
        int slotSize = Math.round(SLOT_SIZE * uiScale);
        int slotPadding = Math.round(SLOT_PADDING * uiScale);
        int sectionSpacing = Math.round(SECTION_SPACING * uiScale);
        int titleHeight = Math.round(TITLE_HEIGHT * uiScale);
        int panelPadding = Math.round(PANEL_PADDING * uiScale);

        int baseInventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (slotSize + slotPadding) + slotPadding + (panelPadding * 2);

        // Crafting area: 3x3 grid + arrow + output slot + recipe button with modern spacing
        int craftingGridVisualWidth = WORKBENCH_CRAFTING_GRID_SIZE * (slotSize + slotPadding) - slotPadding;

        // Calculate recipe button width with improved proportions
        int recipeButtonMinWidth = Math.round(100 * uiScale);
        int recipeButtonWidth = Math.max(recipeButtonMinWidth, (int)(baseInventoryPanelWidth * 0.28f));

        // Total crafting section with better spacing
        int craftingSectionWidth = craftingGridVisualWidth + slotPadding + slotSize + slotPadding + slotSize + (slotPadding * 2) + recipeButtonWidth;

        int inventoryPanelWidth = Math.max(baseInventoryPanelWidth, craftingSectionWidth + (panelPadding * 2));

        // Height with modern section spacing
        int craftingGridActualHeight = WORKBENCH_CRAFTING_GRID_SIZE * (slotSize + slotPadding) + slotPadding;
        int mainInventoryHeight = Inventory.MAIN_INVENTORY_ROWS * (slotSize + slotPadding) + slotPadding;
        int hotbarHeight = slotSize + slotPadding;
        int playerInvHeight = mainInventoryHeight + hotbarHeight + sectionSpacing;

        int inventoryPanelHeight = titleHeight + craftingGridActualHeight + titleHeight + playerInvHeight + (sectionSpacing * 3) + (panelPadding * 2);

        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

        // Crafting elements layout with improved spacing
        int craftingGridStartY = panelStartY + panelPadding + titleHeight + sectionSpacing;
        int craftingElementsStartX = panelStartX + panelPadding + (inventoryPanelWidth - craftingSectionWidth - (panelPadding * 2)) / 2;

        // Arrow position centered with improved alignment
        int arrowX = craftingElementsStartX + craftingGridVisualWidth + slotPadding;
        int arrowY = craftingGridStartY + (WORKBENCH_CRAFTING_GRID_SIZE * (slotSize + slotPadding) - slotPadding - slotSize) / 2;

        // Output slot position aligned with arrow
        int outputSlotX = arrowX + slotSize + slotPadding;
        int outputSlotY = arrowY;

        // Player inventory area with better section separation
        int playerInvTitleY = craftingGridStartY + craftingGridActualHeight + sectionSpacing + (titleHeight / 2);
        int mainInvContentStartY = playerInvTitleY + (titleHeight / 2) + sectionSpacing;

        // Hotbar Y calculation with improved spacing - ensure it stays within panel bounds
        int hotbarRowY = mainInvContentStartY + Inventory.MAIN_INVENTORY_ROWS * (slotSize + slotPadding) + sectionSpacing;

        // Validate that hotbar is within panel bounds for workbench layout
        int panelBottomY = panelStartY + inventoryPanelHeight;
        int hotbarBottomY = hotbarRowY + slotSize;
        if (hotbarBottomY > panelBottomY - panelPadding) {
            System.err.println("WARNING: Workbench hotbar extends beyond panel bounds! " +
                             "Hotbar bottom: " + hotbarBottomY +
                             ", Panel bottom (with padding): " + (panelBottomY - panelPadding));
        }

        // Calculate centered inventory section start X
        int inventoryGridWidth = Inventory.MAIN_INVENTORY_COLS * (slotSize + slotPadding) - slotPadding;
        int inventorySectionStartX = panelStartX + (inventoryPanelWidth - inventoryGridWidth) / 2;

        return new InventoryLayout(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight,
                                 craftingGridStartY, craftingElementsStartX, craftingGridVisualWidth,
                                 mainInvContentStartY, hotbarRowY, outputSlotX, outputSlotY, inventorySectionStartX);
    }
}