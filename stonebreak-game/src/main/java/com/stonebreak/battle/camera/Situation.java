package com.stonebreak.battle.camera;

/** The moments the director has authored coverage for; {@link ShotLibrary} holds variants per situation. */
public enum Situation {
    INTRO(Priority.RESULT),
    IDLE(Priority.IDLE),
    TURN_READY(Priority.TURN_READY),
    STRIKE(Priority.ACTION),
    FLURRY(Priority.ACTION),
    STUNNING_STRIKE(Priority.ACTION),
    SWIFT_STEP(Priority.ACTION),
    MARTIAL_SURGE(Priority.ACTION),
    MEDITATE(Priority.ACTION),
    GUARD(Priority.ACTION),
    ARCHON_MELEE(Priority.ACTION),
    FROST_CAST(Priority.ACTION),
    COMBO_WINDUP(Priority.ULTIMATE),
    /** The per-input angle set: variants are used in authored order, one per correct input. */
    COMBO_ANGLE(Priority.ULTIMATE),
    COMBO_FINISHER(Priority.ULTIMATE),
    VICTORY(Priority.RESULT),
    DEFEAT(Priority.RESULT);

    /** Higher ordinal preempts lower. */
    public enum Priority { IDLE, TURN_READY, ACTION, ULTIMATE, RESULT }

    private final Priority priority;

    Situation(Priority priority) { this.priority = priority; }

    public Priority priority() { return priority; }
}
