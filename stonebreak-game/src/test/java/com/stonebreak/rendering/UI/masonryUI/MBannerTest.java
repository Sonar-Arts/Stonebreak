package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MBanner}: the drop / hold / fade timeline (including the hold the caller controls and what a
 * single huge step does), and the plate's pixels in each phase.
 */
class MBannerTest {

    private static final int W = 480;
    private static final int H = 160;
    private static final float EPS = 1.0e-4f;
    private static final int ICE = 0xFF7FC8FF;   // a caller-supplied accent

    private static MBanner banner() {
        return new MBanner().bounds(90f, 60f, 300f, 36f);
    }

    // ── Timeline ─────────────────────────────────────────────────────────────

    @Test
    void itDropsInOverInSeconds() {
        MBanner b = banner().show("Glacial Overhead", ICE);
        assertTrue(b.active());
        assertFalse(b.visible(), "fully transparent on the frame it is shown");
        assertEquals(0f, b.dropProgress(), EPS);
        assertTrue(b.plateRect()[1] < 60f - 36f, "it starts above its slot");

        b.update(0.06f);
        assertTrue(b.visible());
        assertEquals(0.5f, b.alpha(), 0.01f);
        assertTrue(b.dropProgress() > 0.5f && b.dropProgress() < 1f, "eased: past halfway in half the time");
        float midY = b.plateRect()[1];
        assertTrue(midY < 60f);

        b.update(0.06f);
        assertEquals(1f, b.alpha(), EPS);
        assertEquals(1f, b.dropProgress(), EPS);
        assertEquals(60f, b.plateRect()[1], EPS, "seated in its slot");
    }

    @Test
    void itHoldsForAtLeastTheMinimumThenFades() {
        MBanner b = banner().show("Quest updated", 0);
        b.update(0.5f);
        assertFalse(b.fading());
        b.update(0.39f);
        assertFalse(b.fading(), "0.89 s: still inside the minimum hold");
        assertEquals(1f, b.alpha(), EPS);

        b.update(0.02f);
        assertTrue(b.fading(), "0.91 s: the fade has begun");
        assertEquals(1f - 0.01f / MBanner.DEFAULT_OUT_SECONDS, b.alpha(), 0.01f, "and began exactly at the minimum hold");

        b.update(0.12f);
        assertTrue(b.visible());
        assertTrue(b.alpha() < 0.6f);
        b.update(0.13f);
        assertFalse(b.active(), "gone once the fade has run");
        assertFalse(b.visible());
        assertNull(b.text());
        assertEquals(0f, b.alpha(), 0f);
    }

    @Test
    void itStaysUpWhileHeld() {
        MBanner b = banner().show("Frozen Depths", ICE).hold(true);
        for (int i = 0; i < 600; i++) b.update(1f / 60f);
        assertTrue(b.visible());
        assertFalse(b.fading(), "ten seconds on, still held");
        assertEquals(1f, b.alpha(), EPS);

        b.hold(false);
        assertFalse(b.fading(), "releasing is not a time step");
        b.update(0.1f);
        assertTrue(b.fading());
        assertEquals(1f - 0.1f / MBanner.DEFAULT_OUT_SECONDS, b.alpha(), 0.01f, "the fade started at the release");
        b.update(0.2f);
        assertFalse(b.active());
    }

    @Test
    void aShortHoldStillRespectsTheMinimum() {
        MBanner b = banner().show("Quick Jab", ICE).hold(true);
        b.update(0.2f);
        b.hold(false);
        b.update(0.5f);
        assertFalse(b.fading(), "released at 0.2 s, but the plate must still read");
        b.update(0.25f);
        assertTrue(b.fading());
    }

    @Test
    void holdingAgainDuringTheFadeDoesNotReviveIt() {
        MBanner b = banner().show("Done", 0);
        b.update(1f);
        assertTrue(b.fading());
        b.hold(true);
        b.update(1f);
        assertFalse(b.active());
    }

