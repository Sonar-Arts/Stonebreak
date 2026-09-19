package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The additive {@link MBadge} chip styles: outlined, trailing text, leading dot. The first rule
 * is that a badge using none of them is the badge it always was.
 */
class MBadgeStyleTest {

    private static final int W = 200;
    private static final int H = 64;
    private static final int FROST = 0xFF8FD8FF;
    private static final int GOOD = 0xFF5EC46A;

    private static RasterUiFixture paint(MBadge badge) {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        badge.render(fx.ui);
        return fx;
    }

    @Test
    void aBadgeUsingNoneOfTheStylesIsUnchanged() {
        RasterUiFixture plain = paint(new MBadge("NEW").bounds(20, 20, 44, 18));
        RasterUiFixture cleared = paint(new MBadge("NEW").trailing("").trailing(null).dot(0).bounds(20, 20, 44, 18));
        assertEquals(0, plain.diff(cleared, 0, 0, W, H), "empty trailing and a transparent dot are no style at all");
        assertTrue(plain.countExactly(MStyle.TEXT_ACCENT, 20, 20, 64, 38) > 300, "still the solid gold pill");

        RasterUiFixture fx = new RasterUiFixture(W, H);
        MBadge a = new MBadge("12").size(0, 16);
        MBadge b = new MBadge("12").trailing("").dot(0).size(0, 16);
        assertEquals(a.preferredWidth(fx.ui), b.preferredWidth(fx.ui));
        assertEquals(16f, new MBadge("1").size(0, 16).preferredWidth(fx.ui), "one character: still a circle");
    }

    @Test
    void anOutlinedBadgeIsADarkChipInTheCallersColour() {
        RasterUiFixture solid = paint(new MBadge("Chilled").bounds(20, 20, 70, 20));
        RasterUiFixture outlined = paint(new MBadge("Chilled").outlined(FROST).bounds(20, 20, 70, 20));
        assertTrue(solid.diff(outlined, 20, 20, 90, 40) > 800);
        assertEquals(0, outlined.countExactly(MStyle.TEXT_ACCENT, 0, 0, W, H), "no gold fill");
        assertTrue(outlined.countExactly(MStyle.DROPDOWN_FILL, 20, 20, 90, 40) > 500, "dark fill");
        assertTrue(outlined.countExactly(FROST, 20, 20, 90, 40) > 60, "coloured outline and label");

        RasterUiFixture other = paint(new MBadge("Chilled").outlined(GOOD).bounds(20, 20, 70, 20));
        assertTrue(outlined.diff(other, 20, 20, 90, 40) > 60, "the colour is the caller's");
        assertTrue(new MBadge("x").outlined(FROST).isOutlined());
    }

    @Test
    void trailingTextWidensTheBadgeAndIsDimmer() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MBadge bare = new MBadge("Chilled").outlined(FROST).size(0, 20);
        MBadge timed = new MBadge("Chilled").outlined(FROST).trailing("12s").size(0, 20);
        float bareW = bare.preferredWidth(fx.ui), timedW = timed.preferredWidth(fx.ui);
        assertTrue(timedW > bareW + 10f, "room for the gap and the timer");
        assertEquals("12s", timed.trailing());

        RasterUiFixture a = paint(bare.bounds(20, 20, timedW, 20));
        RasterUiFixture b = paint(timed.bounds(20, 20, timedW, 20));
        assertTrue(a.diff(b, 20, 20, 20 + (int) timedW, 40) > 40);
        assertTrue(b.countExactly(MStyle.TEXT_SECONDARY, 20, 20, 20 + (int) timedW, 40) > 15,
                "the trailing text is the dimmer secondary colour");
        assertEquals(0, a.countExactly(MStyle.TEXT_SECONDARY, 0, 0, W, H));

        // On the solid pill too.
        MBadge solid = new MBadge("Sale").trailing("2d").size(0, 18);
        assertTrue(solid.preferredWidth(fx.ui) > new MBadge("Sale").size(0, 18).preferredWidth(fx.ui));
        RasterUiFixture withTrailing = paint(solid.bounds(20, 20, 80, 18));
        assertTrue(withTrailing.diff(paint(new MBadge("Sale").bounds(20, 20, 80, 18)), 20, 20, 100, 38) > 20);
    }

    @Test
    void aLeadingDotIsDrawnInItsColourAndCountedInTheWidth() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MBadge bare = new MBadge("ACTIVE").outlined(MStyle.TEXT_PRIMARY).size(0, 20);
        MBadge dotted = new MBadge("ACTIVE").outlined(MStyle.TEXT_PRIMARY).dot(GOOD).size(0, 20);
        float w = dotted.preferredWidth(fx.ui);
        assertTrue(w > bare.preferredWidth(fx.ui) + 6f);

        RasterUiFixture painted = paint(dotted.bounds(20, 20, w, 20));
        assertTrue(painted.countExactly(GOOD, 20, 20, 50, 40) > 8, "the dot leads, on the left");
        assertEquals(0, painted.countExactly(GOOD, 20 + (int) (w / 2f), 20, W, 40), "and only there");

        // A dot alone (no text) still fits a circular badge.
        RasterUiFixture alone = paint(new MBadge("").dot(GOOD).bounds(20, 20, 18, 18));
        assertTrue(alone.countExactly(GOOD, 20, 20, 38, 38) > 4);
    }

    @Test
    void theStylesCombineAndStayCentred() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MBadge chip = new MBadge("Haste x2").outlined(GOOD).dot(GOOD).trailing("8s").size(0, 20);
        float w = (float) Math.ceil(chip.preferredWidth(fx.ui));
        RasterUiFixture painted = paint(chip.bounds(20, 20, w, 20));
        // Nothing is painted outside the pill: the content fits the width it asked for.
        assertEquals(0, painted.countPainted(0, 0, 20, H));
        assertEquals(0, painted.countPainted(20 + (int) w + 1, 0, W, H));
        assertTrue(painted.countExactly(MStyle.TEXT_SECONDARY, 20, 20, 20 + (int) w, 40) > 10);
    }

    @Test
    void scaleScalesTheStyledBadge() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MBadge one = new MBadge("Chilled").outlined(FROST).dot(FROST).trailing("12s").scale(1f).size(0, 20);
        MBadge two = new MBadge("Chilled").outlined(FROST).dot(FROST).trailing("12s").scale(2f).size(0, 40);
        float w1 = one.preferredWidth(fx.ui), w2 = two.preferredWidth(fx.ui);
        assertTrue(w2 > w1 * 1.8f && w2 < w1 * 2.2f, "twice the scale, about twice the width: " + w1 + " -> " + w2);
        MBadge fluent = new MBadge("x").scale(2f).scaleText(false);   // compiles only while both stay covariant
        assertEquals("x", fluent.text());
    }

    @Test
    void degenerateInputIsSafe() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        assertDoesNotThrow(() -> new MBadge(null).outlined(FROST).trailing(null).dot(GOOD).bounds(0, 0, 0, 0).render(fx.ui));
        assertDoesNotThrow(() -> new MBadge("x").outlined(0).trailing("y").bounds(10, 10, -4, 12).render(fx.ui));
        assertEquals(0, fx.countPainted(0, 0, W, H), "zero and negative sizes draw nothing");
        assertDoesNotThrow(() -> new MBadge("x").outlined(FROST).trailing("9s").dot(GOOD).bounds(10, 10, 4, 4).render(fx.ui));
    }
}
