package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MResultCard}: focus and activation through the card, one rect formula for drawing and
 * hit-testing at any size and scale, the rise gate on input, the count-up, and the card's pixels.
 */
class MResultCardTest {

    private static final int W = 640;
    private static final int H = 420;
    private static final int FROST_RED = 0xFFE0707A;   // a caller-supplied, non-gold accent

    /** A three-button card that records which callbacks ran. */
    private static final class Rig {
        final List<String> pressed = new ArrayList<>();
        final MButton retry = new MButton("Retry").onClick(() -> pressed.add("retry"));
        final MButton explore = new MButton("Explore").onClick(() -> pressed.add("explore"));
        final MButton leave = new MButton("Leave").onClick(() -> pressed.add("leave"));
        final MResultCard card = new MResultCard()
                .title("VICTORY", MStyle.TEXT_ACCENT)
                .subtitle("Ice Archon defeated")
                .stat("Time", "2:41")
                .statNumber("Damage dealt", 1284f, "%.0f")
                .statNumber("Parries", 7f, "%d")
                .stat("Rank", "A")
                .buttons(List.of(retry, explore, leave))
                .scale(1f)
                .bounds(60f, 50f, 520f, 320f);
    }

    // ── Focus ────────────────────────────────────────────────────────────────

    @Test
    void focusStartsOnTheFirstButtonAndWrapsBothWays() {
        Rig rig = new Rig();
        assertEquals(3, rig.card.buttonCount());
        assertEquals(0, rig.card.focused());
        assertTrue(rig.retry.isSelected(), "the focused button is simply the selected MButton");

        rig.card.moveFocus(1);
        assertEquals(1, rig.card.focused());
        assertFalse(rig.retry.isSelected());
        assertTrue(rig.explore.isSelected());
        rig.card.moveFocus(1);
        rig.card.moveFocus(1);
        assertEquals(0, rig.card.focused(), "right off the end wraps to the first");
        rig.card.moveFocus(-1);
        assertEquals(2, rig.card.focused(), "left off the start wraps to the last");
        rig.card.moveFocus(-7);
        assertEquals(1, rig.card.focused(), "any delta wraps");
        rig.card.moveFocus(0);
        assertEquals(1, rig.card.focused());

        rig.card.focus(2);
        assertEquals(2, rig.card.focused());
        rig.card.focus(3);
        rig.card.focus(-1);
        assertEquals(2, rig.card.focused(), "an index that is no button is ignored");
        int selected = 0;
        for (MButton b : List.of(rig.retry, rig.explore, rig.leave)) if (b.isSelected()) selected++;
        assertEquals(1, selected, "exactly one button is ever selected");
    }

    @Test
    void focusSkipsDisabledButtons() {
        Rig rig = new Rig();
        rig.explore.enabled(false);
        rig.card.moveFocus(1);
        assertEquals(2, rig.card.focused());
        rig.card.moveFocus(-1);
        assertEquals(0, rig.card.focused());
        rig.card.focus(1);
        assertEquals(0, rig.card.focused(), "a disabled button cannot take focus");

        MButton off = new MButton("Off").enabled(false);
        MButton on = new MButton("On");
        MResultCard card = new MResultCard().buttons(List.of(off, on));
        assertEquals(1, card.focused(), "initial focus lands on the first enabled button");
    }

    @Test
    void activateFiresTheFocusedButtonsCallbackOnly() {
        Rig rig = new Rig();
        assertTrue(rig.card.activate());
        rig.card.moveFocus(1);
        assertTrue(rig.card.activate());
        rig.card.focus(2);
        assertTrue(rig.card.activate());
        assertEquals(List.of("retry", "explore", "leave"), rig.pressed);

        rig.leave.enabled(false);
        assertFalse(rig.card.activate(), "a disabled button does not fire");
        assertEquals(3, rig.pressed.size());

        assertFalse(new MResultCard().activate(), "no buttons, nothing to press");
        assertFalse(new MResultCard().buttons(List.of(new MButton("No callback"))).activate());
        assertEquals(-1, new MResultCard().focused());
    }

