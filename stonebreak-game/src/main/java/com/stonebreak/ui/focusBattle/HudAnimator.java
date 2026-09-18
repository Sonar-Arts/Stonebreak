package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;

import java.util.List;

/**
 * The HUD's motion layer and the <b>only</b> writer of {@link BattleHudAnimState}. Once per frame,
 * after the battle model has updated, it reads the frame's events plus the view and turns them into
 * the numbers the painters apply: HP ghost trails, hit shakes and flashes, window slides, gauge
 * pulses, the help cross-fade and the cinematic slide-out of the bottom HUD.
 *
 * <p>Deterministic by construction: every value is a function of the {@code (dt, view, events)}
 * sequence fed to {@link #update} since the last {@link #reset}. There is no wall clock and no
 * randomness (a shake is a decaying sinusoid, not noise), so two animators fed the same sequence
 * agree bit for bit and raster tests can pin any moment.
 */
public final class HudAnimator {

    public static final float GHOST_HOLD_SECONDS = 0.35f;
    public static final float GHOST_EASE_SECONDS = 0.5f;
    public static final float SHAKE_SECONDS = 0.3f;
    public static final float COMMAND_SLIDE_SECONDS = 0.15f;
    public static final float SUBMENU_SLIDE_SECONDS = 0.10f;
    public static final float CINEMATIC_SLIDE_SECONDS = 0.25f;
    public static final float ATB_FLASH_SECONDS = 0.45f;
    public static final float FOCUS_PULSE_SECONDS = 0.6f;
    public static final float QI_POP_SECONDS = 0.3f;
    public static final float QI_SPEND_SECONDS = 0.4f;
    public static final float HELP_FADE_OUT_SECONDS = 0.07f;
    public static final float HELP_FADE_IN_SECONDS = 0.14f;

    /** Shake amplitude in design pixels: a graze, and a blow worth a fifth of the target's HP. */
    private static final float SHAKE_MIN_PX = 3f;
    private static final float SHAKE_MAX_PX = 10f;
    private static final float HEAVY_HIT_HP_SHARE = 0.2f;

    /** Delayed damage trail behind one HP bar. */
    private static final class GhostTrail {
        float lastFraction = -1f;
        float ghost = -1f;
        float hold;
        float easeFrom;
        float easeTime;

        void reset() {
            lastFraction = -1f;
            ghost = -1f;
            hold = 0f;
            easeFrom = 0f;
            easeTime = 0f;
        }

        float step(float dt, float fraction) {
            if (lastFraction >= 0f && fraction < lastFraction - 1.0e-6f) {
                // A further hit during a trail keeps the trail's head and restarts the hold.
                ghost = Math.max(ghost, lastFraction);
                hold = GHOST_HOLD_SECONDS;
                easeTime = 0f;
            } else if (ghost >= 0f) {
                if (hold > 0f) {
                    hold -= dt;
                    if (hold <= 0f) {
                        easeFrom = ghost;
                        easeTime = Math.max(0f, -hold);
                    }
                } else {
                    easeTime += dt;
                }
                if (hold <= 0f) {
                    float t = Math.min(1f, easeTime / GHOST_EASE_SECONDS);
                    ghost = easeFrom + (fraction - easeFrom) * EasingFunctions.apply(t, EasingType.EaseOutCubic);
                    if (t >= 1f) ghost = -1f;
                }
            }
            // Heals snap the trail away: it only ever trails damage.
            if (ghost >= 0f && fraction >= ghost) ghost = -1f;
            lastFraction = fraction;
            return ghost;
        }
    }

    /** Decaying sinusoidal shake + red flash of one window. */
    private static final class HitShake {
        float amplitude;
        float flash;
        float age = Float.MAX_VALUE;

        void reset() {
            amplitude = 0f;
            flash = 0f;
            age = Float.MAX_VALUE;
        }

