package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.List;

/** Shared scene builders for the timed-input tests: views, a placeable camera matrix, layer rendering. */
final class TimedScenes {

    private TimedScenes() {}

    static final int W = 1280;
    static final int H = 720;
    static final float UI_SCALE = 1f;
    static final float SCALE = FocusBattleLayout.effectiveScale(W, H, UI_SCALE);
    static final BattleStageLayout LAYOUT = FakeBattleView.crucibleLayout();

    /** The model's default Flurry ring timings. */
    static final float RING_DURATION = 0.9f, PERFECT_START = 0.55f, PERFECT_END = 0.69f,
            GOOD_START = 0.46f, GOOD_END = 0.78f;

    static final List<ComboDirection> SEQUENCE = List.of(ComboDirection.LEFT, ComboDirection.UP,
            ComboDirection.RIGHT, ComboDirection.DOWN, ComboDirection.LEFT, ComboDirection.RIGHT);

    static PromptView.Ring ring(int hitIndex, float elapsed) {
        return new PromptView.Ring(hitIndex, 3, elapsed, RING_DURATION, PERFECT_START, PERFECT_END,
                GOOD_START, GOOD_END);
    }

    static FakeBattleView ringView(int hitIndex, float elapsed) {
        FakeBattleView view = new FakeBattleView();
        view.prompt = ring(hitIndex, elapsed);
        return view;
    }

    /** Guarding against an attack that lands at 1.25 s; the parry window is its last 0.25 s. */
    static FakeBattleView parryView(float elapsed) {
        FakeBattleView view = new FakeBattleView();
        view.prompt = new PromptView.Parry(elapsed, 1.0f, 1.25f, 1.25f);
        view.telegraph = new TelegraphView(EnemyAction.values()[0], elapsed, 1.25f, 1.0f, 1.25f, false);
        return view;
    }

    static FakeBattleView comboView(int index, float stepElapsed, TimedGrade... results) {
        FakeBattleView view = new FakeBattleView();
        view.prompt = new PromptView.Combo(SEQUENCE, index, stepElapsed, 0.9f, List.of(results));
        view.currentAction = new ActionView(CombatantId.MONK, "Focus Combo", BattleCommand.FOCUS_COMBO, null, 1f, 8f);
        return view;
    }

    /**
     * An affine "camera" that puts {@code world} at window pixel {@code (sx, sy)} with one block
     * covering {@code pixelsPerBlock} pixels there — all the overlays read from a view-projection.
     */
    static Matrix4f cameraPlacing(Vector3f world, float sx, float sy, float pixelsPerBlock, int w, int h) {
        float ndcX = sx / w * 2f - 1f, ndcY = 1f - sy / h * 2f;
        return new Matrix4f().translate(ndcX, ndcY, 0f)
                .scale(2f * pixelsPerBlock / w, 2f * pixelsPerBlock / h, 0.001f)
                .translate(-world.x, -world.y, -world.z);
    }

    static Vector3f archonChest() {
        return LAYOUT.bodyPoint(CombatantId.ARCHON, com.stonebreak.battle.api.ActorPose.IDLE,
                TimedLayout.RING_BODY_FRACTION);
    }

    static Vector3f monkTorso() {
        return LAYOUT.bodyPoint(CombatantId.MONK, com.stonebreak.battle.api.ActorPose.IDLE,
                TimedLayout.PARRY_BODY_FRACTION);
    }

    /** Feeds one frame: {@code events} are visible to exactly this update. */
    static void frame(TimedInputLayers layers, FakeBattleView view, float dt, BattleEvent... events) {
        view.events.clear();
        view.events.addAll(List.of(events));
        layers.update(view, dt);
        view.events.clear();
    }

    /** Only the four timed layers (underlay first), on a fresh snow canvas. */
    static BattleRasterFixture paintLayers(TimedInputLayers layers, FakeBattleView view, Matrix4fc vp) {
        return paintLayers(layers, view, vp, W, H, null);
    }

    static BattleRasterFixture paintLayers(TimedInputLayers layers, FakeBattleView view, Matrix4fc vp, int w, int h,
                                           Integer clearColor) {
        BattleRasterFixture fx = new BattleRasterFixture(w, h);
        if (clearColor != null) fx.canvas.clear(clearColor);
        float s = FocusBattleLayout.effectiveScale(w, h, UI_SCALE);
        for (SkijaFocusBattleRenderer.Layer layer : List.of(layers.screenFxLayer(), layers.timingRingLayer(),
                layers.parryLayer(), layers.comboStripLayer())) {
            layer.paint(fx.ui, fx.canvas, w, h, s, UI_SCALE, view, BattleHudAnimState.NEUTRAL, vp);
        }
        return fx;
    }
}