    // ── Rise gate ────────────────────────────────────────────────────────────

    @Test
    void theCardAnswersNothingUntilItHasFullyRisen() {
        Rig rig = new Rig();
        float[] leave = rig.card.buttonRect(2);
        float lx = leave[0] + leave[2] / 2f, ly = leave[1] + leave[3] / 2f;

        for (float rise : new float[]{0f, 0.4f, 0.999f}) {
            rig.card.rise(rise);
            assertFalse(rig.card.interactive());
            assertFalse(rig.retry.isSelected(), "no button looks focused on a card that cannot be pressed");
            rig.card.moveFocus(1);
            rig.card.focus(2);
            assertEquals(0, rig.card.focused());
            assertFalse(rig.card.activate());
            assertEquals(-1, rig.card.hover(lx, ly));
            assertFalse(rig.card.click(lx, ly));
        }
        assertTrue(rig.pressed.isEmpty(), "a confirm mashed while it rises presses nothing");

        rig.card.rise(1f);
        assertTrue(rig.card.interactive());
        assertTrue(rig.retry.isSelected());
        assertEquals(2, rig.card.hover(lx, ly));
        assertEquals(2, rig.card.focused());
        assertTrue(rig.card.click(lx, ly));
        assertEquals(List.of("leave"), rig.pressed);

        rig.card.rise(Float.NaN);
        assertEquals(0f, rig.card.rise(), 0f);
        rig.card.rise(9f);
        assertEquals(1f, rig.card.rise(), 0f);
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    @Test
    void buttonAtAgreesWithButtonRectAtEverySizeAndScale() {
        float[][] sizes = {{520f, 320f}, {300f, 180f}, {900f, 560f}, {521.5f, 333.25f}};
        float[] scales = {0.75f, 1f, 1.5f, 2f};
        for (float[] size : sizes) {
            for (float scale : scales) {
                Rig rig = new Rig();
                rig.card.scale(scale).bounds(37f, 21f, size[0], size[1]);
                String at = " (" + size[0] + "x" + size[1] + " @" + scale + ")";
                float previousRight = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < 3; i++) {
                    float[] r = rig.card.buttonRect(i);
                    assertTrue(r[2] > 0f && r[3] > 0f, "button " + i + " has an area" + at);
                    assertTrue(r[0] > previousRight, "buttons run left to right without overlap" + at);
                    previousRight = r[0] + r[2];
                    assertTrue(r[0] >= 37f && r[0] + r[2] <= 37f + size[0] && r[1] >= 21f
                            && r[1] + r[3] <= 21f + size[1], "inside the card" + at);

                    assertEquals(i, rig.card.buttonAt(r[0] + r[2] / 2f, r[1] + r[3] / 2f), "centre" + at);
                    assertEquals(i, rig.card.buttonAt(r[0], r[1]), "top-left corner" + at);
                    assertEquals(i, rig.card.buttonAt(r[0] + r[2], r[1] + r[3]), "bottom-right corner" + at);
                    assertEquals(-1, rig.card.buttonAt(r[0] + r[2] / 2f, r[1] - 1f), "just above" + at);
                    assertEquals(-1, rig.card.buttonAt(r[0] + r[2] / 2f, r[1] + r[3] + 1f), "just below" + at);
                    assertEquals(-1, rig.card.buttonAt(r[0] + r[2] + 1f, r[1] + r[3] / 2f), "in the gap to its right" + at);
                }
                assertEquals(-1, rig.card.buttonAt(37f + size[0] / 2f, 21f + 10f), "the title is no button" + at);
            }
        }
    }

