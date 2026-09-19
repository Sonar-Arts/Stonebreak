package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.CombatantId;

import java.util.function.Consumer;

/**
 * A stand-in-place monk action that is exactly one authored clip with one effect: Swift Step,
 * Martial Surge, Meditate (effect on the clip's effect cue) and Guard (effect as the clip starts).
 */
final class SimpleAction extends BattleAction {

    private final BattleCommand command;
    private final BattleClip clip;
    private final float effectTime;
    private final Consumer<BattleContext> effect;
    private boolean applied;

    /** The effect fires on the clip's first cue, or as the action begins when the clip has none. */
    SimpleAction(BattleCommand command, BattleClip clip, Consumer<BattleContext> effect) {
        this.command = command;
        this.clip = clip;
        this.effectTime = clip.cueCount() > 0 ? Math.min(clip.cue(0), clip.duration()) : 0f;
        this.effect = effect;
    }

    @Override CombatantId actor() { return CombatantId.MONK; }
    @Override String displayName() { return command.displayName(); }
    @Override BattleCommand command() { return command; }
    @Override float duration() { return clip.duration(); }
    @Override boolean spendsTurn() { return command.endsTurn(); }

    @Override
    void begin(BattleContext ctx) {
        if (effectTime <= 0f) apply(ctx);
    }

    private float nextCueTime() {
        return applied ? clip.duration() : effectTime;
    }

    @Override
    float timeToNextCue() {
        return Math.max(0f, nextCueTime() - elapsed);
    }

    @Override
    protected void fireCue(BattleContext ctx) {
        elapsed = nextCueTime();
        if (applied) finish();
        else apply(ctx);
    }

    private void apply(BattleContext ctx) {
        applied = true;
        effect.accept(ctx);
    }

    @Override
    void applyPose(BattleContext ctx) {
        ctx.monk.drive(clip, elapsed, 0f, 0f);
    }
}
