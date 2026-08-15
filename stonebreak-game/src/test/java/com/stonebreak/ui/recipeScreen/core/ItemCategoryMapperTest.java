package com.stonebreak.ui.recipeScreen.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;

/**
 * Guards the item-to-category mapping of {@link ItemCategoryMapper}: every real
 * item maps to a non-null, deterministic category string.
 *
 * <p>Regression: a new item whose {@link com.stonebreak.items.ItemCategory} is
 * not covered by the switch expression could return an unexpected string or
 * null, causing the recipe book filter to misclassify recipes.
 */
class ItemCategoryMapperTest {

    // ---- null item -----------------------------------------------------------------------------

    @Test
    void nullItemMapsToAll() {
        assertEquals("All", ItemCategoryMapper.getCategoryForItem(null),
            "null item must map to \"All\" category");
    }

    // ---- BlockType items (BLOCKS -> Building) -------------------------------------------------

    @Test
    void blockTypeDirtMapsToBuilding() {
        String category = ItemCategoryMapper.getCategoryForItem(BlockType.DIRT);
        assertEquals("Building", category,
            "BlockType.DIRT (BLOCKS category) must map to \"Building\"");
    }

    @Test
    void blockTypeStoneMapsToBuilding() {
        assertEquals("Building", ItemCategoryMapper.getCategoryForItem(BlockType.STONE),
            "BlockType.STONE (BLOCKS category) must map to \"Building\"");
    }

    @Test
    void blockTypeWoodMapsToBuilding() {
        assertEquals("Building", ItemCategoryMapper.getCategoryForItem(BlockType.WOOD),
            "BlockType.WOOD (BLOCKS category) must map to \"Building\"");
    }

    // ---- ItemType tools (TOOLS -> Tools) ------------------------------------------------------

    @Test
    void toolItemTypeMapsToTools() {
        assertEquals("Tools", ItemCategoryMapper.getCategoryForItem(ItemType.WOODEN_PICKAXE),
            "WOODEN_PICKAXE (TOOLS category) must map to \"Tools\"");
    }

    @Test
    void axeMapsToTools() {
        assertEquals("Tools", ItemCategoryMapper.getCategoryForItem(ItemType.WOODEN_AXE),
            "WOODEN_AXE (TOOLS category) must map to \"Tools\"");
    }

    // ---- ItemType materials (MATERIALS -> Building) --------------------------------------------

    @Test
    void materialItemTypeMapsToBuilding() {
        assertEquals("Building", ItemCategoryMapper.getCategoryForItem(ItemType.STICK),
            "STICK (MATERIALS category) must map to \"Building\"");
    }

    // ---- ItemType food (FOOD -> Food) ---------------------------------------------------------

    @Test
    void foodItemTypeMapsToFood() {
        assertEquals("Food", ItemCategoryMapper.getCategoryForItem(ItemType.BANANA),
            "BANANA (FOOD category) must map to \"Food\"");
    }

    // ---- determinism: same item always maps to the same category ------------------------------

    @Test
    void mappingIsDeterministicForBlockType() {
        Item item = BlockType.DIRT;
        String first = ItemCategoryMapper.getCategoryForItem(item);
        String second = ItemCategoryMapper.getCategoryForItem(item);
        assertEquals(first, second,
            "getCategoryForItem must return the same result for the same item across calls");
    }

    @Test
    void mappingIsDeterministicForItemType() {
        Item item = ItemType.WOODEN_PICKAXE;
        String first = ItemCategoryMapper.getCategoryForItem(item);
        String second = ItemCategoryMapper.getCategoryForItem(item);
        assertEquals(first, second,
            "getCategoryForItem must return the same result for the same ItemType across calls");
    }

    // ---- every registered BlockType maps to non-null category ---------------------------------

    @Test
    void everyRegisteredBlockTypeMapsToNonNullCategory() {
        for (BlockType blockType : BlockType.values()) {
            String category = ItemCategoryMapper.getCategoryForItem(blockType);
            assertNotNull(category,
                blockType.getName() + " must map to a non-null category");
            assertFalse(category.isBlank(),
                blockType.getName() + " must map to a non-blank category");
        }
    }

    // ---- every registered ItemType maps to non-null category -----------------------------------

    @Test
    void everyRegisteredItemTypeMapsToNonNullCategory() {
        for (ItemType itemType : ItemType.values()) {
            String category = ItemCategoryMapper.getCategoryForItem(itemType);
            assertNotNull(category,
                itemType.getName() + " must map to a non-null category");
            assertFalse(category.isBlank(),
                itemType.getName() + " must map to a non-blank category");
        }
    }
}