package com.stonebreak.ui.inventoryScreen;

import com.stonebreak.items.Inventory;

public class InventoryLayoutCalculator {

    private static final int SLOT_SIZE = 40;
    private static final int SLOT_PADDING = 5;
    private static final int TITLE_HEIGHT = 30;
    private static final int CRAFTING_GRID_SIZE = 2;

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

        public InventoryLayout(int panelStartX, int panelStartY, int inventoryPanelWidth, int inventoryPanelHeight,
                             int craftingGridStartY, int craftingElementsStartX, int craftInputGridVisualWidth,
                             int mainInvContentStartY, int hotbarRowY, int outputSlotX, int outputSlotY) {
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
        }
    }

    public static InventoryLayout calculateLayout(int screenWidth, int screenHeight) {
        int baseInventoryPanelWidth = Inventory.MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int craftingSectionHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + SLOT_SIZE;
        int totalInventoryRows = Inventory.MAIN_INVENTORY_ROWS + 1; // +1 for hotbar
        int mainAndHotbarHeight = totalInventoryRows * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;

        int inventoryPanelWidth = baseInventoryPanelWidth;
        int inventoryPanelHeight = mainAndHotbarHeight + TITLE_HEIGHT + craftingSectionHeight + SLOT_PADDING * 2;

        int panelStartX = (screenWidth - inventoryPanelWidth) / 2;
        int panelStartY = (screenHeight - inventoryPanelHeight) / 2;

        // Crafting area calculations
        int craftingGridStartY = panelStartY + TITLE_HEIGHT + SLOT_PADDING;
        int craftInputGridVisualWidth = CRAFTING_GRID_SIZE * SLOT_SIZE + (CRAFTING_GRID_SIZE - 1) * SLOT_PADDING;
        int craftingElementsTotalWidth = craftInputGridVisualWidth + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE;
        int craftingElementsStartX = panelStartX + (inventoryPanelWidth - craftingElementsTotalWidth) / 2;
        int craftingAreaHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;

        // Main inventory area calculations
        float inventoryTitleY = craftingGridStartY + craftingSectionHeight - SLOT_SIZE / 2f;
        int mainInvContentStartY = (int)(inventoryTitleY + TITLE_HEIGHT / 2f + SLOT_PADDING);

        // Hotbar Y calculation
        int hotbarRowY = mainInvContentStartY + SLOT_PADDING + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;

        // Output slot calculation
        int outputSlotX = craftingElementsStartX + craftInputGridVisualWidth + SLOT_PADDING + SLOT_SIZE + SLOT_PADDING;
        int outputSlotY = craftingGridStartY + (craftingAreaHeight - SLOT_SIZE) / 2 - SLOT_PADDING;

        return new InventoryLayout(panelStartX, panelStartY, inventoryPanelWidth, inventoryPanelHeight,
                                 craftingGridStartY, craftingElementsStartX, craftInputGridVisualWidth,
                                 mainInvContentStartY, hotbarRowY, outputSlotX, outputSlotY);
    }

    public static int getSlotSize() { return SLOT_SIZE; }
    public static int getSlotPadding() { return SLOT_PADDING; }
    public static int getCraftingGridSize() { return CRAFTING_GRID_SIZE; }
    public static int getTitleHeight() { return TITLE_HEIGHT; }
    public static int getCraftingInputSlotsCount() { return CRAFTING_GRID_SIZE * CRAFTING_GRID_SIZE; }
}