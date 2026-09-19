package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.battle.timed.ComboString;

import java.util.ArrayList;
import java.util.List;

/**
 * Focus Combo: dash in, then ONE continuous combo clip that is never restarted. A prompt opens a
 * little before each authored contact; if it is still unanswered just short of the contact the clip
 * freezes there and waits (the step timer keeps counting). The right direction lets the clip run on
 * and the hit lands ON the contact; a wrong one or a timeout ends the string and the monk breaks off.
 * The last contact is the finisher: reaching it means the string was flawless, and it carries the
 * finisher bonus. The rest of the clip then plays out before the dash home.
 *
 * <p>The clip clock only ever advances with the action clock (or holds), so the pose stays a pure
 * function of what the model has simulated: pausing or slowing {@code dt} upstream slows both.
 */
final class ComboAction extends BattleAction {

    private enum Stage { DASH_OUT, STRING, BREAK_OFF, DASH_BACK }

    private enum Cue { STAGE_END, PROMPT_OPEN, FREEZE, TIMEOUT, CONTACT, CLIP_END }

    private static final BattleClip CLIP = MonkClips.FOCUS_COMBO;

    private final BattleConfig.Combo tuning;
    private final BattleConfig.Melee melee;
    private final ComboString string;
    private final List<TimedGrade> results = new ArrayList<>();

    private Stage stage = Stage.DASH_OUT;
    private float stageElapsed;
    private float clipTime;
    /** The prompt that is open, or the next one to open. */
    private int index;
    private boolean promptOpen;
    private boolean promptAnnounced;
    /** The current prompt was answered correctly; its hit is on its way to the contact. */
    private boolean hitPending;
    private boolean frozen;
    private float stepElapsed;
    private float standoffAtBreak;
    private Cue pendingCue = Cue.STAGE_END;

    /** Draws the direction string immediately so the random stream does not depend on frame timing. */
    ComboAction(BattleConfig config, java.util.Random random) {
        this.tuning = config.combo();
        this.melee = config.melee();
        int length = Math.max(1, Math.min(tuning.length(), CLIP.cueCount()));
        this.string = ComboString.random(random, length, tuning.stepSeconds());
    }

    @Override CombatantId actor() { return CombatantId.MONK; }
    @Override String displayName() { return BattleCommand.FOCUS_COMBO.displayName(); }
    @Override BattleCommand command() { return BattleCommand.FOCUS_COMBO; }

    List<ComboDirection> sequence() { return string.sequence(); }

    /** Seconds into the combo clip; holds while the clip is frozen on an unanswered prompt. */
    float clipTime() { return clipTime; }
    boolean frozen() { return frozen; }

    @Override
    void begin(BattleContext ctx) {
        ctx.focus.consumeAll();
    }

    // ---- timeline -------------------------------------------------------------------------------

    private float promptOpenAt(int prompt) {
        return Math.max(0f, CLIP.cue(prompt) - tuning.promptLeadSeconds());
    }

    private float freezeAt(int prompt) {
        return CLIP.cue(prompt) - Math.max(0f, tuning.freezeOffsetSeconds());
    }

    private float stageDuration() {
        return stage == Stage.BREAK_OFF ? tuning.breakOffSeconds() : melee.dashSeconds();
    }

    /** Decides what happens next and how long until it does; remembered for {@link #fireCue}. */
    @Override
    float timeToNextCue() {
        if (stage != Stage.STRING) {
            pendingCue = Cue.STAGE_END;
            return Math.max(0f, stageDuration() - stageElapsed);
        }
        if (hitPending) {
            pendingCue = Cue.CONTACT;
            return Math.max(0f, CLIP.cue(index) - clipTime);
        }
        if (promptOpen) {
            float timeout = Math.max(0f, string.stepDuration() - stepElapsed);
            float freeze = frozen ? Float.POSITIVE_INFINITY : Math.max(0f, freezeAt(index) - clipTime);
            pendingCue = freeze < timeout ? Cue.FREEZE : Cue.TIMEOUT;
            return Math.min(freeze, timeout);
        }
        if (index < string.length()) {
            pendingCue = Cue.PROMPT_OPEN;
            return Math.max(0f, promptOpenAt(index) - clipTime);
        }
        pendingCue = Cue.CLIP_END;
        return Math.max(0f, CLIP.duration() - clipTime);
    }

    @Override
    protected void tick(float dt) {
        if (stage != Stage.STRING) {
            stageElapsed += dt;
            return;
        }
        if (promptOpen) stepElapsed += dt;
        if (!frozen) clipTime += dt;
    }

