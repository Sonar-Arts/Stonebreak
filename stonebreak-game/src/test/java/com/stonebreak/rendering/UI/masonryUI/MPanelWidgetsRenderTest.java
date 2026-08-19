package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render tests for the panel-side furniture — category tabs, equipment slots, stat rows and
 * panels, tooltips — over the same headless raster rig as {@link MWidgetRenderTest}. Alongside
 * the visible-state rules, these pin two documented promises whole screens are laid out on:
 * {@code MCategoryButton} renders identically to a plain button, and {@code MStatPanel}'s
 * {@code measureHeight()} is exactly what {@code render()} draws and returns — stacked panels
 * overlap the moment those disagree.
 */
class MPanelWidgetsRenderTest {

    private static final int W = 192;
    private static final int H = 112;

    // ── MCategoryButton ──────────────────────────────────────────────────────

    @Test
    void aCategoryButtonIsAPlainButtonWithATag() {
        RasterUiFixture plain = new RasterUiFixture(W, H);
        RasterUiFixture tagged = new RasterUiFixture(W, H);
        new MButton("Video").bounds(16, 16, 120, 28).render(plain.ui);
        MCategoryButton<String> tab = new MCategoryButton<>("video", "Video");
        tab.bounds(16, 16, 120, 28);
        tab.render(tagged.ui);

        assertEquals(0, plain.diff(tagged, 0, 0, W, H),
                "its class doc promises identical rendering to MButton");
        assertEquals("video", tab.tag());
    }

    @Test
    void theActiveTabIsVisiblyMarked() {
        RasterUiFixture idle = new RasterUiFixture(W, H);
        RasterUiFixture active = new RasterUiFixture(W, H);
        new MCategoryButton<>("a", "Video").bounds(16, 16, 120, 28).render(idle.ui);
        MCategoryButton<String> selected = new MCategoryButton<>("a", "Video");
        selected.bounds(16, 16, 120, 28);
        selected.setSelected(true);
        selected.render(active.ui);

        assertTrue(idle.diff(active, 16, 16, 136, 44) > 50,
                "the settings menu marks the open category via selection");
    }

    // ── MEquipSlot ───────────────────────────────────────────────────────────

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

    // ── MStatRow ─────────────────────────────────────────────────────────────

    @Test
    void aStatRowLaysLabelLeftAndValueRight() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        new MStatRow().label("STR").value("18").bounds(10, 10, 150, 18).render(fx.ui);

        assertTrue(fx.countPainted(10, 12, 45, 27) > 10, "the label starts at the left edge");
        assertTrue(fx.countPainted(135, 12, 160, 27) > 10, "the value ends at the right edge");
        assertEquals(0, fx.countPainted(60, 12, 130, 25), "the middle stays open");
    }

    @Test
    void theStatRowUnderlineBarTracksItsFraction() {
        int cyan = 0xFF00CCFF;
        RasterUiFixture with = new RasterUiFixture(W, H);
        RasterUiFixture without = new RasterUiFixture(W, H);
        new MStatRow().label("STR").value("18").bar(cyan, 0.5f)
                .bounds(10, 10, 150, 18).render(with.ui);
        new MStatRow().label("STR").value("18")
                .bounds(10, 10, 150, 18).render(without.ui);

        assertTrue(with.countExactly(cyan, 15, 26, 70, 29) > 10,
                "the underline covers the filled fraction");
        assertEquals(0, with.countExactly(cyan, 110, 26, 160, 29),
                "and stops at it");
        assertEquals(0, without.countExactly(cyan, 10, 26, 160, 29), "no bar when unconfigured");
    }

    // ── MStatPanel ───────────────────────────────────────────────────────────

    @Test
    void measuredHeightIsExactlyWhatRenderDrawsAndReturns() {
        // Constants from the panel: padding 10, title 16 + gap 8, row 16, bar 7 + gap 8.
        assertEquals(44f, new MStatPanel("Stats").measureHeight(), 1e-4f);
        assertEquals(76f, new MStatPanel("Stats").row("a", "1").row("b", "2").measureHeight(), 1e-4f);
        assertEquals(59f, new MStatPanel("Stats").usageBar(1, 2).measureHeight(), 1e-4f);

        RasterUiFixture fx = new RasterUiFixture(W, H);
        MStatPanel panel = new MStatPanel("Stats").row("STR", "18");
        float drawn = panel.render(fx.ui, 10, 10, 160);

        assertEquals(panel.measureHeight(), drawn, 1e-4f,
                "callers stack panels off this return value");
        assertTrue(fx.countPainted(20, 15, 160, (int) (10 + drawn) - 5) > 2000,
                "the panel chrome covers its measured area");
        assertEquals(0, fx.countPainted(0, (int) (10 + drawn) + 10, W, H),
                "nothing paints below the measured height (past the drop shadow)");
    }

    @Test
    void theUsageBarChangesColorWithPressure() {
        int green = 0xFF50C878, amber = 0xFFE6BE3C, red = 0xFFDC5050;
        // Bar row sits at y+38..y+45 for a panel at y=10; fill starts at x+10.
        RasterUiFixture ok = new RasterUiFixture(W, H);
        RasterUiFixture warn = new RasterUiFixture(W, H);
        RasterUiFixture crit = new RasterUiFixture(W, H);
        new MStatPanel("Memory").usageBar(50, 100).render(ok.ui, 10, 10, 160);
        new MStatPanel("Memory").usageBar(70, 100).render(warn.ui, 10, 10, 160);
        new MStatPanel("Memory").usageBar(90, 100).render(crit.ui, 10, 10, 160);

        assertTrue(ok.countExactly(green, 25, 50, 60, 54) > 10, "healthy usage reads green");
        assertTrue(warn.countExactly(amber, 25, 50, 60, 54) > 10, "pressure reads amber");
        assertTrue(crit.countExactly(red, 25, 50, 60, 54) > 10, "near-full reads red");
        assertEquals(0, ok.countExactly(green, 130, 50, 155, 54),
                "a half bar's fill stops at half");
    }

    // ── MTooltip ─────────────────────────────────────────────────────────────

    @Test
    void aTooltipDrawsItsBoxAtTheAnchorAndClampsOnScreen() {
        Settings settings = Settings.getInstance();
        float priorScale = settings.getUiScale();
        settings.setUiScale(1.0f); // tooltip geometry scales with the user setting
        try {
            RasterUiFixture anchored = new RasterUiFixture(W, H);
            MTooltip.draw(anchored.ui, "Stone", 40, 30, W, H);
            assertTrue(anchored.countPainted(42, 32, 100, 60) > 400,
                    "the stone box appears at the anchor");
            assertEquals(0, anchored.countPainted(0, 0, W, 20), "nothing above it");

            RasterUiFixture clamped = new RasterUiFixture(W, H);
            MTooltip.draw(clamped.ui, "Stone", -100, -100, W, H);
            assertEquals(0, clamped.countPainted(0, 0, W, 7),
                    "an off-screen anchor is pulled back inside the margin");
            assertEquals(0, clamped.countPainted(0, 0, 7, H));
            assertTrue(clamped.countPainted(9, 9, 90, 45) > 400,
                    "and the box lands at the screen corner instead");
        } finally {
            settings.setUiScale(priorScale);
        }
    }

    @Test
    void aTooltipWithNothingToSayDrawsNothing() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MTooltip.draw(fx.ui, "", 40, 30, W, H);
        MTooltip.draw(fx.ui, null, 40, 30, W, H);

        assertEquals(0, fx.countPainted(0, 0, W, H));
    }
}
