package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
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

    @Test
    void idleLayersPaintNothingAtAll() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        for (int i = 0; i < 10; i++) TimedScenes.frame(layers, view, 0.05f);
        assertEquals(0, TimedScenes.paintLayers(layers, view, CAMERA).countPainted(0, 0, W, H),
                "no prompt, no trigger → the HUD underneath is pixel-identical");
        assertTrue(layers.state().idle());
    }

    @Test
    void theScreenFxAreTheFirstLayerAndThePromptsComeAfter() {
        // install() registers screenFxLayer as the underlay and these three as overlays, in this order;
        // the renderer's own tests pin where underlays and overlays sit relative to the windows.
        FakeBattleView hurt = TimedScenes.comboView(1, 0.2f, TimedGrade.PERFECT);
        hurt.monk.hp = 10f;
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, hurt, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
        for (int i = 0; i < 30; i++) TimedScenes.frame(layers, hurt, 0.05f);

        BattleRasterFixture fxOnly = new BattleRasterFixture(W, H);
        layers.screenFxLayer().paint(fxOnly.ui, fxOnly.canvas, W, H, TimedScenes.SCALE, UI_SCALE, hurt, null, CAMERA);
        assertTrue(fxOnly.countPainted(0, 0, 200, 60) > 1000, "vignette visible in the open corner");
        float[] rect = FocusBattleLayout.comboStripRect(W, H, UI_SCALE);
        assertEquals(0, fxOnly.countPainted(rect), "the underlay leaves the strip's place alone here");

        BattleRasterFixture all = TimedScenes.paintLayers(layers, hurt, CAMERA);
        assertTrue(all.diff(fxOnly, rect) > rect[2] * rect[3] * 0.9f, "the strip covers its rect, over the FX");
        assertEquals(0, all.diff(fxOnly, 0, 0, 200, 60), "and leaves the FX alone elsewhere");
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
        assertTrue(a.countExactly(ParryOverlay.PARRY_WORD, 0, 0, W, H) > 400
                && b.countExactly(ParryOverlay.PARRY_WORD, 0, 0, W, H) == 0, "word gone");
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
