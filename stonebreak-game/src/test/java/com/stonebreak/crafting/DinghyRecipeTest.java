package com.stonebreak.crafting;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dinghy recipe from its SBO: shovel _ shovel / plank iron-chunk plank / plank plank plank,
 * with all five planks of one wood.
 */
class DinghyRecipeTest {

    private static final CraftingManager MANAGER = new CraftingManager();

    @BeforeAll
    static void load() {
        RecipeLoader.loadFromSBOs(MANAGER);
    }

    private static ItemStack s(Item item) {
        return item == null ? null : new ItemStack(item, 1);
    }

    private static List<List<ItemStack>> grid(Item plankA, Item plankB, Item middle) {
        Item shovel = ItemType.WOODEN_SHOVEL;
        return Arrays.asList(
                Arrays.asList(s(shovel), null, s(shovel)),
                Arrays.asList(s(plankA), s(middle), s(plankA)),
                Arrays.asList(s(plankB), s(plankA), s(plankA)));
    }

    @Test
    void everyWoodMakesADinghy() {
        Item iron = ItemType.getByObjectId("stonebreak:iron_chunk");
        for (BlockType plank : new BlockType[]{BlockType.WOOD_PLANKS, BlockType.PINE_WOOD_PLANKS,
                BlockType.ELM_WOOD_PLANKS, BlockType.getByObjectId("stonebreak:cypress_planks")}) {
            ItemStack out = MANAGER.craftItem(grid(plank, plank, iron));
            assertTrue(out != null && !out.isEmpty(), "planks: " + plank);
            assertEquals(ItemType.DINGHY, out.getItem());
            assertEquals(1, out.getCount());
        }
    }

    @Test
    void mixedWoodsDoNotCraft() {
        ItemStack out = MANAGER.craftItem(grid(BlockType.WOOD_PLANKS, BlockType.PINE_WOOD_PLANKS,
                ItemType.getByObjectId("stonebreak:iron_chunk")));
        assertTrue(out == null || out.isEmpty() || out.getItem() != ItemType.DINGHY);
    }

    @Test
    void theIronChunkIsRequired() {
        ItemStack out = MANAGER.craftItem(grid(BlockType.WOOD_PLANKS, BlockType.WOOD_PLANKS, null));
        assertTrue(out == null || out.isEmpty() || out.getItem() != ItemType.DINGHY);
    }
}
