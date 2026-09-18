package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.ui.focusBattle.BattleMenuState;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static com.stonebreak.ui.focusBattle.timed.TimedScenes.H;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.UI_SCALE;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.W;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The integration seam: install / update / reset through the real HUD renderer. */
class TimedInputLayersTest {

    private static final Matrix4f CAMERA = TimedScenes.cameraPlacing(TimedScenes.archonChest(), 640f, 300f, 90f, W, H);

    private static BattleRasterFixture hud(TimedInputLayers layers, FakeBattleView view) {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        SkijaFocusBattleRenderer renderer = new SkijaFocusBattleRenderer(null);
        if (layers != null) layers.install(renderer);
        renderer.paintHud(fx.ui, fx.canvas, W, H, UI_SCALE, view, new BattleMenuState(), null, CAMERA);
        return fx;
    }

    @Test
    void installedButIdleTheHudIsPixelIdentical() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        for (int i = 0; i < 10; i++) TimedScenes.frame(layers, view, 0.05f);
        assertEquals(0, hud(layers, view).diff(hud(null, view)), "no prompt, no trigger → nothing painted");
        assertTrue(layers.state().idle());
    }

    @Test
    void screenFxGoUnderTheWindowsAndPromptsOverThem() {
        // Underlay: a low-HP vignette must not tint the party window's opaque bar track.
        FakeBattleView hurt = new FakeBattleView();
        hurt.monk.hp = 10f;
        TimedInputLayers fxLayers = new TimedInputLayers(TimedScenes.LAYOUT);
        for (int i = 0; i < 30; i++) TimedScenes.frame(fxLayers, hurt, 0.05f);
        BattleRasterFixture with = hud(fxLayers, hurt), without = hud(null, hurt);
        assertTrue(with.diff(without, 0, 0, 200, 60) > 1000, "vignette visible in the open corner");
        float[] party = FocusBattleLayout.partyWindowRect(W, H, UI_SCALE);
        // The bars' 1px outlines are opaque and unblended: tinted from above they would all change colour.
        int outlines = without.countExactly(com.stonebreak.ui.focusBattle.FocusBattleTheme.BAR_OUTLINE,
                (int) party[0], (int) party[1], (int) (party[0] + party[2]), (int) (party[1] + party[3]));
        assertTrue(outlines > 200);
        assertEquals(outlines, with.countExactly(com.stonebreak.ui.focusBattle.FocusBattleTheme.BAR_OUTLINE,
                (int) party[0], (int) party[1], (int) (party[0] + party[2]), (int) (party[1] + party[3])),
                "opaque HUD pixels are untouched: the vignette is beneath the windows");
        assertTrue(with.diff(without, (int) party[0] - 40, (int) party[1], (int) party[0] - 4, (int) (party[1] + party[3])) > 500,
                "while the scene right beside the window is tinted");

        // Overlay: the combo strip covers whatever the HUD put in its rect.
        FakeBattleView combo = TimedScenes.comboView(1, 0.2f, TimedGrade.PERFECT);
        TimedInputLayers strip = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(strip, combo, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
        TimedScenes.frame(strip, combo, 0.5f);
        float[] rect = FocusBattleLayout.comboStripRect(W, H, UI_SCALE);
        assertTrue(hud(strip, combo).diff(hud(null, combo), rect) > rect[2] * rect[3] * 0.9f);
    }

    @Test
    void resetClearsEveryLayer() {
        FakeBattleView busy = TimedScenes.comboView(2, 0.3f, TimedGrade.GOOD, TimedGrade.MISS);
        busy.monk.hp = 5f;
        busy.focus = 100f;
        busy.monk.statuses.add(new StatusView(BattleStatus.CHILLED, 9f, 1));
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, busy, 0.3f, new BattleEvent.PromptOpened(PromptKind.COMBO),
                new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 0),
                new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.PERFECT, 0),
                new BattleEvent.DamageDealt(CombatantId.ARCHON, 50f, BattleEvent.DamageFlavor.CRITICAL),
                new BattleEvent.Ended(BattleOutcome.DEFEAT));
        TimedScenes.frame(layers, busy, 0.1f);
        FakeBattleView calm = new FakeBattleView();
        assertTrue(TimedScenes.paintLayers(layers, calm, CAMERA).countPainted(0, 0, W, H) > 10_000);

        layers.reset();
        assertTrue(layers.state().idle());
        assertEquals(0, TimedScenes.paintLayers(layers, calm, CAMERA).countPainted(0, 0, W, H), "a retry starts clean");
    }

    @Test
    void theSameFramesAlwaysGiveTheSamePicture() {
        BattleRasterFixture[] shots = new BattleRasterFixture[2];
        for (int run = 0; run < 2; run++) {
            TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
            FakeBattleView view = TimedScenes.ringView(1, 0.4f);
            view.monk.hp = 30f;
            view.monk.statuses.add(new StatusView(BattleStatus.CHILLED, 9f, 1));
            TimedScenes.frame(layers, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.MISS, 0));
            for (int i = 0; i < 12; i++) TimedScenes.frame(layers, view, 1f / 60f);
            shots[run] = TimedScenes.paintLayers(layers, view, CAMERA);
        }
        assertTrue(shots[0].countPainted(0, 0, W, H) > 5000);
        assertEquals(0, shots[0].diff(shots[1]), "no wall clock, no randomness");
    }

    @Test
    void paintingNeverAdvancesAnything() {
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        FakeBattleView view = TimedScenes.parryView(1.1f);
        view.monk.hp = 20f;
        TimedScenes.frame(layers, view, 0.2f, new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.GOOD, 0));
        BattleRasterFixture first = TimedScenes.paintLayers(layers, view, CAMERA);
        for (int i = 0; i < 5; i++) TimedScenes.paintLayers(layers, view, CAMERA);
        assertEquals(0, first.diff(TimedScenes.paintLayers(layers, view, CAMERA)));
    }

    @Test
    void theStageCanArriveAfterConstruction() {
        // The screen builds its layers once, before it is bound to a battle.
        TimedInputLayers layers = new TimedInputLayers(null);
        FakeBattleView view = TimedScenes.ringView(0, 0.62f);
        TimedScenes.frame(layers, view, 0.016f);
        BattleRasterFixture centred = TimedScenes.paintLayers(layers, view, CAMERA);
        layers.setStage(TimedScenes.LAYOUT);
        BattleRasterFixture anchored = TimedScenes.paintLayers(layers, view, CAMERA);
        assertTrue(centred.diff(anchored) > 2000, "bound: the ring moves from the centre to the Archon");
        assertEquals(0, anchored.diff(TimedScenes.paintLayers(new TimedInputLayersTestProbe().fresh(view), view, CAMERA)));
    }

    /** A constructor-bound instance fed the same single frame, for comparison. */
    private static final class TimedInputLayersTestProbe {
        TimedInputLayers fresh(FakeBattleView view) {
            TimedInputLayers l = new TimedInputLayers(TimedScenes.LAYOUT);
            TimedScenes.frame(l, view, 0.016f);
            return l;
        }
    }

    @Test
    void guardWordsCanBeLeftToTheFloaters() {
        Matrix4f monkCamera = TimedScenes.cameraPlacing(TimedScenes.monkTorso(), 820f, 440f, 120f, W, H);
        FakeBattleView view = new FakeBattleView();
        TimedInputLayers with = new TimedInputLayers(TimedScenes.LAYOUT), without = new TimedInputLayers(TimedScenes.LAYOUT);
        without.setGuardWordsEnabled(false);
        for (TimedInputLayers l : new TimedInputLayers[]{with, without}) {
            TimedScenes.frame(l, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.PERFECT, 0));
            TimedScenes.frame(l, view, 0.06f);
        }
        BattleRasterFixture a = TimedScenes.paintLayers(with, view, monkCamera), b = TimedScenes.paintLayers(without, view, monkCamera);
        assertTrue(a.countExactly(0xFFFFFFFF, 0, 0, W, H) > 400 && b.countExactly(0xFFFFFFFF, 0, 0, W, H) == 0, "word gone");
        assertEquals(W * H, b.countPainted(0, 0, W, H), "flash stays");
    }

    @Test
    void aMissingStageLayoutOrViewIsHarmless() {
        TimedInputLayers layers = new TimedInputLayers(null);
        FakeBattleView view = TimedScenes.ringView(0, 0.3f);
        TimedScenes.frame(layers, view, 0.016f);
        assertTrue(TimedScenes.paintLayers(layers, view, CAMERA).countPainted(W / 2 - 200, H / 2 - 200, W / 2 + 200, H / 2 + 200) > 1000,
                "no layout → centred ring");
        layers.update(null, 0.1f);
        layers.install(null);
    }
}
