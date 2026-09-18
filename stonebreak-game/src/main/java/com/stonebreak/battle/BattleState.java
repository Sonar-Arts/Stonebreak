package com.stonebreak.battle;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleSimulation;
import com.stonebreak.battle.api.BattleStats;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.CommandAvailability;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * The Focus battle model: a GL-free, deterministic ATB duel between the Monk and the Ice Archon.
 *
 * <p>This class owns only the clock, the phase and the turn order. What a hit does lives in
 * {@link BattleContext}; how an action plays out lives in the {@link BattleAction} timelines.
 *
 * <p>Time is advanced in slices that never cross a boundary (a gauge filling, a status expiring, an
 * action cue), so a large frame dt resolves exactly the same events, in the same order, as many
 * small ones.
 */
public final class BattleState implements BattleSimulation {

    static final String REASON_NOT_YOUR_TURN = "Not your turn";
    static final String REASON_NO_QI = "Not enough Qi";
    static final String REASON_FOCUS_NOT_FULL = "Focus gauge not full";
    static final String REASON_NO_CHARGES = "No charges left";
    static final String REASON_SURGE_QUEUED = "Surge already queued";
    static final String REASON_ALREADY_GUARDING = "Already guarding";

    /** Far above what any real frame needs; only a guard against a tuning value that stalls time. */
    private static final int MAX_SLICES_PER_UPDATE = 4096;

    private final BattleConfig config;
    private final BattleContext ctx;

    private BattlePhase phase = BattlePhase.INTRO;
    private BattleOutcome outcome = BattleOutcome.NONE;
    private float elapsedSeconds;
    private BattleAction action;
    private BattleCommand queuedCommand;

    public BattleState(BattleConfig config, Random random) {
        this.config = Objects.requireNonNull(config, "config");
        this.ctx = new BattleContext(config, Objects.requireNonNull(random, "random"));
    }

    // ---- clock ----------------------------------------------------------------------------------

    @Override
    public void update(float dt) {
        float step = Float.isFinite(dt) && dt > 0f ? dt : 0f;
        // Recoil first, so the frame that publishes a hit still shows the target where the blow met
        // it; the knock-back starts rising next frame.
        advanceRecoil(step);
        float remaining = step;
        int guard = MAX_SLICES_PER_UPDATE;
        while (phase == BattlePhase.RUNNING || phase == BattlePhase.ACTION) {
            settleTurns();
            if (remaining <= 0f || guard-- <= 0 || phase == BattlePhase.RESULT) break;
            float slice = Math.min(remaining, timeToNextBoundary());
            advanceBattle(slice);
            remaining -= slice;
        }
        // Outside the fight (intro, result) nothing slices the frame: whatever is left of it still
        // moves the actors' own clips (rest pose, death, victory), so a battle that ends mid-frame
        // starts its endings from exactly that moment.
        if (remaining > 0f && (phase == BattlePhase.INTRO || phase == BattlePhase.RESULT)) advancePoses(remaining);
        ctx.events.publish();
    }

    private float timeToNextBoundary() {
        float next = Math.min(ctx.timeToNextStatusExpiry(), ctx.timeToArchonRecovered());
        if (phase == BattlePhase.ACTION) {
            next = Math.min(next, action.timeToNextCue());
        } else {
            next = Math.min(next, ctx.monk.gauge().timeToFull(ctx.monkAtbSpeed()));
            next = Math.min(next, ctx.archon.gauge().timeToFull(ctx.archonAtbSpeed()));
        }
        return Math.max(0f, next);
    }