    @Override
    protected void fireCue(BattleContext ctx) {
        switch (pendingCue) {
            case STAGE_END -> {
                switch (stage) {
                    case DASH_OUT -> enter(Stage.STRING);
                    case BREAK_OFF -> enter(Stage.DASH_BACK);
                    default -> finish();
                }
            }
            case PROMPT_OPEN -> {
                clipTime = Math.max(clipTime, promptOpenAt(index));
                promptOpen = true;
                stepElapsed = 0f;
                if (!promptAnnounced) {
                    // Once per string: the HUD opens its strip on this and tracks the steps through the view.
                    promptAnnounced = true;
                    ctx.raise(new BattleEvent.PromptOpened(PromptKind.COMBO));
                }
            }
            case FREEZE -> {
                clipTime = freezeAt(index);
                frozen = true;
            }
            case TIMEOUT -> miss(ctx);
            case CONTACT -> land(ctx);
            case CLIP_END -> enter(Stage.DASH_BACK);
        }
    }

    @Override
    void pressDirection(ComboDirection direction, BattleContext ctx) {
        if (stage != Stage.STRING || !promptOpen || direction == null) return;
        TimedGrade grade = string.press(index, direction, stepElapsed, frozen);
        if (grade == TimedGrade.MISS) {
            miss(ctx);
            return;
        }
        results.add(grade);
        ctx.raise(new BattleEvent.PromptResolved(PromptKind.COMBO, grade, index));
        promptOpen = false;
        frozen = false;
        hitPending = true;
    }

    /** The answered prompt's blow reaches its contact. */
    private void land(BattleContext ctx) {
        clipTime = CLIP.cue(index);
        hitPending = false;
        boolean finisher = index == string.length() - 1;
        float multiplier = results.get(index) == TimedGrade.PERFECT ? tuning.perfectMultiplier() : 1f;
        ctx.stats.comboHit();
        // One blow, one number: the flawless bonus rides on the finisher instead of being a seventh hit.
        ctx.monkHit(tuning.damage() * multiplier + (finisher ? tuning.finisherDamage() : 0f), 1f,
                index, string.length());
        index++;
        if (finisher) ctx.raise(new BattleEvent.ComboFinished(results.size(), true));
    }

    private void miss(BattleContext ctx) {
        ctx.raise(new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.MISS, index));
        ctx.raise(new BattleEvent.ComboFinished(results.size(), false));
        standoffAtBreak = standoff();
        promptOpen = false;
        frozen = false;
        enter(Stage.BREAK_OFF);
    }

    private void enter(Stage next) {
        stage = next;
        stageElapsed = 0f;
    }

    @Override
    float duration() {
        float dash = melee.dashSeconds();
        float remaining = switch (stage) {
            case DASH_OUT -> dash - stageElapsed + CLIP.duration() + dash;
            // Best case from here: no further freezes.
            case STRING -> CLIP.duration() - clipTime + dash;
            case BREAK_OFF -> tuning.breakOffSeconds() - stageElapsed + dash;
            case DASH_BACK -> dash - stageElapsed;
        };
        return elapsed + Math.max(0f, remaining);
    }

    @Override
    PromptView prompt(BattleContext ctx) {
        if (stage != Stage.STRING || !promptOpen) return null;
        return new PromptView.Combo(string.sequence(), index, stepElapsed, string.stepDuration(), results);
    }

    /** Kicking distance around the front kick, as a function of CLIP time so it holds through a freeze. */
    private float standoff() {
        if (MonkClips.FOCUS_COMBO_KICK_CONTACT >= CLIP.cueCount()) return 0f;
        float kick = CLIP.cue(MonkClips.FOCUS_COMBO_KICK_CONTACT);
        float ease = melee.standoffEaseSeconds();
        return melee.kickStandoff() * envelope(clipTime, kick - tuning.kickStandoffBefore() - ease,
                kick + tuning.kickStandoffAfter() + ease, ease);
    }

    @Override
    void applyPose(BattleContext ctx) {
        float dash = melee.dashSeconds();
        switch (stage) {
            case DASH_OUT -> ctx.monk.drive(MonkClips.COMBAT_DASH, stageElapsed, ease(ratio(stageElapsed, dash)), 0f);
            case STRING -> ctx.monk.drive(CLIP, clipTime, 1f, standoff());
            case BREAK_OFF -> ctx.monk.drive(MonkClips.COMBAT_IDLE, stageElapsed, 1f,
                    standoffAtBreak * (1f - ease(ratio(stageElapsed, melee.standoffEaseSeconds()))));
            case DASH_BACK -> ctx.monk.drive(MonkClips.COMBAT_DASH, stageElapsed,
                    1f - ease(ratio(stageElapsed, dash)), 0f);
        }
    }
}
