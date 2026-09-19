package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MFloatingText}: the screen position is captured once at spawn, lanes and the burst fan keep
 * simultaneous texts apart, the cap and lifetimes bound the live set, the band holds the whole arc,
 * and everything is a deterministic function of the spawn order and {@code update(dt)}.
 */
class MFloatingTextTest {

    private static final int W = 480;
    private static final int H = 320;
    private static final float EPS = 1.0e-3f;

    // ── Captured position ────────────────────────────────────────────────────

    @Test
    void aFloaterStartsExactlyWhereItWasSpawned() {
        MFloatingText texts = new MFloatingText();
        MFloatingText.Floater f = texts.spawn("12", MFloatingText.Style.number(), 200f, 150f);

        assertNotNull(f);
        assertTrue(f.started(), "the first spawn on a quiet anchor has no stagger delay");
        assertArrayEquals(new float[]{200f, 150f}, f.origin(1f), EPS);
        assertArrayEquals(new float[]{200f, 150f}, f.position(1f), EPS);
    }

    @Test
    void thePositionIsHeldWhenNothingAdvancesAndIgnoresLaterAnchors() {
        MFloatingText texts = new MFloatingText();
        MFloatingText.Floater first = texts.spawn("12", MFloatingText.Style.number(), 200f, 150f);
        float[] before = first.position(1f);

        texts.update(0f);
        texts.update(-1f);
        texts.update(Float.NaN);
        // Whatever it was born from has "moved": later spawns come from somewhere else entirely.
        texts.spawn("7", MFloatingText.Style.number(), 420f, 40f);
        texts.spawn("PERFECT", MFloatingText.Style.word(), 30f, 300f);

        assertArrayEquals(before, first.position(1f), 0f, "no time passed, nothing may move");
        assertArrayEquals(new float[]{200f, 150f}, first.origin(1f), EPS, "later anchors never re-seat a live floater");
    }

    @Test
    void motionIsABallisticArcInScreenSpace() {
        MFloatingText texts = new MFloatingText();
        MFloatingText.Style style = MFloatingText.Style.number();
        MFloatingText.Floater f = texts.spawn("12", style, 200f, 150f);

        texts.update(0.1f);
        float[] early = f.position(1f);
        assertTrue(early[1] < 150f, "it is thrown upward first");
        assertTrue(early[0] > 200f, "the first spawn drifts right (the side comes from the spawn counter)");
        // Serial 0: launch = riseSpeed, gravity = riseSpeed × 520/175.
        float expectedY = 150f - style.riseSpeed() * 0.1f + 0.5f * style.riseSpeed() * (520f / 175f) * 0.01f;
        assertEquals(expectedY, early[1], 0.01f);

        texts.update(0.7f);
        assertTrue(f.position(1f)[1] > early[1], "then gravity brings it back down");
    }

    @Test
    void consecutiveSpawnsAlternateSides() {
        MFloatingText texts = new MFloatingText();
        MFloatingText.Floater a = texts.spawn("1", MFloatingText.Style.number(), 100f, 200f);
        MFloatingText.Floater b = texts.spawn("2", MFloatingText.Style.number(), 300f, 200f);
        texts.update(0.2f);

        assertTrue(a.position(1f)[0] > 100f);
        assertTrue(b.position(1f)[0] < 300f);
    }

    // ── Lanes and stagger ────────────────────────────────────────────────────

    @Test
    void lanesKeepSimultaneousSpawnsApart() {
        MFloatingText texts = new MFloatingText();
        MFloatingText.Floater number = texts.spawn("31", MFloatingText.Style.number(), 240f, 160f);
        MFloatingText.Floater word = texts.spawn("PERFECT", MFloatingText.Style.word(), 240f, 160f);
        MFloatingText.Floater status = texts.spawn("Chilled", MFloatingText.Style.minor(), 240f, 160f);

        assertTrue(number.started() && word.started() && status.started(),
                "different lanes are different bursts: nobody waits");
        float ny = number.origin(1f)[1], wy = word.origin(1f)[1], sy = status.origin(1f)[1];
        assertTrue(ny - wy >= 50f, "the word lane sits above the number");
        assertTrue(sy - ny >= 50f, "the status lane sits below it");
        assertEquals(2f * (ny - wy), number.origin(2f)[1] - word.origin(2f)[1], EPS, "lane offsets follow the scale");
    }

