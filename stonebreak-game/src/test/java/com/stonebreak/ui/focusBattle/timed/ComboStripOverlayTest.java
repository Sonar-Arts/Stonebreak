package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MPromptStrip;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MSymbol;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import org.junit.jupiter.api.Test;

import static com.stonebreak.ui.focusBattle.timed.TimedScenes.H;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.SCALE;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.UI_SCALE;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.W;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E10: the adapter between the battle's combo string and the library's {@link MPromptStrip}. The
 * widget's own drawing is covered by the library's tests; these pin what the battle feeds it
 * (steps, states, timer, counter, slide, the FLAWLESS word) and that it lands in the layout's rect.
 */
class ComboStripOverlayTest {

    private static final float[] STRIP = FocusBattleLayout.comboStripRect(W, H, UI_SCALE);

    /** Strip fully slid in, showing {@code view}. */
    private static TimedInputLayers opened(FakeBattleView view) {
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, view, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
        TimedScenes.frame(layers, view, 0.5f);
        return layers;
    }

    private static BattleRasterFixture stripOnly(TimedInputLayers layers, FakeBattleView view) {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        layers.comboStripLayer().paint(fx.ui, fx.canvas, W, H, SCALE, UI_SCALE, view, null, null);
        return fx;
    }

    /** The widget, placed exactly as the layer places it. */
    private static MPromptStrip widget(TimedInputLayers layers) {
        return layers.comboStrip().layout(W, H, SCALE, UI_SCALE);
    }

    private static int probe(BattleRasterFixture fx, float[] rect, float fx01, float fy01) {
        return fx.bitmap.getColor(Math.round(rect[0] + rect[2] * fx01), Math.round(rect[1] + rect[3] * fy01));
    }

    @Test
    void theStripFillsItsLayoutRectAndNothingElse() {
        FakeBattleView view = TimedScenes.comboView(0, 0.1f);
        TimedInputLayers layers = opened(view);
        BattleRasterFixture fx = stripOnly(layers, view);
        MPromptStrip strip = widget(layers);
        assertEquals(STRIP[0], strip.x(), 0f);
        assertEquals(STRIP[1], strip.y(), 0f);
        assertEquals(STRIP[2], strip.width(), 0f);
        assertEquals(STRIP[3], strip.height(), 0f);
        assertEquals(0f, strip.slideOffset(), 0f, "at rest once slid in");
        assertTrue(fx.countPainted(STRIP) > STRIP[2] * STRIP[3] * 0.9f, "a HUD frame fills the reserved rect");
        assertEquals(0, fx.countPainted(0, 0, W, (int) STRIP[1] - 8), "nothing above it");
        assertEquals(0, fx.countPainted(0, (int) (STRIP[1] + STRIP[3]) + 8, W, H), "nothing below it");
    }

    @Test
    void directionsBecomeArrowGlyphsWithTheirKeyLetters() {
        FakeBattleView view = TimedScenes.comboView(0, 0.1f);
        MPromptStrip strip = widget(opened(view));
        assertEquals(TimedScenes.SEQUENCE.size(), strip.steps().size());
        for (int i = 0; i < TimedScenes.SEQUENCE.size(); i++) {
            ComboDirection dir = TimedScenes.SEQUENCE.get(i);
            assertSame(ComboStripOverlay.glyphFor(dir), strip.steps().get(i).glyph());
            assertEquals(ComboStripOverlay.keyFor(dir), strip.steps().get(i).key());
        }
        assertSame(MSymbol.ARROW_UP, ComboStripOverlay.glyphFor(ComboDirection.UP));
        assertSame(MSymbol.ARROW_LEFT, ComboStripOverlay.glyphFor(ComboDirection.LEFT));
        assertSame(MSymbol.ARROW_DOWN, ComboStripOverlay.glyphFor(ComboDirection.DOWN));
        assertSame(MSymbol.ARROW_RIGHT, ComboStripOverlay.glyphFor(ComboDirection.RIGHT));
        assertEquals("W", ComboStripOverlay.keyFor(ComboDirection.UP));
        assertEquals("A", ComboStripOverlay.keyFor(ComboDirection.LEFT));
        assertEquals("S", ComboStripOverlay.keyFor(ComboDirection.DOWN));
        assertEquals("D", ComboStripOverlay.keyFor(ComboDirection.RIGHT));
    }

