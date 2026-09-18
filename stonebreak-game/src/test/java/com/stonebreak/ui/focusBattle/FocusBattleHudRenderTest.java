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
        assertTrue(fx.countPainted(FocusBattleLayout.commandWindowRect(W, H, UI_SCALE)) > 30_000,
                "E1 rests on screen in its static state even before the monk's gauge fills");
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

        // The command window never slides away. Waking (0) it still fills its rect, veiled like the
        // static state; awake (1) the veil is gone and the cursor row is lit.
        float[] cmd = FocusBattleLayout.commandWindowRect(W, H, UI_SCALE);
        BattleHudAnimState waking = new BattleHudAnimState();
        waking.commandWake = 0f;
        BattleRasterFixture veiled = hud(view, new BattleMenuState(), waking);
        assertTrue(veiled.countPainted(cmd) > 30_000, "E1 stays in its rect while it wakes");
        assertTrue(veiled.diff(rest, cmd) > 10_000, "and is veiled until it has woken");

        FakeBattleView resting = new FakeBattleView();
        BattleRasterFixture staticState = hud(resting, new BattleMenuState(), null);
        assertTrue(staticState.countPainted(cmd) > 30_000, "between turns E1 is still there");
        assertTrue(staticState.diff(rest, cmd) > 10_000, "in its static look: veiled, no cursor");

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

    @Test
    void theWindowLooksStaticWhileAPromptOwnsTheInput() {
        // A guarding monk with a full gauge gets the command window AND a parry prompt at once. Confirm
        // then means "parry", so the window must look exactly as static as it behaves.
        FakeBattleView resting = new FakeBattleView();
        resting.prompt = new com.stonebreak.battle.api.PromptView.Parry(0.2f, 0.4f, 0.65f, 0.65f);
        FakeBattleView both = new FakeBattleView();
        both.prompt = resting.prompt;
        both.commandWindowOpen = true;
        float[] cmd = FocusBattleLayout.commandWindowRect(W, H, UI_SCALE);
        assertEquals(0, hud(both, new BattleMenuState(), null).diff(hud(resting, new BattleMenuState(), null), cmd),
                "no cursor, veiled: identical to the resting window");
        assertTrue(!BattleHudRules.menuLive(both), "a prompt outranks the menu");
        both.prompt = null;
        assertTrue(BattleHudRules.menuLive(both));
    }
}
