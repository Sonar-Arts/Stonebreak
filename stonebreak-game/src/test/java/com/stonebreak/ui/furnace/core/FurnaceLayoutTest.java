package com.stonebreak.ui.furnace.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.stonebreak.ui.furnace.core.FurnaceLayout.Slots;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import com.stonebreak.ui.support.Resolutions;
import com.stonebreak.ui.support.UiLayoutAssert;
import com.stonebreak.ui.support.UiLayoutAssert.Rect;

/**
 * Guards the radial/crucible furnace UI layout: the three slots (ingredient, fuel, output)
 * must be distinct, non-overlapping, and inside the panel at every resolution.
 *
 * <p>The regression this prevents: a change to the constants or formula that silently shifts
 * two furnace slots to the same position, or pushes a slot outside the panel bounds.
 */
class FurnaceLayoutTest {

    @Test
    void threeSlotsHaveDistinctPositionsAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());
            Slots s = FurnaceLayout.compute(layout);

            assertTrue(s.ingredientX != s.fuelX || s.ingredientY != s.fuelY,
                size + ": ingredient and fuel slots must not share the same position");
            assertTrue(s.ingredientX != s.outputX || s.ingredientY != s.outputY,
                size + ": ingredient and output slots must not share the same position");
            assertTrue(s.fuelX != s.outputX || s.fuelY != s.outputY,
                size + ": fuel and output slots must not share the same position");
        }
    }

    @Test
    void threeSlotsDoNotOverlapAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());
            Slots s = FurnaceLayout.compute(layout);
            int ss = s.slotSize;

            List<Rect> rects = new ArrayList<>();
            rects.add(new Rect(s.ingredientX, s.ingredientY, ss, ss));
            rects.add(new Rect(s.fuelX, s.fuelY, ss, ss));
            rects.add(new Rect(s.outputX, s.outputY, ss, ss));

            UiLayoutAssert.assertNoOverlap(rects, size.toString());
        }
    }

    @Test
    void threeSlotsSitInsideThePanelAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());
            Slots s = FurnaceLayout.compute(layout);
            int ss = s.slotSize;

            Rect panel = new Rect(layout.panelStartX, layout.panelStartY,
                layout.inventoryPanelWidth, layout.inventoryPanelHeight);

            for (Rect slot : List.of(
                    new Rect(s.ingredientX, s.ingredientY, ss, ss),
                    new Rect(s.fuelX, s.fuelY, ss, ss),
                    new Rect(s.outputX, s.outputY, ss, ss))) {
                UiLayoutAssert.assertContains(panel, slot,
                    size + ": furnace slot must sit inside panel");
            }
        }
    }

    @Test
    void slotSizeIsPositiveAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());
            Slots s = FurnaceLayout.compute(layout);
            assertTrue(s.slotSize > 0,
                size + ": furnace slot size must be positive");
        }
    }
}