    @Test
    void theButtonsAreDrawnExactlyWhereTheyAreHit() {
        for (float scale : new float[]{1f, 1.5f}) {
            Rig with = new Rig();
            Rig without = new Rig();
            // No stats (their block centres itself in whatever room the buttons leave) and the same
            // height for both, so the button row is the only difference between the two pictures.
            with.card.clearStats();
            without.card.clearStats().buttons(List.of());
            float h = with.card.scale(scale).preferredHeight();
            with.card.bounds(40f, 20f, 560f, h);
            without.card.scale(scale).bounds(40f, 20f, 560f, h);
            RasterUiFixture a = new RasterUiFixture(W, H);
            RasterUiFixture b = new RasterUiFixture(W, H);
            with.card.render(a.ui);
            without.card.render(b.ui);

            float[] first = with.card.buttonRect(0), last = with.card.buttonRect(2);
            int top = (int) first[1], bottom = (int) (first[1] + first[3]);
            for (int i = 0; i < 3; i++) {
                float[] r = with.card.buttonRect(i);
                int inside = a.diff(b, (int) r[0] + 2, (int) r[1] + 2, (int) (r[0] + r[2]) - 2, (int) (r[1] + r[3]) - 2);
                assertTrue(inside > 0.9f * (r[2] - 4f) * (r[3] - 4f), "button " + i + " fills its rect at scale " + scale);
                assertArrayEquals(r, new float[]{with.card.buttons().get(i).x(), with.card.buttons().get(i).y(),
                        with.card.buttons().get(i).width(), with.card.buttons().get(i).height()}, 0f);
            }
            assertEquals(0, a.diff(b, 0, 0, W, top - 1), "nothing above the button row changes");
            assertEquals(0, a.diff(b, 0, top - 1, (int) first[0] - 1, bottom), "nothing left of the first button");
            assertEquals(0, a.diff(b, (int) (last[0] + last[2]) + 4, top - 1, W, bottom), "nothing right of the last (beyond its shadow)");
        }
    }

    @Test
    void scaleScalesEveryMetric() {
        Rig one = new Rig();
        Rig two = new Rig();
        one.card.scale(1f);
        two.card.scale(2f);
        assertEquals(2f * one.card.preferredHeight(), two.card.preferredHeight(), 1.0e-3f);

        one.card.bounds(0f, 0f, 400f, one.card.preferredHeight());
        two.card.bounds(0f, 0f, 800f, two.card.preferredHeight());
        for (int i = 0; i < 3; i++) {
            float[] a = one.card.buttonRect(i), b = two.card.buttonRect(i);
            for (int k = 0; k < 4; k++) assertEquals(2f * a[k], b[k], 2f, "button " + i + " component " + k);
        }
        for (int i = 0; i < one.card.statCount(); i++) {
            float[] a = one.card.statRect(i), b = two.card.statRect(i);
            for (int k = 0; k < 4; k++) assertEquals(2f * a[k], b[k], 1.0e-2f, "stat " + i + " component " + k);
        }
    }

    @Test
    void statsAreDealtIntoColumnsAboveTheButtons() {
        Rig rig = new Rig();
        float[] s0 = rig.card.statRect(0), s1 = rig.card.statRect(1), s2 = rig.card.statRect(2), s3 = rig.card.statRect(3);
        assertEquals(s0[0], s1[0], 0f, "four stats in two columns: the first two share a column");
        assertTrue(s1[1] > s0[1]);
        assertTrue(s2[0] > s0[0] + s0[2], "the third starts the second column");
        assertEquals(s0[1], s2[1], 0f);
        assertEquals(s1[1], s3[1], 0f);
        assertTrue(s3[1] + s3[3] <= rig.card.buttonRect(0)[1], "stats end above the buttons");
        assertArrayEquals(new float[4], rig.card.statRect(4), 0f);
        assertArrayEquals(new float[4], rig.card.buttonRect(-1), 0f);
        assertArrayEquals(new float[4], rig.card.buttonRect(3), 0f);

        rig.card.columns(1);
        assertEquals(rig.card.statRect(0)[0], rig.card.statRect(3)[0], 0f);
        assertTrue(rig.card.statRect(3)[1] > rig.card.statRect(2)[1]);
    }

    @Test
    void aCardShorterThanItsContentCompressesInsteadOfOverflowing() {
        Rig rig = new Rig();
        rig.card.bounds(60f, 50f, 520f, rig.card.preferredHeight() * 0.5f);
        float[] button = rig.card.buttonRect(0);
        float[] lastStat = rig.card.statRect(3);
        assertEquals(20f, button[3], 1f, "half the height, half the button");
        assertTrue(button[1] + button[3] <= 50f + rig.card.height());
        assertTrue(lastStat[1] + lastStat[3] <= button[1] + 0.5f);
        assertTrue(rig.card.statRect(0)[1] >= 50f);
    }