    @Test
    void showingAgainRestartsTheTimeline() {
        MBanner b = banner().show("First", ICE);
        b.update(1.0f);
        assertTrue(b.fading());

        b.show("Second", MStyle.TEXT_ACCENT);
        assertEquals("Second", b.text());
        assertEquals(MStyle.TEXT_ACCENT, b.accent());
        assertFalse(b.fading());
        assertEquals(0f, b.dropProgress(), EPS, "it drops in again");
        b.update(0.12f);
        assertEquals(1f, b.alpha(), EPS);
        b.update(0.7f);
        assertFalse(b.fading(), "with a fresh minimum hold");

        b.hold(true).show("Third", 0);
        b.update(2f);
        assertFalse(b.active(), "show() releases a hold left over from the previous plate");
    }

    @Test
    void oneHugeStepEndsWhereManySmallOnesDo() {
        MBanner big = banner().show("Zone", ICE);
        big.update(60f);
        assertFalse(big.active());

        MBanner coarse = banner().show("Zone", ICE);
        MBanner fine = banner().show("Zone", ICE);
        coarse.update(1.0f);
        for (int i = 0; i < 100; i++) fine.update(0.01f);
        assertTrue(coarse.active() && fine.active());
        assertEquals(fine.alpha(), coarse.alpha(), 0.01f, "the fade start does not depend on the step size");
    }

    @Test
    void timingsAreConfigurable() {
        MBanner b = banner().inSeconds(0f).minHold(0.2f).outSeconds(0f).show("Snap", 0);
        assertEquals(1f, b.alpha(), EPS, "no drop: fully in at once");
        assertEquals(60f, b.plateRect()[1], EPS);
        b.update(0.1f);
        assertTrue(b.visible());
        b.update(0.11f);
        assertFalse(b.active(), "no fade: gone the moment the hold ends");

        MBanner sane = banner().inSeconds(Float.NaN).minHold(-3f).outSeconds(Float.POSITIVE_INFINITY).show("x", 0);
        sane.update(MBanner.DEFAULT_IN_SECONDS);
        assertEquals(1f, sane.alpha(), EPS, "nonsense timings fall back to the defaults");
    }

    @Test
    void hideAndEmptyTextRemoveItAtOnce() {
        MBanner b = banner().show("Here", ICE);
        b.update(0.3f);
        b.hide();
        assertFalse(b.active());
        assertFalse(b.visible());

        b.show("Again", ICE);
        b.update(0.3f);
        b.show("", ICE);
        assertFalse(b.active());
        b.show(null, ICE);
        assertFalse(b.active());
        assertDoesNotThrow(() -> {
            b.update(1f);
            b.update(Float.NaN);
            b.update(-1f);
            b.hold(true).hold(false);
        });
    }

    // ── Pixels ───────────────────────────────────────────────────────────────

    @Test
    void aSeatedPlatePaintsItsFrameAccentAndLabelInsideItsSlot() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MBanner b = banner().show("Glacial Overhead", ICE);
        b.update(0.2f);
        b.render(fx.ui);

