package com.stonebreak.blocks.blueberrybush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.PlayerConstants;

class BlueberryBushPlantingTest {

    @Test
    void bushesArePlantedOnGrassOnly() {
        assertTrue(BlueberryBushPlanting.canPlantOn(BlockType.GRASS));
        assertTrue(BlueberryBushPlanting.canPlantOn(BlockType.SWAMPY_GRASS));
        assertTrue(BlueberryBushPlanting.canPlantOn(BlockType.DRY_GRASS));
        assertFalse(BlueberryBushPlanting.canPlantOn(BlockType.DIRT));
        assertFalse(BlueberryBushPlanting.canPlantOn(BlockType.STONE));
        assertFalse(BlueberryBushPlanting.canPlantOn(BlockType.AIR));
    }

    @Test
    void theBlueberryItemPlantsAndHealsTwoHearts() {
        assertTrue(BlueberryBushPlanting.isBerryItem(ItemType.BLUEBERRY));
        assertFalse(BlueberryBushPlanting.isBerryItem(ItemType.BANANA));
        assertTrue(ItemType.BLUEBERRY.isFood());
        assertEquals(2 * PlayerConstants.HEALTH_PER_HEART, ItemType.BLUEBERRY.getHealAmount());
    }

    @Test
    void aPlantedBushIsPredictedAsASprout() {
        BlueberryBushState planted = BlueberryBushState.parse(BlueberryBushPlanting.PLANTED_STATE);
        assertEquals(Stage.SPROUT, planted.stage());
    }
}
