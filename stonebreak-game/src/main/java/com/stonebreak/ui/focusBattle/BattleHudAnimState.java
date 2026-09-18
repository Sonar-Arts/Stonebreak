package com.stonebreak.ui.focusBattle;

/**
 * The frame-level motion of the HUD: what moves whole windows or belongs to no single widget. Each
 * field has a <b>neutral default</b> that draws the HUD at rest, so painting is deterministic for
 * raster tests. {@link HudAnimator} is the single writer; everything else reads.
 *
 * <p>Anything a widget can animate for itself (gauge trails and flashes, pip pops, the menu cursor,
 * the cast-bar marker pulse) lives inside that widget instance and is <em>not</em> mirrored here.
 *
 * <p>Conventions: offsets are in screen pixels; {@code 0..1} values are eased by the writer and
 * applied linearly.
 */
public final class BattleHudAnimState {

    /** {@link #NEUTRAL} is shared and must never be written to. */
    public static final BattleHudAnimState NEUTRAL = new BattleHudAnimState();

    /** Seconds since bind; free-running clock for anything periodic. */
    public float time;

    /**
     * 0 = the command window has just become active and is still veiled like its static state,
     * 1 = fully awake. The window itself never moves: it is on screen for the whole fight.
     */
    public float commandWake = 1f;
    /** 0 = submenu tucked behind the command window, 1 = in place. */
    public float submenuSlideIn = 1f;

    /** Hit recoil of the party window, applied by the renderer to the window's rect. */
    public float partyShakeX;
    public float partyShakeY;
    /** Hit recoil of the enemy plate. */
    public float enemyShakeX;
    public float enemyShakeY;

    /** Help text opacity, 0..1 (cross-fade on change). */
    public float helpFade = 1f;
    /**
     * The line the help strip shows while it cross-fades (the outgoing one until the fade turns
     * round); null = show the live line.
     */
    public BattleHelpText.Line helpLine;

    /** Target hand cursor bob, −1..1 (× a few pixels, along the finger). */
    public float targetBob;

    /**
     * 0 = bottom windows in place, 1 = slid out below the screen. Only the pre-fight intro and the
     * victory hold do this; no action animation ever does (see BattleHudRules.cinematic).
     */
    public float bottomHudSlideOut;
}
