package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.ui.focusBattle.BattleHudRules;

import java.util.Arrays;
import java.util.List;

/**
 * All animation state of the timed-input overlays and screen FX. It advances only through
 * {@link #update} (no wall clock), driven by the view and the frame's {@link BattleEvent}s, so two
 * instances fed the same frames are identical and every painter is a pure function of
 * {@code (view, this)}.
 *
 * <p>Why events and not just the view: a prompt disappears from the view the instant it resolves,
 * which is exactly when its feedback has to start. The combo strip in particular outlives its
 * prompt (the last grade, the finisher wait, the FLAWLESS flash), so it keeps its own snapshot.
 *
 * <p>What is NOT here: motion a library widget owns. The combo cells' stamp pops, the prompt pulse,
 * the timer colour and the FLAWLESS word's timeline live in the strip's {@code MPromptStrip}; the
 * hit pips' pop lives in the ring's {@code MPipRow}. This class only says <em>what happened</em>
 * (which cell got which grade, that the string was flawless); the overlays feed that to their widgets.
 */
public final class TimedInputState {

    /** What the parry overlay is celebrating (or not). */
    public enum ParryFeedback { NONE, PARRY, BLOCK, TOO_EARLY }

    public static final float RING_FEEDBACK_SECONDS = 0.35f;
    /** The full-screen parry flash ring. */
    public static final float PARRY_FLASH_SECONDS = 0.25f;
    /** The word outlives the flash a little so it can actually be read. */
    public static final float PARRY_TEXT_SECONDS = 0.6f;
    public static final float BLOCK_SECONDS = 0.45f;
    public static final float TOO_EARLY_SECONDS = 0.5f;
    public static final float COMBO_SLIDE_SECONDS = 0.18f;
    /** How long the finished strip stays up before sliding out. */
    public static final float COMBO_HOLD_SECONDS = 0.7f;
    public static final float COMBO_FLAWLESS_HOLD_SECONDS = 1.4f;
    public static final float LETTERBOX_SECONDS = 0.25f;
    public static final float FX_EASE_SECONDS = 0.35f;
    public static final float CRIT_FLASH_SECONDS = 0.18f;
    public static final float DEFEAT_FADE_SECONDS = 2.5f;
    public static final float LOW_HP_THRESHOLD = 0.3f;

    /** Free-running seconds since the last reset, for pulses. */
    float time;

    // ── E9
    /** Grade of the most recent ring, or null once its feedback has expired. */
    TimedGrade ringGrade;
    float ringFeedbackAge;

    // ── E12
    ParryFeedback parryFeedback = ParryFeedback.NONE;
    float parryFeedbackAge;

    // ── E10
    boolean comboActive;
    boolean comboFinished;
    int comboLanded;
    boolean comboFlawless;
    /** 0 = off-screen, 1 = in place (linear; the painter eases it). */
    float comboSlide;
    float comboHold;
    List<ComboDirection> comboSequence = List.of();
    TimedGrade[] comboResults = new TimedGrade[0];
    /** Index of the prompt awaiting input, or -1 while none is (finisher wait, finished). */
    int comboIndex = -1;
    float comboStepElapsed;
    float comboStepDuration;

    // ── E14
    float lowHp;
    float lowHpPhase;
    float frost;
    float focusAura;
    float letterbox;
    float victoryHold;
    /** Seconds since the last critical hit; negative = none. */
    float critFlashAge = -1f;
    CombatantId critFlashTarget;
    boolean defeated;
    float defeatFade;

    // Identity of the last consumed event list: the model hands out the same list until its next
    // update, so a frame rendered twice (pause, dt 0) must not replay its events.
    private List<BattleEvent> lastEvents;

    public void reset() {
        time = 0f;
        comboLanded = 0;
        ringGrade = null;
        ringFeedbackAge = 0f;
        parryFeedback = ParryFeedback.NONE;
        parryFeedbackAge = 0f;
        comboActive = false;
        comboFinished = false;
        comboFlawless = false;
        comboSlide = 0f;
        comboHold = 0f;
        comboSequence = List.of();
        comboResults = new TimedGrade[0];
        comboIndex = -1;
        comboStepElapsed = 0f;
        comboStepDuration = 0f;
        lowHp = 0f;
        lowHpPhase = 0f;
        frost = 0f;
        focusAura = 0f;
        letterbox = 0f;
        victoryHold = 0f;
        critFlashAge = -1f;
        critFlashTarget = null;
        defeated = false;
        defeatFade = 0f;
        lastEvents = null;
    }

    /** Advances every timer by {@code dt}, then folds in this frame's events and view. */
    public void update(BattleView view, float dt) {
        float step = Math.max(0f, dt);
        advance(step);
        if (view == null) return;
        List<BattleEvent> events = view.frameEvents();
        if (events != null && events != lastEvents) {
            for (BattleEvent e : events) consume(e);
        }
        lastEvents = events;
        sampleCombo(view);
        sampleScreenFx(view, step);
    }

