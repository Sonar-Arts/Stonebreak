package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleInput;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleScreenHost;
import com.stonebreak.battle.api.BattleStats;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.masonryUI.MResultCard;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.BattleHudRules;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleScreen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

/** E16: the result panel's delay, rise and count-up, its buttons, and the screen's RESULT input. */
class ResultPanelTest {

    private static final int W = 1280;
    private static final int H = 720;
    private static final float DT = 1f / 60f;
    private static final BattleStats STATS = new BattleStats(2400f, 96f, 4, 2, 7, 5, 6, 11);

    private static final class SilentInput implements BattleInput {
        final List<String> calls = new ArrayList<>();
        @Override public void introFinished() { calls.add("introFinished"); }
        @Override public boolean submit(BattleCommand command) { calls.add("submit " + command); return true; }
        @Override public void pressConfirm() { calls.add("confirm"); }
        @Override public void pressDirection(ComboDirection direction) { calls.add("direction " + direction); }
    }

    /** Records callbacks; {@code onRetry} lets a test re-bind from inside the callback as the stage does. */
    private static final class RecordingHost implements BattleScreenHost {
        final List<String> calls = new ArrayList<>();
        Runnable onRetry = () -> { };
        @Override public void skipIntro() { calls.add("skipIntro"); }
        @Override public void retry() { calls.add("retry"); onRetry.run(); }
        @Override public void exploreArena() { calls.add("exploreArena"); }
        @Override public void returnToWorld() { calls.add("returnToWorld"); }
        @Override public void openPauseMenu() { calls.add("openPauseMenu"); }
    }

    private FakeBattleView view;
    private SilentInput input;
    private RecordingHost host;
    private FocusBattleScreen screen;

    @BeforeEach
    void bindAScreen() {
        view = new FakeBattleView();
        input = new SilentInput();
        host = new RecordingHost();
        screen = new FocusBattleScreen(null);
        screen.bind(view, input, FakeBattleView.crucibleLayout(), host);
    }

    private static float uiScale() {
        return Settings.getInstance().getUiScale();
    }

    private void end(BattleOutcome outcome) {
        view.phase = BattlePhase.RESULT;
        view.outcome = outcome;
        view.stats = STATS;
        view.elapsedSeconds = 83f;
        view.events.add(new BattleEvent.Ended(outcome));
        screen.update(DT);
        view.events.clear();
    }

    private void run(float seconds) {
        for (int i = 0; i < Math.round(seconds / DT); i++) screen.update(DT);
    }

    private void endAndRaise(BattleOutcome outcome) {
        end(outcome);
        run(ResultPanel.delayFor(outcome) + ResultPanel.RISE_SECONDS + 0.05f);
        assertTrue(screen.resultPanel().interactive());
    }

    private boolean press(int key) {
        return screen.handleKeyInput(key, GLFW_PRESS, 0);
    }

    // ── timing ───────────────────────────────────────────────────────────────

    @Test
    void victoryWaitsForTheCinematicDefeatForTwoSeconds() {
        assertEquals(BattleHudRules.VICTORY_CINEMATIC_SECONDS, ResultPanel.delayFor(BattleOutcome.VICTORY), 0f);
        assertEquals(2f, ResultPanel.delayFor(BattleOutcome.DEFEAT), 0f);

        end(BattleOutcome.VICTORY);
        ResultPanel panel = screen.resultPanel();
        assertTrue(panel.ended());
        run(BattleHudRules.VICTORY_CINEMATIC_SECONDS - 0.1f);
        assertFalse(panel.visible(), "the camera keeps the moment for the whole victory cinematic");
        run(0.1f + ResultPanel.RISE_SECONDS / 2f);
        assertTrue(panel.visible());
        assertTrue(panel.rise() > 0f && panel.rise() < 1f, "rising");
        assertFalse(panel.interactive());
        run(ResultPanel.RISE_SECONDS);
        assertEquals(1f, panel.rise(), 1e-6f);
        assertTrue(panel.interactive());

        bindAScreen();
        end(BattleOutcome.DEFEAT);
        run(1.9f);
        assertFalse(screen.resultPanel().visible());
        run(0.2f);
        assertTrue(screen.resultPanel().visible(), "a defeat shows its panel after two seconds");
        assertEquals(BattleOutcome.DEFEAT, screen.resultPanel().outcome());
    }

