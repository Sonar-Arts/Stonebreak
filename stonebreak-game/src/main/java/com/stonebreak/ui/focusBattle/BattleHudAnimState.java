package com.stonebreak.ui.focusBattle;

/**
 * Every motion input the HUD painters accept, in one mutable bag. Each field has a <b>neutral
 * default</b> that draws the static HUD, so painters are deterministic for raster tests and the
 * motion layer ({@link HudAnimator}: ghost HP trails, shakes, pulses, slides driven from the frame's
 * battle events) only has to write fields here — no painter signature changes. The animator is the
 * single writer; everything else reads.
 *
 * <p>Conventions: offsets are in screen pixels; {@code 0..1} values are eased by the writer, the
 * painters apply them linearly; a negative ghost fraction means "no ghost".
 */
public final class BattleHudAnimState {

    /** {@link #NEUTRAL} is shared and must never be written to. */
    public static final BattleHudAnimState NEUTRAL = new BattleHudAnimState();

    /** Seconds since bind; free-running clock for anything periodic. */
    public float time;

    // ── E1 / E2
    /** 0 = command window fully off-screen left, 1 = in place. */
    public float commandSlideIn = 1f;
    /** 0 = submenu tucked behind E1, 1 = in place. */
    public float submenuSlideIn = 1f;
    /** Hand cursor bob, −1..1 (× a few pixels, horizontally). */
    public float cursorBob;
    /** Selected-row highlight pulse, 0..1 (adds brightness). */
    public float selectedPulse;
    /** Gold glow strength of a ready Focus Combo row, 0..1. */
    public float focusReadyGlow = 1f;

    // ── E3
    /** Delayed damage trail behind the monk's HP fill; negative = none. */
    public float monkGhostHpFraction = -1f;
    public float partyShakeX;
    public float partyShakeY;
    /** Red hit flash over the party window, 0..1. */
    public float partyHitFlash;
    /** How far a full ATB gauge is pushed to the flash colour, 0..1 (pulse it for the blink). */
    public float atbFullFlash = 1f;
    /** Position of the Focus shimmer band across a full gauge, 0..1; negative = no band. */
    public float focusShimmer = -1f;
    /** Scale pop of the most recently gained Qi pip, 0..1 (0 = at rest). */
    public float qiPipPop;
    /** Shatter flash over the Qi pips just spent, 1 → 0 (0 = none). */
    public float qiSpendFlash;
    /** Index of the first pip the last spend emptied (== Qi after the spend). */
    public int qiSpendFirstPip;
    /** How many pips the last spend emptied. */
    public int qiSpendCount;
    /** One-off burst when Focus reaches its maximum, 1 → 0 (0 = none). */
    public float focusFullPulse;

    // ── E4
    /** Help text opacity, 0..1 (cross-fade on change). */
    public float helpFade = 1f;
    /**
     * The line the help strip shows while it cross-fades (the outgoing one until the fade turns
     * round); null = show the live line.
     */
    public BattleHelpText.Line helpLine;

    // ── E5 / E6
    public float archonGhostHpFraction = -1f;
    public float enemyShakeX;
    public float enemyShakeY;
    public float enemyHitFlash;
    /** Parry marker pulse while the monk is guarding, 0..1. */
    public float parryMarkerPulse;

    // ── E8
    /** Target hand cursor bob, −1..1 (× a few pixels, along the finger). */
    public float targetBob;

    // ── Whole HUD
    /** 0 = bottom windows (E1–E4) in place, 1 = slid out below the screen (Focus Combo, intro). */
    public float bottomHudSlideOut;
}
