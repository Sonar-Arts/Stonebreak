package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import org.junit.jupiter.api.Test;

import static com.stonebreak.ui.focusBattle.timed.TimedScenes.H;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.SCALE;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.UI_SCALE;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.W;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E10 on a CPU canvas: cell states, the draining underline, slide and the FLAWLESS flash. */
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
        layers.comboStripLayer().paint(fx.ui, fx.canvas, W, H, SCALE, UI_SCALE, view,
                com.stonebreak.ui.focusBattle.BattleHudAnimState.NEUTRAL, null);
        return fx;
    }

    private static float[] cell(int i) {
        return TimedLayout.comboCellRect(STRIP, i, 6, SCALE);
    }

    private static int centre(BattleRasterFixture fx, float[] rect, float fx01, float fy01) {
        return fx.bitmap.getColor(Math.round(rect[0] + rect[2] * fx01), Math.round(rect[1] + rect[3] * fy01));
    }

    @Test
    void theStripFillsItsLayoutRectAndNothingElse() {
        FakeBattleView view = TimedScenes.comboView(0, 0.1f);
        BattleRasterFixture fx = stripOnly(opened(view), view);
        assertTrue(fx.countPainted(STRIP) > STRIP[2] * STRIP[3] * 0.9f, "a battle window fills the reserved rect");
        assertEquals(0, fx.countPainted(0, 0, W, (int) STRIP[1] - 8), "nothing above it");
        assertEquals(0, fx.countPainted(0, (int) (STRIP[1] + STRIP[3]) + 8, W, H), "nothing below it");
    }

    @Test
    void resolvedCurrentAndUpcomingCellsLookDifferent() {
        // P, G resolved by the view; index 2 awaiting; 3..5 upcoming. Then a MISS on 2.
        FakeBattleView view = TimedScenes.comboView(2, 0.2f, TimedGrade.PERFECT, TimedGrade.GOOD);
        TimedInputLayers layers = opened(view);
        BattleRasterFixture fx = stripOnly(layers, view);

        // Stamps: a corner of the cell interior, clear of the arrow and the key letter.
        assertEquals(TimedTheme.PERFECT, centre(fx, cell(0), 0.15f, 0.2f), "PERFECT is stamped gold");
        assertEquals(0xFFF2F6FA, centre(fx, cell(1), 0.15f, 0.2f), "GOOD is stamped white");
        assertTrue(((centre(fx, cell(2), 0.15f, 0.2f) >> 8) & 0xFF) < 0x40, "current keeps the dark fill");
        assertEquals(TimedTheme.CELL_ARROW, centre(fx, cell(2), 0.44f, 0.46f), "current arrow is full white");
        assertTrue(centre(fx, cell(3), 0.44f, 0.46f) != TimedTheme.CELL_ARROW, "upcoming arrows are dimmed");

        // The current cell is enlarged: painted pixels beyond its rest rect, unlike an upcoming one.
        float[] cur = cell(2), up = cell(4);
        float grow = cur[2] * (TimedLayout.COMBO_CURRENT_SCALE - 1f) / 2f;
        int aboveCurrent = centre(fx, new float[]{cur[0], cur[1] - grow, cur[2], grow}, 0.5f, 0.5f);
        int aboveUpcoming = centre(fx, new float[]{up[0], up[1] - grow, up[2], grow}, 0.5f, 0.5f);
        assertTrue(aboveCurrent != aboveUpcoming, "current prompt is drawn larger");

        FakeBattleView after = TimedScenes.comboView(2, 0.2f, TimedGrade.PERFECT, TimedGrade.GOOD);
        after.prompt = null;
        TimedScenes.frame(layers, after, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.MISS, 2),
                new BattleEvent.ComboFinished(2, false));
        TimedScenes.frame(layers, after, 0.3f);
        BattleRasterFixture missed = stripOnly(layers, after);
        assertEquals(TimedTheme.MISS_DARK, centre(missed, cell(2), 0.15f, 0.2f), "MISS is stamped dark red");
        assertTrue(missed.countExactly(TimedTheme.MISS, (int) cell(2)[0], (int) cell(2)[1],
                (int) (cell(2)[0] + cell(2)[2]), (int) (cell(2)[1] + cell(2)[3])) > 60, "with a red arrow + rim");
        assertTrue(missed.diff(fx, cell(2)) > 600);
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
        BattleRasterFixture fx = stripOnly(layers, gap);
        assertEquals(TimedTheme.PERFECT, centre(fx, cell(0), 0.15f, 0.2f));
        assertTrue(fx.diff(stripOnly(opened(TimedScenes.comboView(5, 0f)), TimedScenes.comboView(5, 0f)), cell(1))
                > 40, "next cell is lit, unlike a plain upcoming one");
        float[] track = TimedLayout.comboTimerTrack(cell(1), SCALE);
        assertEquals(0, fx.countExactly(TimedTheme.TIMER_FULL, (int) track[0], (int) track[1],
                (int) (track[0] + track[2]), (int) (track[1] + track[3])), "no timer until it opens");

        FakeBattleView second = TimedScenes.comboView(1, 0.05f, TimedGrade.PERFECT);
        TimedScenes.frame(layers, second, 0.016f);
        assertEquals(1, layers.state().comboIndex);
        assertEquals(TimedGrade.PERFECT, layers.state().comboResults[0], "and the earlier stamp is kept");
    }

    @Test
    void theStampPopsThenSettles() {
        FakeBattleView view = TimedScenes.comboView(1, 0.0f, TimedGrade.PERFECT);
        TimedInputLayers layers = opened(TimedScenes.comboView(0, 0.3f));
        TimedScenes.frame(layers, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.PERFECT, 0));
        BattleRasterFixture fresh = stripOnly(layers, view);
        TimedScenes.frame(layers, view, TimedInputState.COMBO_STAMP_SECONDS + 0.01f);
        FakeBattleView same = TimedScenes.comboView(1, 0.0f, TimedGrade.PERFECT);
        BattleRasterFixture settled = stripOnly(layers, same);
        float[] c = TimedLayout.scaled(cell(0), 1.35f);
        assertTrue(fresh.diff(settled, c) > 150, "a fresh stamp lands oversized");
    }

    @Test
    void theTimerUnderlineDrains() {
        float[] track = TimedLayout.comboTimerTrack(cell(0), SCALE);
        int previous = Integer.MAX_VALUE;
        for (float elapsed : new float[]{0.0f, 0.3f, 0.6f, 0.88f}) {
            FakeBattleView view = TimedScenes.comboView(0, elapsed);
            BattleRasterFixture fx = stripOnly(opened(view), view);
            float left = TimedLayout.comboTimeLeft(elapsed, 0.9f);
            int fill = 0;
            int y = Math.round(track[1] + track[3] / 2f);
            for (int x = Math.round(track[0]) + 1; x < Math.round(track[0] + track[2]) - 1; x++) {
                if (fx.bitmap.getColor(x, y) == ComboStripOverlay.timerColor(left)) fill++;
            }
            assertEquals((track[2] - 2f) * left, fill, 2.5f, "fill length at " + elapsed + " s");
            assertTrue(fill < previous);
            previous = fill;
        }
        // Only the current cell has one.
        FakeBattleView view = TimedScenes.comboView(0, 0.3f);
        BattleRasterFixture fx = stripOnly(opened(view), view);
        float[] other = TimedLayout.comboTimerTrack(cell(3), SCALE);
        float[] mine = TimedLayout.comboTimerTrack(cell(0), SCALE);
        assertTrue(fx.countExactly(ComboStripOverlay.timerColor(TimedLayout.comboTimeLeft(0.3f, 0.9f)),
                (int) other[0], (int) other[1], (int) (other[0] + other[2]), (int) (other[1] + other[3])) == 0);
        assertTrue(fx.countExactly(ComboStripOverlay.timerColor(TimedLayout.comboTimeLeft(0.3f, 0.9f)),
                (int) mine[0], (int) mine[1], (int) (mine[0] + mine[2]), (int) (mine[1] + mine[3])) > 20);
    }

    @Test
    void arrowsPointTheWayTheirKeyDoes() {
        // SEQUENCE starts LEFT, UP, RIGHT, DOWN. Probe the tip side of each glyph box vs the tail side.
        FakeBattleView view = TimedScenes.comboView(5, 0.1f, TimedGrade.GOOD, TimedGrade.GOOD, TimedGrade.GOOD,
                TimedGrade.GOOD, TimedGrade.GOOD);
        BattleRasterFixture fx = stripOnly(opened(view), view);
        float[][] tipOffset = {{-0.22f, 0f}, {0f, -0.22f}, {0.22f, 0f}, {0f, 0.22f}};
        for (int i = 0; i < 4; i++) {
            float[] c = cell(i);
            float gx = c[0] + c[2] * 0.44f, gy = c[1] + c[3] * 0.46f;
            // Across the head, perpendicular to travel, the arrow is wider than across the shaft.
            float px = tipOffset[i][1] != 0f ? 1f : 0f, py = tipOffset[i][0] != 0f ? 1f : 0f;
            int head = inkAcross(fx, gx + tipOffset[i][0] * c[2] * 0.3f, gy + tipOffset[i][1] * c[3] * 0.3f, px, py, c[2]);
            int tail = inkAcross(fx, gx - tipOffset[i][0] * c[2] * 0.9f, gy - tipOffset[i][1] * c[3] * 0.9f, px, py, c[2]);
            assertTrue(head > tail, TimedScenes.SEQUENCE.get(i) + " arrow head faces its direction (" + head + " vs " + tail + ")");
        }
        assertEquals("W", ComboStripOverlay.keyFor(ComboDirection.UP));
        assertEquals("A", ComboStripOverlay.keyFor(ComboDirection.LEFT));
        assertEquals("S", ComboStripOverlay.keyFor(ComboDirection.DOWN));
        assertEquals("D", ComboStripOverlay.keyFor(ComboDirection.RIGHT));
    }

    private static int inkAcross(BattleRasterFixture fx, float x, float y, float px, float py, float cellSize) {
        int ink = 0;
        for (int k = -(int) (cellSize * 0.3f); k <= (int) (cellSize * 0.3f); k++) {
            if (fx.bitmap.getColor(Math.round(x + px * k), Math.round(y + py * k)) == TimedTheme.CELL_STAMP_INK) ink++;
        }
        return ink;
    }

    @Test
    void theHitCounterCounts() {
        FakeBattleView none = TimedScenes.comboView(0, 0.1f);
        FakeBattleView three = TimedScenes.comboView(3, 0.1f, TimedGrade.PERFECT, TimedGrade.GOOD, TimedGrade.PERFECT);
        float[] counter = TimedLayout.comboCounterRect(STRIP, SCALE);
        // The counter follows blows that LANDED (Impact), which the authored clip delivers a beat after
        // each graded input, not the inputs themselves.
        TimedInputLayers landed = opened(three);
        for (int i = 0; i < 3; i++) {
            TimedScenes.frame(landed, three, 0.016f,
                    new BattleEvent.Impact(com.stonebreak.battle.api.CombatantId.MONK,
                            com.stonebreak.battle.api.CombatantId.ARCHON, i, 6));
        }
        BattleRasterFixture a = stripOnly(opened(none), none), b = stripOnly(landed, three);
        assertTrue(a.diff(b, counter) > 60, "0 HITS vs 3 HITS");
        assertTrue(a.diff(stripOnly(opened(three), three), counter) == 0
                        || stripOnly(opened(three), three).diff(b, counter) > 60,
                "graded-but-not-yet-landed inputs do not advance the counter");
    }

    @Test
    void itSlidesInOnOpenAndOutAfterTheString() {
        FakeBattleView view = TimedScenes.comboView(0, 0.0f);
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, view, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
        assertEquals(0, stripOnly(layers, view).countPainted(0, 0, W, H), "frame 0: still off-screen");
        TimedScenes.frame(layers, view, TimedInputState.COMBO_SLIDE_SECONDS * 0.4f);
        BattleRasterFixture mid = stripOnly(layers, view);
        assertTrue(mid.countPainted(0, (int) (STRIP[1] + STRIP[3]), W, H) > 1000, "partway: below its resting place");
        TimedScenes.frame(layers, view, TimedInputState.COMBO_SLIDE_SECONDS);
        BattleRasterFixture in = stripOnly(layers, view);
        assertEquals(0, in.countPainted(0, (int) (STRIP[1] + STRIP[3]) + 8, W, H));

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
        BattleRasterFixture a = stripOnly(flawless, view), b = stripOnly(plain, view);
        float[] word = ComboStripOverlay.flawlessWordRect(STRIP, SCALE);
        assertTrue(a.countExactly(TimedTheme.PERFECT, (int) word[0], (int) word[1], (int) (word[0] + word[2]),
                (int) (word[1] + word[3])) > 300, "gold FLAWLESS! above the strip");
        assertEquals(0, b.countPainted(word), "only when flawless");
        assertTrue(a.diff(b, STRIP) > 5000, "and the strip itself flashes");

        TimedScenes.frame(flawless, view, TimedInputState.FLAWLESS_SECONDS);
        assertEquals(0, stripOnly(flawless, view).countPainted(word), "the flash ends");
    }
}