    // ── Count-up ─────────────────────────────────────────────────────────────

    @Test
    void numbersCountUpToTheirExactValue() {
        Rig rig = new Rig();
        rig.card.countUp(1f);
        assertEquals("0", rig.card.statText(1));
        assertEquals("0", rig.card.statText(2));
        assertEquals("2:41", rig.card.statText(0), "text stats do not take part");

        rig.card.update(0.25f);
        int early = Integer.parseInt(rig.card.statText(1));
        assertTrue(early > 0 && early < 1284, "climbing: " + early);
        assertTrue(early > 1284 / 4, "eased out: ahead of linear early on");
        rig.card.update(0.25f);
        int later = Integer.parseInt(rig.card.statText(1));
        assertTrue(later > early && later < 1284);

        rig.card.update(0.5f);
        assertEquals("1284", rig.card.statText(1));
        assertEquals("7", rig.card.statText(2), "an integer pattern works on the float value");
        assertEquals(1f, rig.card.countProgress(), 0f);
        rig.card.update(5f);
        assertEquals("1284", rig.card.statText(1), "and stays there");
    }

    @Test
    void theEndValueIsExactForAwkwardNumbersAndStepSizes() {
        float[] values = {0f, 1f, 3f, 16777217f, 0.1f, 99.95f, -40f, 123456.7f};
        for (float value : values) {
            for (float step : new float[]{0.007f, 0.3f, 100f}) {
                MResultCard card = new MResultCard().statNumber("v", value, "%.2f").countUp(0.9f);
                MResultCard direct = new MResultCard().statNumber("v", value, "%.2f");
                for (float t = 0f; t < 1.2f; t += step) card.update(step);
                assertEquals(direct.statText(0), card.statText(0), "value " + value + " step " + step);
            }
        }
    }

    @Test
    void zeroNaNAndBrokenPatternsAreHandled() {
        MResultCard card = new MResultCard()
                .statNumber("zero", 0f, "%.0f")
                .statNumber("nan", Float.NaN, "%.1f")
                .statNumber("no pattern", 12.6f, null)
                .statNumber("bad pattern", 5f, "%q %%% nonsense")
                .statNumber("suffix", 2.5f, "%.1f s")
                .statNumber("negative zero", -0f, "%.0f")
                .stat(null, null)
                .countUp(1f);
        card.update(0.5f);
        assertEquals("0", card.statText(0), "zero counts up to zero without a hiccup");
        card.update(1f);
        assertEquals("0", card.statText(0));
        assertEquals("0.0", card.statText(1));
        assertEquals("13", card.statText(2));
        assertEquals("5", card.statText(3));
        assertEquals("2.5 s", card.statText(4));
        assertEquals("0", card.statText(5));
        assertEquals("", card.statText(6));
        assertEquals("", card.statText(7));
        assertEquals("", card.statText(-1));
    }

    @Test
    void theCountUpWaitsForTheRiseAndCanBeRestarted() {
        Rig rig = new Rig();
        rig.card.countUp(1f).rise(0.5f);
        rig.card.update(3f);
        assertEquals("0", rig.card.statText(1), "numbers do not run while the card is still arriving");

        rig.card.rise(1f);
        rig.card.update(2f);
        assertEquals("1284", rig.card.statText(1));
        rig.card.restartCountUp();
        assertEquals("0", rig.card.statText(1));

        rig.card.countUp(0f);
        assertEquals("1284", rig.card.statText(1), "no duration: final values at once");
        assertDoesNotThrow(() -> {
            rig.card.update(Float.NaN);
            rig.card.update(-1f);
            rig.card.update(Float.POSITIVE_INFINITY);
            rig.card.countUp(Float.NaN);
        });
        assertEquals("1284", rig.card.statText(1));
    }

    // ── Pixels ───────────────────────────────────────────────────────────────

