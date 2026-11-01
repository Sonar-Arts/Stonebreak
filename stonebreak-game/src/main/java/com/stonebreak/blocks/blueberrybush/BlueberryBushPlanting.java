package com.stonebreak.blocks.blueberrybush;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;

/**
 * Planting rules for blueberry bushes: the {@link ItemType#BLUEBERRY} ITEM plants a
 * {@link BlockType#BLUEBERRY_BUSH} BLOCK, and a bush — however it is placed — only goes on top
 * of grass. Checked by the client's placer and again by the server. A planted bush always
 * starts at its smallest stage, Sprout.
 */
public final class BlueberryBushPlanting {

    /**
     * The state a client predicts for a bush it plants, so it shows the Sprout model at once
     * (never the SBO's base model or a stale stage). Carries no timer: the authoritative
     * growth system starts Sprout's timer itself ({@code BlueberryBushGrowthSystem.onBlockChanged}).
     */
    public static final String PLANTED_STATE = BlueberryBushState.STATE_PREFIX + "state=" + Stage.SPROUT.sboStateName();

    private BlueberryBushPlanting() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static boolean isBerryItem(Item item) {
        return item != null && item == ItemType.BLUEBERRY;
    }

    /** Ground a blueberry bush can be planted on: any grass block. */
    public static boolean canPlantOn(BlockType ground) {
        return ground == BlockType.GRASS || ground == BlockType.SWAMPY_GRASS || ground == BlockType.DRY_GRASS;
    }
}
