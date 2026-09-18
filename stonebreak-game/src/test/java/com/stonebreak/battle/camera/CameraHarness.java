package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.battle.stage.BattleStageLayouts;
import com.stonebreak.battletest.BattleTestArena;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a {@link BattleCameraSystem} with a hand-set {@link FakeBattleView}: queue events, step
 * frames, and every frame is checked finite. Shared fixture, not a test class.
 */
final class CameraHarness {

    static final float DT = 1f / 60f;

    final FakeBattleView view = new FakeBattleView();
    final BattleCameraSystem camera;
    /** Every frame produced so far, for determinism / continuity checks. */
    final List<CameraFrame> frames = new ArrayList<>();
    final List<Boolean> cuts = new ArrayList<>();

    CameraHarness(long seed) {
        this(realLayout(), seed, false);
    }

    CameraHarness(BattleStageLayout layout, long seed, boolean staticOnly) {
        camera = new BattleCameraSystem(layout, seed, staticOnly);
    }

    static BattleStageLayout realLayout() {
        try {
            return BattleStageLayouts.fromArena(BattleTestArena.load());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One update with the queued events, which are then cleared (events live for exactly one frame). */
    CameraHarness tick() {
        camera.update(view, DT);
        view.events.clear();
        CameraFrame f = camera.frame();
        assertTrue(f.eye().isFinite() && f.target().isFinite() && Float.isFinite(f.fovDeg()) && Float.isFinite(f.rollDeg()),
                "frame must be finite during " + camera.shotName());
        assertTrue(f.eye().distance(f.target()) > 1.0e-3f, "eye and target must not coincide");
        assertTrue(f.fovDeg() >= 30f && f.fovDeg() <= 90f, "fov in range");
        frames.add(f);
        cuts.add(camera.cutThisFrame());
        return this;
    }

    CameraHarness run(float seconds) {
        int n = Math.round(seconds / DT);
        for (int i = 0; i < n; i++) tick();
        return this;
    }

    CameraHarness emit(BattleEvent event) {
        view.events.add(event);
        return this;
    }

    /** Monk command starts: event + the matching {@code currentAction}. */
    CameraHarness monkStarts(BattleCommand command) {
        view.commandWindowOpen = false;
        view.currentAction = new ActionView(CombatantId.MONK, command.displayName(), command, null, 0f, 3f);
        return emit(new BattleEvent.ActionStarted(CombatantId.MONK, command.displayName(), command, null, 3f));
    }

    CameraHarness monkFinishes() {
        view.currentAction = null;
        return emit(new BattleEvent.ActionFinished(CombatantId.MONK));
    }

    /** Archon attack starts: telegraph + action, as the model raises them together. */
    CameraHarness archonStarts(EnemyAction action) {
        view.telegraph = new TelegraphView(action, 0f, action.impactTime(), action.impactTime() - 0.25f,
                action.impactTime() + 0.1f, false);
        view.currentAction = new ActionView(CombatantId.ARCHON, action.displayName(), null, action, 0f, action.clipDuration());
        emit(new BattleEvent.TelegraphStarted(action, action.impactTime()));
        return emit(new BattleEvent.ActionStarted(CombatantId.ARCHON, action.displayName(), null, action, action.clipDuration()));
    }

    CameraHarness archonFinishes() {
        view.telegraph = null;
        view.currentAction = null;
        return emit(new BattleEvent.ActionFinished(CombatantId.ARCHON));
    }

    CameraHarness impact(CombatantId actor) {
        if (actor == CombatantId.ARCHON) view.telegraph = null;
        return emit(new BattleEvent.Impact(actor, actor.opponent(), 0, 1));
    }

    static PromptView.Ring ring(int hit) {
        return new PromptView.Ring(hit, 3, 0.1f, 0.8f, 0.5f, 0.6f, 0.4f, 0.7f);
    }

    static PromptView.Parry parry() {
        return new PromptView.Parry(0.1f, 0.4f, 0.75f, 0.66f);
    }
}
