package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full widget {@code render()} calls, headless, pixels asserted — the tier the interaction tests
 * cannot reach. What gets pinned is the part players actually see: state changes must be VISIBLE
 * (a hovered button, a checked toggle, a focused field that don't change pixels are bugs no other
 * test can catch), fills must track their data, and rendering must be deterministic, which is
 * what makes every "these two states differ" assertion here sound.
 */
class MWidgetRenderTest {

    private static final int W = 192;
    private static final int H = 96;

    @Test
    void renderingIsDeterministic() {
        RasterUiFixture a = new RasterUiFixture(W, H);
        RasterUiFixture b = new RasterUiFixture(W, H);
        new MButton("Play").bounds(16, 16, 120, 32).render(a.ui);
        new MButton("Play").bounds(16, 16, 120, 32).render(b.ui);

        assertEquals(0, a.diff(b, 0, 0, W, H),
                "identical widgets must produce identical pixels — the noise is hash-based");
    }

    @Test
    void aButtonPaintsItsBodyAndKeepsItsDistance() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        new MButton("Play").bounds(32, 24, 120, 32).render(fx.ui);

        assertTrue(fx.countPainted(40, 30, 144, 50) > 1500,
                "the stone body must actually cover the button");
        assertEquals(0, fx.countPainted(0, 0, 20, H),
                "nothing paints well left of the button");
        assertEquals(0, fx.countPainted(0, 0, W, 12),
                "nothing paints well above it (the drop shadow falls below-right)");
    }

    @Test
    void hoverVisiblyChangesAButton() {
        RasterUiFixture plain = new RasterUiFixture(W, H);
        RasterUiFixture hovered = new RasterUiFixture(W, H);
        new MButton("Play").bounds(16, 16, 120, 32).render(plain.ui);
        MButton hot = new MButton("Play").bounds(16, 16, 120, 32);
        hot.setHovered(true);
        hot.render(hovered.ui);

        assertTrue(plain.diff(hovered, 16, 16, 136, 48) > 50,
                "a hover the player cannot see is a hover that does not exist");
    }

    @Test
    void aDisabledButtonLooksDisabled() {
        RasterUiFixture enabled = new RasterUiFixture(W, H);
        RasterUiFixture disabled = new RasterUiFixture(W, H);
        new MButton("Play").bounds(16, 16, 120, 32).render(enabled.ui);
        new MButton("Play").bounds(16, 16, 120, 32).enabled(false).render(disabled.ui);

        assertTrue(enabled.diff(disabled, 16, 16, 136, 48) > 50);
    }

    @Test
    void aCheckedToggleShowsItsCheckInTheBoxArea() {
        RasterUiFixture off = new RasterUiFixture(W, H);
        RasterUiFixture on = new RasterUiFixture(W, H);
        MToggle a = new MToggle("Wireframes");
        a.bounds(16, 16, 160, 24);
        a.render(off.ui);
        MToggle b = new MToggle("Wireframes", true);
        b.bounds(16, 16, 160, 24);
        b.render(on.ui);

        assertTrue(off.diff(on, 16, 16, 48, 40) > 10,
                "the on/off indicator lives on the left edge and must change");
    }

    @Test
    void theSliderKnobFollowsItsValue() {
        RasterUiFixture low = new RasterUiFixture(W, H);
        RasterUiFixture high = new RasterUiFixture(W, H);
        // Slider position is the track center: rail spans x ∈ [36, 156].
        new MSlider("", 0, 100, 0).showPercent(false).position(96, 48).size(120, 16).render(low.ui);
        new MSlider("", 0, 100, 100).showPercent(false).position(96, 48).size(120, 16).render(high.ui);

        assertTrue(low.diff(high, 36, 32, 96, 64) > 10, "the left half changes as the knob leaves it");
        assertTrue(low.diff(high, 96, 32, 156, 64) > 10, "and the right half as it arrives");
    }

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
    void aFocusedSearchFieldLooksFocused() {
        RasterUiFixture idle = new RasterUiFixture(W, H);
        RasterUiFixture focused = new RasterUiFixture(W, H);
        new MSearchField().placeholder("Search...").bounds(16, 24, 160, 24).render(idle.ui);
        new MSearchField().placeholder("Search...").active(true).bounds(16, 24, 160, 24).render(focused.ui);

        assertTrue(idle.diff(focused, 16, 24, 176, 48) > 30,
                "focus flips the fill and border — it must read at a glance");
    }

    @Test
    void anEmptySearchFieldShowsItsPlaceholder() {
        RasterUiFixture bare = new RasterUiFixture(W, H);
        RasterUiFixture hinted = new RasterUiFixture(W, H);
        // The default placeholder is already "Search...", so the contrast case must clear it.
        new MSearchField().placeholder("").bounds(16, 24, 160, 24).render(bare.ui);
        new MSearchField().placeholder("Search...").bounds(16, 24, 160, 24).render(hinted.ui);

        assertTrue(bare.diff(hinted, 20, 28, 120, 44) > 10,
                "placeholder glyphs must actually appear in the text area");
    }

    @Test
    void anOpenDropdownDrawsItsListAsAnOverlay() {
        RasterUiFixture closed = new RasterUiFixture(W, H);
        RasterUiFixture open = new RasterUiFixture(W, H);

        MDropdown shut = new MDropdown("Quality", new String[] {"Low", "High"})
                .itemHeight(20).bounds(16, 8, 120, 24);
        shut.render(closed.ui);
        closed.ui.renderOverlays();

        MDropdown popped = new MDropdown("Quality", new String[] {"Low", "High"})
                .itemHeight(20).bounds(16, 8, 120, 24);
        popped.open();
        popped.render(open.ui);
        open.ui.renderOverlays();

        assertEquals(0, closed.countPainted(16, 40, 136, 76),
                "a closed dropdown paints nothing below its header");
        assertTrue(open.countPainted(16, 40, 136, 76) > 200,
                "an open one paints its list there via the overlay pass");
    }
}
