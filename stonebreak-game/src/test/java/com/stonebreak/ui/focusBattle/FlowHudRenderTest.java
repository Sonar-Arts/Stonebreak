package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattleInput;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleScreenHost;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.ui.focusBattle.elements.GaugeSparks;
import com.stonebreak.ui.focusBattle.elements.TargetCursor;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

/**
 * The flow elements assembled on the real screen: that the screen's own layers reach the canvas
 * through the renderer, in the right places, and that the animator's numbers really move pixels.
 * Drawn at the user's UI scale of 1 on a CPU canvas.
 */
class FlowHudRenderTest {

    private static final int W = 1280;
    private static final int H = 720;
    private static final float UI = 1f;
    private static final float DT = 1f / 60f;

    private static final BattleInput NO_INPUT = new BattleInput() {
        @Override public void introFinished() { }
        @Override public boolean submit(BattleCommand command) { return true; }
        @Override public void pressConfirm() { }
        @Override public void pressDirection(ComboDirection direction) { }
    };

    private static final BattleScreenHost NO_HOST = new BattleScreenHost() {
        @Override public void skipIntro() { }
        @Override public void retry() { }
        @Override public void exploreArena() { }
        @Override public void returnToWorld() { }
        @Override public void openPauseMenu() { }
    };

    private FakeBattleView view;
    private FocusBattleScreen screen;

    @BeforeEach
    void bindAScreen() {
        view = new FakeBattleView();
        screen = new FocusBattleScreen(null);
        screen.bind(view, NO_INPUT, FakeBattleView.crucibleLayout(), NO_HOST);
    }

    private void frame(BattleEvent... events) {
        view.events.clear();
        view.events.addAll(java.util.List.of(events));
        screen.update(DT);
        view.events.clear();
    }

    private void run(float seconds) {
        for (int i = 0; i < Math.round(seconds / DT); i++) frame();
    }

    /** The whole HUD as the screen would draw it, on a CPU canvas. */
    private BattleRasterFixture hud(Matrix4f camera) {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        screen.renderer().paintHud(fx.ui, fx.canvas, W, H, UI, view, screen.menuState(), screen.animState(), camera);
        return fx;
    }

    /** Over the monk's shoulder: the Archon mid-screen. */
    private static Matrix4f shoulderCamera() {
        return new Matrix4f().perspective((float) Math.toRadians(60.0), (float) W / H, 0.1f, 200f)
                .lookAt(1.5f, 2.4f, 12.5f, 0f, 1.9f, -9f, 0f, 1f, 0f);
    }

    private static Matrix4f awayCamera() {
        return new Matrix4f().perspective((float) Math.toRadians(60.0), (float) W / H, 0.1f, 200f)
                .lookAt(0f, 3f, 30f, 0f, 3f, 60f, 0f, 1f, 0f);
    }

    // ── E8 ───────────────────────────────────────────────────────────────────

    @Test
    void theTargetCursorAppearsOverTheArchonOnlyWhileAiming() {
        view.commandWindowOpen = true;
        run(0.4f);
        Matrix4f camera = shoulderCamera();
        BattleRasterFixture list = hud(camera);

        screen.handleKeyInput(GLFW_KEY_ENTER, GLFW_PRESS, 0);
        assertTrue(screen.menuState().targeting());
        frame();
        BattleRasterFixture aiming = hud(camera);

        TargetCursor.Placement at = TargetCursor.place(FakeBattleView.crucibleLayout(), view, camera, W, H, UI);
        assertFalse(at.pinned());
        float[] anchor = WorldProjection.toScreen(camera, FakeBattleView.crucibleLayout()
                .bodyPoint(CombatantId.ARCHON, view.archon.pose, TargetCursor.ANCHOR_HEIGHT), W, H, 0f);
        assertEquals(anchor[1], at.tipY(), 0.01f, "the finger points at the Archon's body point");
        assertTrue(at.tipX() < anchor[0] && at.tipX() > anchor[0] - 200f, "from its left flank");
        assertTrue(FocusBattleLayout.contains(anchor[0], anchor[1], at.bodyRect()));

        assertEquals(0, list.countPainted(at.handRect()), "no cursor while the list has the cursor");
        assertTrue(aiming.countPainted(at.handRect()) > 250, "the hand");
        assertTrue(aiming.countPainted(at.tagRect()) > 800, "and the name tag");

        view.archon.displayName = "Frost Warden";
        assertTrue(hud(camera).diff(aiming, at.tagRect()) > 60, "the tag names the target");
    }

