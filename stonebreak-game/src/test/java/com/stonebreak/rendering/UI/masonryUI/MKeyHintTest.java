package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MKeyHint}: a keycap and its label, sized to content, faded, outlined over the scene. */
class MKeyHintTest {

    private static final int W = 260;
    private static final int H = 80;

    private static MKeyHint hint() {
        return new MKeyHint("SPACE", "Confirm").scale(1f).bounds(20, 20, 200, 30);
    }

    private static RasterUiFixture paint(MKeyHint hint) {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        hint.render(fx.ui);
        return fx;
    }

    @Test
    void itDrawsAKeycapThenTheLabelWithinItsPreferredWidth() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MKeyHint hint = hint();
        hint.render(fx.ui);
        float total = hint.preferredWidth(fx.ui);
        float[] cap = hint.capRect(fx.ui);

        assertEquals(20f, cap[0], "left-aligned by default");
        assertEquals(hint.preferredHeight(), cap[3]);
        assertEquals(35f, cap[1] + cap[3] / 2f, 0.001f, "vertically centred in the bounds");
        assertTrue(cap[2] < total);
        assertTrue(fx.countExactly(MStyle.BUTTON_FILL, (int) cap[0], (int) cap[1], (int) (cap[0] + cap[2]),
                (int) (cap[1] + cap[3])) > 300, "the cap is a button surface");
        assertTrue(fx.countPainted((int) (cap[0] + cap[2]) + 6, 20, 20 + (int) Math.ceil(total) + 3, 50) > 80,
                "the label follows the cap");
        assertEquals(0, fx.countPainted(20 + (int) Math.ceil(total) + 6, 0, W, H), "nothing past the preferred width");
        assertEquals(0, fx.countPainted(0, 0, 19, H));
    }

    @Test
    void preferredWidthFollowsTheContent() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        float both = hint().preferredWidth(fx.ui);
        float keyOnly = new MKeyHint("SPACE", "").scale(1f).preferredWidth(fx.ui);
        float labelOnly = new MKeyHint("", "Confirm").scale(1f).preferredWidth(fx.ui);
        assertEquals(both, keyOnly + 6f + labelOnly, 0.001f, "cap + gap + label");
        assertTrue(new MKeyHint("SPACE", "Confirm and continue").scale(1f).preferredWidth(fx.ui) > both);
        assertEquals(0f, new MKeyHint().preferredWidth(fx.ui));
        float oneLetter = new MKeyHint("E", "").scale(1f).preferredWidth(fx.ui);
        assertEquals(new MKeyHint().scale(1f).preferredHeight(), oneLetter, "a one-letter key is a square cap");
    }

    @Test
    void alignmentPlacesTheGroupInsideTheBounds() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        float total = hint().preferredWidth(fx.ui);
        assertEquals(20f, hint().align(MPainter.Align.LEFT).capRect(fx.ui)[0]);
        assertEquals(20f + (200f - total) / 2f, hint().align(MPainter.Align.CENTER).capRect(fx.ui)[0], 0.001f);
        assertEquals(220f - total, hint().align(MPainter.Align.RIGHT).capRect(fx.ui)[0], 0.001f);
        assertEquals(20f, hint().align(null).capRect(fx.ui)[0], "null falls back to left");

        RasterUiFixture right = paint(hint().align(MPainter.Align.RIGHT));
        assertEquals(0, right.countPainted(0, 0, (int) Math.floor(220f - total) - 1, H));
        assertTrue(right.countPainted(150, 20, 224, 54) > 300);
    }

    @Test
    void alphaFadesTheWholeHint() {
        RasterUiFixture full = paint(hint());
        RasterUiFixture half = paint(hint().alpha(0.5f));
        RasterUiFixture none = paint(hint().alpha(0f));
        assertTrue(full.diff(half, 0, 0, W, H) > 500);
        assertEquals(0, none.countPainted(0, 0, W, H), "alpha 0 draws nothing");
        assertEquals(0, half.countExactly(MStyle.TEXT_PRIMARY, 0, 0, W, H), "the text faded too");
        assertEquals(1f, new MKeyHint().alpha(9f).alpha());
        assertEquals(0f, new MKeyHint().alpha(Float.NaN).alpha());
    }

    @Test
    void anOutlinedLabelDiffersFromAShadowedOneButTheCapDoesNot() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        float[] cap = hint().capRect(fx.ui);
        int labelLeft = (int) (cap[0] + cap[2]) + 5;
        RasterUiFixture shadowed = paint(hint());
        RasterUiFixture outlined = paint(hint().outlined(true));
        assertTrue(shadowed.diff(outlined, labelLeft, 0, W, H) > 40);
        assertEquals(0, shadowed.diff(outlined, 0, 0, labelLeft - 1, H));
        // The letters themselves are the same; only what sits under them changed.
        assertEquals(shadowed.countExactly(MStyle.TEXT_PRIMARY, labelLeft, 0, W, H),
                outlined.countExactly(MStyle.TEXT_PRIMARY, labelLeft, 0, W, H));
    }

    @Test
    void pressedLabelColourAndFontSizeAreVisible() {
        RasterUiFixture base = paint(hint());
        RasterUiFixture pressed = paint(hint().pressed(true));
        assertTrue(base.diff(pressed, 0, 0, W, H) > 300);
        assertTrue(pressed.countExactly(MStyle.BUTTON_FILL_HI, 0, 0, W, H) > 300);
        assertTrue(base.diff(paint(hint().labelColor(MStyle.TEXT_WARN)), 0, 0, W, H) > 40);
        assertTrue(paint(hint().labelColor(MStyle.TEXT_WARN)).countExactly(MStyle.TEXT_WARN, 0, 0, W, H) > 40);

        RasterUiFixture fx = new RasterUiFixture(W, H);
        assertTrue(hint().fontSize(20f).preferredWidth(fx.ui) > hint().preferredWidth(fx.ui));
        assertEquals(hint().preferredWidth(fx.ui), hint().fontSize(-1f).preferredWidth(fx.ui), "bad size: the default");
    }

    @Test
    void scaleScalesCapAndText() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MKeyHint one = new MKeyHint("E", "Talk").scale(1f).bounds(10, 10, 240, 60);
        MKeyHint two = new MKeyHint("E", "Talk").scale(2f).bounds(10, 10, 240, 60);
        assertEquals(one.preferredHeight() * 2f, two.preferredHeight());
        float w1 = one.preferredWidth(fx.ui), w2 = two.preferredWidth(fx.ui);
        assertTrue(w2 > w1 * 1.8f && w2 < w1 * 2.2f, w1 + " -> " + w2);
        assertTrue(paint(two).countPainted(0, 0, W, H) > paint(one).countPainted(0, 0, W, H) * 2);
    }

    @Test
    void keyOnlyAndLabelOnlyBothWork() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MKeyHint labelOnly = new MKeyHint("", "Skip").scale(1f).bounds(20, 20, 200, 30);
        assertArrayEquals(new float[4], labelOnly.capRect(fx.ui), "no key, no cap");
        RasterUiFixture a = paint(labelOnly);
        assertTrue(a.countPainted(20, 20, 80, 50) > 40);
        assertEquals(0, a.countExactly(MStyle.BUTTON_FILL, 0, 0, W, H));

        RasterUiFixture b = paint(new MKeyHint("ESC", null).scale(1f).bounds(20, 20, 200, 30));
        assertTrue(b.countExactly(MStyle.BUTTON_FILL, 0, 0, W, H) > 200);
    }

    @Test
    void renderingIsDeterministicAndStateless() {
        MKeyHint hint = hint().outlined(true).alpha(0.8f);
        RasterUiFixture a = paint(hint), b = paint(hint), c = paint(hint().outlined(true).alpha(0.8f));
        assertEquals(0, a.diff(b, 0, 0, W, H));
        assertEquals(0, a.diff(c, 0, 0, W, H));
    }

    @Test
    void degenerateInputIsSafe() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        float[][] bad = {{0, 0, 0, 0}, {10, 10, -5, 20}, {10, 10, 100, 0}, {Float.NaN, 0, 100, 20},
                {0, 0, 100, Float.NaN}, {0, Float.NEGATIVE_INFINITY, 100, 20}};
        for (float[] r : bad) {
            MKeyHint hint = new MKeyHint("SPACE", "Confirm").scale(1f).bounds(r[0], r[1], r[2], r[3]);
            assertDoesNotThrow(() -> hint.render(fx.ui));
            assertArrayEquals(new float[4], hint.capRect(fx.ui));
        }
        assertEquals(0, fx.countPainted(0, 0, W, H));
        assertDoesNotThrow(() -> new MKeyHint(null, null).bounds(0, 0, 50, 20).render(fx.ui));
        assertDoesNotThrow(() -> hint().render(null));
        assertEquals(0f, hint().preferredWidth(null));
        assertEquals("", new MKeyHint(null, null).key());
        assertEquals("", new MKeyHint(null, null).label());
    }
}
