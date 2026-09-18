package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.battle.camera.ShotSequence.AdvanceOn;
import com.stonebreak.battle.camera.ShotSequence.Transition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Picks what the {@link CameraRig} plays. Each update it folds the frame's events into a few "what
 * is going on" flags, derives the one situation that should be on screen (result &gt; ultimate &gt;
 * action &gt; turn-ready &gt; idle) and changes sequence only when that differs from what is live, so a
 * missed or reordered event can delay a cut but never strand the camera.
 *
 * <p>Timing-prompt rule: a ring or parry prompt freezes the director (no new sequence, no event cut,
 * no timed step change) from the frame after it opens until it closes. The opening frame itself is
 * still allowed to cut, which is how Flurry and Guard get onto their prompt-safe shot when the prompt
 * and the action start together. If a prompt is ever open over a shot that is not prompt-safe, the
 * director cuts once to the prompt-safe shot for that prompt kind.
 */
public final class CameraDirector {

    static final float IDLE_CUT_MIN_SECONDS = 6f;
    static final float IDLE_CUT_SPREAD_SECONDS = 4f;
    /** An action flag with no matching model state for this long is treated as a missed "finished" event. */
    static final float ORPHAN_SECONDS = 1.0f;

    private final ShotLibrary library;
    private final ShotValidator validator;
    private final CameraRig rig;
    private final Random random;
    private final boolean staticOnly;

    private Situation situation = Situation.IDLE;
    private String liveKey = "";
    private CombatantId liveOwner;

    private boolean introStarted;
    private boolean introDone;
    private boolean promptWasOpen;
    private boolean resultShown;
    private ShotSequence pinned;

    private boolean monkActionLive;
    private BattleCommand monkCommand;
    private int monkSerial;
    private float monkOrphan;
    private boolean ultimateLive;
    private int comboInputs;
    /** The flawless finisher is on screen: it plays out even if the monk's action ends under it. */
    private boolean finisherHold;
    private boolean finisherActionDone;

    private boolean enemyLive;
    private EnemyAction enemyAction;
    private int enemySerial;
    private float enemyOrphan;
    /** The monk was guarding at some point of the live Archon attack: stay on the guard shot to its end. */
    private boolean guardSticky;

    private float idleTimer;
    private float nextIdleCut;

    /**
     * @param seed own RNG for variant choice and idle cadence; never the battle's
     */
    public CameraDirector(ShotLibrary library, ShotValidator validator, CameraRig rig, long seed, boolean staticOnly) {
        this.library = library;
        this.validator = validator;
        this.rig = rig;
        this.random = new Random(seed);
        this.staticOnly = staticOnly;
        this.introDone = staticOnly;
        if (staticOnly) liveKey = "STATIC";
    }

    public void update(BattleView view, float dt) {
        boolean promptOpen = view.promptSafeRequired();
        boolean frozen = promptOpen && promptWasOpen;
        promptWasOpen = promptOpen;
        rig.tick(dt, !frozen);
        if (staticOnly || pinned != null) return;

        if (!introDone) {
            if (view.phase() == BattlePhase.INTRO) {
                if (!introStarted) {
                    introStarted = true;
                    start("INTRO", Situation.INTRO, null, null);
                    return;
                }
                if (!rig.finished()) return;
            }
            introDone = true;
        }

        for (BattleEvent event : view.frameEvents()) {
            handle(event, view, frozen);
        }
        expireOrphans(view, dt);
        resolve(view, dt, frozen, promptOpen);
        enforcePromptSafety(view);
    }

    // ---- events ------------------------------------------------------------------------------------

    private void handle(BattleEvent event, BattleView view, boolean frozen) {
        if (resultShown) return;
        switch (event) {
            case BattleEvent.ActionStarted a -> {
                if (a.actor() == CombatantId.ARCHON) {
                    enemyStarted(a.enemyAction(), view);
                } else if (a.command() == BattleCommand.FOCUS_COMBO) {
                    ultimateLive = true;
                    monkActionLive = false;
                    monkOrphan = 0f;
                    comboInputs = 0;
                    if (!frozen) start("ULTIMATE", Situation.COMBO_WINDUP, CombatantId.MONK, null);
                } else if (a.command() != null && !ultimateLive) {
                    monkStarted(a.command());
                }
            }
            // Martial Surge as a free action raises no ActionStarted: its status is the only cue.
            case BattleEvent.StatusApplied s -> {
                if (s.target() == CombatantId.MONK && s.status() == BattleStatus.SURGE && !ultimateLive) {
                    monkStarted(BattleCommand.MARTIAL_SURGE);
                }
            }
            case BattleEvent.TelegraphStarted t -> enemyStarted(t.action(), view);
            case BattleEvent.TelegraphCancelled ignored -> enemyFinished();
            case BattleEvent.ActionFinished f -> {
                if (!frozen && liveOwner == f.actor()) rig.signal(AdvanceOn.ACTION_FINISHED);
                if (f.actor() == CombatantId.MONK) {
                    monkActionLive = false;
                    if (finisherHold) finisherActionDone = true;
                    else ultimateLive = false;
                } else {
                    enemyFinished();
                }
            }
            case BattleEvent.Impact i -> {
                if (!frozen && liveOwner == i.actor()) rig.signal(AdvanceOn.IMPACT);
            }
            case BattleEvent.PromptResolved p -> {
                // Combo prompts are the one timed input whose cuts are scripted: one per correct input.
                if (ultimateLive && p.kind() == PromptKind.COMBO && p.grade() != TimedGrade.MISS) cutToNextComboAngle();
            }
            case BattleEvent.ComboFinished c -> {
                if (ultimateLive && c.flawless()) {
                    finisherHold = true;
                    finisherActionDone = false;
                    start("ULTIMATE", Situation.COMBO_FINISHER, CombatantId.MONK, Transition.CUT);
                }
            }
            case BattleEvent.Ended e -> showResult(e.outcome());
            default -> { }
        }
    }

