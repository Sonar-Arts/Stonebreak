package com.stonebreak.items;

/**
 * The bucket conversion rule shared by filling, pouring and stalagmite watering
 * (issue #354): with a single bucket the slot's state swaps in place; with a stack the
 * slot gives up one bucket of the other state. The overflow bucket must have somewhere
 * to go — checked with {@link #canConvertHeld} BEFORE the world is touched — or the
 * action is refused instead of silently deleting it. A refused use sends nothing. The
 * server (which owns the inventory since #334) applies the same stack rule and, when its
 * replica is full while ours had room, drops the converted bucket at the player's feet
 * with its state ({@code ServerBlockHandler.convertOneBucket}).
 */
public final class BucketStacks {

    private BucketStacks() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Non-mutating check for whether using the held bucket can convert it to
     * {@code newState} without losing a bucket. A single bucket always fits (in-place
     * swap); a stack needs room for the one bucket of the new state it gives up.
     */
    public static boolean canConvertHeld(Inventory inventory, ItemStack held, String newState) {
        if (held == null || held.isEmpty() || held.getCount() <= 1) {
            return true;
        }
        return inventory.canAddItem(new ItemStack(ItemType.WOODEN_BUCKET, 1, newState));
    }

    /**
     * Converts the held bucket to {@code newState}. The caller must have passed
     * {@link #canConvertHeld} first.
     */
    public static void convertHeld(Inventory inventory, ItemStack held, String newState) {
        int slot = inventory.getSelectedHotbarSlotIndex();
        int count = held.getCount();
        if (count == 1) {
            inventory.setHotbarSlot(slot, new ItemStack(ItemType.WOODEN_BUCKET, 1, newState));
        } else {
            inventory.setHotbarSlot(slot,
                    new ItemStack(ItemType.WOODEN_BUCKET, count - 1, held.getState()));
            inventory.addItem(new ItemStack(ItemType.WOODEN_BUCKET, 1, newState));
        }
    }
}