    @Test
    void aSeatedCardPaintsPanelTitleStatsAndButtons() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        Rig rig = new Rig();
        rig.card.render(fx.ui);

        assertTrue(fx.countPainted(60, 50, 580, 370) > 520 * 320 * 0.98f, "the stone panel fills the bounds");
        assertEquals(0, fx.countPainted(0, 0, W, 48), "nothing above it without a scrim");
        assertTrue(fx.countExactly(MStyle.TEXT_ACCENT, 200, 55, 440, 110) > 150, "gold title face");
        assertTrue(fx.countExactly(MStyle.TEXT_SECONDARY, 60, 100, 580, 130) > 30, "subtitle under it");
        float[] stat = rig.card.statRect(1);
        assertTrue(fx.countExactly(MStyle.TEXT_PRIMARY, (int) stat[0], (int) stat[1], (int) (stat[0] + stat[2]) + 1,
                (int) (stat[1] + stat[3]) + 1) > 15, "the value of stat 1 is right-aligned in its cell");
        float[] focus = rig.card.buttonRect(0);
        assertTrue(fx.countExactly(MStyle.TEXT_ACCENT, (int) focus[0], (int) focus[1], (int) (focus[0] + focus[2]),
                (int) (focus[1] + focus[3])) > 20, "the focused button's label is gold");
        float[] idle = rig.card.buttonRect(1);
        assertEquals(0, fx.countExactly(MStyle.TEXT_ACCENT, (int) idle[0], (int) idle[1], (int) (idle[0] + idle[2]),
                (int) (idle[1] + idle[3])), "the others are not");
    }

    @Test
    void goldGetsTheExtrudedTitleAnyOtherAccentAPlainOne() {
        RasterUiFixture gold = new RasterUiFixture(W, H);
        RasterUiFixture red = new RasterUiFixture(W, H);
        new Rig().card.render(gold.ui);
        Rig defeat = new Rig();
        defeat.card.title("VICTORY", FROST_RED).render(red.ui);

        assertTrue(red.countExactly(FROST_RED, 200, 55, 440, 110) > 150, "the title takes the caller's accent");
        assertEquals(0, red.countExactly(MStyle.TEXT_ACCENT, 60, 50, 580, 120));
        // The extrusion runs further down-right than a plain drop shadow: more dark ink behind the face.
        assertTrue(gold.diff(red, 200, 55, 460, 120) > 400);
        assertTrue(gold.diff(red, 60, 130, 580, 138) > 50, "the rule under the header follows the accent too");
        assertEquals(0, gold.diff(red, 60, 140, 580, 370), "nothing below the header depends on the accent");
    }

    @Test
    void focusAndRiseAreVisible() {
        RasterUiFixture first = new RasterUiFixture(W, H);
        RasterUiFixture second = new RasterUiFixture(W, H);
        RasterUiFixture rising = new RasterUiFixture(W, H);
        RasterUiFixture hidden = new RasterUiFixture(W, H);
        Rig rig = new Rig();
        rig.card.screen(W, H);
        rig.card.render(first.ui);
        rig.card.moveFocus(1);
        rig.card.render(second.ui);
        float[] b0 = rig.card.buttonRect(0), b1 = rig.card.buttonRect(1);
        assertTrue(first.diff(second, (int) b0[0], (int) b0[1], (int) (b0[0] + b0[2]), (int) (b0[1] + b0[3])) > 200);
        assertTrue(first.diff(second, (int) b1[0], (int) b1[1], (int) (b1[0] + b1[2]), (int) (b1[1] + b1[3])) > 200);
        assertEquals(0, first.diff(second, 0, 0, W, (int) b0[1] - 1), "moving focus repaints the buttons only");

        rig.card.rise(0.5f);
        assertTrue(rig.card.riseOffset() > 100f, "halfway up from the bottom edge");
        rig.card.render(rising.ui);
        assertEquals(0, rising.countPainted(0, 0, W, 50 + (int) rig.card.riseOffset() - 8), "drawn lower while rising");
        assertTrue(rising.countPainted(60, 50 + (int) rig.card.riseOffset(), 580, H) > 5000);
        assertEquals(0, rising.countExactly(MStyle.TEXT_ACCENT, 0, 0, W, H), "and translucent: no full-strength gold yet");

        rig.card.rise(0f);
        assertEquals(H - 50f, rig.card.riseOffset(), 0f, "starts just off the bottom edge");
        rig.card.render(hidden.ui);
        assertEquals(0, hidden.countPainted(0, 0, W, H));
    }

    @Test
    void theScrimDarkensEverythingBehindTheCard() {
        RasterUiFixture plain = new RasterUiFixture(W, H);
        RasterUiFixture veiled = new RasterUiFixture(W, H);
        RasterUiFixture half = new RasterUiFixture(W, H);
        RasterUiFixture unsized = new RasterUiFixture(W, H);
        for (RasterUiFixture fx : new RasterUiFixture[]{plain, veiled, half, unsized}) fx.canvas.clear(0xFFFFFFFF);
        new Rig().card.render(plain.ui);
        new Rig().card.scrim(true).screen(W, H).render(veiled.ui);
        new Rig().card.scrim(true).screen(W, H).rise(0.5f).render(half.ui);
        new Rig().card.scrim(true).render(unsized.ui);

        assertEquals(0xFFFFFFFF, plain.bitmap.getColor(5, 5));
        int full = veiled.bitmap.getColor(5, 5) & 0xFF, partial = half.bitmap.getColor(5, 5) & 0xFF;
        assertEquals(255f * (1f - 0xCC / 255f), full, 2f, "MStyle.OVERLAY_DEEP over white");
        assertTrue(partial > full + 60 && partial < 250, "the scrim follows the rise");
        assertEquals(veiled.bitmap.getColor(W - 3, H - 3), veiled.bitmap.getColor(5, 5), "edge to edge");
        assertEquals(0, veiled.diff(unsized, 0, 0, W, H), "without a screen size the scrim covers the canvas clip");
        assertEquals(0, plain.diff(veiled, 70, 60, 570, 360), "the card itself is opaque over it");
    }

    @Test
    void twoCardsFedTheSameUpdatesPaintIdentically() {
        RasterUiFixture a = new RasterUiFixture(W, H);
        RasterUiFixture b = new RasterUiFixture(W, H);
        for (RasterUiFixture fx : new RasterUiFixture[]{a, b}) {
            Rig rig = new Rig();
            rig.card.countUp(1f).scrim(true).screen(W, H);
            rig.card.update(0.1f);
            rig.card.update(0.23f);
            rig.card.moveFocus(-1);
            rig.card.render(fx.ui);
            String shown = rig.card.statText(1);
            rig.card.render(fx.ui);
            assertEquals(shown, rig.card.statText(1), "painting does not advance the count-up");
        }
        assertEquals(0, a.diff(b, 0, 0, W, H));
    }

    @Test
    void degenerateCardsDrawNothingAndNeverThrow() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        assertDoesNotThrow(() -> {
            new MResultCard().render(fx.ui);
            new Rig().card.bounds(10f, 10f, 0f, 0f).render(fx.ui);
            new Rig().card.bounds(10f, 10f, -50f, 80f).render(fx.ui);
            new Rig().card.bounds(10f, 10f, 200f, Float.NaN).render(fx.ui);
            new Rig().card.bounds(Float.NaN, 10f, 200f, 100f).render(fx.ui);
            new Rig().card.render(null);
            new MResultCard().title(null, 0).subtitle(null).buttons(null).stat(null, null)
                    .bounds(10f, 10f, 0f, 0f).render(fx.ui);
        });
        assertEquals(0, fx.countPainted(0, 0, W, H));

        Rig tiny = new Rig();
        tiny.card.bounds(10f, 10f, 30f, 12f);
        assertDoesNotThrow(() -> tiny.card.render(fx.ui));
        assertEquals(-1, new Rig().card.bounds(0f, 0f, 0f, 0f).buttonAt(0f, 0f), "a card with no area has no buttons to hit");
        List<MButton> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(new MButton("Only"));
        assertEquals(1, new MResultCard().buttons(withNull).buttonCount());
    }
}
