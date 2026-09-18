package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.battle.timed.ParryWindow;

/**
 * One telegraphed Archon attack. The timeline is the authored clip: telegraph until
 * {@link EnemyAction#impactTime()}, the blow, then the rest of the clip as recovery.
 */
final class ArchonAttackAction extends BattleAction {

    private final EnemyAction action;
    private final BattleClip clip;
    private final BattleConfig.Archon tuning;
    private final ParryWindow window;
    /** When the monk's parry clip must start for its deflect to meet this attack's impact. */
    private final float parryPoseTime;

    private boolean impacted;
    private boolean parryAttemptSpent;
    private boolean parryLanded;
    private boolean parryPosed;
    /** The cue {@link #timeToNextCue()} last counted down to. */
    private float pendingCue;

    ArchonAttackAction(EnemyAction action, BattleConfig config) {
        this.action = action;
        this.clip = ArchonClips.attack(action);
        this.tuning = config.archon();
        this.window = ParryWindow.around(action.impactTime(), tuning.parryLeadSeconds(), tuning.parryLagSeconds());
        this.parryPoseTime = action.impactTime() - Math.max(0f, config.support().parryContactOffset());
    }

    @Override CombatantId actor() { return CombatantId.ARCHON; }
    @Override String displayName() { return action.displayName(); }
    @Override EnemyAction enemyAction() { return action; }
    @Override float duration() { return action.clipDuration(); }

    boolean telegraphing() { return !impacted; }

    @Override
    void begin(BattleContext ctx) {
        ctx.raise(new BattleEvent.TelegraphStarted(action, action.impactTime()));
        if (ctx.monk.has(BattleStatus.GUARDING)) ctx.raise(new BattleEvent.PromptOpened(PromptKind.PARRY));
    }

    /** Guard went up mid-telegraph: the parry prompt opens late. */
    void guardRaised(BattleContext ctx) {
        if (parryPromptOpen(ctx)) ctx.raise(new BattleEvent.PromptOpened(PromptKind.PARRY));
    }

    private boolean parryPosePending() {
        return parryLanded && !parryPosed && !impacted;
    }

    private float impactCue() {
        return Math.min(action.impactTime(), action.clipDuration());
    }

    private float nextCueTime() {
        if (impacted) return action.clipDuration();
        return parryPosePending() ? Math.min(impactCue(), Math.max(parryPoseTime, elapsed)) : impactCue();
    }

    @Override
    float timeToNextCue() {
        pendingCue = nextCueTime();
        return Math.max(0f, pendingCue - elapsed);
    }

    @Override
    protected void fireCue(BattleContext ctx) {
        elapsed = pendingCue; // snap: accumulated float error never shifts later cues
        if (impacted) {
            finish();
            return;
        }
        if (parryPosePending() && elapsed >= parryPoseTime) startParryPose(ctx);
        if (elapsed >= impactCue()) {
            impacted = true;
            ctx.archonHit(action, parryLanded);
        }
    }

    /**
     * The parry clip's deflect has to meet the blade: an early press waits for the right moment, a
     * late one enters the clip part-way through, so either way its contact lands on the impact.
     */
    private void startParryPose(BattleContext ctx) {
        parryPosed = true;
        ctx.monk.playFrom(Math.max(0f, elapsed - parryPoseTime), MonkClips.PARRY, MonkClips.GUARD_EXIT);
    }

    private boolean parryPromptOpen(BattleContext ctx) {
        return !impacted && !parryAttemptSpent && ctx.monk.has(BattleStatus.GUARDING);
    }

    @Override
    void pressConfirm(BattleContext ctx) {
        if (!parryPromptOpen(ctx)) return;
        // One attempt per telegraph, so mashing confirm is never a substitute for timing.
        parryAttemptSpent = true;
        TimedGrade grade = window.press(elapsed);
        parryLanded = grade == TimedGrade.PERFECT;
        ctx.raise(new BattleEvent.PromptResolved(PromptKind.PARRY, grade, 0));
        if (parryLanded && elapsed >= parryPoseTime) startParryPose(ctx);
    }

    @Override
    PromptView prompt(BattleContext ctx) {
        if (!parryPromptOpen(ctx)) return null;
        return new PromptView.Parry(elapsed, window.start(), window.end(), action.impactTime());
    }

    @Override
    TelegraphView telegraph() {
        if (impacted) return null;
        return new TelegraphView(action, elapsed, action.impactTime(), window.start(), window.end(), false);
    }

    /** Stunned mid-attack: the telegraph (if still up) is called off and the clip abandoned. */
    void cancel(BattleContext ctx) {
        if (!impacted) ctx.raise(new BattleEvent.TelegraphCancelled(action));
        impacted = true;
        finish();
    }

    @Override
    void applyPose(BattleContext ctx) {
        ctx.archon.drive(clip, elapsed, glide(), 0f);
    }

    private float glide() {
        if (action == EnemyAction.FROST_CAST) return 0f; // cast from the home ring
        float reach = action.impactTime() * tuning.glideFraction();
        float backStart = action.impactTime() + tuning.glideBackDelay();
        if (elapsed < reach) return ease(ratio(elapsed, reach));
        if (elapsed < backStart) return 1f;
        return 1f - ease(ratio(elapsed - backStart, action.clipDuration() - backStart));
    }
}
