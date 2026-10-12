package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.ui.focusBattle.BattleHudLayers;

/**
 * The timed-input HUD in one object: E9 timing ring, E12 parry feedback, E10 combo strip and E14
 * screen FX. The screen wires it with two lines — {@link #install} once, {@link #update} every
 * frame after the model update — and calls {@link #reset} (plus {@link #setStage} when the stage
 * is only known at bind time) on every bind/retry.
 *
 * <p>Layer order: the screen FX (vignettes, flashes, defeat fade, then the letterbox bars over
 * them) are one <b>underlay</b>, beneath every HUD window. The ring, parry and combo strip are
 * <b>overlays</b>, in that order. Nothing here reads a clock; all motion comes from
 * {@link #update}'s {@code dt} and the frame's battle events.
 *
 * <p>Each overlay is an instance that owns its MasonryUI widgets (the combo strip's
 * {@code MPromptStrip}, the ring's {@code MPipRow}, the key hints); {@link TimedInputState} holds
 * what happened in the battle, the widgets hold the motion of their own parts. Both advance here.
 */
public final class TimedInputLayers {

    private BattleStageLayout layout;
    private final TimedInputState state = new TimedInputState();
    private boolean gradeWords = true;
    private boolean guardWords = true;

    private final TimingRingOverlay ringOverlay = new TimingRingOverlay();
    private final ParryOverlay parryOverlay = new ParryOverlay();
    private final ComboStripOverlay comboOverlay = new ComboStripOverlay();

    private final BattleHudLayers.Layer screenFx =
            (ui, canvas, w, h, s, raw, view, anim, vp) -> ScreenFxLayer.paint(canvas, w, h, s, view, state);
    private final BattleHudLayers.Layer timingRing =
            (ui, canvas, w, h, s, raw, view, anim, vp) ->
                    ringOverlay.paint(ui, canvas, w, h, s, view, state, layout(), vp, gradeWords);
    private final BattleHudLayers.Layer parry =
            (ui, canvas, w, h, s, raw, view, anim, vp) ->
                    parryOverlay.paint(ui, canvas, w, h, s, view, state, layout(), vp, guardWords);
    private final BattleHudLayers.Layer comboStrip =
            (ui, canvas, w, h, s, raw, view, anim, vp) -> comboOverlay.paint(ui, w, h, s, raw);

    /** @param layout the stage the ring and parry brackets anchor to; null pins them to their fallbacks */
    public TimedInputLayers(BattleStageLayout layout) {
        this.layout = layout;
    }

    /** Registers the underlay and the three overlays with the HUD layers. Call once. */
    public void install(BattleHudLayers layers) {
        if (layers == null) return;
        layers.addUnderlay(screenFx);
        layers.addOverlay(timingRing);
        layers.addOverlay(parry);
        layers.addOverlay(comboStrip);
    }

    /** Once per frame, after the battle model's update: consumes {@code view.frameEvents()}. */
    public void update(BattleView view, float dt) {
        state.update(view, dt);
        ringOverlay.update(view, dt);
        comboOverlay.update(state, dt);
    }

    /** Back to a blank slate (bind, retry). */
    public void reset() {
        state.reset();
        ringOverlay.reset();
        comboOverlay.reset();
    }

    /**
     * The stage the ring and the parry reticle anchor to. For a screen that builds its layers before
     * it is bound: construct with {@code null}, then call this from {@code bind} (and with
     * {@code null} on unbind).
     */
    public void setStage(BattleStageLayout layout) {
        this.layout = layout;
    }

    /**
     * Whether the ring's PERFECT / GOOD / MISS words are drawn. Turn off if the floating-number
     * layer spawns the same words; the bursts, pops and shakes always draw.
     */
    public void setGradeWordsEnabled(boolean enabled) {
        this.gradeWords = enabled;
    }

    /**
     * Whether the guard words the floating-number layer can announce instead (the big PARRY!, BLOCK)
     * are drawn. The flash ring, the shield and the brackets always draw, and so does TOO EARLY: it
     * has no floater twin, so it never rides on this switch.
     */
    public void setGuardWordsEnabled(boolean enabled) {
        this.guardWords = enabled;
    }

    public TimedInputState state() { return state; }

    public TimingRingOverlay timingRing() { return ringOverlay; }
    public ComboStripOverlay comboStrip() { return comboOverlay; }

    public BattleHudLayers.Layer screenFxLayer() { return screenFx; }
    public BattleHudLayers.Layer timingRingLayer() { return timingRing; }
    public BattleHudLayers.Layer parryLayer() { return parry; }
    public BattleHudLayers.Layer comboStripLayer() { return comboStrip; }

    private BattleStageLayout layout() { return layout; }
}
