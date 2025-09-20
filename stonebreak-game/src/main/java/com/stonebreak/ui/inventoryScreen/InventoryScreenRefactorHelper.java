package com.stonebreak.ui.inventoryScreen;

/**
 * Helper class to assist with the InventoryScreen refactoring.
 * Contains constants and utility methods that were previously hardcoded in InventoryScreen.
 */
public class InventoryScreenRefactorHelper {

    // Constants that were hardcoded in InventoryScreen
    public static final int CRAFTING_GRID_SIZE = 2;
    public static final int CRAFTING_INPUT_SLOTS_COUNT = CRAFTING_GRID_SIZE * CRAFTING_GRID_SIZE;
    public static final int CRAFTING_INPUT_SLOT_START_INDEX = 1000;
    public static final int CRAFTING_OUTPUT_SLOT_INDEX = 2000;

    private InventoryScreenRefactorHelper() {
        // Utility class
    }

    /**
     * Updates layout coordinates in render methods to use InventoryLayoutCalculator.
     * This method helps replace hardcoded coordinate calculations.
     */
    public static void updateRenderMethodLayout(InventoryLayoutCalculator.InventoryLayout layout,
                                              Runnable renderCraftingSlots,
                                              Runnable renderMainInventorySlots,
                                              Runnable renderHotbarSlots) {
        // Execute rendering in proper order
        renderCraftingSlots.run();
        renderMainInventorySlots.run();
        renderHotbarSlots.run();
    }
}