    @Test
    void aBurstOnOneAnchorIsStaggeredInTimeAndFannedInSpace() {
        MFloatingText texts = new MFloatingText();
        MFloatingText.Floater a = texts.spawn("5", MFloatingText.Style.number(), 240f, 160f);
        MFloatingText.Floater b = texts.spawn("6", MFloatingText.Style.number(), 244f, 158f);
        MFloatingText.Floater c = texts.spawn("7", MFloatingText.Style.number(), 240f, 160f);

        assertEquals(0, a.slot());
        assertEquals(1, b.slot());
        assertEquals(2, c.slot());
        assertTrue(a.started());
        assertFalse(b.started());
        assertFalse(c.started());
        float[] oa = a.origin(1f), ob = b.origin(1f), oc = c.origin(1f);
        assertTrue(Math.abs(oa[0] - ob[0]) + Math.abs(oa[1] - ob[1]) > 25f, "slot 1 is moved off slot 0");
        assertTrue(Math.abs(ob[0] - oc[0]) + Math.abs(ob[1] - oc[1]) > 25f, "slot 2 is moved off slot 1");

        texts.update(MFloatingText.STAGGER_SECONDS + 0.001f);
        assertTrue(b.started());
        assertFalse(c.started());
        assertEquals(0.001f, b.age(), 1.0e-4f, "the rest of the step after its delay is flight time");
        texts.update(MFloatingText.STAGGER_SECONDS);
        assertTrue(c.started());
    }

    @Test
    void aQuietAnchorForgetsItsBurstAndFarAnchorsNeverShareOne() {
        MFloatingText texts = new MFloatingText();
        texts.spawn("5", MFloatingText.Style.number(), 240f, 160f);
        MFloatingText.Floater elsewhere = texts.spawn("6", MFloatingText.Style.number(), 240f + 200f, 160f);
        assertEquals(0, elsewhere.slot(), "a different anchor is a different burst");

        texts.update(MFloatingText.STAGGER_MEMORY_SECONDS + 0.01f);
        assertEquals(0, texts.spawn("7", MFloatingText.Style.number(), 240f, 160f).slot(),
                "after a pause the next hit starts a new burst");
    }

    // ── Cap and lifetime ─────────────────────────────────────────────────────

    @Test
    void theCapDropsTheOldest() {
        MFloatingText texts = new MFloatingText();
        for (int i = 0; i < 30; i++) texts.spawn("n" + i, MFloatingText.Style.number(), 10f + i * 100f, 50f);
        assertEquals(MFloatingText.DEFAULT_CAP, texts.liveCount());
        assertEquals("n6", texts.live().get(0).text());

        texts.cap(3);
        assertEquals(3, texts.liveCount());
        assertEquals("n27", texts.live().get(0).text());
        texts.spawn("new", MFloatingText.Style.number(), 0f, 0f);
        assertEquals(3, texts.liveCount());
        assertEquals("new", texts.live().get(2).text());

        texts.cap(0);
        assertEquals(1, texts.liveCount(), "the cap never goes below one");
    }

    @Test
    void aFloaterLivesForItsLifetimeAndFadesAtTheEnd() {
        MFloatingText texts = new MFloatingText();
        MFloatingText.Floater f = texts.spawn("12", MFloatingText.Style.number().withLifetime(1f), 200f, 150f);

        texts.update(0.5f);
        assertEquals(1f, f.alpha(), EPS, "opaque for the first 55%");
        texts.update(0.3f);
        assertTrue(f.alpha() > 0f && f.alpha() < 1f, "fading");
        texts.update(0.19f);
        assertEquals(1, texts.liveCount());
        texts.update(0.02f);
        assertEquals(0, texts.liveCount());
    }

    @Test
    void oneHugeStepClearsEverythingAndClearResetsTheCounter() {
        MFloatingText texts = new MFloatingText();
        for (int i = 0; i < 5; i++) texts.spawn("x", MFloatingText.Style.number(), 200f, 150f);
        texts.update(1000f);
        assertEquals(0, texts.liveCount());

        MFloatingText fresh = new MFloatingText();
        MFloatingText.Floater first = fresh.spawn("1", MFloatingText.Style.number(), 100f, 200f);
        fresh.update(0.2f);

        MFloatingText.Floater late = texts.spawn("1", MFloatingText.Style.number(), 100f, 200f);
        texts.update(0.2f);
        assertTrue(late.position(1f)[0] < 100f, "the sixth spawn scatters the other way from the first");
        texts.clear();
        assertEquals(0, texts.liveCount());
        MFloatingText.Floater again = texts.spawn("1", MFloatingText.Style.number(), 100f, 200f);
        texts.update(0.2f);
        assertEquals(0, again.slot());
        assertArrayEquals(first.position(1f), again.position(1f), 0f, "after clear() the scatter sequence starts over");
    }

    // ── Band ─────────────────────────────────────────────────────────────────