        assertTrue(fx.countPainted(90, 60, 390, 96) > 300 * 36 * 0.95f, "the HUD frame fills the slot");
        assertEquals(0, fx.countPainted(0, 0, W, 58), "nothing above a seated plate");
        assertTrue(fx.countExactly(ICE, 94, 62, 386, 66) > 200, "the accent hairline runs along the top edge");
        assertTrue(fx.countExactly(MStyle.TEXT_PRIMARY, 150, 66, 330, 92) > 40, "the label is centred in the plate");
        assertEquals(0, fx.countExactly(MStyle.TEXT_PRIMARY, 90, 66, 120, 92), "and only in the middle");
    }

    @Test
    void eachPhaseLooksDifferent() {
        RasterUiFixture dropping = new RasterUiFixture(W, H);
        RasterUiFixture seated = new RasterUiFixture(W, H);
        RasterUiFixture fading = new RasterUiFixture(W, H);
        RasterUiFixture gone = new RasterUiFixture(W, H);
        MBanner b = banner().show("Glacial Overhead", ICE);
        b.update(0.04f);
        b.render(dropping.ui);
        b.update(0.4f);
        b.render(seated.ui);
        b.update(0.6f);
        b.render(fading.ui);
        b.update(1f);
        b.render(gone.ui);

        assertTrue(dropping.countPainted(0, 0, W, 58) > 0, "while dropping it is still above its slot");
        assertTrue(dropping.diff(seated, 0, 0, W, H) > 500);
        assertTrue(seated.diff(fading, 0, 0, W, H) > 500);
        assertEquals(0, gone.countPainted(0, 0, W, H));
    }

    @Test
    void theAccentIsTheCallersAndOptional() {
        RasterUiFixture ice = new RasterUiFixture(W, H);
        RasterUiFixture gold = new RasterUiFixture(W, H);
        RasterUiFixture bare = new RasterUiFixture(W, H);
        for (Object[] c : new Object[][]{{ice, ICE}, {gold, MStyle.TEXT_ACCENT}, {bare, 0}}) {
            MBanner b = banner().show("Frozen Depths", (Integer) c[1]);
            b.update(0.2f);
            b.render(((RasterUiFixture) c[0]).ui);
        }
        assertTrue(ice.diff(gold, 90, 60, 390, 70) > 100);
        assertEquals(0, ice.diff(gold, 90, 70, 390, 100), "only the hairline changes with the accent");
        assertEquals(0, bare.countExactly(ICE, 0, 0, W, H));
        assertTrue(bare.diff(ice, 90, 60, 390, 70) > 100);
    }

    @Test
    void aLongNameIsFittedInsideThePlate() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MBanner b = new MBanner().bounds(150f, 60f, 180f, 36f)
                .show("The Exceedingly Long And Winding Name Of A Place", 0);
        b.update(0.2f);
        b.render(fx.ui);
        assertEquals(0, fx.countExactly(MStyle.TEXT_PRIMARY, 0, 0, 150, H), "no label spills out to the left");
        assertEquals(0, fx.countExactly(MStyle.TEXT_PRIMARY, 330, 0, W, H), "nor to the right");
    }

    @Test
    void scaleScalesTheLabel() {
        RasterUiFixture small = new RasterUiFixture(W, H);
        RasterUiFixture large = new RasterUiFixture(W, H);
        MBanner a = new MBanner().bounds(40f, 40f, 400f, 80f).scale(1f).show("Zone", 0);
        MBanner b = new MBanner().bounds(40f, 40f, 400f, 80f).scale(2f).show("Zone", 0);
        a.update(0.2f);
        b.update(0.2f);
        a.render(small.ui);
        b.render(large.ui);
        int smallInk = small.countExactly(MStyle.TEXT_PRIMARY, 0, 0, W, H);
        assertTrue(smallInk > 0);
        assertTrue(large.countExactly(MStyle.TEXT_PRIMARY, 0, 0, W, H) > 2.5f * smallInk);
    }

    @Test
    void twoBannersFedTheSameUpdatesPaintIdentically() {
        RasterUiFixture a = new RasterUiFixture(W, H);
        RasterUiFixture b = new RasterUiFixture(W, H);
        for (RasterUiFixture fx : new RasterUiFixture[]{a, b}) {
            MBanner banner = banner().show("Glacial Overhead", ICE);
            banner.update(0.05f);
            banner.update(0.9f);
            banner.update(0.03f);
            banner.render(fx.ui);
            banner.render(fx.ui);   // painting twice changes nothing: render does not advance the timeline
            assertTrue(banner.fading());
        }
        assertTrue(a.countPainted(0, 0, W, H) > 1000);
        assertEquals(0, a.diff(b, 0, 0, W, H));
    }

    @Test
    void degenerateBoundsDrawNothingAndNeverThrow() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        assertDoesNotThrow(() -> {
            for (float[] r : new float[][]{{0, 0, 0, 0}, {10, 10, -5, 20}, {10, 10, 100, Float.NaN}, {10, 10, 2, 2}}) {
                MBanner b = new MBanner().bounds(r[0], r[1], r[2], r[3]).show("Zone", ICE);
                b.update(0.2f);
                b.render(fx.ui);
            }
            banner().render(fx.ui);            // never shown
            MBanner shown = banner().show("Zone", ICE);
            shown.update(0.2f);
            shown.render(null);
        });
        assertTrue(fx.countPainted(0, 0, W, H) < 40, "at most the 2×2 sliver");
    }
}