    @Test
    void gradesBecomeStampsAndTheAwaitedPromptIsCurrent() {
        // P, G resolved by the view; index 2 awaiting; 3..5 upcoming. Then a MISS on 2.
        FakeBattleView view = TimedScenes.comboView(2, 0.2f, TimedGrade.PERFECT, TimedGrade.GOOD);
        TimedInputLayers layers = opened(view);
        MPromptStrip strip = widget(layers);
        assertEquals(MPromptStrip.State.RESOLVED_BEST, strip.stateOf(0), "PERFECT");
        assertEquals(MPromptStrip.State.RESOLVED_OK, strip.stateOf(1), "GOOD");
        assertEquals(MPromptStrip.State.CURRENT, strip.stateOf(2));
        for (int i = 3; i < 6; i++) assertEquals(MPromptStrip.State.UPCOMING, strip.stateOf(i));
        assertEquals(2, strip.current());

        // On screen that is the library's look: a corner of each cell interior, clear of glyph and key.
        BattleRasterFixture fx = stripOnly(layers, view);
        assertEquals(MStyle.TEXT_ACCENT, probe(fx, strip.cellRect(0), 0.15f, 0.2f), "PERFECT is stamped gold");
        assertEquals(MStyle.TEXT_PRIMARY, probe(fx, strip.cellRect(1), 0.15f, 0.2f), "GOOD is stamped pale");
        assertEquals(MStyle.BUTTON_FILL_HI, probe(fx, strip.cellRect(2), 0.15f, 0.2f), "current is the lit button fill");
        assertEquals(MStyle.BUTTON_FILL_DIS, probe(fx, strip.cellRect(4), 0.15f, 0.2f), "upcoming is dimmed");

        FakeBattleView after = TimedScenes.comboView(2, 0.2f, TimedGrade.PERFECT, TimedGrade.GOOD);
        after.prompt = null;
        TimedScenes.frame(layers, after, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.MISS, 2),
                new BattleEvent.ComboFinished(2, false));
        TimedScenes.frame(layers, after, 0.3f);
        assertEquals(MPromptStrip.State.RESOLVED_FAIL, strip.stateOf(2), "MISS");
        assertEquals(-1, strip.current(), "nothing is awaited once the string is over");
        BattleRasterFixture missed = stripOnly(layers, after);
        assertTrue(missed.diff(fx, strip.cellRect(2)) > 600);
        assertEquals(MStyle.TEXT_ACCENT, probe(missed, strip.cellRect(0), 0.15f, 0.2f), "earlier stamps are kept");
    }

    @Test
    void betweenPromptsTheNextCellIsLitButNotLive() {
        // The model raises PromptOpened once per string; between two prompts the view has none.
        FakeBattleView first = TimedScenes.comboView(0, 0.2f);
        TimedInputLayers layers = opened(first);
        FakeBattleView gap = TimedScenes.comboView(0, 0f);
        gap.prompt = null;
        TimedScenes.frame(layers, gap, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.PERFECT, 0));
        TimedScenes.frame(layers, gap, 0.3f);
        assertTrue(layers.state().comboActive(), "the strip survives the gap");
        assertEquals(1, layers.state().comboNextIndex());

        MPromptStrip strip = widget(layers);
        assertEquals(MPromptStrip.State.RESOLVED_BEST, strip.stateOf(0));
        assertEquals(MPromptStrip.State.UPCOMING, strip.stateOf(1), "lit, but not yet awaiting input");
        assertEquals(0, strip.count(MPromptStrip.State.CURRENT));
        BattleRasterFixture fx = stripOnly(layers, gap);
        FakeBattleView plain = TimedScenes.comboView(5, 0f);
        assertTrue(fx.diff(stripOnly(opened(plain), plain), strip.cellRect(1)) > 40,
                "next cell is lit, unlike a plain upcoming one");
        assertEquals(MStyle.BUTTON_FILL, probe(fx, strip.cellRect(1), 0.15f, 0.2f));

        FakeBattleView second = TimedScenes.comboView(1, 0.05f, TimedGrade.PERFECT);
        TimedScenes.frame(layers, second, 0.016f);
        assertEquals(MPromptStrip.State.CURRENT, strip.stateOf(1));
        assertEquals(MPromptStrip.State.RESOLVED_BEST, strip.stateOf(0), "and the earlier stamp is kept");
    }

    @Test
    void aFreshGradeStartsTheWidgetsStampPop() {
        FakeBattleView view = TimedScenes.comboView(1, 0.0f, TimedGrade.PERFECT);
        TimedInputLayers layers = opened(TimedScenes.comboView(0, 0.3f));
        TimedScenes.frame(layers, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.PERFECT, 0));
        MPromptStrip strip = widget(layers);
        assertEquals(1.3f, strip.stampPop(0), 1.0e-4f, "lands oversized in the frame the grade arrives");
        BattleRasterFixture fresh = stripOnly(layers, view);
        TimedScenes.frame(layers, view, MPromptStrip.STAMP_SECONDS + 0.01f);
        assertEquals(1f, strip.stampPop(0), 1.0e-4f, "and settles through update(dt)");
        assertTrue(fresh.diff(stripOnly(layers, view), strip.cellRect(0)) > 100);
        // Re-feeding the same result must not restart it.
        TimedScenes.frame(layers, view, 0.016f);
        assertEquals(1f, strip.stampPop(0), 1.0e-4f);
    }

    @Test
    void theTimerIsTheShareOfTheStepThatIsLeft() {
        int previous = Integer.MAX_VALUE;
        for (float elapsed : new float[]{0.0f, 0.3f, 0.6f, 0.88f}) {
            FakeBattleView view = TimedScenes.comboView(0, elapsed);
            TimedInputLayers layers = opened(view);
            MPromptStrip strip = widget(layers);
            float left = 1f - elapsed / 0.9f;
            assertEquals(left, strip.timer(), 1.0e-5f);

            BattleRasterFixture fx = stripOnly(layers, view);
            float[] track = strip.timerRect(0);
            int color = MColor.ramp(left, MStyle.VITAL_CRIT, MStyle.VITAL_WARN, BattlePalette.ATB, 0f, 1f);
            int fill = 0, y = Math.round(track[1] + track[3] / 2f);
            for (int x = Math.round(track[0]) + 1; x < Math.round(track[0] + track[2]) - 1; x++) {
                if (fx.bitmap.getColor(x, y) == color) fill++;
            }
            assertEquals((track[2] - 2f) * left, fill, 2.5f, "fill length at " + elapsed + " s");
            assertTrue(fill < previous);
            previous = fill;
        }
        assertEquals(0f, ComboStripOverlay.timeLeft(0.1f, 0f), 0f, "a step with no duration has no time left");
        assertTrue(ComboStripOverlay.timeLeft(2f, 0.9f) < 0f, "overrun is left to the widget's clamp");
    }

    @Test
    void theCounterFollowsBlowsThatLanded() {
        FakeBattleView none = TimedScenes.comboView(0, 0.1f);
        FakeBattleView three = TimedScenes.comboView(3, 0.1f, TimedGrade.PERFECT, TimedGrade.GOOD, TimedGrade.PERFECT);
        // Impact events: the authored clip delivers each blow a beat after its graded input.
        TimedInputLayers landed = opened(three);
        for (int i = 0; i < 3; i++) {
            TimedScenes.frame(landed, three, 0.016f, new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, i, 6));
        }
        assertEquals(3, landed.state().comboLanded());
        assertEquals(0, opened(three).state().comboLanded(), "graded-but-not-yet-landed inputs do not count");
        assertEquals("0 HITS", ComboStripOverlay.counter(0));
        assertEquals("1 HIT", ComboStripOverlay.counter(1));
        assertEquals("3 HITS", ComboStripOverlay.counter(3));

        MPromptStrip strip = widget(landed);
        float[] last = strip.cellRect(5);
        int x0 = (int) (last[0] + last[2]) + 8;
        BattleRasterFixture a = stripOnly(opened(none), none), b = stripOnly(landed, three);
        assertTrue(a.diff(b, x0, (int) STRIP[1], (int) (STRIP[0] + STRIP[2]), (int) (STRIP[1] + STRIP[3])) > 60,
                "0 HITS vs 3 HITS in the counter area");
    }

    @Test
    void itSlidesInOnOpenAndOutAfterTheString() {
        FakeBattleView view = TimedScenes.comboView(0, 0.0f);
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, view, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
        assertEquals(0, stripOnly(layers, view).countPainted(0, 0, W, H), "frame 0: still off-screen");
        TimedScenes.frame(layers, view, TimedInputState.COMBO_SLIDE_SECONDS * 0.4f);
        MPromptStrip strip = widget(layers);
        assertEquals(layers.state().comboSlide(), strip.slide(), 0f, "the widget follows the state's slide");
        assertTrue(strip.slideOffset() > 0f);
        BattleRasterFixture mid = stripOnly(layers, view);
        assertTrue(mid.countPainted(0, (int) (STRIP[1] + STRIP[3]), W, H) > 1000, "partway: below its resting place");
        TimedScenes.frame(layers, view, TimedInputState.COMBO_SLIDE_SECONDS);
        BattleRasterFixture in = stripOnly(layers, view);
        assertEquals(0, in.countPainted(0, (int) (STRIP[1] + STRIP[3]) + 8, W, H));

        // Fully out means below the window, whatever the window size.
        strip.slide(0f);
        assertTrue(strip.y() + strip.slideOffset() >= H);
        strip.slide(layers.state().comboSlide());

        view.prompt = null;
        TimedScenes.frame(layers, view, 0.016f, new BattleEvent.ComboFinished(0, false));
        TimedScenes.frame(layers, view, TimedInputState.COMBO_HOLD_SECONDS - 0.1f);
        assertTrue(stripOnly(layers, view).countPainted(STRIP) > 1000, "held");
        TimedScenes.frame(layers, view, 0.2f);
        TimedScenes.frame(layers, view, 0.5f);
        assertEquals(0, stripOnly(layers, view).countPainted(0, 0, W, H), "gone");
    }

    @Test
    void aFlawlessStringFlashesGold() {
        FakeBattleView view = TimedScenes.comboView(5, 0.2f, TimedGrade.PERFECT, TimedGrade.PERFECT, TimedGrade.PERFECT,
                TimedGrade.PERFECT, TimedGrade.PERFECT);
        TimedInputLayers flawless = opened(view), plain = opened(view);
        view.prompt = null;
        for (TimedInputLayers l : new TimedInputLayers[]{flawless, plain}) {
            TimedScenes.frame(l, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.PERFECT, 5));
            TimedScenes.frame(l, view, 0.6f, new BattleEvent.ComboFinished(6, l == flawless));
            TimedScenes.frame(l, view, 0.3f);
        }
        assertEquals(0.3f, widget(flawless).flashAge(), 1.0e-5f, "the word starts when the string finishes");
        assertTrue(widget(plain).flashAge() < 0f);

        BattleRasterFixture a = stripOnly(flawless, view), b = stripOnly(plain, view);
        float[] word = widget(flawless).flashRect();
        assertTrue(a.countExactly(BattlePalette.FOCUS, (int) word[0], (int) word[1], (int) (word[0] + word[2]),
                (int) (word[1] + word[3])) > 300, "gold FLAWLESS! above the strip");
        assertEquals(0, b.countPainted(word), "only when flawless");
        assertTrue(a.diff(b, STRIP) > 5000, "and the strip itself flashes");

        TimedScenes.frame(flawless, view, ComboStripOverlay.FLAWLESS_SECONDS);
        assertTrue(widget(flawless).flashAge() < 0f);
        assertEquals(0, stripOnly(flawless, view).countPainted(word), "the flash ends");
    }

    @Test
    void aSecondStringStartsFromACleanStrip() {
        FakeBattleView view = TimedScenes.comboView(5, 0.2f, TimedGrade.PERFECT, TimedGrade.PERFECT, TimedGrade.PERFECT,
                TimedGrade.PERFECT, TimedGrade.PERFECT);
        TimedInputLayers layers = opened(view);
        view.prompt = null;
        TimedScenes.frame(layers, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.PERFECT, 5),
                new BattleEvent.ComboFinished(6, true));
        MPromptStrip strip = widget(layers);
        assertEquals(6, strip.count(MPromptStrip.State.RESOLVED_BEST));

        // Same directions again: the old stamps must not survive into the new string.
        FakeBattleView again = TimedScenes.comboView(0, 0f);
        TimedScenes.frame(layers, again, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
        assertEquals(0, strip.count(MPromptStrip.State.RESOLVED_BEST));
        assertEquals(MPromptStrip.State.CURRENT, strip.stateOf(0));
        assertTrue(strip.flashAge() < 0f, "nor the last string's FLAWLESS word");

        layers.reset();
        assertEquals(0, strip.steps().size(), "reset empties the widget too");
        assertEquals(0f, strip.slide(), 0f);
        assertFalse(layers.state().comboActive());
    }
}