    @Test
    void theWholeArcStaysInsideTheBand() {
        float[] anchors = {-500f, 0f, 101f, 200f, 299f, 900f};
        for (float scale : new float[]{1f, 1.5f}) {
            for (float anchorY : anchors) {
                MFloatingText texts = new MFloatingText().band(100f, 300f);
                MFloatingText.Floater[] all = {
                        texts.spawn("12", MFloatingText.Style.number(), 200f, anchorY),
                        texts.spawn("99!", MFloatingText.Style.emphasis(), 200f, anchorY),
                        texts.spawn("GOOD", MFloatingText.Style.word(), 200f, anchorY),
                        texts.spawn("Wet", MFloatingText.Style.minor(), 200f, anchorY)};
                for (int step = 0; step < 100; step++) {
                    for (MFloatingText.Floater f : all) {
                        if (!texts.live().contains(f)) continue;   // expired: no longer drawn
                        float y = f.position(scale)[1];
                        assertTrue(y >= 100f - EPS && y <= 300f + EPS,
                                "y=" + y + " left the band (anchor " + anchorY + ", scale " + scale + ", step " + step + ")");
                    }
                    texts.update(0.01f);
                }
            }
        }
    }

    @Test
    void anAnchorAlreadyInsideTheBandIsNotMoved() {
        MFloatingText texts = new MFloatingText().band(0f, 1000f);
        assertArrayEquals(new float[]{200f, 500f},
                texts.spawn("12", MFloatingText.Style.number(), 200f, 500f).origin(1f), EPS);
    }

    @Test
    void theBandIsCapturedAtSpawn() {
        MFloatingText texts = new MFloatingText().band(100f, 300f);
        MFloatingText.Floater f = texts.spawn("12", MFloatingText.Style.number(), 200f, 900f);
        float[] before = f.origin(1f);
        texts.band(0f, 2000f);
        assertArrayEquals(before, f.origin(1f), 0f, "a live floater keeps the band it was born under");
        texts.band(Float.NaN, 3f).band(10f, 5f);   // nonsense clears it, never throws
        assertEquals(900f, texts.spawn("13", MFloatingText.Style.number(), 600f, 900f).origin(1f)[1], EPS);
    }

    @Test
    void sidesKeepTheStartAndTheDrawnWordInside() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MFloatingText texts = new MFloatingText().sides(0f, W);
        MFloatingText.Floater f = texts.spawn("A LONG REFUSAL", MFloatingText.Style.minor(), -200f, 160f);
        assertEquals(0f, f.origin(1f)[0], EPS);