        void hit(float amplitudePx, float flashStrength) {
            if (amplitudePx <= 0f && flashStrength <= 0f) return;
            // A small hit landing inside a big one must not cut the big one short.
            float remaining = 1f - Math.min(1f, age / SHAKE_SECONDS);
            amplitude = Math.max(amplitudePx, amplitude * remaining * remaining);
            flash = Math.max(flashStrength, flash * remaining);
            age = 0f;
        }

        float decay() {
            float t = Math.min(1f, age / SHAKE_SECONDS);
            return (1f - t) * (1f - t);
        }

        float x() { return age >= SHAKE_SECONDS ? 0f : amplitude * decay() * (float) Math.sin(age * 88.0); }
        float y() { return age >= SHAKE_SECONDS ? 0f : amplitude * 0.6f * decay() * (float) Math.cos(age * 67.0); }
        float flashNow() { return age >= SHAKE_SECONDS ? 0f : flash * (1f - age / SHAKE_SECONDS); }
    }

    private final BattleHudAnimState out;
    private final GhostTrail monkGhost = new GhostTrail();
    private final GhostTrail archonGhost = new GhostTrail();
    private final HitShake partyShake = new HitShake();
    private final HitShake enemyShake = new HitShake();

    private float hudScale = 1f;
    private boolean windowWasOpen;
    private boolean submenuWasOpen;
    private float commandSlide = 1f;
    private float submenuSlide = 1f;
    private float atbFlashAge = Float.MAX_VALUE;
    private float focusPulseAge = Float.MAX_VALUE;
    private float qiPopAge = Float.MAX_VALUE;
    private float qiSpendAge = Float.MAX_VALUE;
    private float cinematicSlide;
    /** Seconds since the battle ended; negative while it is running. */
    private float endAge = -1f;
    private BattleHelpText.Line shownHelp = BattleHelpText.Line.EMPTY;
    private float helpFade = 1f;

    public HudAnimator(BattleHudAnimState out) {
        this.out = out;
        reset(null, null);
    }

    /**
     * Back to rest for a new encounter (bind / retry). The bottom HUD starts slid out when the
     * battle opens on its intro, so it never flashes on screen for the slide's first frames.
     */
    public void reset(BattleView view, BattleMenuState menu) {
        monkGhost.reset();
        archonGhost.reset();
        partyShake.reset();
        enemyShake.reset();
        windowWasOpen = false;
        submenuWasOpen = false;
        commandSlide = 1f;
        submenuSlide = 1f;
        atbFlashAge = Float.MAX_VALUE;
        focusPulseAge = Float.MAX_VALUE;
        qiPopAge = Float.MAX_VALUE;
        qiSpendAge = Float.MAX_VALUE;
        cinematicSlide = BattleHudRules.cinematic(view) ? 1f : 0f;
        endAge = -1f;
        shownHelp = BattleHelpText.lineFor(view, menu);
        helpFade = 1f;
        out.time = 0f;
        out.monkGhostHpFraction = -1f;
        out.archonGhostHpFraction = -1f;
        out.qiSpendFirstPip = 0;
        out.qiSpendCount = 0;
        write(view, menu);
    }

    /**
     * Shake amplitudes are authored in design pixels; the screen tells the animator the HUD's
     * effective scale so a 4K HUD shakes as far, relative to its windows, as a 720p one.
     */
    public void setHudScale(float scale) {
        hudScale = Float.isFinite(scale) && scale > 0f ? scale : 1f;
    }

    /** Seconds since the battle ended, or a negative value while it is running. */
    public float secondsSinceEnd() { return endAge; }

    /** Advances one frame from the view's own {@link BattleView#frameEvents()}. */
    public void update(float dt, BattleView view, BattleMenuState menu) {
        update(dt, view, menu, view == null ? List.of() : view.frameEvents());
    }