    private void advanceBattle(float slice) {
        elapsedSeconds += slice;
        // Speeds are read before statuses tick: a status expiring at the end of this slice was
        // still in force for all of it.
        float monkSpeed = ctx.monkAtbSpeed();
        float archonSpeed = ctx.archonAtbSpeed();
        // Undriven clips run on battle time, advanced before this slice's cue fires: a reaction that
        // cue starts (hurt, block, a parry timed to the blade) begins at exactly zero.
        advancePoses(slice);
        ctx.advanceArchonRecovery(slice);
        ctx.advanceStatuses(slice);
        if (phase == BattlePhase.ACTION) {
            action.advance(slice, ctx);
            afterActionStep();
        } else {
            if (ctx.monk.gauge().advance(slice, monkSpeed)) onMonkTurnReady();
            if (ctx.archon.gauge().advance(slice, archonSpeed)) ctx.raise(new BattleEvent.TurnReady(CombatantId.ARCHON));
        }
    }

    private void onMonkTurnReady() {
        ctx.raise(new BattleEvent.TurnReady(CombatantId.MONK));
        ctx.qi.gain(config.resources().qiPerTurn());
    }

    /** Starts whoever may act now. The monk's queued command outranks an Archon that is also ready. */
    private void settleTurns() {
        if (phase != BattlePhase.RUNNING) return;
        if (queuedCommand != null) {
            BattleCommand command = queuedCommand;
            queuedCommand = null;
            CommandAvailability check = resourceCheck(command);
            if (check.available()) {
                startMonkAction(command);
                return;
            }
            ctx.raise(new BattleEvent.CommandRejected(command, check.reason()));
        }
        if (ctx.archon.gauge().full() && !ctx.archonIncapacitated()) {
            startArchonAction(ctx.archonScript.pick(ctx.random, ctx.monk.has(BattleStatus.CHILLED)));
        }
    }

    /** After anything that may have ended the action, stunned the Archon or killed someone. */
    private void afterActionStep() {
        if (ctx.takeArchonInterruptRequest()) interruptArchon();
        if (checkBattleOver()) return;
        if (action != null && action.finished()) finishAction(true);
    }

    /** Input can change what the action shows (a missed combo breaks off): repose before anyone reads it. */
    private void afterInput() {
        afterActionStep();
        if (action != null) action.applyPose(ctx);
    }

    private void advanceRecoil(float dt) {
        float rise = config.damage().recoilRiseSeconds();
        float recoil = config.damage().recoilSeconds();
        ctx.monk.advanceRecoil(dt, rise, recoil);
        ctx.archon.advanceRecoil(dt, rise, recoil);
    }

    private void advancePoses(float dt) {
        ctx.monk.advancePose(dt);
        ctx.archon.advancePose(dt);
    }

    // ---- actions --------------------------------------------------------------------------------

    private void startMonkAction(BattleCommand command) {
        // Attacking out of the stance gives it up; only an enemy blow "consumes" it for value.
        // A free action (Martial Surge) keeps the stance, the turn and the full gauge.
        if (command.endsTurn()) {
            if (command != BattleCommand.GUARD) ctx.expireStatus(ctx.monk, BattleStatus.GUARDING);
            ctx.stats.turnTaken();
            ctx.monk.gauge().reset(0f);
        }
        ctx.qi.spend(command.qiCost());
        begin(createMonkAction(command));
    }

    private BattleAction createMonkAction(BattleCommand command) {
        BattleConfig.Support s = config.support();
        return switch (command) {
            case STRIKE -> StrikeAction.strike(config, ctx.consumeSurge());
            case FLURRY -> new FlurryAction(config, ctx.consumeSurge());
            case STUNNING_STRIKE -> StrikeAction.stunningStrike(config);
            case FOCUS_COMBO -> new ComboAction(config, ctx.random);
            case SWIFT_STEP -> new SimpleAction(command, MonkClips.SWIFT_STEP,
                    c -> c.applyStatus(c.monk, BattleStatus.HASTE, s.hasteSeconds()));
            case MEDITATE -> {
                ctx.spendMeditateCharge();
                yield new SimpleAction(command, MonkClips.MEDITATE, c -> c.healMonk(s.meditateHealFraction()));
            }
            // The Guard ACTION is only the stance going up; holding it costs no further action time.
            case GUARD -> new SimpleAction(command, MonkClips.GUARD_ENTER, c -> c.raiseGuard(false));
            case MARTIAL_SURGE -> new SimpleAction(command, MonkClips.MARTIAL_SURGE, BattleContext::queueSurge);
        };
    }

