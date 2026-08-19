package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless pixel tests for the newer reusable components ({@link MIconButton},
 * {@link MTabBar}, {@link MProgressBar}, {@link MBadge}, {@link MSectionHeader}),
 * in the same spirit as {@code MWidgetRenderTest}: state the player must be able
 * to see (a live tab, a hover, a fill fraction) has to change actual pixels,
 * and the deterministic renderer makes those diffs sound assertions.
 */
class MComponentsRenderTest {

    private static final int W = 240;
    private static final int H = 96;

    // ─────────────────────────────────────────────── MIconButton

    @Test
    void anIconButtonDrawsItsSymbolOnTheBody() {
        RasterUiFixture bare = new RasterUiFixture(W, H);
        RasterUiFixture iconed = new RasterUiFixture(W, H);
        new MIconButton(null).bounds(16, 16, 32, 32).render(bare.ui);
        new MIconButton(MSymbol.CROSS).bounds(16, 16, 32, 32).render(iconed.ui);

        assertTrue(bare.countPainted(20, 20, 44, 44) > 300,
                "the stone body must render even without a symbol");
        assertTrue(bare.diff(iconed, 20, 20, 44, 44) > 20,
                "the symbol must be visible on top of the body");
    }

    @Test
    void iconButtonHoverIsVisible() {
        RasterUiFixture plain = new RasterUiFixture(W, H);
        RasterUiFixture hovered = new RasterUiFixture(W, H);
        new MIconButton(MSymbol.GEAR).bounds(16, 16, 32, 32).render(plain.ui);
        MIconButton hot = new MIconButton(MSymbol.GEAR).bounds(16, 16, 32, 32);
        hot.setHovered(true);
        hot.render(hovered.ui);

        assertTrue(plain.diff(hovered, 16, 16, 48, 48) > 30,
                "hover flips the fill and icon tint — it must read at a glance");
    }

    // ─────────────────────────────────────────────── MTabBar

    @Test
    void theSelectedTabIsVisiblyDifferent() {
        RasterUiFixture first = new RasterUiFixture(W, H);
        RasterUiFixture second = new RasterUiFixture(W, H);
        new MTabBar("Items", "Feats").selected(0).bounds(16, 16, 200, 28).render(first.ui);
        new MTabBar("Items", "Feats").selected(1).bounds(16, 16, 200, 28).render(second.ui);

        assertTrue(first.diff(second, 16, 16, 116, 44) > 50,
                "the left tab must change when it loses the selection");
        assertTrue(first.diff(second, 116, 16, 216, 44) > 50,
                "and the right tab when it gains it");
    }

    @Test
    void theSelectedTabCarriesTheAccentUnderline() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        new MTabBar("Items", "Feats").selected(0).bounds(16, 16, 200, 28).render(fx.ui);

