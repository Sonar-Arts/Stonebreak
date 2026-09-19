package com.stonebreak.battle.api;

/** Coarse battle clock state. */
public enum BattlePhase {
    /** Camera intro is playing; gauges are frozen until {@link BattleInput#introFinished()}. */
    INTRO,
    /** ATB gauges fill in real time (Classic Active: menus never pause this). */
    RUNNING,
    /** An action animation is executing; ATB gauges are paused, timed prompts may be open. */
    ACTION,
    /** The battle is over; see {@link BattleView#outcome()}. */
    RESULT
}