    @Test
    void statsCountUpOverAboutASecond() {
        endAndRaise(BattleOutcome.VICTORY);
        ResultPanel panel = screen.resultPanel();
        MResultCard card = panel.card();
        assertTrue(panel.countUp() < 0.3f, "just started");
        run(ResultPanel.COUNT_UP_SECONDS / 2f);
        float half = panel.countUp();
        assertTrue(half > 0.3f && half < 1f);
        int dealtMid = Integer.parseInt(card.statText(1));
        assertTrue(dealtMid > 0 && dealtMid < 2400, "damage dealt is part-way: " + dealtMid);
        assertTrue(!card.statText(0).equals("0:00") && !card.statText(0).equals("1:23"),
                "and so is the clock: " + card.statText(0));

        run(ResultPanel.COUNT_UP_SECONDS);
        assertEquals(1f, panel.countUp(), 1e-6f);
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < card.statCount(); i++) shown.add(card.statText(i));
        assertEquals(List.of("1:23", "2400", "96", "4", "2", "7", "5", "6", "11"), shown,
                "time, damage dealt / taken, parries, blocks, perfect rings, best streak, combo hits, turns");
    }

    @Test
    void nullStatsAreZerosNotACrash() {
        view.phase = BattlePhase.RESULT;
        view.outcome = BattleOutcome.DEFEAT;
        view.stats = null;
        screen.update(DT);
        run(ResultPanel.DEFEAT_DELAY_SECONDS + ResultPanel.RISE_SECONDS + ResultPanel.COUNT_UP_SECONDS + 0.1f);
        MResultCard card = screen.resultPanel().card();
        assertEquals(9, card.statCount());
        assertEquals("0:00", card.statText(0));
        assertEquals("0", card.statText(1));
    }

    @Test
    void theCountUpStartsWhenTheCardSeatsHoweverLargeTheStep() {
        end(BattleOutcome.DEFEAT);
        // One giant step lands past the rise: only the part after seating counts up.
        screen.update(ResultPanel.DEFEAT_DELAY_SECONDS + ResultPanel.RISE_SECONDS + ResultPanel.COUNT_UP_SECONDS / 2f);
        ResultPanel panel = screen.resultPanel();
        assertTrue(panel.interactive());
        assertTrue(panel.countUp() > 0.3f && panel.countUp() < 1f, "half-way, not finished: " + panel.countUp());
    }

    // ── input ────────────────────────────────────────────────────────────────

    @Test
    void inputIsSwallowedUntilThePanelIsUpButEscapeStillPauses() {
        end(BattleOutcome.VICTORY);
        run(1f);
        assertTrue(press(GLFW_KEY_ENTER), "consumed");
        assertTrue(press(GLFW_KEY_RIGHT));
        assertTrue(screen.handleMouseClick(W / 2.0, H / 2.0, W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS));
        float[] retry = ResultPanel.buttonRect(0, W, H, uiScale());
        screen.handleMouseClick(retry[0] + 5, retry[1] + 5, W, H, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        assertTrue(host.calls.isEmpty(), "a confirm mashed at the killing blow must not pick Retry");
        assertTrue(input.calls.isEmpty(), "and nothing reaches the model");
        assertEquals(0, screen.resultPanel().focusedIndex());

        press(GLFW_KEY_ESCAPE);
        assertEquals(List.of("openPauseMenu"), host.calls);
    }

    @Test
    void retryIsTheDefaultAndConfirmActivatesTheFocusedButton() {
        endAndRaise(BattleOutcome.VICTORY);
        assertEquals(ResultPanel.Button.RETRY, screen.resultPanel().focusedButton());
        press(GLFW_KEY_SPACE);
        assertEquals(List.of("retry"), host.calls);

        press(GLFW_KEY_RIGHT);
        assertEquals(ResultPanel.Button.EXPLORE_ARENA, screen.resultPanel().focusedButton());
        press(GLFW_KEY_ENTER);
        press(GLFW_KEY_DOWN);
        assertEquals(ResultPanel.Button.RETURN_TO_WORLD, screen.resultPanel().focusedButton());
        press(GLFW_KEY_ENTER);
        assertEquals(List.of("retry", "exploreArena", "returnToWorld"), host.calls);
        assertTrue(input.calls.isEmpty(), "the result panel never talks to the model");
    }

    @Test
    void focusMovesWithAnyDirectionAndWraps() {
        endAndRaise(BattleOutcome.DEFEAT);
        ResultPanel panel = screen.resultPanel();
        press(GLFW_KEY_LEFT);
        assertEquals(2, panel.focusedIndex(), "left from Retry wraps to Return to world");
        press(GLFW_KEY_D);
        assertEquals(0, panel.focusedIndex());
        press(GLFW_KEY_UP);
        assertEquals(2, panel.focusedIndex());
        press(GLFW_KEY_A);
        assertEquals(1, panel.focusedIndex());
        press(GLFW_KEY_DOWN);
        press(GLFW_KEY_DOWN);
        assertEquals(0, panel.focusedIndex());
        assertTrue(host.calls.isEmpty(), "moving is not activating");
    }

    @Test
    void mouseHoverAndClickUseTheButtonRectsAtEveryResolution() {
        int[][] windows = {{1024, 600}, {1280, 720}, {1920, 1080}, {3840, 2160}};
        String[] expected = {"retry", "exploreArena", "returnToWorld"};
        for (int[] win : windows) {
            float[] panel = FocusBattleLayout.resultPanelRect(win[0], win[1], uiScale());
            for (int i = 0; i < ResultPanel.BUTTON_COUNT; i++) {
                bindAScreen();
                endAndRaise(BattleOutcome.VICTORY);
                float[] r = ResultPanel.buttonRect(i, win[0], win[1], uiScale());
                String where = win[0] + "x" + win[1] + " button " + i;
                assertTrue(r[2] > 40f && r[3] > 16f, where + " has a usable size");
                assertTrue(r[0] >= panel[0] && r[0] + r[2] <= panel[0] + panel[2]
                        && r[1] >= panel[1] && r[1] + r[3] <= panel[1] + panel[3], where + " inside the panel");
                assertEquals(i, ResultPanel.buttonAt(r[0] + 1, r[1] + 1, win[0], win[1], uiScale()), where);
                assertEquals(i, ResultPanel.buttonAt(r[0] + r[2] - 1, r[1] + r[3] - 1, win[0], win[1], uiScale()), where);

                screen.handleMouseMove(r[0] + r[2] / 2f, r[1] + r[3] / 2f, win[0], win[1]);
                assertEquals(i, screen.resultPanel().focusedIndex(), where + " hover");
                assertTrue(host.calls.isEmpty());
                screen.handleMouseClick(r[0] + r[2] / 2f, r[1] + r[3] / 2f, win[0], win[1],
                        GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
                assertEquals(List.of(expected[i]), host.calls, where + " click");
            }
            // Buttons never overlap, and a click between or outside them does nothing.
            float[] a = ResultPanel.buttonRect(0, win[0], win[1], uiScale());
            float[] b = ResultPanel.buttonRect(1, win[0], win[1], uiScale());
            assertTrue(a[0] + a[2] < b[0]);
            assertEquals(-1, ResultPanel.buttonAt((a[0] + a[2] + b[0]) / 2f, a[1] + 2, win[0], win[1], uiScale()));
            assertEquals(-1, ResultPanel.buttonAt(panel[0] + panel[2] / 2f, panel[1] + 10, win[0], win[1], uiScale()));
        }
    }

    // ── retry ────────────────────────────────────────────────────────────────

    @Test
    void retryReBindsAndEveryFlowStateStartsOver() {
        screen.update(DT);
        view.archon.hp = 10f;
        view.events.add(new BattleEvent.DamageDealt(com.stonebreak.battle.api.CombatantId.ARCHON, 300f,
                BattleEvent.DamageFlavor.CRITICAL));
        view.events.add(new BattleEvent.ActionStarted(com.stonebreak.battle.api.CombatantId.MONK, "Strike",
                BattleCommand.STRIKE, null, 1f));
        screen.update(DT);
        view.events.clear();
        assertEquals(1, screen.floaters().count());
        assertEquals("Strike", screen.actionBanner().text());

        FakeBattleView next = new FakeBattleView();
        next.phase = BattlePhase.INTRO;
        host.onRetry = () -> screen.bind(next, input, FakeBattleView.crucibleLayout(), host);
        endAndRaise(BattleOutcome.DEFEAT);
        press(GLFW_KEY_ENTER);

        assertEquals(List.of("retry"), host.calls);
        assertFalse(screen.resultPanel().ended(), "the panel is gone");
        assertFalse(screen.resultPanel().visible());
        assertEquals(0, screen.floaters().count(), "no floater survives into the new fight");
        assertEquals(0, screen.resultPanel().focusedIndex());
        assertEquals(0f, screen.resultPanel().countUp(), 0f, "and the next result counts up from zero again");
        assertFalse(screen.actionBanner().visible());
        assertEquals(1f, screen.animState().bottomHudSlideOut, 1e-6f, "straight into the new intro's framing");
        assertTrue(screen.animator().secondsSinceEnd() < 0f);

        screen.update(0f);
        assertTrue(screen.encounterTransition().active(), "and the encounter transition plays again");
        assertEquals(1f, screen.encounterTransition().flashAlpha(), 1e-6f);

        press(GLFW_KEY_ENTER);
        assertEquals(List.of("retry", "skipIntro"), host.calls, "input is the new battle's from the same press on");
    }

    // ── raster ───────────────────────────────────────────────────────────────

    private BattleRasterFixture paint(int w, int h) {
        BattleRasterFixture fx = new BattleRasterFixture(w, h);
        screen.resultPanel().paint(fx.ui, fx.canvas, w, h, FocusBattleLayout.effectiveScale(w, h, 1f), 1f, view,
                screen.animState(), null);
        return fx;
    }

    @Test
    void thePanelRisesIntoItsRectAndPaintsBothOutcomes() {
        float[] rect = FocusBattleLayout.resultPanelRect(W, H, 1f);
        end(BattleOutcome.VICTORY);
        run(1f);
        assertEquals(0, paint(W, H).countPainted(0, 0, W, H), "nothing during the victory cinematic");

        run(BattleHudRules.VICTORY_CINEMATIC_SECONDS - 1f + ResultPanel.RISE_SECONDS * 0.3f);
        BattleRasterFixture rising = paint(W, H);
        run(ResultPanel.RISE_SECONDS + ResultPanel.COUNT_UP_SECONDS + 0.1f);
        BattleRasterFixture victory = paint(W, H);
        assertTrue(victory.countPainted(rect) > rect[2] * rect[3] * 0.95f, "the panel fills the reserved rect");
        assertEquals(W * H, victory.countPainted(0, 0, W, H), "and the scrim darkens everything behind it");
        assertTrue(rising.diff(victory, rect) > 20_000, "mid-rise it is still below its seat");
        assertTrue(victory.countExactly(MStyle.TEXT_ACCENT, (int) rect[0], (int) rect[1], (int) (rect[0] + rect[2]),
                (int) (rect[1] + rect[3] * 0.3f)) > 300, "VICTORY in the house gold");
        assertTrue(victory.countExactly(MStyle.PANEL_BORDER, (int) rect[0], (int) rect[1], (int) (rect[0] + rect[2]),
                (int) (rect[1] + rect[3])) > 500, "on the house stone panel");

        screen.resultPanel().moveFocus(1);
        BattleRasterFixture moved = paint(W, H);
        float[] first = ResultPanel.buttonRect(0, W, H, 1f);
        float[] second = ResultPanel.buttonRect(1, W, H, 1f);
        assertTrue(moved.diff(victory, first) > 300 && moved.diff(victory, second) > 300, "focus is drawn");
        assertTrue(moved.countExactly(MStyle.TEXT_ACCENT, (int) second[0], (int) second[1],
                (int) (second[0] + second[2]), (int) (second[1] + second[3])) > 40, "as the gold label of a selected MButton");
        assertArrayEquals(second, screen.resultPanel().card().buttonRect(1), 0f,
                "the hit-test rect is the rect the card paints");

        bindAScreen();
        end(BattleOutcome.DEFEAT);
        run(ResultPanel.DEFEAT_DELAY_SECONDS + ResultPanel.RISE_SECONDS + ResultPanel.COUNT_UP_SECONDS + 0.1f);
        BattleRasterFixture defeat = paint(W, H);
        assertTrue(defeat.countExactly(BattlePalette.ACCENT_ARCHON, (int) rect[0], (int) rect[1],
                (int) (rect[0] + rect[2]), (int) (rect[1] + rect[3] * 0.3f)) > 300, "DEFEATED in the Archon's accent");
        assertTrue(defeat.diff(victory, rect) > 3000);
    }

    @Test
    void statsCountUpOnScreen() {
        end(BattleOutcome.VICTORY);
        run(BattleHudRules.VICTORY_CINEMATIC_SECONDS + ResultPanel.RISE_SECONDS + 0.1f);
        BattleRasterFixture early = paint(W, H);
        run(ResultPanel.COUNT_UP_SECONDS);
        BattleRasterFixture done = paint(W, H);
        float[] rect = FocusBattleLayout.resultPanelRect(W, H, 1f);
        assertTrue(early.diff(done, rect) > 100, "the numbers changed");
        assertEquals(0, done.diff(paint(W, H)), "and then hold still");
    }

    @Test
    void thePanelFitsEveryWindow() {
        endAndRaise(BattleOutcome.VICTORY);
        int[][] windows = {{1024, 600}, {1920, 1080}, {3840, 2160}};
        for (int[] win : windows) {
            BattleRasterFixture fx = paint(win[0], win[1]);
            float[] rect = FocusBattleLayout.resultPanelRect(win[0], win[1], 1f);
            assertTrue(fx.countPainted(rect) > rect[2] * rect[3] * 0.95f, win[0] + "x" + win[1]);
            for (int i = 0; i < ResultPanel.BUTTON_COUNT; i++) {
                float[] b = ResultPanel.buttonRect(i, win[0], win[1], 1f);
                assertTrue(b[1] + b[3] <= rect[1] + rect[3] && b[0] + b[2] <= rect[0] + rect[2]);
            }
        }
    }
}