    /** The status cue and the ActionStarted of one Martial Surge may both arrive: they are one action. */
    private void monkStarted(BattleCommand command) {
        if (monkActionLive && monkCommand == command && command == BattleCommand.MARTIAL_SURGE) return;
        monkActionLive = true;
        monkCommand = command;
        monkSerial++;
        monkOrphan = 0f;
    }

    private void enemyStarted(EnemyAction action, BattleView view) {
        if (action == null || (enemyLive && enemyAction == action)) return; // TelegraphStarted + ActionStarted pair
        enemyLive = true;
        enemyAction = action;
        enemySerial++;
        enemyOrphan = 0f;
        guardSticky = guardUp(view);
    }

    private void enemyFinished() {
        enemyLive = false;
        guardSticky = false;
    }

    private void cutToNextComboAngle() {
        List<ShotSequence> angles = library.variants(Situation.COMBO_ANGLE);
        for (int i = 0; i < angles.size(); i++) {
            ShotSequence candidate = angles.get((comboInputs + i) % angles.size());
            if (validator.usable(candidate)) {
                comboInputs += i + 1;
                play(candidate, "ULTIMATE", Situation.COMBO_ANGLE, CombatantId.MONK, Transition.CUT);
                return;
            }
        }
        comboInputs++;
    }

    private void showResult(BattleOutcome outcome) {
        if (resultShown || outcome == null || outcome == BattleOutcome.NONE) return;
        resultShown = true;
        monkActionLive = false;
        ultimateLive = false;
        finisherHold = false;
        enemyFinished();
        start("RESULT", outcome == BattleOutcome.VICTORY ? Situation.VICTORY : Situation.DEFEAT, null, null);
    }

    // ---- state → situation -------------------------------------------------------------------------

    private void expireOrphans(BattleView view, float dt) {
        ActionView action = view.currentAction();
        if ((monkActionLive || ultimateLive) && !finisherHold) {
            boolean backed = action != null && action.actor() == CombatantId.MONK;
            monkOrphan = backed ? 0f : monkOrphan + dt;
            if (monkOrphan > ORPHAN_SECONDS) {
                monkActionLive = false;
                ultimateLive = false;
            }
        }
        TelegraphView telegraph = view.telegraph();
        boolean telegraphLive = telegraph != null && !telegraph.cancelled();
        if (!enemyLive && telegraphLive && !resultShown) enemyStarted(telegraph.action(), view);
        if (enemyLive) {
            boolean backed = telegraphLive || (action != null && action.actor() == CombatantId.ARCHON);
            enemyOrphan = backed ? 0f : enemyOrphan + dt;
            if (enemyOrphan > ORPHAN_SECONDS) enemyFinished();
        }
    }

    private void resolve(BattleView view, float dt, boolean frozen, boolean promptOpen) {
        if (!resultShown && view.phase() == BattlePhase.RESULT) showResult(view.outcome());
        if (finisherHold && rig.finished() && (finisherActionDone || view.currentAction() == null)) {
            finisherHold = false;
            ultimateLive = false;
        }
        if (resultShown || ultimateLive) return;

        String monkKey = monkCommand == BattleCommand.GUARD ? "GUARD" : "MONK#" + monkSerial;
        // A sequence made only of timed steps (Swift Step, Martial Surge) ends itself.
        if (monkActionLive && rig.finished() && liveKey.equals(monkKey)) monkActionLive = false;

        String key;
        Situation wanted;
        CombatantId owner = null;
        if (monkActionLive) {
            key = monkKey;
            wanted = situationFor(monkCommand);
            owner = CombatantId.MONK;
        } else if (enemyLive) {
            guardSticky |= guardUp(view);
            key = guardSticky ? "GUARD" : "ENEMY#" + enemySerial;
            wanted = guardSticky ? Situation.GUARD
                    : enemyAction == EnemyAction.FROST_CAST ? Situation.FROST_CAST : Situation.ARCHON_MELEE;
            owner = CombatantId.ARCHON;
        } else if (guardUp(view)) {
            key = "GUARD";
            wanted = Situation.GUARD;
        } else if (view.commandWindowOpen()) {
            key = "TURN_READY";
            wanted = Situation.TURN_READY;
        } else {
            key = "IDLE";
            wanted = Situation.IDLE;
        }

        if (frozen) return;
        if (!key.equals(liveKey)) {
            // A prompt that opens together with the change (reaction Guard mid-telegraph) gets a cut:
            // a blend would still be travelling while the player times the press.
            start(key, wanted, owner, promptOpen ? Transition.CUT : null);
        } else {
            liveOwner = owner;
            if (wanted == Situation.IDLE) idleSoftCut(dt);
        }
    }