        // Underline band: bottom of the selected (left) tab. Probe its interior
        // row where the rounded ends can't dilute the exact color.
        assertTrue(fx.countExactly(MStyle.TEXT_ACCENT, 30, 38, 100, 42) > 20,
                "the live tab must carry the accent underline");
        assertEquals(0, fx.countExactly(MStyle.TEXT_ACCENT, 130, 38, 200, 42),
                "the idle tab must not");
    }

    // ─────────────────────────────────────────────── MProgressBar

    @Test
    void theProgressFillTracksItsFraction() {
        int fill = 0xFF3366FF;
        RasterUiFixture low = new RasterUiFixture(W, H);
        RasterUiFixture full = new RasterUiFixture(W, H);
        new MProgressBar().fraction(0.3f).fillColor(fill).bounds(16, 32, 200, 16).render(low.ui);
        new MProgressBar().fraction(1f).fillColor(fill).bounds(16, 32, 200, 16).render(full.ui);

        assertTrue(low.countExactly(fill, 24, 38, 60, 44) > 50,
                "30% must paint fill on the left");
        assertEquals(0, low.countExactly(fill, 120, 38, 210, 44),
                "and none past its fraction");
        assertTrue(full.countExactly(fill, 120, 38, 208, 44) > 50,
                "a full bar's fill must reach the right end");
    }

    @Test
    void thePercentLabelActuallyAppears() {
        RasterUiFixture silent = new RasterUiFixture(W, H);
        RasterUiFixture labeled = new RasterUiFixture(W, H);
        new MProgressBar().fraction(0.5f).bounds(16, 32, 200, 18).render(silent.ui);
        new MProgressBar().fraction(0.5f).showPercent(true).bounds(16, 32, 200, 18).render(labeled.ui);

        assertTrue(silent.diff(labeled, 80, 32, 160, 50) > 10,
                "showPercent must put glyphs in the middle of the bar");
    }

    // ─────────────────────────────────────────────── MBadge

    @Test
    void aBadgePaintsItsPillAndStaysInBounds() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        new MBadge("3").fillColor(0xFFCC3333).bounds(32, 32, 20, 16).render(fx.ui);

        assertTrue(fx.countExactly(0xFFCC3333, 36, 36, 48, 44) > 20,
                "the pill fill must cover the badge interior");
        assertEquals(0, fx.countPainted(0, 0, 30, H), "nothing left of the badge");
        assertEquals(0, fx.countPainted(56, 0, W, H), "nothing right of it");
    }

    @Test
    void badgeTextShowsOnThePill() {
        RasterUiFixture blank = new RasterUiFixture(W, H);
        RasterUiFixture numbered = new RasterUiFixture(W, H);
        new MBadge("").bounds(32, 32, 24, 16).render(blank.ui);
        new MBadge("12").bounds(32, 32, 24, 16).render(numbered.ui);

        assertTrue(blank.diff(numbered, 32, 32, 56, 48) > 5,
                "the count must actually be legible on the pill");
    }

    // ─────────────────────────────────────────────── MSectionHeader

    @Test
    void aBareHeaderIsAFullWidthRule() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        new MSectionHeader().bounds(16, 40, 200, 16).render(fx.ui);

        // The two-tone rule sits on the vertical midline (y = 47/48).
        assertTrue(fx.countPainted(16, 46, 216, 50) > 300,
                "an unlabeled header must draw its divider across the full width");
        assertEquals(0, fx.countPainted(0, 0, W, 44), "nothing above the rule");
        assertEquals(0, fx.countPainted(0, 52, W, H), "nothing below it");
    }

    @Test
    void aLabeledHeaderBreaksTheRuleForItsText() {
        RasterUiFixture bare = new RasterUiFixture(W, H);
        RasterUiFixture labeled = new RasterUiFixture(W, H);
        new MSectionHeader().bounds(16, 32, 200, 24).render(bare.ui);
        new MSectionHeader("AUDIO").bounds(16, 32, 200, 24).render(labeled.ui);

        assertTrue(bare.diff(labeled, 80, 32, 160, 56) > 20,
                "the label must appear in the center");
        assertTrue(labeled.countPainted(16, 42, 60, 46) > 40,
                "the left rule still flanks it");
        assertTrue(labeled.countPainted(180, 42, 216, 46) > 30,
                "and the right rule too");
    }

    @Test
    void componentRenderingIsDeterministic() {
        RasterUiFixture a = new RasterUiFixture(W, H);
        RasterUiFixture b = new RasterUiFixture(W, H);
        for (RasterUiFixture fx : new RasterUiFixture[] {a, b}) {
            new MIconButton(MSymbol.MAGNIFIER).bounds(8, 8, 28, 28).render(fx.ui);
            new MTabBar("A", "B", "C").selected(1).bounds(48, 8, 180, 24).render(fx.ui);
            new MProgressBar().fraction(0.6f).showPercent(true).bounds(8, 48, 220, 16).render(fx.ui);
            new MBadge("9+").bounds(8, 72, 26, 16).render(fx.ui);
            new MSectionHeader("MISC").bounds(48, 72, 180, 16).render(fx.ui);
        }
        assertEquals(0, a.diff(b, 0, 0, W, H),
                "the whole component set must render identical pixels every frame");
    }
}