    /** Advances one frame. {@code events} are the battle events published for this frame. */
    public void update(float dt, BattleView view, BattleMenuState menu, List<BattleEvent> events) {
        if (view == null) return;
        float step = Float.isFinite(dt) ? Math.max(0f, dt) : 0f;
        out.time += step;

        atbFlashAge = age(atbFlashAge, step);
        focusPulseAge = age(focusPulseAge, step);
        qiPopAge = age(qiPopAge, step);
        qiSpendAge = age(qiSpendAge, step);
        partyShake.age = age(partyShake.age, step);
        enemyShake.age = age(enemyShake.age, step);
        if (endAge >= 0f) endAge += step;

        if (events != null) {
            for (BattleEvent event : events) consume(event, view);
        }
        if (endAge < 0f && view.outcome() != BattleOutcome.NONE) endAge = 0f;

        // Slides: restart on the closed → open edge, then run to rest.
        boolean windowOpen = view.commandWindowOpen();
        if (windowOpen && !windowWasOpen) commandSlide = 0f;
        else commandSlide = Math.min(1f, commandSlide + step / COMMAND_SLIDE_SECONDS);
        windowWasOpen = windowOpen;

        boolean submenuOpen = menu != null && menu.submenuOpen();
        if (submenuOpen && !submenuWasOpen) submenuSlide = 0f;
        else submenuSlide = Math.min(1f, submenuSlide + step / SUBMENU_SLIDE_SECONDS);
        submenuWasOpen = submenuOpen;

        boolean cinematic = BattleHudRules.cinematic(view)
                || (view.outcome() == BattleOutcome.VICTORY && endAge >= 0f
                && endAge < BattleHudRules.VICTORY_CINEMATIC_SECONDS);
        float slideStep = step / CINEMATIC_SLIDE_SECONDS;
        cinematicSlide = cinematic ? Math.min(1f, cinematicSlide + slideStep) : Math.max(0f, cinematicSlide - slideStep);

        stepHelp(step, BattleHelpText.lineFor(view, menu));

        out.monkGhostHpFraction = view.monk() == null ? -1f : monkGhost.step(step, view.monk().hpFraction());
        out.archonGhostHpFraction = view.archon() == null ? -1f : archonGhost.step(step, view.archon().hpFraction());

        write(view, menu);
    }

    private void consume(BattleEvent event, BattleView view) {
        switch (event) {
            case BattleEvent.DamageDealt hit -> shake(hit, view);
            case BattleEvent.TurnReady ready -> {
                if (ready.who() == CombatantId.MONK) atbFlashAge = 0f;
            }
            case BattleEvent.FocusFull full -> focusPulseAge = 0f;
            case BattleEvent.QiChanged qi -> {
                if (qi.delta() > 0) {
                    qiPopAge = 0f;
                } else if (qi.delta() < 0) {
                    qiSpendAge = 0f;
                    out.qiSpendFirstPip = Math.max(0, qi.now());
                    out.qiSpendCount = -qi.delta();
                }
            }
            case BattleEvent.Ended ended -> {
                if (endAge < 0f) endAge = 0f;
            }
            default -> { }
        }
    }

    private void shake(BattleEvent.DamageDealt hit, BattleView view) {
        if (hit.target() == null) return;
        CombatantView target = view.combatant(hit.target());
        float share = target == null || target.maxHp() <= 0f ? 0f
                : FocusBattleTheme.clamp01(hit.amount() / (target.maxHp() * HEAVY_HIT_HP_SHARE));
        float amplitude = (SHAKE_MIN_PX + (SHAKE_MAX_PX - SHAKE_MIN_PX) * share) * hudScale;
        float flash = 0.75f;
        BattleEvent.DamageFlavor flavor = hit.flavor() == null ? BattleEvent.DamageFlavor.NORMAL : hit.flavor();
        switch (flavor) {
            case CRITICAL -> { amplitude *= 1.6f; flash = 1f; }
            case BLOCKED -> { amplitude *= 0.35f; flash = 0.3f; }
            case PARRIED -> { amplitude = 0f; flash = 0f; }   // a parry is the player's win: no recoil
            case NORMAL -> { }
        }
        (hit.target() == CombatantId.MONK ? partyShake : enemyShake).hit(amplitude, flash);
    }