    private void idleSoftCut(float dt) {
        idleTimer += dt;
        if (idleTimer < nextIdleCut) return;
        List<ShotSequence> others = new ArrayList<>();
        for (ShotSequence s : library.variants(Situation.IDLE)) {
            if (s != rig.sequence() && validator.usable(s)) others.add(s);
        }
        if (!others.isEmpty()) {
            play(others.get(random.nextInt(others.size())), "IDLE", Situation.IDLE, null, Transition.CUT);
        }
        armIdleTimer();
    }

    private void enforcePromptSafety(BattleView view) {
        if (!view.promptSafeRequired() || rig.liveShot().promptSafe()) return;
        PromptView prompt = view.prompt();
        Situation safe = prompt.kind() == PromptKind.PARRY ? Situation.GUARD : Situation.FLURRY;
        ShotSequence choice = library.wide();
        for (ShotSequence s : library.variants(safe)) {
            if (s.steps().getFirst().shot().promptSafe() && validator.usable(s)) {
                choice = s;
                break;
            }
        }
        // liveKey is left alone so the situation that was wanted anyway does not restart over this shot.
        play(choice, liveKey, safe, liveOwner, Transition.CUT);
    }

    private static boolean guardUp(BattleView view) {
        return (view.monk() != null && view.monk().has(BattleStatus.GUARDING)) || view.prompt() instanceof PromptView.Parry;
    }

    private static Situation situationFor(BattleCommand command) {
        return switch (command) {
            case STRIKE -> Situation.STRIKE;
            case FLURRY -> Situation.FLURRY;
            case STUNNING_STRIKE -> Situation.STUNNING_STRIKE;
            case SWIFT_STEP -> Situation.SWIFT_STEP;
            case MARTIAL_SURGE -> Situation.MARTIAL_SURGE;
            case MEDITATE -> Situation.MEDITATE;
            case GUARD -> Situation.GUARD;
            case FOCUS_COMBO -> Situation.COMBO_WINDUP;
        };
    }

    // ---- starting sequences ------------------------------------------------------------------------

    private void start(String key, Situation wanted, CombatantId owner, Transition entryOverride) {
        play(choose(wanted), key, wanted, owner, entryOverride);
    }

    private void play(ShotSequence sequence, String key, Situation wanted, CombatantId owner, Transition entryOverride) {
        // 180° rule: the way back from the far side of the line is a cut, never a glide through the actors.
        if (entryOverride == null && rig.liveShot().crossesLine() && !sequence.steps().getFirst().shot().crossesLine()) {
            entryOverride = Transition.CUT;
        }
        rig.play(sequence, entryOverride);
        liveKey = key;
        situation = wanted;
        liveOwner = owner;
        if (wanted == Situation.IDLE) armIdleTimer();
    }

    private void armIdleTimer() {
        idleTimer = 0f;
        nextIdleCut = IDLE_CUT_MIN_SECONDS + random.nextFloat() * IDLE_CUT_SPREAD_SECONDS;
    }

    /**
     * A random variant, walking on to the next when the stage blocks it; WIDE when none survives.
     * Idle always re-enters on its first (home) variant so the player re-orients after every action.
     */
    private ShotSequence choose(Situation wanted) {
        List<ShotSequence> variants = library.variants(wanted);
        int first = wanted == Situation.IDLE || variants.size() < 2 ? 0 : random.nextInt(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            ShotSequence candidate = variants.get((first + i) % variants.size());
            if (validator.usable(candidate)) return candidate;
        }
        return library.wide();
    }

    // ---- facade hooks ------------------------------------------------------------------------------

    public void skipIntro() {
        if (introDone) return;
        introDone = true;
        if (situation == Situation.INTRO) start("IDLE", Situation.IDLE, null, Transition.CUT);
    }

    public boolean introFinished() { return introDone; }

    /** Debug: hold one library sequence regardless of the battle (null releases it). */
    public void pin(ShotSequence sequence) {
        pinned = sequence;
        if (sequence != null) {
            rig.play(sequence, Transition.CUT);
        } else {
            liveKey = "";
        }
    }

    /** The situation currently on screen. */
    public Situation situation() { return situation; }
}
