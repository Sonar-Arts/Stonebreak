package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rpg.backgrounds.BackgroundRegistry;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * The extra stacks a background grants at character creation (issue #334). Shared by the
     * client's first spawn and the server's fresh-player replica seed, so the authoritative
     * inventory includes them and the next sync no longer wipes them. Unknown item names are
     * skipped (logged); an unknown/null background grants nothing.
     */
    public static List<Entry> backgroundEntries(String backgroundId) {
        List<Entry> out = new ArrayList<>();
        BackgroundRegistry.findById(backgroundId).ifPresent(bg -> {
            for (String itemName : bg.extraItemNames()) {
                try {
                    out.add(new Entry(ItemType.valueOf(itemName).getId(), 1));
                } catch (IllegalArgumentException e) {
                    System.err.println("[SPAWN] Unknown background item: " + itemName);
                }
            }
        });
        return out;
    }

    /** Adds every starting stack to {@code inventory}. */
    public static void applyTo(Inventory inventory) {
        for (Entry e : ENTRIES) {
            inventory.addItem(e.itemId(), e.count());
        }
    }
}