    /** Out-then-in cross-fade: the old line leaves before the new one arrives. */
    private void stepHelp(float step, BattleHelpText.Line live) {
        if (!live.equals(shownHelp)) {
            helpFade = shownHelp.isEmpty() ? 0f : helpFade - step / HELP_FADE_OUT_SECONDS;
            if (helpFade <= 0f) {
                helpFade = 0f;
                shownHelp = live;
            }
        } else {
            helpFade = Math.min(1f, helpFade + step / HELP_FADE_IN_SECONDS);
        }
    }

    /** Everything that is a pure function of the clocks above. */
    private void write(BattleView view, BattleMenuState menu) {
        float time = out.time;
        boolean targeting = menu != null && menu.targeting();

        out.commandSlideIn = EasingFunctions.apply(commandSlide, EasingType.EaseOutCubic);
        out.submenuSlideIn = EasingFunctions.apply(submenuSlide, EasingType.EaseOutCubic);
        // The list cursor holds still while the target cursor has the player's attention.
        out.cursorBob = targeting ? 0f : (float) Math.sin(time * 6.0);
        out.selectedPulse = targeting ? 0f : 0.5f + 0.5f * (float) Math.sin(time * 4.0);
        out.targetBob = (float) Math.sin(time * 7.0);

        float focusPulse = pulse(focusPulseAge, FOCUS_PULSE_SECONDS);
        boolean focusReady = view != null && view.focusReady();
        out.focusFullPulse = focusPulse;
        out.focusShimmer = focusReady ? (time * 0.7f) % 1f : -1f;
        out.focusReadyGlow = Math.min(1f, 0.7f + 0.3f * (float) Math.sin(time * 5.0) + 0.5f * focusPulse);

        // A hard white flash as the gauge fills, settling into a slow "ready" blink.
        float rest = 0.3f + 0.2f * (float) Math.sin(time * 5.0);
        float atbFlash = pulse(atbFlashAge, ATB_FLASH_SECONDS);
        out.atbFullFlash = rest + (1f - rest) * atbFlash;

        float pop = pulse(qiPopAge, QI_POP_SECONDS);
        out.qiPipPop = pop * pop;
        out.qiSpendFlash = pulse(qiSpendAge, QI_SPEND_SECONDS);

        out.partyShakeX = partyShake.x();
        out.partyShakeY = partyShake.y();
        out.partyHitFlash = partyShake.flashNow();
        out.enemyShakeX = enemyShake.x();
        out.enemyShakeY = enemyShake.y();
        out.enemyHitFlash = enemyShake.flashNow();

        TelegraphView telegraph = view == null ? null : view.telegraph();
        boolean guarding = view != null && view.monk() != null && view.monk().has(BattleStatus.GUARDING);
        out.parryMarkerPulse = guarding && telegraph != null && !telegraph.cancelled()
                ? 0.5f + 0.5f * (float) Math.sin(time * 12.0) : 0f;

        out.helpLine = shownHelp;
        out.helpFade = FocusBattleTheme.clamp01(helpFade);
        out.bottomHudSlideOut = EasingFunctions.apply(cinematicSlide, EasingType.EaseInOutCubic);
    }

    private static float age(float age, float step) {
        return age >= Float.MAX_VALUE - step ? Float.MAX_VALUE : age + step;
    }

    /** 1 at the trigger, easing to 0 over {@code seconds}. */
    private static float pulse(float age, float seconds) {
        if (age >= seconds) return 0f;
        return 1f - EasingFunctions.apply(age / seconds, EasingType.EaseOutCubic);
    }
}