    @Test
    void anOffScreenTargetPinsTheCursorToTheEnemyPlate() {
        view.commandWindowOpen = true;
        run(0.4f);
        screen.handleKeyInput(GLFW_KEY_ENTER, GLFW_PRESS, 0);
        frame();

        for (Matrix4f camera : new Matrix4f[]{awayCamera(), null}) {
            TargetCursor.Placement at = TargetCursor.place(FakeBattleView.crucibleLayout(), view, camera, W, H, UI);
            assertTrue(at.pinned());
            float[] plate = FocusBattleLayout.enemyPlateRect(W, H, UI);
            assertTrue(at.tipX() <= plate[0] && at.tipX() > plate[0] - 20f, "finger at the plate's left edge");
            assertTrue(at.tipY() > plate[1] && at.tipY() < plate[1] + plate[3]);
            assertTrue(hud(camera).countPainted(at.handRect()) > 250, "and it is drawn there");
        }
    }

    @Test
    void theTargetCursorBobs() {
        view.commandWindowOpen = true;
        run(0.4f);
        screen.handleKeyInput(GLFW_KEY_ENTER, GLFW_PRESS, 0);
        frame();
        Matrix4f camera = shoulderCamera();
        BattleRasterFixture a = hud(camera);
        run(0.2f);
        TargetCursor.Placement at = TargetCursor.place(FakeBattleView.crucibleLayout(), view, camera, W, H, UI);
        float[] hand = at.handRect();
        assertTrue(hud(camera).diff(a, (int) hand[0] - 14, (int) hand[1] - 2, (int) (hand[0] + hand[2]) + 4,
                (int) (hand[1] + hand[3]) + 2) > 40, "the hand moved");
    }

    // ── E3 sparks ────────────────────────────────────────────────────────────

    @Test
    void spendingQiShattersThePipsThatEmptied() {
        view.qi = 4;
        run(0.2f);
        BattleRasterFixture rest = hud(null);

        view.qi = 2;
        frame(new BattleEvent.QiChanged(-2, 2));
        BattleRasterFixture shatter = hud(null);
        float[] party = FocusBattleLayout.partyWindowRect(W, H, UI);
        float[] third = FlowLayout.qiPipRect(party, 2, view.maxQi, FocusBattleLayout.effectiveScale(W, H, UI));
        float[] first = FlowLayout.qiPipRect(party, 0, view.maxQi, FocusBattleLayout.effectiveScale(W, H, UI));
        assertTrue(shatter.countExactly(0xFFFFFFFF, (int) third[0], (int) third[1], (int) (third[0] + third[2]),
                (int) (third[1] + third[3])) > 30, "a white flash exactly on the emptied pip");
        assertEquals(0, shatter.countExactly(0xFFFFFFFF, (int) first[0], (int) first[1], (int) (first[0] + first[2]),
                (int) (first[1] + first[3])), "pips that are still full are left alone");

        run(HudAnimator.QI_SPEND_SECONDS + DT);
        view.qi = 4;
        assertEquals(0, hud(null).diff(rest, party), "and it is over without a trace");
    }

    @Test
    void sparksFollowTheWindowTheyDecorate() {
        BattleHudAnimState anim = new BattleHudAnimState();
        anim.qiSpendFlash = 1f;
        anim.qiSpendFirstPip = 1;
        anim.qiSpendCount = 1;
        anim.bottomHudSlideOut = 1f;
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        new GaugeSparks().paint(fx.ui, fx.canvas, W, H, 1f, UI, view, anim, null);
        assertEquals(0, fx.countPainted(0, 0, W, H), "slid out with the party window, not left hanging in mid-air");
    }

    @Test
    void focusReachingItsMaximumBurstsOnTheFocusBar() {
        view.focus = 100f;
        run(1.0f);
        BattleRasterFixture ready = hud(null);
        frame(new BattleEvent.FocusFull());
        BattleRasterFixture burst = hud(null);
        float[] party = FocusBattleLayout.partyWindowRect(W, H, UI);
        float[] bar = FlowLayout.focusBarRect(party, FocusBattleLayout.effectiveScale(W, H, UI));
        assertTrue(burst.diff(ready, bar) > bar[2] * bar[3] * 0.5f, "the whole bar flashes");
    }

    // ── animator → pixels ────────────────────────────────────────────────────

