package com.stonebreak.ui.chest;

import com.stonebreak.blocks.container.ChestState;
import com.stonebreak.items.Inventory;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;

/**
 * Where the chest screen's slots sit, in device pixels: the furnace's panel, with the chest's
 * three rows of nine in the band under the title the furnace's crucible fills (exactly three slot
 * rows tall), lined up with the inventory grid below. Shared by hit-testing and slot addresses, and
 * mirrored by {@code chest.sbui}'s layout script.
 */
final class ChestLayout {

    private ChestLayout() {
    }

    static int[] chestSlot(InventoryLayoutCalculator.InventoryLayout layout, int i) {
        int ss = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        int bandTop = layout.panelStartY + InventoryLayoutCalculator.getPanelPadding()
                + InventoryLayoutCalculator.getTitleHeight() + InventoryLayoutCalculator.getSectionSpacing();
        return new int[]{
                layout.inventorySectionStartX + pad + (i % ChestState.COLS) * (ss + pad),
                bandTop + pad + (i / ChestState.COLS) * (ss + pad)};
    }

    static int[] mainSlot(InventoryLayoutCalculator.InventoryLayout layout, int i) {
        int ss = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        return new int[]{
                layout.inventorySectionStartX + pad + (i % Inventory.MAIN_INVENTORY_COLS) * (ss + pad),
                layout.mainInvContentStartY + pad + (i / Inventory.MAIN_INVENTORY_COLS) * (ss + pad)};
    }

    static int[] hotbarSlot(InventoryLayoutCalculator.InventoryLayout layout, int i) {
        int ss = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        return new int[]{layout.inventorySectionStartX + pad + i * (ss + pad), layout.hotbarRowY};
    }
}
