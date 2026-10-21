package com.stonebreak.core;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemLookup;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * PR #410 review: the autofurnace dev hook sends "/obtain <id> 8"; the server splits the line on
 * whitespace, so the id must be one token its ItemLookup resolves back to the same item (a display
 * name such as "Iron Ore" arrived as item "Iron" with count "Ore").
 */
class AutoFurnaceObtainIdTest {

    @Test
    void obtainIdsAreSingleTokensTheServerResolves() {
        for (Item item : new Item[]{BlockType.IRON_ORE, BlockType.COAL_ORE, BlockType.WOOD_PLANKS,
                ItemType.WOODEN_PICKAXE}) {
            String id = Main.obtainId(item);
            assertFalse(id.chars().anyMatch(Character::isWhitespace), id);
            assertSame(item, ItemLookup.find(ItemLookup.parseName(id)), id);
        }
    }
}