    // ─────────────────────────────────────────────── Timers

    private void advance(float dt) {
        time += dt;
        if (ringGrade != null) {
            ringFeedbackAge += dt;
            if (ringFeedbackAge >= RING_FEEDBACK_SECONDS) ringGrade = null;
        }
        if (parryFeedback != ParryFeedback.NONE) {
            parryFeedbackAge += dt;
            if (parryFeedbackAge >= parryFeedbackSeconds(parryFeedback)) parryFeedback = ParryFeedback.NONE;
        }
        if (comboActive && comboFinished) comboHold -= dt;
        boolean comboShown = comboActive && !(comboFinished && comboHold <= 0f);
        comboSlide = approach(comboSlide, comboShown ? 1f : 0f, dt / COMBO_SLIDE_SECONDS);
        if (comboActive && !comboShown && comboSlide <= 0f) comboActive = false;
        if (critFlashAge >= 0f) {
            critFlashAge += dt;
            if (critFlashAge >= CRIT_FLASH_SECONDS) critFlashAge = -1f;
        }
        if (victoryHold > 0f) victoryHold = Math.max(0f, victoryHold - dt);
        if (defeated) defeatFade = Math.min(1f, defeatFade + dt / DEFEAT_FADE_SECONDS);
    }

    static float parryFeedbackSeconds(ParryFeedback f) {
        return switch (f) {
            case PARRY -> PARRY_TEXT_SECONDS;
            case BLOCK -> BLOCK_SECONDS;
            case TOO_EARLY -> TOO_EARLY_SECONDS;
            case NONE -> 0f;
        };
    }

    // ─────────────────────────────────────────────── Events

    private void consume(BattleEvent event) {
        switch (event) {
            case BattleEvent.PromptOpened opened -> {
                if (opened.kind() == PromptKind.COMBO) openCombo();
            }
            case BattleEvent.PromptResolved resolved -> {
                switch (resolved.kind()) {
                    case RING -> {
                        ringGrade = resolved.grade();
                        ringFeedbackAge = 0f;
                    }
                    case PARRY -> {
                        parryFeedback = switch (resolved.grade()) {
                            case PERFECT -> ParryFeedback.PARRY;
                            case GOOD -> ParryFeedback.BLOCK;
                            case MISS -> ParryFeedback.TOO_EARLY;
                        };
                        parryFeedbackAge = 0f;
                    }
                    case COMBO -> stampCombo(resolved.index(), resolved.grade());
                }
            }
            case BattleEvent.Impact impact -> {
                // The authored clip lands each blow a beat AFTER its input is graded, so the strip's
                // counter follows the blows, not the button presses.
                if (comboActive && !comboFinished && impact.actor() == CombatantId.MONK) {
                    comboLanded++;
                }
            }
            case BattleEvent.ComboFinished finished -> {
                if (comboActive) {
                    comboFinished = true;
                    comboFlawless = finished.flawless();
                    comboIndex = -1;
                    comboHold = finished.flawless() ? COMBO_FLAWLESS_HOLD_SECONDS : COMBO_HOLD_SECONDS;
                }
            }
            case BattleEvent.DamageDealt damage -> {
                if (damage.flavor() == BattleEvent.DamageFlavor.CRITICAL) {
                    critFlashAge = 0f;
                    critFlashTarget = damage.target();
                } else if (damage.flavor() == BattleEvent.DamageFlavor.BLOCKED
                        && damage.target() == CombatantId.MONK && parryFeedback != ParryFeedback.PARRY) {
                    parryFeedback = ParryFeedback.BLOCK;
                    parryFeedbackAge = 0f;
                }
            }
            case BattleEvent.Ended ended -> {
                if (ended.outcome() == BattleOutcome.VICTORY) {
                    victoryHold = BattleHudRules.VICTORY_CINEMATIC_SECONDS;
                } else if (ended.outcome() == BattleOutcome.DEFEAT) {
                    defeated = true;
                }
            }
            default -> { }
        }
    }

    // ─────────────────────────────────────────────── Combo strip

    private void openCombo() {
        comboActive = true;
        comboFinished = false;
        comboFlawless = false;
        comboHold = 0f;
        comboSequence = List.of();
        comboResults = new TimedGrade[0];
        comboIndex = -1;
        comboLanded = 0;
    }

    private void ensureComboCapacity(int size) {
        if (comboResults.length >= size) return;
        comboResults = Arrays.copyOf(comboResults, size);
    }

    private void stampCombo(int index, TimedGrade grade) {
        if (!comboActive || index < 0 || grade == null) return;
        ensureComboCapacity(Math.max(index + 1, comboSequence.size()));
        comboResults[index] = grade;
        if (comboIndex == index) comboIndex = -1;
    }

