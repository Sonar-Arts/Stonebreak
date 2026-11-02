package com.stonebreak.crafting;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;

/**
 * Resolves an SBO {@code objectId} to its {@link Item} singleton: blocks first
 * ({@link BlockType#getByObjectId}), then items ({@link ItemType#getByObjectId}).
 * Both keep a {@code BY_OBJECT_ID} index filled whenever a type is registered.
 */
public final class ItemResolver {

    private ItemResolver() {}

    /** @return the item, or {@code null} for a null, blank or unknown id */
    public static Item resolve(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return null;
        }
        BlockType bt = BlockType.getByObjectId(objectId);
        if (bt != null) return bt;
        return ItemType.getByObjectId(objectId);
    }
}