        texts.render(fx.ui, 1f);
        assertTrue(fx.countPainted(0, 100, W, 260) > 100, "the word is on screen although its centre is at the edge");
        assertEquals(0, fx.countPainted(0, 100, 2, 260), "and it is not cut off by the left edge");
    }

    // ── Painting ─────────────────────────────────────────────────────────────

    @Test
    void itPaintsStartedFloatersOnlyAndNothingAfterClear() {
        RasterUiFixture one = new RasterUiFixture(W, H);
        RasterUiFixture two = new RasterUiFixture(W, H);
        MFloatingText texts = new MFloatingText();
        texts.spawn("128", MFloatingText.Style.number(), 240f, 160f);
        texts.render(one.ui, 1f);
        assertTrue(one.countPainted(180, 120, 300, 200) > 80, "the number is drawn around its anchor");
        assertEquals(0, one.countPainted(0, 0, 100, H), "and nowhere else");

        texts.spawn("129", MFloatingText.Style.number(), 240f, 160f);   // staggered: not started yet
        texts.render(two.ui, 1f);
        assertEquals(0, one.diff(two, 0, 0, W, H), "a floater still in its stagger delay is not drawn");

        texts.clear();
        RasterUiFixture cleared = new RasterUiFixture(W, H);
        texts.render(cleared.ui, 1f);
        assertEquals(0, cleared.countPainted(0, 0, W, H));
    }

    @Test
    void stylesPopAndFadeAreVisible() {
        MFloatingText.Style plain = MFloatingText.Style.number();
        MFloatingText.Style flat = new MFloatingText.Style(plain.color(), plain.fontSize(), plain.lane(), 0f, 0f, 0.9f, false);
        MFloatingText.Style popping = new MFloatingText.Style(plain.color(), plain.fontSize(), plain.lane(), 0f, 0f, 0.9f, true);

        RasterUiFixture a = new RasterUiFixture(W, H);
        RasterUiFixture b = new RasterUiFixture(W, H);
        RasterUiFixture gold = new RasterUiFixture(W, H);
        RasterUiFixture faded = new RasterUiFixture(W, H);
        MFloatingText still = new MFloatingText();
        still.spawn("128", flat, 240f, 160f);
        still.render(a.ui, 1f);
        MFloatingText pop = new MFloatingText();
        pop.spawn("128", popping, 240f, 160f);
        pop.render(b.ui, 1f);
        assertTrue(b.countPainted(0, 0, W, H) > a.countPainted(0, 0, W, H), "the pop lands bigger");

        MFloatingText accent = new MFloatingText();
        accent.spawn("128", flat.withColor(MStyle.TEXT_ACCENT), 240f, 160f);
        accent.render(gold.ui, 1f);
        assertTrue(a.diff(gold, 0, 0, W, H) > 40, "colour is the caller's");

        still.update(0.8f);
        still.render(faded.ui, 1f);
        assertTrue(a.diff(faded, 0, 0, W, H) > 40, "late in its life it has faded");
        assertEquals(1.45f, pop.live().get(0).popScale(), EPS);
        pop.update(0.12f);
        assertEquals(1f, pop.live().get(0).popScale(), EPS, "settled after the pop");
        assertEquals(1f, still.live().get(0).popScale(), EPS);
    }

    @Test
    void theScaleParameterScalesTheText() {
        MFloatingText.Style flat = new MFloatingText.Style(MStyle.TEXT_PRIMARY, 20f, MFloatingText.Lane.NUMBER, 0f, 0f, 1f, false);
        RasterUiFixture small = new RasterUiFixture(W, H);
        RasterUiFixture large = new RasterUiFixture(W, H);
        MFloatingText texts = new MFloatingText();
        texts.spawn("128", flat, 240f, 160f);
        texts.render(small.ui, 1f);
        texts.render(large.ui, 2f);

        int smallArea = small.countPainted(0, 0, W, H);
        assertTrue(smallArea > 0);
        assertTrue(large.countPainted(0, 0, W, H) > 2.5f * smallArea, "twice the scale, about four times the ink");
    }

    // ── Determinism and degenerate input ─────────────────────────────────────

    @Test
    void twoInstancesFedTheSameInputPaintIdentically() {
        RasterUiFixture a = new RasterUiFixture(W, H);
        RasterUiFixture b = new RasterUiFixture(W, H);
        MFloatingText[] both = {new MFloatingText().band(20f, 300f), new MFloatingText().band(20f, 300f)};
        for (MFloatingText texts : both) {
            texts.spawn("31", MFloatingText.Style.number(), 240f, 160f);
            texts.spawn("PERFECT", MFloatingText.Style.word(), 240f, 160f);
            texts.spawn("44!", MFloatingText.Style.emphasis(), 240f, 160f);
            texts.update(0.05f);
            texts.spawn("+3 Wood", MFloatingText.Style.minor(), 100f, 250f);
            texts.update(0.11f);
            texts.update(0.2f);
        }
        both[0].render(a.ui, 1.25f);
        both[1].render(b.ui, 1.25f);

        assertTrue(a.countPainted(0, 0, W, H) > 200);
        assertEquals(0, a.diff(b, 0, 0, W, H));
        for (int i = 0; i < both[0].liveCount(); i++) {
            assertArrayEquals(both[0].live().get(i).position(1.25f), both[1].live().get(i).position(1.25f), 0f);
        }
    }

    @Test
    void degenerateInputIsIgnoredNotThrown() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MFloatingText texts = new MFloatingText();
        assertNull(texts.spawn(null, MFloatingText.Style.number(), 1f, 1f));
        assertNull(texts.spawn("", MFloatingText.Style.number(), 1f, 1f));
        assertNull(texts.spawn("x", null, 1f, 1f));
        assertNull(texts.spawn("x", MFloatingText.Style.number(), Float.NaN, 1f));
        assertNull(texts.spawn("x", MFloatingText.Style.number(), 1f, Float.POSITIVE_INFINITY));
        assertEquals(0, texts.liveCount());

        MFloatingText.Style broken = new MFloatingText.Style(0, Float.NaN, null, Float.NaN, -4f, 0f, true);
        assertEquals(MFloatingText.Lane.NUMBER, broken.lane());
        assertTrue(broken.lifetime() > 0f && broken.fontSize() > 0f);
        assertEquals(0f, broken.riseSpeed());
        assertEquals(0f, broken.spread());

        texts.spawn("x", broken, 100f, 100f);
        texts.spawn("y", MFloatingText.Style.number(), 100f, 100f);
        assertDoesNotThrow(() -> {
            texts.render(null, 1f);
            texts.render(fx.ui, 0f);
            texts.render(fx.ui, -2f);
            texts.render(fx.ui, Float.NaN);
            texts.update(Float.POSITIVE_INFINITY);
        });
        assertEquals(0, fx.countPainted(0, 0, W, H), "a transparent colour and unusable scales draw nothing");
        assertDoesNotThrow(() -> texts.render(fx.ui, 1f));
    }
}
