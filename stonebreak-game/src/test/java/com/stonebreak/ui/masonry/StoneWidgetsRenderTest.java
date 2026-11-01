package com.stonebreak.ui.masonry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render tests of Stonebreak's own Masonry widgets (moved out of the engine with them, #384): the
 * vital bar, the inventory/hotbar slot and the equipment slot, over the headless raster rig.
 */
class StoneWidgetsRenderTest {

    private static final int W = 192;
    private static final int H = 112;

    @Test
    void theVitalBarFillTracksItsFraction() {
        int fill = 0xFF3366FF;
        RasterUiFixture half = new RasterUiFixture(W, H);
        RasterUiFixture full = new RasterUiFixture(W, H);
        new MVitalBar().label("").value(50).max(100).fillColor(fill)
                .bounds(16, 32, 160, 20).render(half.ui);
        new MVitalBar().label("").value(100).max(100).fillColor(fill)
                .bounds(16, 32, 160, 20).render(full.ui);

        // The bar spans x ∈ [22, 176]; half health fills to ~99. Probe the right
        // quarter, where only a full bar reaches (the "100/100" text drawn there
        // punches glyph-shaped holes in the fill but leaves plenty of it).
        assertTrue(half.countExactly(fill, 30, 38, 90, 45) > 50,
                "half health must paint a visible fill on the left");
        assertEquals(0, half.countExactly(fill, 110, 38, 170, 45),
                "a half bar's fill must stop at its fraction");
        assertTrue(full.countExactly(fill, 110, 38, 170, 45) > 20,
                "a full bar's fill must reach the right end");
    }

    @Test
    void theHotbarSelectionRingIsVisible() {
        RasterUiFixture plain = new RasterUiFixture(W, H);
        RasterUiFixture selected = new RasterUiFixture(W, H);
        new MItemSlot().bounds(32, 24, 40, 40).render(plain.ui);
        new MItemSlot().hotbarSelected(true).bounds(32, 24, 40, 40).render(selected.ui);

        assertTrue(plain.diff(selected, 32, 24, 72, 64) > 30,
                "the gold ring is how the player knows which slot is live");
    }

    @Test
    void anEquipSlotPaintsItsBodyAndItsLabelBelow() {
        RasterUiFixture labeled = new RasterUiFixture(W, H);
        RasterUiFixture bare = new RasterUiFixture(W, H);
        new MEquipSlot().slotLabel("HEAD").bounds(32, 16, 40, 40).render(labeled.ui);
        new MEquipSlot().bounds(32, 16, 40, 40).render(bare.ui);

        assertTrue(labeled.countPainted(36, 20, 68, 52) > 500, "the recessed body covers the slot");
        assertTrue(labeled.countPainted(28, 58, 76, 76) > 10, "the label draws under the slot");
        assertEquals(0, bare.countPainted(28, 58, 76, 76), "no label, nothing below");
    }

    @Test
    void theLabelIsDecorativeNotHitArea() {
        MEquipSlot slot = new MEquipSlot().slotLabel("HEAD").bounds(32, 16, 40, 40);

        assertTrue(slot.contains(52, 40), "the slot rect itself is clickable");
        assertFalse(slot.contains(52, 62), "the label below is not — drops must not land on it");
    }

    @Test
    void equipSlotHoverIsVisible() {
        RasterUiFixture idle = new RasterUiFixture(W, H);
        RasterUiFixture hot = new RasterUiFixture(W, H);
        new MEquipSlot().bounds(32, 16, 40, 40).render(idle.ui);
        MEquipSlot hovered = new MEquipSlot().bounds(32, 16, 40, 40);
        hovered.setHovered(true);
        hovered.render(hot.ui);

        assertTrue(idle.diff(hot, 32, 16, 72, 56) > 30);
    }
}
