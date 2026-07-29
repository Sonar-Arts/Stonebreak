package com.stonebreak.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.support.UiTestFixtures;

/**
 * Guards the hotbar's tooltip fade state machine: showing a tooltip makes it visible at alpha 1.0,
 * stepping {@code update} with enough deltaTime drives alpha back to 0 and hides the tooltip,
 * and alpha is monotonically non-increasing once the fade has begun.
 *
 * <p>The regression this prevents: a timing or interpolation bug that makes the tooltip flash,
 * freeze, or display with a broken alpha value.
 */
class HotbarScreenTest {

    @Test
    void displayingATooltipMakesItVisible() {
        HotbarScreen hotbar = new HotbarScreen(UiTestFixtures.emptyInventory());
        hotbar.displayItemTooltip(BlockType.DIRT);

        assertTrue(hotbar.shouldShowTooltip(),
            "showing a tooltip must make shouldShowTooltip() return true");
        assertEquals(1.0f, hotbar.getTooltipAlpha(), 0.0001f,
            "tooltip alpha must start at 1.0");
        assertEquals("Dirt", hotbar.getTooltipText(),
            "tooltip text must match the item's name");
    }

    @Test
    void displayingAirHidesTheTooltip() {
        HotbarScreen hotbar = new HotbarScreen(UiTestFixtures.emptyInventory());
        hotbar.displayItemTooltip(BlockType.DIRT);
        hotbar.displayItemTooltip(BlockType.AIR);

        assertFalse(hotbar.shouldShowTooltip(),
            "displaying AIR must hide the tooltip");
        assertEquals(0.0f, hotbar.getTooltipAlpha(), 0.0001f,
            "alpha must be 0 when tooltip is hidden");
    }

    @Test
    void displayingEmptyItemStackHidesTheTooltip() {
        HotbarScreen hotbar = new HotbarScreen(UiTestFixtures.emptyInventory());
        hotbar.displayItemTooltip(BlockType.DIRT);
        hotbar.displayItemTooltip(new ItemStack(BlockType.AIR.getId(), 0));

        assertFalse(hotbar.shouldShowTooltip(),
            "displaying an empty stack must hide the tooltip");
    }

    @Test
    void displayingNonNullItemStackShowsTheTooltip() {
        HotbarScreen hotbar = new HotbarScreen(UiTestFixtures.emptyInventory());
        hotbar.displayItemTooltip(new ItemStack(BlockType.STONE, 3));

        assertTrue(hotbar.shouldShowTooltip(),
            "displaying a non-empty stack must show the tooltip");
        assertEquals(1.0f, hotbar.getTooltipAlpha(), 0.0001f);
    }

    @Test
    void steppingUpdateWithLargeDeltaTimeDrivesAlphaToZero() {
        HotbarScreen hotbar = new HotbarScreen(UiTestFixtures.emptyInventory());
        hotbar.displayItemTooltip(BlockType.DIRT);

        // Step with a very large deltaTime (2 seconds — far more than display + fade duration).
        hotbar.update(2.0f);

        assertEquals(0.0f, hotbar.getTooltipAlpha(), 0.0001f,
            "after enough time, alpha must reach 0");
        assertFalse(hotbar.shouldShowTooltip(),
            "after enough time, shouldShowTooltip must return false");
    }

    @Test
    void alphaIsWithinValidRangeAtAllTimes() {
        HotbarScreen hotbar = new HotbarScreen(UiTestFixtures.emptyInventory());
        hotbar.displayItemTooltip(BlockType.DIRT);

        // Step in small increments and check alpha is always in [0, 1].
        for (int i = 0; i < 200; i++) {
            float alpha = hotbar.getTooltipAlpha();
            assertTrue(alpha >= 0.0f && alpha <= 1.0f,
                "step " + i + ": alpha must stay within [0, 1] but was " + alpha);
            hotbar.update(0.01f);
        }
    }

    @Test
    void alphaIsMonotonicallyNonIncreasingOnceFadeHasBegun() {
        HotbarScreen hotbar = new HotbarScreen(UiTestFixtures.emptyInventory());
        hotbar.displayItemTooltip(BlockType.DIRT);

        // Skip the display-duration phase (alpha stays at 1.0), then check monotonicity.
        // Display duration is 1.5s, fade starts after that.
        float prevAlpha = 1.0f;
        for (int i = 0; i < 300; i++) {
            hotbar.update(0.01f);
            float currAlpha = hotbar.getTooltipAlpha();
            assertTrue(currAlpha <= prevAlpha + 0.0001f,
                "step " + i + ": alpha must be monotonically non-increasing during fade, "
                    + "but went from " + prevAlpha + " to " + currAlpha);
            prevAlpha = currAlpha;
        }
    }

    @Test
    void getSelectedSlotIndexDelegatesToInventory() {
        Inventory inv = UiTestFixtures.emptyInventory();
        inv.setSelectedHotbarSlotIndex(7);
        HotbarScreen hotbar = new HotbarScreen(inv);

        assertEquals(7, hotbar.getSelectedSlotIndex(),
            "getSelectedSlotIndex must delegate to the inventory");
    }

    @Test
    void getHotbarSlotsDelegatesToInventory() {
        HotbarScreen hotbar = new HotbarScreen(UiTestFixtures.emptyInventory());
        assertNotNull(hotbar.getHotbarSlots(),
            "getHotbarSlots must not return null");
        assertEquals(9, hotbar.getHotbarSlots().length,
            "getHotbarSlots must return an array of HOTBAR_SIZE (9) elements");
    }
}