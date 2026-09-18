package com.stonebreak.battle;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;

/**
 * One executing action as a timeline of cues. {@link BattleState} never steps past the next cue
 * ({@link #timeToNextCue()}), so however large the frame's dt, cues fire one at a time and in order.
 */
abstract class BattleAction {

    protected float elapsed;
    private boolean finished;

    abstract CombatantId actor();
    abstract String displayName();
    BattleCommand command() { return null; }
    EnemyAction enemyAction() { return null; }

    /** Projected total length; timed-input actions revise it as prompts resolve early. */
    abstract float duration();

    /** Side effects of starting, raised right after ActionStarted. */
    void begin(BattleContext ctx) {}

    /** Seconds from now to the next cue. Never negative. */
    abstract float timeToNextCue();

    /** Fires the cue {@link #timeToNextCue()} was counting down to. */
    protected abstract void fireCue(BattleContext ctx);

    /** Advances clocks local to the action (combo step timer, clip clock). */
    protected void tick(float dt) {}

    /** Writes this action's pose onto its actor. */
    abstract void applyPose(BattleContext ctx);

    /** Monk gauge value once this action is over. */
    float atbRestart() { return 0f; }

    /** False for a free action (Martial Surge): the actor's gauge is left exactly as it was. */
    boolean spendsTurn() { return true; }

    PromptView prompt(BattleContext ctx) { return null; }
    TelegraphView telegraph() { return null; }
    void pressConfirm(BattleContext ctx) {}
    void pressDirection(ComboDirection direction, BattleContext ctx) {}

    /** {@code dt} must not exceed {@link #timeToNextCue()}. */
    final void advance(float dt, BattleContext ctx) {
        // Same expression the scheduler sliced by: a slice that ends on the cue always fires it.
        boolean due = dt >= timeToNextCue();
        elapsed += dt;
        tick(dt);
        if (due) fireCue(ctx);
        if (!finished) applyPose(ctx);
    }

    final boolean finished() { return finished; }
    protected final void finish() { finished = true; }

    final ActionView view() {
        return new ActionView(actor(), displayName(), command(), enemyAction(), elapsed, Math.max(elapsed, duration()));
    }

    /** Smoothstep: the dash eases out of the home ring and into the strike anchor. */
    static float ease(float x) {
        float t = Math.max(0f, Math.min(1f, x));
        return t * t * (3f - 2f * t);
    }

    static float ratio(float time, float span) {
        return span <= 0f ? 1f : time / span;
    }

    /**
     * 0→1 over the first {@code ease} seconds of [{@code start}, {@code end}], 1 in between, 1→0 over the
     * last: the kick stand-off, stepped into and out of rather than snapped.
     */
    static float envelope(float time, float start, float end, float ease) {
        if (time <= start || time >= end) return 0f;
        float edge = Math.min(ease, (end - start) * 0.5f);
        if (edge <= 0f) return 1f;
        return Math.min(ease((time - start) / edge), ease((end - time) / edge));
    }
}
