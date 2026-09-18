package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.FakeBattleView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assembled HUD on a CPU canvas: which elements are visible when, that each one lands in the
 * rect the layout (and therefore the mouse hit-test) says it does, and the Wave-2 layer hooks.
 */
class FocusBattleHudRenderTest {

    private static final int W = 1280;
    private static final int H = 720;
    private static final float UI_SCALE = 1f;

    private static BattleRasterFixture hud(FakeBattleView view, BattleMenuState menu, BattleHudAnimState anim) {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        new SkijaFocusBattleRenderer(null).paintHud(fx.ui, fx.canvas, W, H, UI_SCALE, view, menu, anim, null);
        return fx;
    }

    @Test
    void theAlwaysOnElementsPaintIntoTheirLayoutRects() {
        BattleRasterFixture fx = hud(new FakeBattleView(), new BattleMenuState(), null);
        assertTrue(fx.countPainted(FocusBattleLayout.partyWindowRect(W, H, UI_SCALE)) > 30_000, "E3");
        assertTrue(fx.countPainted(FocusBattleLayout.enemyPlateRect(W, H, UI_SCALE)) > 30_000, "E5");
        assertTrue(fx.countPainted(FocusBattleLayout.modeTagRect(W, H, UI_SCALE)) > 1500, "E13");
        assertEquals(0, fx.countPainted(FocusBattleLayout.commandWindowRect(W, H, UI_SCALE)),
                "E1 is hidden until the monk's gauge fills");
        assertEquals(0, fx.countPainted(FocusBattleLayout.actionBannerRect(W, H, UI_SCALE)),
                "reserved rects stay empty until Wave 2 fills them");
        assertEquals(0, fx.countPainted(FocusBattleLayout.comboStripRect(W, H, UI_SCALE)));
    }

    @Test
    void theCommandWindowAndHelpAppearWithTheTurn() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        BattleRasterFixture fx = hud(view, new BattleMenuState(), null);
        assertTrue(fx.countPainted(FocusBattleLayout.commandWindowRect(W, H, UI_SCALE)) > 30_000, "E1");
        assertTrue(fx.countPainted(FocusBattleLayout.helpStripRect(W, H, UI_SCALE)) > 20_000, "E4");
        assertEquals(0, fx.countPainted(FocusBattleLayout.submenuRect(W, H, UI_SCALE)) > 5000 ? 1 : 0,
                "E2 stays closed until opened");

        BattleMenuState menu = new BattleMenuState();
        menu.selectRoot(FocusBattleLayout.qiArtsRowIndex());
        menu.openSubmenu();
        BattleRasterFixture open = hud(view, menu, null);
        assertTrue(open.countPainted(FocusBattleLayout.submenuRect(W, H, UI_SCALE)) > 20_000, "E2");
    }

    @Test
    void slidesMoveTheBottomWindowsWithoutTouchingTheTop() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        BattleRasterFixture rest = hud(view, new BattleMenuState(), null);

        BattleHudAnimState sliding = new BattleHudAnimState();
        sliding.commandSlideIn = 0f;
        BattleRasterFixture hidden = hud(view, new BattleMenuState(), sliding);
        assertEquals(0, hidden.countPainted(FocusBattleLayout.commandWindowRect(W, H, UI_SCALE)) > 600 ? 1 : 0,
                "slide-in 0 parks E1 off the left edge");

        BattleHudAnimState out = new BattleHudAnimState();
        out.bottomHudSlideOut = 1f;
        BattleRasterFixture cinematic = hud(view, new BattleMenuState(), out);
        assertEquals(0, cinematic.countPainted(0, H / 2, W, H), "the whole bottom HUD leaves for the cinematics");
        assertEquals(0, cinematic.diff(rest, 0, 0, W, H / 3), "the enemy plate stays put");
    }

    @Test
    void layersPaintInsideTheSameFrameInOrder() {
        List<String> order = new ArrayList<>();
        SkijaFocusBattleRenderer renderer = new SkijaFocusBattleRenderer(null);
        renderer.addUnderlay((ui, c, w, h, s, raw, v, a, vp) -> {
            order.add("under");
            FocusBattleTheme.fillRect(c, 0f, 0f, w, h, 0xFF000000);
        });
        renderer.addOverlay((ui, c, w, h, s, raw, v, a, vp) -> {
            order.add("over");
            assertEquals(FocusBattleLayout.effectiveScale(w, h, raw), s, 1e-6f);
        });
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        renderer.paintHud(fx.ui, fx.canvas, W, H, UI_SCALE, new FakeBattleView(), null, null, null);

        assertEquals(List.of("under", "over"), order);
        // The underlay's black is covered by the opaque parts of the windows drawn after it.
        float[] party = FocusBattleLayout.partyWindowRect(W, H, UI_SCALE);
        assertTrue(fx.countExactly(0xFF000000, (int) party[0] + 12, (int) party[1] + 12,
                (int) (party[0] + party[2]) - 12, (int) (party[1] + party[3]) - 12)
                < (party[2] - 24) * (party[3] - 24), "windows draw over underlays");
        renderer.dispose();
    }

    @Test
    void theHudSurvivesEveryResolutionAndScale() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        BattleMenuState menu = new BattleMenuState();
        menu.selectRoot(FocusBattleLayout.qiArtsRowIndex());
        menu.openSubmenu();
        int[][] windows = {{1024, 600}, {1920, 1080}};
        for (int[] win : windows) {
            for (float scale : new float[]{0.75f, 2f}) {
                BattleRasterFixture fx = new BattleRasterFixture(win[0], win[1]);
                new SkijaFocusBattleRenderer(null).paintHud(fx.ui, fx.canvas, win[0], win[1], scale, view, menu,
                        null, null);
                assertTrue(fx.countPainted(FocusBattleLayout.commandWindowRect(win[0], win[1], scale)) > 5000,
                        win[0] + "x" + win[1] + " @" + scale);
            }
        }
    }
}
