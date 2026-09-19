package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;

import java.util.List;

/**
 * The HUD's frame-level motion and the <b>only</b> writer of {@link BattleHudAnimState}: window
 * recoil on a hit, the command window's wake and submenu slide, the help cross-fade and the
 * cinematic slide-out of the bottom HUD. Everything a widget animates for itself (gauge trails and
 * flashes, pip pops, the menu cursor) is driven by the windows' own {@code update}, not from here.
 *
 * <p>Deterministic by construction: every value is a function of the {@code (dt, view, events)}
 * sequence fed to {@link #update} since the last {@link #reset}. There is no wall clock and no
 * randomness (a shake is a decaying sinusoid, not noise), so two animators fed the same sequence
 * agree bit for bit and raster tests can pin any moment.
 */
public final class HudAnimator {

    public static final float SHAKE_SECONDS = 0.3f;
    public static final float COMMAND_WAKE_SECONDS = 0.15f;
    public static final float SUBMENU_SLIDE_SECONDS = 0.10f;
    public static final float CINEMATIC_SLIDE_SECONDS = 0.25f;
    public static final float HELP_FADE_OUT_SECONDS = 0.07f;
    public static final float HELP_FADE_IN_SECONDS = 0.14f;

    /** Shake amplitude in design pixels: a graze, and a blow worth a fifth of the target's HP. */
    private static final float SHAKE_MIN_PX = 3f;
    private static final float SHAKE_MAX_PX = 10f;
    private static final float HEAVY_HIT_HP_SHARE = 0.2f;

    /** Decaying sinusoidal recoil of one window. */
    private static final class HitShake {
        float amplitude;
        float age = Float.MAX_VALUE;

        void reset() {
            amplitude = 0f;
            age = Float.MAX_VALUE;
        }

        void hit(float amplitudePx) {
            if (amplitudePx <= 0f) return;
            // A small hit landing inside a big one must not cut the big one short.
            float remaining = 1f - Math.min(1f, age / SHAKE_SECONDS);
            amplitude = Math.max(amplitudePx, amplitude * remaining * remaining);
            age = 0f;
        }

        float decay() {
            float t = Math.min(1f, age / SHAKE_SECONDS);
            return (1f - t) * (1f - t);
        }

        float x() { return age >= SHAKE_SECONDS ? 0f : amplitude * decay() * (float) Math.sin(age * 88.0); }
        float y() { return age >= SHAKE_SECONDS ? 0f : amplitude * 0.6f * decay() * (float) Math.cos(age * 67.0); }
    }

    private final BattleHudAnimState out;
    private final HitShake partyShake = new HitShake();
    private final HitShake enemyShake = new HitShake();

    private float hudScale = 1f;
    private boolean windowWasOpen;
    private boolean submenuWasOpen;
    private float commandWakeClock = 1f;
    private float submenuSlide = 1f;
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
        partyShake.reset();
        enemyShake.reset();
        windowWasOpen = false;
        submenuWasOpen = false;
        commandWakeClock = 1f;
        submenuSlide = 1f;
        cinematicSlide = BattleHudRules.cinematic(view) ? 1f : 0f;
        endAge = -1f;
        shownHelp = BattleHelpText.lineFor(view, menu);
        helpFade = 1f;
        out.time = 0f;
        write();
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

        partyShake.age = age(partyShake.age, step);
        enemyShake.age = age(enemyShake.age, step);
        if (endAge >= 0f) endAge += step;

        if (events != null) {
            for (BattleEvent event : events) {
                if (event instanceof BattleEvent.DamageDealt hit) shake(hit, view);
                else if (event instanceof BattleEvent.Ended && endAge < 0f) endAge = 0f;
            }
        }
        if (endAge < 0f && view.outcome() != BattleOutcome.NONE) endAge = 0f;

        // Command window wake-up (a veil fade, not a slide) and the submenu slide: restart on the
        // closed → open edge, then run to rest.
        boolean windowOpen = view.commandWindowOpen();
        if (windowOpen && !windowWasOpen) commandWakeClock = 0f;
        else commandWakeClock = Math.min(1f, commandWakeClock + step / COMMAND_WAKE_SECONDS);
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
        write();
    }

    private void shake(BattleEvent.DamageDealt hit, BattleView view) {
        if (hit.target() == null) return;
        CombatantView target = view.combatant(hit.target());
        float share = target == null || target.maxHp() <= 0f ? 0f
                : MColor.clamp01(hit.amount() / (target.maxHp() * HEAVY_HIT_HP_SHARE));
        float amplitude = (SHAKE_MIN_PX + (SHAKE_MAX_PX - SHAKE_MIN_PX) * share) * hudScale;
        BattleEvent.DamageFlavor flavor = hit.flavor() == null ? BattleEvent.DamageFlavor.NORMAL : hit.flavor();
        switch (flavor) {
            case CRITICAL -> amplitude *= 1.6f;
            case BLOCKED -> amplitude *= 0.35f;
            case PARRIED -> amplitude = 0f;   // a parry is the player's win: no recoil
            case NORMAL -> { }
        }
        (hit.target() == CombatantId.MONK ? partyShake : enemyShake).hit(amplitude);
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
    private void write() {
        float time = out.time;
        out.commandWake = EasingFunctions.apply(commandWakeClock, EasingType.EaseOutCubic);
        out.submenuSlideIn = EasingFunctions.apply(submenuSlide, EasingType.EaseOutCubic);
        out.targetBob = (float) Math.sin(time * 7.0);

        out.partyShakeX = partyShake.x();
        out.partyShakeY = partyShake.y();
        out.enemyShakeX = enemyShake.x();
        out.enemyShakeY = enemyShake.y();

        out.helpLine = shownHelp;
        out.helpFade = MColor.clamp01(helpFade);
        out.bottomHudSlideOut = EasingFunctions.apply(cinematicSlide, EasingType.EaseInOutCubic);
    }

    private static float age(float age, float step) {
        return age >= Float.MAX_VALUE - step ? Float.MAX_VALUE : age + step;
    }
}
