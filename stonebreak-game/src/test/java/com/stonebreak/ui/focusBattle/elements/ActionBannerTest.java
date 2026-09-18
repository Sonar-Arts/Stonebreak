package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FlowTheme;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E7: the banner's drop / hold / fade timing, its side tint, and that it paints into its rect. */
class ActionBannerTest {

    private static final int W = 1280;
    private static final int H = 720;
    private static final float UI = 1f;
    private static final float DT = 1f / 60f;

    private FakeBattleView view;
    private ActionBanner banner;

    @BeforeEach
    void freshBanner() {
        view = new FakeBattleView();
        banner = new ActionBanner();
    }

    private void start(CombatantId actor, String name, BattleCommand command, EnemyAction enemyAction, float duration) {
        view.phase = BattlePhase.ACTION;
        view.currentAction = new ActionView(actor, name, command, enemyAction, 0f, duration);
        banner.update(DT, view, List.of(new BattleEvent.ActionStarted(actor, name, command, enemyAction, duration)));
    }

    private void run(float seconds) {
        for (int i = 0; i < Math.round(seconds / DT); i++) banner.update(DT, view, List.of());
    }

    private BattleRasterFixture paint() {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        banner.paint(fx.ui, fx.canvas, W, H, FocusBattleLayout.effectiveScale(W, H, UI), UI, view, null, null);
        return fx;
    }

    @Test
    void nothingShowsUntilAnActionStarts() {
        run(0.5f);
        assertFalse(banner.visible());
        assertEquals(0, paint().countPainted(0, 0, W, H));
    }

    @Test
    void itDropsInHoldsWhileTheActionRunsThenFades() {
        start(CombatantId.MONK, "Flurry of Blows", BattleCommand.FLURRY, null, 2.4f);
        assertEquals("Flurry of Blows", banner.text());
        assertTrue(banner.dropProgress() < 0.05f, "starts above its slot");
        run(ActionBanner.DROP_SECONDS / 2f);
        assertTrue(banner.dropProgress() > 0.4f && banner.dropProgress() < 1f, "dropping");
        run(ActionBanner.DROP_SECONDS);
        assertEquals(1f, banner.dropProgress(), 1e-6f);
        assertEquals(1f, banner.alpha(), 1e-6f);

        run(2f);
        assertEquals(1f, banner.alpha(), 1e-6f, "held for as long as the action runs, well past the minimum");

        view.currentAction = null;
        view.phase = BattlePhase.RUNNING;
        banner.update(DT, view, List.of(new BattleEvent.ActionFinished(CombatantId.MONK)));
        run(ActionBanner.FADE_SECONDS / 2f);
        assertTrue(banner.alpha() > 0f && banner.alpha() < 1f, "fading");
        run(ActionBanner.FADE_SECONDS);
        assertFalse(banner.visible());
    }

    @Test
    void aShortActionStillHoldsForTheMinimum() {
        start(CombatantId.MONK, "Martial Surge", BattleCommand.MARTIAL_SURGE, null, 0.1f);
        view.currentAction = null;   // a free action: over at once
        banner.update(DT, view, List.of(new BattleEvent.ActionFinished(CombatantId.MONK)));
        run(ActionBanner.MIN_HOLD_SECONDS - 0.1f);
        assertEquals(1f, banner.alpha(), 1e-6f, "legible for the minimum hold");
        run(0.1f + ActionBanner.FADE_SECONDS + 2 * DT);
        assertFalse(banner.visible());
    }

    @Test
    void aNewActionReplacesTheOldOneAndRestartsTheDrop() {
        start(CombatantId.MONK, "Strike", BattleCommand.STRIKE, null, 1f);
        run(1.2f);
        start(CombatantId.ARCHON, "Frost Slash", null, EnemyAction.SLASH, 1.65f);
        assertEquals("Frost Slash", banner.text());
        assertTrue(banner.dropProgress() < 0.5f);
        run(0.3f);
        assertEquals(1f, banner.alpha(), 1e-6f, "the monk's finished action does not fade the Archon's plate");
    }

    @Test
    void theTintNamesTheSide() {
        start(CombatantId.ARCHON, "Glacial Overhead", null, EnemyAction.OVERHEAD, 2f);
        assertEquals(FlowTheme.FROST_RED, banner.tint());
        start(CombatantId.MONK, "Strike", BattleCommand.STRIKE, null, 1f);
        assertEquals(FlowTheme.MONK_TINT, banner.tint());
        start(CombatantId.MONK, "Focus Combo", BattleCommand.FOCUS_COMBO, null, 6f);
        assertEquals(FlowTheme.GOLD, banner.tint());
    }

    @Test
    void itIsHiddenDuringTheIntroAndClearedOnReset() {
        start(CombatantId.MONK, "Strike", BattleCommand.STRIKE, null, 1f);
        run(0.3f);
        view.phase = BattlePhase.INTRO;
        banner.update(DT, view, List.of());
        assertFalse(banner.visible());
        assertEquals(0, paint().countPainted(0, 0, W, H));

        view.phase = BattlePhase.ACTION;
        banner.update(DT, view, List.of());
        assertTrue(banner.visible());
        banner.reset();
        assertFalse(banner.visible());
    }

    @Test
    void itPaintsIntoTheReservedRectAndDiffersBySideAndState() {
        float[] rect = FocusBattleLayout.actionBannerRect(W, H, UI);
        start(CombatantId.MONK, "Strike", BattleCommand.STRIKE, null, 1f);
        run(0.3f);
        BattleRasterFixture monk = paint();
        assertTrue(monk.countPainted(rect) > 8000, "the plate fills its rect");
        assertEquals(monk.countPainted(0, 0, W, H), monk.countPainted((int) rect[0] - 4, (int) rect[1] - 4,
                (int) (rect[0] + rect[2]) + 8, (int) (rect[1] + rect[3]) + 8), "and nothing else");
        assertEquals(0, monk.diff(paint()), "deterministic");

        start(CombatantId.ARCHON, "Frost Slash", null, EnemyAction.SLASH, 1.65f);
        run(0.3f);
        BattleRasterFixture archon = paint();
        assertTrue(archon.diff(monk, rect) > 4000, "the Archon's plate is a different colour and name");
        assertNotEquals(0, archon.countExactly(FlowTheme.FROST_RED, 0, 0, W, H), "frost-red accents");

        start(CombatantId.MONK, "Strike", BattleCommand.STRIKE, null, 1f);
        BattleRasterFixture dropping = paint();
        assertTrue(dropping.diff(monk) > 2000, "mid-drop differs from seated");
    }

    @Test
    void theBannerFitsEveryWindow() {
        int[][] windows = {{1024, 600}, {1920, 1080}, {3840, 2160}};
        for (int[] win : windows) {
            start(CombatantId.ARCHON, "Glacial Overhead", null, EnemyAction.OVERHEAD, 2f);
            run(0.3f);
            BattleRasterFixture fx = new BattleRasterFixture(win[0], win[1]);
            banner.paint(fx.ui, fx.canvas, win[0], win[1], FocusBattleLayout.effectiveScale(win[0], win[1], UI), UI,
                    view, null, null);
            float[] rect = FocusBattleLayout.actionBannerRect(win[0], win[1], UI);
            assertTrue(fx.countPainted(rect) > rect[2] * rect[3] * 0.8f, win[0] + "x" + win[1]);
        }
    }
}
