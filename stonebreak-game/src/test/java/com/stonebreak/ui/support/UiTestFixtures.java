package com.stonebreak.ui.support;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;

/**
 * Builders for the plain game objects UI logic operates on.
 *
 * <p>{@link Inventory} has a no-arg constructor and only three imports, so tests use the real class
 * rather than a mock — matching {@code InventorySortTest} and the repo's general preference for
 * real objects over Mockito (only 5 of 44 game tests mock anything).
 *
 * <p>The empty-slot convention is {@code new ItemStack(BlockType.AIR.getId(), 0)}, which is what
 * production code writes when it clears a slot; {@link #empty()} exists so tests state that
 * intent rather than open-coding it.
 */
public final class UiTestFixtures {

    private UiTestFixtures() {}

    /** The canonical empty slot marker used throughout the inventory code. */
    public static ItemStack empty() {
        return new ItemStack(BlockType.AIR.getId(), 0);
    }

    /** An inventory with every hotbar and main slot explicitly empty. */
    public static Inventory emptyInventory() {
        Inventory inv = new Inventory();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            inv.setHotbarSlot(i, empty());
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            inv.setMainInventorySlot(i, empty());
        }
        return inv;
    }

    /** A stack of {@code type} filled to its own maximum, so it can absorb nothing further. */
    public static ItemStack fullStack(BlockType type) {
        return new ItemStack(type, new ItemStack(type, 1).getMaxStackSize());
    }

    /** Fills every hotbar slot with a full stack, so nothing more can be deposited there. */
    public static void fillHotbar(Inventory inv, BlockType type) {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            inv.setHotbarSlot(i, fullStack(type));
        }
    }

    /** Fills every main inventory slot with a full stack. */
    public static void fillMainInventory(Inventory inv, BlockType type) {
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            inv.setMainInventorySlot(i, fullStack(type));
        }
    }

    /** A crafting grid of the given side length, every cell empty. */
    public static ItemStack[] emptyCraftingGrid(int gridSize) {
        ItemStack[] slots = new ItemStack[gridSize * gridSize];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = empty();
        }
        return slots;
    }

    /** Total count of a given block type across hotbar and main inventory. */
    public static int countOf(Inventory inv, BlockType type) {
        int total = 0;
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            total += matching(inv.getHotbarSlot(i), type);
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            total += matching(inv.getMainInventorySlot(i), type);
        }
        return total;
    }

    private static int matching(ItemStack stack, BlockType type) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return stack.getBlockTypeId() == type.getId() ? stack.getCount() : 0;
    }
}