    @Test
    void aHitLeavesAGhostTrailAndShakesThePlate() {
        run(0.2f);
        BattleRasterFixture before = hud(null);
        view.archon.hp = 450f;
        frame(new BattleEvent.DamageDealt(CombatantId.ARCHON, 450f, DamageFlavor.NORMAL));
        frame();
        BattleRasterFixture hit = hud(null);

        float[] plate = FocusBattleLayout.enemyPlateRect(W, H, UI);
        float[] hp = FocusBattleLayout.enemyHpBarRect(plate, FocusBattleLayout.effectiveScale(W, H, UI));
        assertTrue(hit.countExactly(FocusBattleTheme.HP_GHOST, (int) hp[0] - 12, (int) hp[1] - 12,
                (int) (hp[0] + hp[2]) + 12, (int) (hp[1] + hp[3]) + 12) == 0, "the ghost is under the red flash…");
        assertTrue(hit.diff(before, plate) > plate[2] * plate[3] * 0.8f, "…which tints the whole plate");

        run(HudAnimator.SHAKE_SECONDS);   // flash gone, the hold still running
        BattleRasterFixture trailing = hud(null);
        assertTrue(trailing.countExactly(FocusBattleTheme.HP_GHOST, (int) hp[0], (int) hp[1],
                (int) (hp[0] + hp[2]), (int) (hp[1] + hp[3])) > 1000, "the pale trail spans the lost half");

        run(HudAnimator.GHOST_HOLD_SECONDS + HudAnimator.GHOST_EASE_SECONDS);
        assertEquals(0, hud(null).countExactly(FocusBattleTheme.HP_GHOST, (int) hp[0], (int) hp[1],
                (int) (hp[0] + hp[2]), (int) (hp[1] + hp[3])), "and drains away");
    }

    @Test
    void theBottomHudReallyLeavesTheScreenForTheFocusCombo() {
        view.commandWindowOpen = true;
        run(0.4f);
        assertTrue(hud(null).countPainted(0, H / 2, W, H) > 100_000, "command, help and party windows are up");

        view.commandWindowOpen = false;
        view.phase = BattlePhase.ACTION;
        view.currentAction = new ActionView(CombatantId.MONK, "Focus Combo", BattleCommand.FOCUS_COMBO, null, 0f, 6f);
        frame(new BattleEvent.ActionStarted(CombatantId.MONK, "Focus Combo", BattleCommand.FOCUS_COMBO, null, 6f));
        run(HudAnimator.CINEMATIC_SLIDE_SECONDS / 2f);
        int half = hud(null).countPainted(0, H / 2, W, H);
        run(HudAnimator.CINEMATIC_SLIDE_SECONDS);
        // The cinematic letterbox bar owns the very bottom of the screen now; everything above it is clear.
        int barTop = (int) Math.floor(FocusBattleLayout.letterboxBottomRect(W, H, UI)[1]);
        assertEquals(0, hud(null).countPainted(0, H / 2, W, barTop), "E1–E4 are off the bottom edge");
        assertTrue(half > 0, "having slid there, not vanished");

        float[] banner = FocusBattleLayout.actionBannerRect(W, H, UI);
        assertTrue(hud(null).countPainted(banner) > 8000, "while the gold banner names the combo");

        view.currentAction = null;
        view.phase = BattlePhase.RUNNING;
        run(HudAnimator.CINEMATIC_SLIDE_SECONDS + 2 * DT);
        assertTrue(hud(null).countPainted(FocusBattleLayout.partyWindowRect(W, H, UI)) > 30_000, "and back");
    }

    @Test
    void theHelpLineCrossFades() {
        view.commandWindowOpen = true;
        run(0.5f);
        float[] help = FocusBattleLayout.helpStripRect(W, H, UI);
        BattleRasterFixture strike = hud(null);
        screen.menuState().moveDown();
        run(HudAnimator.HELP_FADE_OUT_SECONDS + DT);
        BattleRasterFixture between = hud(null);
        run(HudAnimator.HELP_FADE_IN_SECONDS + DT);
        BattleRasterFixture flurry = hud(null);
        assertTrue(flurry.diff(strike, help) > 400, "a different sentence");
        assertTrue(between.diff(strike, help) > 200 && between.diff(flurry, help) > 200,
                "with a moment in between where neither is fully there");
    }

    @Test
    void floatersAndTransitionReachTheCanvasThroughTheScreensLayers() {
        run(0.1f);
        assertEquals(0, hud(shoulderCamera()).diff(hud(shoulderCamera())), "deterministic");
        BattleRasterFixture quiet = hud(shoulderCamera());
        frame(new BattleEvent.DamageDealt(CombatantId.ARCHON, 77f, DamageFlavor.CRITICAL));
        assertEquals(1, screen.floaters().live().size());
        BattleRasterFixture hit = hud(shoulderCamera());
        float[] bounds = FlowLayout.floaterBounds(W, H, UI);
        assertTrue(hit.diff(quiet, 0, (int) bounds[1] - 40, W, (int) bounds[3]) > 300, "the number is on screen");

        bindAScreen();
        view.phase = BattlePhase.INTRO;
        screen.bind(view, NO_INPUT, FakeBattleView.crucibleLayout(), NO_HOST);
        screen.update(0f);
        assertEquals(W * H, hud(null).countExactly(0xFFFFFFFF, 0, 0, W, H),
                "the encounter opens on a white frame, above every other layer");
    }
}
