package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;

/**
 * The shared new-character starting inventory. Used by both sides so the client's
 * starting items and the server's authoritative {@code ServerInventory} seed agree:
 * {@link Inventory#initializeStartingItems()} applies it client-side, the server
 * applies it when a player joins with no saved data.
 */
public final class StartingItems {

    /** One entry per starting stack: block/item id + count. */
    public record Entry(int itemId, int count) {}

    public static final Entry[] ENTRIES = {
        new Entry(BlockType.GRASS.getId(), 10),
        new Entry(BlockType.DIRT.getId(), 10),
        new Entry(BlockType.STONE.getId(), 10),
        new Entry(BlockType.WOOD.getId(), 10),
        new Entry(BlockType.WATER.getId(), 5),
        new Entry(ItemType.STICK.getId(), 5),
        new Entry(ItemType.WOODEN_PICKAXE.getId(), 1),
    };

    private StartingItems() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Adds every starting stack to {@code inventory}. */
    public static void applyTo(Inventory inventory) {
        for (Entry e : ENTRIES) {
            inventory.addItem(e.itemId(), e.count());
        }
    }
}