    private void sampleCombo(BattleView view) {
        if (view.prompt() instanceof PromptView.Combo combo) {
            // The PromptOpened event is the normal trigger; this covers a strip bound mid-string.
            if (!comboActive || comboFinished) openCombo();
            comboSequence = combo.sequence();
            ensureComboCapacity(comboSequence.size());
            for (int i = 0; i < combo.results().size() && i < comboResults.length; i++) {
                if (comboResults[i] == null) comboResults[i] = combo.results().get(i);
            }
            comboIndex = combo.index();
            comboStepElapsed = combo.stepElapsed();
            comboStepDuration = combo.stepDuration();
            return;
        }
        comboIndex = -1;
        if (comboActive && !comboFinished) {
            // A string that vanished without ComboFinished (battle ended, action torn down) must
            // not leave the strip stranded on screen.
            ActionView action = view.currentAction();
            boolean stillCombo = action != null && action.command() == BattleCommand.FOCUS_COMBO
                    && view.outcome() == BattleOutcome.NONE;
            if (!stillCombo) {
                comboFinished = true;
                comboHold = 0f;
            }
        }
    }

    /**
     * The first prompt with no grade yet, or -1. Between two prompts (the blow is still travelling
     * and the view has no prompt) this is the one about to open, so the strip can point at it.
     */
    public int comboNextIndex() {
        for (int i = 0; i < comboSequence.size(); i++) {
            if (i >= comboResults.length || comboResults[i] == null) return i;
        }
        return -1;
    }

    /** Combo blows that have actually landed (Impact events) in the current string. */
    public int comboLanded() {
        return comboLanded;
    }

    /** Prompts answered without a MISS so far. */
    public int comboHits() {
        int hits = 0;
        for (TimedGrade g : comboResults) {
            if (g != null && g != TimedGrade.MISS) hits++;
        }
        return hits;
    }

    // ─────────────────────────────────────────────── Screen FX

    private void sampleScreenFx(BattleView view, float dt) {
        CombatantView monk = view.monk();
        float hpFraction = monk == null ? 1f : monk.hpFraction();
        // A defeat hands the screen to the grey fade; the red heartbeat bows out.
        boolean low = monk != null && hpFraction < LOW_HP_THRESHOLD && !defeated;
        float rate = dt / FX_EASE_SECONDS;
        lowHp = approach(lowHp, low ? 1f : 0f, rate);
        // The heartbeat quickens as HP drops: 1 Hz at the threshold, 2.6 Hz near zero.
        float severity = low ? 1f - hpFraction / LOW_HP_THRESHOLD : 0f;
        lowHpPhase += dt * (float) (2.0 * Math.PI) * (1f + 1.6f * severity);
        frost = approach(frost, monk != null && monk.has(BattleStatus.CHILLED) ? 1f : 0f, rate);
        focusAura = approach(focusAura, view.focusReady() && view.outcome() == BattleOutcome.NONE ? 1f : 0f, rate);
        boolean bars = BattleHudRules.cinematic(view) || victoryHold > 0f;
        letterbox = approach(letterbox, bars ? 1f : 0f, dt / LETTERBOX_SECONDS);
    }

    /** How hurt the monk is below the low-HP threshold, 0 (at it) .. 1 (dead), for the pulse depth. */
    static float lowHpSeverity(BattleView view) {
        CombatantView monk = view == null ? null : view.monk();
        if (monk == null) return 0f;
        float f = monk.hpFraction();
        return f >= LOW_HP_THRESHOLD ? 0f : 1f - f / LOW_HP_THRESHOLD;
    }

    private static float approach(float value, float target, float maxStep) {
        if (value < target) return Math.min(target, value + maxStep);
        if (value > target) return Math.max(target, value - maxStep);
        return value;
    }

    // ─────────────────────────────────────────────── Read access (painters, tests)

    public TimedGrade ringGrade() { return ringGrade; }
    public float ringFeedbackAge() { return ringFeedbackAge; }
    public ParryFeedback parryFeedback() { return parryFeedback; }
    public float parryFeedbackAge() { return parryFeedbackAge; }
    public boolean comboActive() { return comboActive; }
    public boolean comboFinished() { return comboFinished; }
    public float comboSlide() { return comboSlide; }
    /** True once the current string has finished without a MISS (the strip flashes its word on this). */
    public boolean comboFlawless() { return comboFinished && comboFlawless; }
    public float lowHp() { return lowHp; }
    public float frost() { return frost; }
    public float focusAura() { return focusAura; }
    /** Linear 0..1; painters ease it. */
    public float letterbox() { return letterbox; }
    public float victoryHold() { return victoryHold; }
    public boolean critFlashing() { return critFlashAge >= 0f; }
    public float defeatFade() { return defeatFade; }

    /** True when nothing is animating and nothing would be painted for a neutral view. */
    public boolean idle() {
        return ringGrade == null && parryFeedback == ParryFeedback.NONE && !comboActive && comboSlide <= 0f
                && lowHp <= 0f && frost <= 0f && focusAura <= 0f && letterbox <= 0f
                && critFlashAge < 0f && defeatFade <= 0f;
    }
}