    private void startArchonAction(EnemyAction enemyAction) {
        begin(new ArchonAttackAction(enemyAction, config));
    }

    private void begin(BattleAction next) {
        action = next;
        phase = BattlePhase.ACTION;
        ctx.raise(new BattleEvent.ActionStarted(next.actor(), next.displayName(), next.command(),
                next.enemyAction(), next.duration()));
        next.begin(ctx);
        next.applyPose(ctx);
    }

    /** @param ranToEnd false when the action was cut short and its actor may still be away from home */
    private void finishAction(boolean ranToEnd) {
        BattleAction done = action;
        action = null;
        phase = BattlePhase.RUNNING;
        Combatant actor = ctx.combatant(done.actor());
        if (ranToEnd) actor.settleHome();
        else actor.release();
        // Any other action ends in the ready stance; a guard that outlived it (free action) goes back up.
        if (actor == ctx.monk && done.command() != BattleCommand.GUARD && ctx.monk.has(BattleStatus.GUARDING)) {
            ctx.monk.play(MonkClips.GUARD_ENTER);
        }
        if (done.spendsTurn()) actor.gauge().reset(done.actor() == CombatantId.MONK ? done.atbRestart() : 0f);
        ctx.raise(new BattleEvent.ActionFinished(done.actor()));
    }

    /** Stun landed: abort the Archon's attack if one is somehow in flight. */
    private void interruptArchon() {
        if (!(action instanceof ArchonAttackAction attack)) return;
        attack.cancel(ctx);
        ctx.archon.flinch();
        finishAction(false);
    }

    /** Test hook for the stun-cancel path, which normal play cannot reach (actions never overlap). */
    void stunArchonNow() {
        if (phase == BattlePhase.INTRO || phase == BattlePhase.RESULT) return;
        ctx.stunArchon();
        if (ctx.takeArchonInterruptRequest()) interruptArchon();
    }

    private boolean checkBattleOver() {
        if (phase == BattlePhase.RESULT) return true;
        BattleOutcome now = ctx.outcome();
        if (now == BattleOutcome.NONE) return false;
        outcome = now;
        phase = BattlePhase.RESULT;
        action = null;
        queuedCommand = null;
        if (now == BattleOutcome.VICTORY) {
            // The monk lets the killing blow follow through, goes home, and only then celebrates.
            ctx.monk.celebrate(MonkClips.VICTORY, MonkClips.VICTORY_LOOP);
            ctx.archon.collapse(ArchonClips.DEATH, ArchonClips.DEFEATED);
        } else {
            ctx.monk.collapse(MonkClips.DEFEAT, MonkClips.DEFEATED);
            ctx.archon.release(); // finishes its swing, glides home
        }
        ctx.raise(new BattleEvent.Ended(now));
        return true;
    }

    // ---- input ----------------------------------------------------------------------------------

    @Override
    public void introFinished() {
        if (phase != BattlePhase.INTRO) return;
        phase = BattlePhase.RUNNING;
        ctx.monk.enterCombat(MonkClips.COMBAT_ENTER);
        ctx.archon.enterCombat();
        ctx.raise(new BattleEvent.BattleStarted());
    }

    @Override
    public boolean submit(BattleCommand command) {
        if (command == null || phase == BattlePhase.RESULT) return false;
        CommandAvailability check = availability(command);
        if (!check.available()) {
            ctx.raise(new BattleEvent.CommandRejected(command, check.reason()));
            return false;
        }
        if (command == BattleCommand.GUARD && action instanceof ArchonAttackAction attack
                && attack.telegraphing()) {
            // Reaction guard: the reward for sitting on a full gauge. Instant, no animation lock.
            ctx.stats.turnTaken();
            ctx.monk.gauge().reset(0f);
            ctx.raiseGuard(true);
            attack.guardRaised(ctx);
        } else if (phase == BattlePhase.RUNNING) {
            startMonkAction(command);
        } else {
            queuedCommand = command; // the Archon is mid-action: runs the moment it finishes
        }
        return true;
    }

