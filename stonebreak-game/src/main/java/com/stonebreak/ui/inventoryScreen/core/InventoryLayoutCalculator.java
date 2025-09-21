package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.items.Inventory;
import com.stonebreak.ui.inventoryScreen.styling.InventoryTheme;

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

    /**
     * Calculates modern inventory layout with improved spacing and visual hierarchy.
     */
    public static InventoryLayout calculateLayout(int screenWidth, int screenHeight) {
        // Calculate base inventory panel width with improved spacing
        int baseInventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + (PANEL_PADDING * 2);

        // Crafting section with better proportions
        int craftingSectionHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + SLOT_SIZE + SECTION_SPACING;

        // Calculate main inventory and hotbar heights separately for better control
        int mainInventoryHeight = Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int hotbarHeight = SLOT_SIZE + SLOT_PADDING; // Single row for hotbar
        int mainAndHotbarHeight = mainInventoryHeight + hotbarHeight + SECTION_SPACING; // Extra spacing between main inv and hotbar

        // Calculate required width for crafting section with improved button sizing
        int craftInputGridVisualWidth = CRAFTING_GRID_SIZE * SLOT_SIZE + (CRAFTING_GRID_SIZE - 1) * SLOT_PADDING;
        int recipeButtonWidth = Math.max(100, (int)(baseInventoryPanelWidth * 0.28f)); // Larger button
        int craftingElementsTotalWidth = craftInputGridVisualWidth + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE + (SLOT_PADDING * 2) + recipeButtonWidth;

        // Use modern spacing for panel dimensions
        int inventoryPanelWidth = Math.max(baseInventoryPanelWidth, craftingElementsTotalWidth + (PANEL_PADDING * 2));
        // Panel height: crafting title + crafting section + inventory title + main inventory + hotbar + padding
        int inventoryPanelHeight = TITLE_HEIGHT + craftingSectionHeight + TITLE_HEIGHT + mainAndHotbarHeight + (PANEL_PADDING * 2);

        // Center the panel on screen
        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

        // Crafting area calculations with improved spacing
        int craftingGridStartY = panelStartY + PANEL_PADDING + TITLE_HEIGHT + SECTION_SPACING;
        int craftingElementsStartX = panelStartX + PANEL_PADDING + (inventoryPanelWidth - craftingElementsTotalWidth - (PANEL_PADDING * 2)) / 2;
        int craftingAreaHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;

        // Main inventory area calculations with better section separation
        float inventoryTitleY = craftingGridStartY + craftingSectionHeight - SECTION_SPACING;
        int mainInvContentStartY = (int)(inventoryTitleY + TITLE_HEIGHT / 2f + SECTION_SPACING);

        // Hotbar Y calculation with improved spacing - ensure it stays within panel bounds
        int hotbarRowY = mainInvContentStartY + SLOT_PADDING + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SECTION_SPACING;

        // Output slot calculation with centered positioning
        int outputSlotX = craftingElementsStartX + craftInputGridVisualWidth + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING;
        int outputSlotY = craftingGridStartY + (craftingAreaHeight - SLOT_SIZE) / 2;

        // Validate that hotbar is within panel bounds
        int panelBottomY = panelStartY + inventoryPanelHeight;
        int hotbarBottomY = hotbarRowY + SLOT_SIZE;
        if (hotbarBottomY > panelBottomY - PANEL_PADDING) {
            System.err.println("WARNING: Hotbar extends beyond panel bounds! " +
                             "Hotbar bottom: " + hotbarBottomY +
                             ", Panel bottom (with padding): " + (panelBottomY - PANEL_PADDING));
        }

        return new InventoryLayout(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight,
                                 craftingGridStartY, craftingElementsStartX, craftInputGridVisualWidth,
                                 mainInvContentStartY, hotbarRowY, outputSlotX, outputSlotY);
    }

    // ==================== GETTER METHODS ====================

    public static int getSlotSize() { return SLOT_SIZE; }
    public static int getSlotPadding() { return SLOT_PADDING; }
    public static int getSectionSpacing() { return SECTION_SPACING; }
    public static int getTitleHeight() { return TITLE_HEIGHT; }
    public static int getPanelPadding() { return PANEL_PADDING; }
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
     * Gets the visual theme-compatible corner radius for panels.
     */
    public static float getPanelCornerRadius() {
        return InventoryTheme.Measurements.CORNER_RADIUS_LARGE;
    }

    /**
     * Calculate modern layout for workbench screen with 3x3 crafting grid and improved spacing.
     */
    public static InventoryLayout calculateWorkbenchLayout(int screenWidth, int screenHeight) {
        int baseInventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + (PANEL_PADDING * 2);

        // Crafting area: 3x3 grid + arrow + output slot + recipe button with modern spacing
        int craftingGridVisualWidth = WORKBENCH_CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING;

        // Calculate recipe button width with improved proportions
        int minPanelWidth = baseInventoryPanelWidth;
        int recipeButtonWidth = Math.max(100, (int)(minPanelWidth * 0.28f)); // Larger button

        // Total crafting section with better spacing
        int craftingSectionWidth = craftingGridVisualWidth + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE + (SLOT_PADDING * 2) + recipeButtonWidth;

        int inventoryPanelWidth = Math.max(baseInventoryPanelWidth, craftingSectionWidth + (PANEL_PADDING * 2));

        // Height with modern section spacing
        int craftingGridActualHeight = WORKBENCH_CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        // Calculate main inventory and hotbar heights separately for better control
        int mainInventoryHeight = Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int hotbarHeight = SLOT_SIZE + SLOT_PADDING; // Single row for hotbar
        int playerInvHeight = mainInventoryHeight + hotbarHeight + SECTION_SPACING; // Extra spacing between main inv and hotbar

        int inventoryPanelHeight = TITLE_HEIGHT + craftingGridActualHeight + TITLE_HEIGHT + playerInvHeight + (SECTION_SPACING * 3) + (PANEL_PADDING * 2);

        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

        // Crafting elements layout with improved spacing
        int craftingGridStartY = panelStartY + PANEL_PADDING + TITLE_HEIGHT + SECTION_SPACING;
        int craftingElementsStartX = panelStartX + PANEL_PADDING + (inventoryPanelWidth - craftingSectionWidth - (PANEL_PADDING * 2)) / 2;

        // Arrow position centered with improved alignment
        int arrowX = craftingElementsStartX + craftingGridVisualWidth + SLOT_PADDING;
        int arrowY = craftingGridStartY + (WORKBENCH_CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING - SLOT_SIZE) / 2;

        // Output slot position aligned with arrow
        int outputSlotX = arrowX + SLOT_SIZE + SLOT_PADDING;
        int outputSlotY = arrowY;

        // Player inventory area with better section separation
        int playerInvTitleY = craftingGridStartY + craftingGridActualHeight + SECTION_SPACING + (TITLE_HEIGHT / 2);
        int mainInvContentStartY = playerInvTitleY + (TITLE_HEIGHT / 2) + SECTION_SPACING;

        // Hotbar Y calculation with improved spacing - ensure it stays within panel bounds
        int hotbarRowY = mainInvContentStartY + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SECTION_SPACING;

        // Validate that hotbar is within panel bounds for workbench layout
        int panelBottomY = panelStartY + inventoryPanelHeight;
        int hotbarBottomY = hotbarRowY + SLOT_SIZE;
        if (hotbarBottomY > panelBottomY - PANEL_PADDING) {
            System.err.println("WARNING: Workbench hotbar extends beyond panel bounds! " +
                             "Hotbar bottom: " + hotbarBottomY +
                             ", Panel bottom (with padding): " + (panelBottomY - PANEL_PADDING));
        }

        // Calculate centered inventory section start X
        int inventoryGridWidth = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING;
        int inventorySectionStartX = panelStartX + (inventoryPanelWidth - inventoryGridWidth) / 2;

        return new InventoryLayout(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight,
                                 craftingGridStartY, craftingElementsStartX, craftingGridVisualWidth,
                                 mainInvContentStartY, hotbarRowY, outputSlotX, outputSlotY, inventorySectionStartX);
    }
}