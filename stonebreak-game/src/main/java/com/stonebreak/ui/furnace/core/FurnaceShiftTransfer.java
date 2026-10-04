package com.stonebreak.ui.furnace.core;

import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;

/**
 * Shift-click transfer from a furnace slot into the player inventory. Moves
 * only what fits and hands back the rest, so the caller can never delete
 * (or, by re-adding the whole stack, duplicate) items on a partial fit (#319).
 */
final class FurnaceShiftTransfer {

    private FurnaceShiftTransfer() {
    }

    /**
     * Adds as much of {@code slotStack} to {@code inventory} as fits.
     *
     * @return what is left over — an empty stack when everything fit. Never
     *         mutates {@code slotStack}.
     */
    static ItemStack intoInventory(ItemStack slotStack, Inventory inventory) {
        if (slotStack == null || slotStack.isEmpty()) {
            return new ItemStack(0, 0);
        }
        int added = inventory.addItemAndReturnCount(slotStack);
        if (added >= slotStack.getCount()) {
            return new ItemStack(0, 0);
        }
        ItemStack remainder = slotStack.copy();
        remainder.decrementCount(added);
        return remainder;
    }
}