    @Override
    public void pressConfirm() {
        if (phase != BattlePhase.ACTION) return;
        action.pressConfirm(ctx);
        afterInput();
    }

    @Override
    public void pressDirection(ComboDirection direction) {
        if (phase != BattlePhase.ACTION) return;
        action.pressDirection(direction, ctx);
        afterInput();
    }

    // ---- view -----------------------------------------------------------------------------------

    @Override public BattlePhase phase() { return phase; }
    @Override public BattleOutcome outcome() { return outcome; }
    @Override public float elapsedSeconds() { return elapsedSeconds; }
    @Override public CombatantView monk() { return ctx.monk; }
    @Override public CombatantView archon() { return ctx.archon; }
    @Override public int qi() { return ctx.qi.value(); }
    @Override public int maxQi() { return ctx.qi.max(); }
    @Override public float focus() { return ctx.focus.value(); }
    @Override public float maxFocus() { return ctx.focus.max(); }
    @Override public int meditateCharges() { return ctx.meditateCharges(); }

    @Override
    public int queuedSurgeHits() {
        return ctx.surgeQueued() ? config.melee().surgeBonusHits() : 0;
    }

    @Override
    public boolean commandWindowOpen() {
        if (outcome != BattleOutcome.NONE || queuedCommand != null || !ctx.monk.gauge().full()) return false;
        if (phase == BattlePhase.RUNNING) return true;
        // FF7 rule: the player may pick a command while the enemy animates, never during their own action.
        return phase == BattlePhase.ACTION && action.actor() == CombatantId.ARCHON;
    }

    @Override
    public CommandAvailability availability(BattleCommand command) {
        if (command == null) return CommandAvailability.no(REASON_NOT_YOUR_TURN);
        CommandAvailability resources = resourceCheck(command);
        if (!resources.available()) return resources;
        return commandWindowOpen() ? CommandAvailability.OK : CommandAvailability.no(REASON_NOT_YOUR_TURN);
    }

    /** What the command itself needs, independent of whose turn it is (so the HUD can dim rows early). */
    private CommandAvailability resourceCheck(BattleCommand command) {
        if (!ctx.qi.canAfford(command.qiCost())) return CommandAvailability.no(REASON_NO_QI);
        return switch (command) {
            case FOCUS_COMBO -> ctx.focus.full() ? CommandAvailability.OK : CommandAvailability.no(REASON_FOCUS_NOT_FULL);
            case MEDITATE -> ctx.meditateCharges() > 0 ? CommandAvailability.OK : CommandAvailability.no(REASON_NO_CHARGES);
            case MARTIAL_SURGE -> ctx.surgeQueued() ? CommandAvailability.no(REASON_SURGE_QUEUED) : CommandAvailability.OK;
            case GUARD -> ctx.monk.has(BattleStatus.GUARDING)
                    ? CommandAvailability.no(REASON_ALREADY_GUARDING) : CommandAvailability.OK;
            default -> CommandAvailability.OK;
        };
    }

    @Override
    public ActionView currentAction() {
        return action == null ? null : action.view();
    }

    @Override
    public TelegraphView telegraph() {
        return action == null ? null : action.telegraph();
    }

    @Override
    public PromptView prompt() {
        return action == null ? null : action.prompt(ctx);
    }

    @Override public BattleStats stats() { return ctx.stats.snapshot(); }
    @Override public List<BattleEvent> frameEvents() { return ctx.events.published(); }

    // ---- package-private test seams ---------------------------------------------------------------

    BattleContext context() { return ctx; }
}
